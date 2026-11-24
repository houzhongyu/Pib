package com.houxinzhe.pib.tools

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.houxinzhe.pib.agent.Risk
import com.houxinzhe.pib.agent.Tool
import com.houxinzhe.pib.agent.ToolContext
import com.houxinzhe.pib.agent.ToolResult
import com.houxinzhe.pib.agent.b
import com.houxinzhe.pib.agent.s
import com.houxinzhe.pib.data.ButlerParser
import com.houxinzhe.pib.data.ButlerStore
import com.houxinzhe.pib.data.ParcelItem
import com.houxinzhe.pib.data.TripItem
import com.houxinzhe.pib.data.TripKind
import com.houxinzhe.pib.llm.bool
import com.houxinzhe.pib.llm.schema
import com.houxinzhe.pib.llm.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ParcelAddTool(private val butler: ButlerStore) : Tool {
    override val name = "parcel_add"
    override val title = "记录快递取件码"
    override val risk = Risk.REVERSIBLE
    override val description = "记录一条快递或驿站取件码。可以直接传入整条快递短信 sms_text 自动识别，也可以单独传 code、station、carrier。"
    override val parameters = schema(
        str("sms_text", "完整的快递取件短信或通知原文，传入会自动提取取件码与驿站"),
        str("code", "取件码，例如 3-2-1048"),
        str("station", "驿站或快递柜位置，例如 阳光花园菜鸟驿站"),
        str("carrier", "快递品牌，例如 顺丰、中通、丰巢"),
        str("tracking_no", "运单号，可选"),
    )

    override fun preview(args: JsonObject) = "记录取件码 ${args.s("code") ?: args.s("sms_text")?.take(24).orEmpty()}"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val parsed = args.s("sms_text")?.let { ButlerParser.parseParcel(it) }
        val code = args.s("code") ?: parsed?.code ?: return ToolResult.fail("没识别出取件码，请提供取件码或完整快递短信。")
        val item = butler.addParcel(
            ParcelItem(
                code = code,
                station = args.s("station") ?: parsed?.station.orEmpty(),
                carrier = args.s("carrier") ?: parsed?.carrier.orEmpty(),
                trackingNo = args.s("tracking_no") ?: parsed?.trackingNo.orEmpty(),
                rawNote = args.s("sms_text") ?: "",
            )
        )
        val loc = listOf(item.carrier, item.station).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "待取包裹" }
        return ToolResult.ok("已记下快递：取件码【${item.code}】，$loc（编号 ${item.id}）。")
    }
}

class ParcelListTool(private val butler: ButlerStore) : Tool {
    override val name = "parcel_list"
    override val title = "查看待取快递"
    override val risk = Risk.READ
    override val description = "列出手机里记录的待取快递和取件码。include_done=true 时包含已取出的。"
    override val parameters = schema(bool("include_done", "是否包含已取件记录"))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val list = butler.listParcels(args.b("include_done") == true)
        if (list.isEmpty()) return ToolResult.ok("目前没有待取的快递。")
        return ToolResult.ok(
            list.joinToString("\n") { p ->
                val place = listOf(p.carrier, p.station).filter { it.isNotBlank() }.joinToString(" ")
                val status = if (p.pickedUp) "（已取）" else ""
                "• 取件码【${p.code}】${if (place.isNotBlank()) " · $place" else ""}${if (p.trackingNo.isNotBlank()) " · 单号 ${p.trackingNo}" else ""} [编号 ${p.id}]$status"
            }
        )
    }
}

class ParcelDoneTool(private val butler: ButlerStore) : Tool {
    override val name = "parcel_done"
    override val title = "标记快递已取"
    override val risk = Risk.REVERSIBLE
    override val description = "把某件快递标为已取出。可以传编号、取件码、驿站关键词，或传 all 全部标为已取。"
    override val parameters = schema(str("query", "取件码、编号、驿站名，或 all", required = true))

    override fun preview(args: JsonObject) = "标记快递已取：${args.s("query")}"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val q = args.s("query") ?: return ToolResult.fail("缺少取件码或编号")
        val hit = butler.markParcelDone(q) ?: return ToolResult.fail("没有找到匹配「$q」的待取快递")
        return if (q == "all" || q == "全部") ToolResult.ok("已把所有待取快递标为已取。")
        else ToolResult.ok("已标记取完：【${hit.code}】${hit.station}")
    }
}

class TripAddTool(private val butler: ButlerStore, private val calendarAdd: CalendarAddTool = CalendarAddTool()) : Tool {
    override val name = "trip_add"
    override val title = "记录火车或航班行程"
    override val risk = Risk.REVERSIBLE
    override val description = "记录高铁/火车（12306）或航班行程，如果已授权系统日历且有发车/起飞时间，会自动写入日历并提前提醒出门。可直接传整条购票短信 sms_text。"
    override val parameters = schema(
        str("sms_text", "12306 或航司/航旅短信原文，传入可自动解析"),
        str("trip_no", "车次或航班号，例如 G1204、CZ6201"),
        str("kind", "train 或 flight", enum = listOf("train", "flight")),
        str("depart_station", "出发车站或机场航站楼"),
        str("arrive_station", "到达车站或机场"),
        str("depart_time", "出发时间 yyyy-MM-dd HH:mm"),
        str("seat", "车厢座位号，例如 05车12F号"),
        str("gate", "检票口或登机口，例如 B6"),
        bool("sync_calendar", "是否同步写入手机系统日历并设出门提醒，默认 true"),
    )

    override fun preview(args: JsonObject) = "记录行程 ${args.s("trip_no") ?: args.s("sms_text")?.take(24).orEmpty()}"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val parsed = args.s("sms_text")?.let { ButlerParser.parseTrip(it) }
        val no = (args.s("trip_no") ?: parsed?.tripNo)?.uppercase() ?: return ToolResult.fail("缺少车次或航班号，请提供车次/航班号或完整短信。")
        val kind = when {
            args.s("kind") == "flight" -> TripKind.FLIGHT
            args.s("kind") == "train" -> TripKind.TRAIN
            parsed != null -> parsed.kind
            no.firstOrNull() in listOf('G', 'D', 'C', 'Z', 'T', 'K') && no.drop(1).all { it.isDigit() } -> TripKind.TRAIN
            else -> TripKind.FLIGHT
        }
        val item = butler.addTrip(
            TripItem(
                kind = kind,
                tripNo = no,
                departStation = args.s("depart_station") ?: parsed?.departStation.orEmpty(),
                arriveStation = args.s("arrive_station") ?: parsed?.arriveStation.orEmpty(),
                departTime = args.s("depart_time") ?: parsed?.departTime.orEmpty(),
                seat = args.s("seat") ?: parsed?.seat.orEmpty(),
                gate = args.s("gate") ?: parsed?.gate.orEmpty(),
                rawNote = args.s("sms_text").orEmpty(),
            )
        )
        var calNote = ""
        val syncCal = args.b("sync_calendar") != false
        if (syncCal && item.departTime.isNotBlank()) {
            val hasCalPerm = ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
            if (hasCalPerm) {
                val route = listOf(item.departStation, item.arriveStation).filter { it.isNotBlank() }.joinToString(" → ")
                val details = listOfNotNull(
                    item.seat.takeIf { it.isNotBlank() }?.let { "座位 $it" },
                    item.gate.takeIf { it.isNotBlank() }?.let { "${if (kind == TripKind.FLIGHT) "登机口" else "检票口"} $it" },
                ).joinToString("，")
                val calRes = calendarAdd.run(
                    buildJsonObject {
                        put("title", "${item.tripNo} ${route.ifBlank { kind.label }}".trim())
                        put("start", item.departTime)
                        if (item.departStation.isNotBlank()) put("location", item.departStation)
                        if (details.isNotBlank()) put("notes", details)
                        put("remind_minutes", ButlerParser.leadMinutes(kind))
                    },
                    ctx,
                )
                if (calRes.ok) calNote = "；已同步到系统日历并设提前 ${ButlerParser.leadMinutes(kind)} 分钟出门提醒"
            } else {
                calNote = "（尚未授予日历权限，仅保存在生活管家；授权日历后可自动写入出门提醒）"
            }
        }
        val summary = listOfNotNull(
            item.tripNo,
            listOf(item.departStation, item.arriveStation).filter { it.isNotBlank() }.joinToString("→").takeIf { it.isNotBlank() },
            item.departTime.takeIf { it.isNotBlank() },
            item.seat.takeIf { it.isNotBlank() },
            item.gate.takeIf { it.isNotBlank() }?.let { "${if (kind == TripKind.FLIGHT) "登机口" else "检票口"}$it" },
        ).joinToString(" ")
        return ToolResult.ok("已记录${kind.label}：$summary$calNote。")
    }
}

class TripListTool(private val butler: ButlerStore) : Tool {
    override val name = "trip_list"
    override val title = "查看火车与航班行程"
    override val risk = Risk.READ
    override val description = "列出已保存的高铁/火车和航班行程。"
    override val parameters = schema()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val list = butler.listTrips()
        if (list.isEmpty()) return ToolResult.ok("目前没有记录任何火车或航班行程。")
        return ToolResult.ok(
            list.joinToString("\n") { t ->
                val route = listOf(t.departStation, t.arriveStation).filter { it.isNotBlank() }.joinToString(" → ")
                val extra = listOfNotNull(
                    t.seat.takeIf { it.isNotBlank() },
                    t.gate.takeIf { it.isNotBlank() }?.let { "${if (t.kind == TripKind.FLIGHT) "登机口" else "检票口"} $it" },
                ).joinToString(" · ")
                "• [${t.kind.label}] ${t.tripNo} ${t.departTime} $route ${if (extra.isNotBlank()) "($extra)" else ""}".trim()
            }
        )
    }
}
