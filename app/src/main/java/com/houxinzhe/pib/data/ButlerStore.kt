package com.houxinzhe.pib.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

@Serializable
data class ParcelItem(
    val id: String = UUID.randomUUID().toString().take(8),
    val code: String,
    val station: String = "",
    val carrier: String = "",
    val trackingNo: String = "",
    val rawNote: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var pickedUp: Boolean = false,
)

@Serializable
enum class TripKind(val label: String) { TRAIN("火车/高铁"), FLIGHT("航班") }

@Serializable
data class TripItem(
    val id: String = UUID.randomUUID().toString().take(8),
    val kind: TripKind,
    val tripNo: String,
    val departStation: String = "",
    val arriveStation: String = "",
    /** yyyy-MM-dd HH:mm when known. */
    val departTime: String = "",
    val seat: String = "",
    val gate: String = "",
    val rawNote: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
private data class ButlerSnapshot(
    val parcels: List<ParcelItem> = emptyList(),
    val trips: List<TripItem> = emptyList(),
)

/** On-device store for parcel pickup codes and train/flight itineraries. */
class ButlerStore(private val file: File?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val parcels = mutableListOf<ParcelItem>()
    private val trips = mutableListOf<TripItem>()

    init {
        file?.takeIf { it.exists() }?.let { f ->
            runCatching { json.decodeFromString<ButlerSnapshot>(f.readText()) }.getOrNull()?.let {
                parcels += it.parcels
                trips += it.trips
            }
        }
    }

    @Synchronized
    fun listParcels(includeDone: Boolean = false): List<ParcelItem> =
        parcels.filter { includeDone || !it.pickedUp }.sortedByDescending { it.createdAt }

    @Synchronized
    fun addParcel(item: ParcelItem): ParcelItem {
        val dup = parcels.firstOrNull { !it.pickedUp && it.code.equals(item.code, ignoreCase = true) && it.station == item.station }
        if (dup != null) return dup
        parcels.add(0, item)
        while (parcels.size > 200) parcels.removeAt(parcels.lastIndex)
        save()
        return item
    }

    @Synchronized
    fun markParcelDone(query: String): ParcelItem? {
        val q = query.trim()
        val hit = parcels.firstOrNull { !it.pickedUp && (it.id == q || it.code.equals(q, true) || it.code.contains(q, true) || (q.length >= 2 && it.station.contains(q, true))) }
            ?: if (q == "all" || q == "全部") parcels.firstOrNull { !it.pickedUp } else null
        if (hit != null) {
            if (q == "all" || q == "全部") parcels.forEach { it.pickedUp = true } else hit.pickedUp = true
            save()
        }
        return hit
    }

    @Synchronized
    fun listTrips(): List<TripItem> = trips.sortedBy { it.departTime.ifBlank { "9999" } }

    @Synchronized
    fun addTrip(item: TripItem): TripItem {
        val dup = trips.indexOfFirst { it.tripNo.equals(item.tripNo, true) && it.departTime == item.departTime }
        if (dup >= 0) trips[dup] = item.copy(id = trips[dup].id) else trips.add(0, item)
        while (trips.size > 100) trips.removeAt(trips.lastIndex)
        save()
        return item
    }

    @Synchronized
    fun removeTrip(idOrNo: String): Boolean {
        val removed = trips.removeAll { it.id == idOrNo || it.tripNo.equals(idOrNo, true) }
        if (removed) save()
        return removed
    }

    private fun save() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(ButlerSnapshot(parcels.toList(), trips.toList())))
        tmp.renameTo(f)
    }
}

/**
 * Deterministic local SMS / notification parser for Chinese parcel pickup codes,
 * 12306 train tickets, and domestic flights. Never calls the network and ignores OTPs.
 */
object ButlerParser {
    private val OTP_GUARD = Regex("验证码|校验码|动态码|登录密码|支付密码|找回密码|消费人民币|信用卡还款")
    private val PARCEL_CUE = Regex("快递|驿站|丰巢|取件|提货|取货|包裹|快件|快递柜|智能柜|顺丰|京东|菜鸟|中通|圆通|申通|韵达|极兔|兔喜|邮政|EMS|德邦")
    private val CARRIERS = listOf("菜鸟驿站", "妈妈驿站", "兔喜生活", "丰巢", "顺丰", "京东", "中通", "圆通", "申通", "韵达", "极兔", "邮政", "EMS", "德邦", "菜鸟")

    private val EXPLICIT_CODE = Regex("""(?:取件码|提货码|取货码|取件密码|凭取件码|凭提货码|凭码|请凭|密码)[:：\s【\[「]*([A-Za-z0-9]{1,5}(?:-[A-Za-z0-9]{1,6}){1,3}|[A-Za-z]?\d{4,8})[】\]」]?""")
    private val SHELF_CODE = Regex("""(?<![0-9A-Za-z])([A-Za-z0-9]{1,4}-\d{1,3}-\d{2,5}|\d{1,2}-\d{3,6})(?![0-9A-Za-z])""")
    private val STATION_AFTER_VERB = Regex("""(?:已到|到达|放至|放入|存入|放在|送至|投递至|投递到|请到|前往|到)\s*([^，。！!；;\n【】\[\]]{2,28}?(?:菜鸟驿站|妈妈驿站|兔喜生活超市|兔喜生活|兔喜驿站|驿站|丰巢智能柜|丰巢快递柜|丰巢柜|智能柜|快递柜|代收点|服务站|超市|门卫|保安室))""")
    private val STATION_PLAIN = Regex("""([^\s，。！!；;【】\[\]：:]{2,24}?(?:菜鸟驿站|妈妈驿站|兔喜生活超市|兔喜生活|兔喜驿站|丰巢智能柜|丰巢快递柜|丰巢柜|智能快递柜))""")
    private val TRACKING = Regex("""\b(SF\d{11,15}|JD[A-Z0-9]{10,16}|YT\d{11,15}|ZTO?\d{11,15}|JT\d{11,15}|STO\d{10,15}|7\d{13})\b""", RegexOption.IGNORE_CASE)

    private val TRAIN_NO = Regex("""(?<![A-Za-z0-9])([GDCZTK]\d{1,4})(?=次|列车|\s|，|,|$|站|[一-龥])""")
    private val TRAIN_CUE = Regex("12306|铁路|列车|车次|检票|候车|硬卧|软卧|二等座|一等座|商务座|无座|车\\d{1,3}[A-DF号]|站\\s*\\d{1,2}:\\d{2}\\s*开")
    private val FLIGHT_NO = Regex("""(?<![A-Za-z0-9])((?:CA|MU|CZ|HU|ZH|MF|3U|FM|SC|KN|HO|9C|GS|PN|EU|KY|TV|G5|BK|OQ|GJ)[0-9]{3,4})(?![0-9])""")
    private val FLIGHT_CUE = Regex("航班|起飞|航站楼|登机口|值机|机场|航空|航旅")

    private val CN_DATE_TIME = Regex("""(?:(20\d{2})年)?(\d{1,2})月(\d{1,2})日[^\n]{0,36}?(?<!\d)(\d{1,2}:\d{2})""")
    private val ISO_DATE_TIME = Regex("""(20\d{2})-(\d{1,2})-(\d{1,2})\s+(\d{1,2}:\d{2})""")
    private val CN_DATE_ONLY = Regex("""(?:(20\d{2})年)?(\d{1,2})月(\d{1,2})日""")
    private val DEPART_STATION_OPEN = Regex("""(?:日|次)\s*([一-龥]{2,9}?站)\s*(\d{1,2}:\d{2})\s*开""")
    private val STATION_PAIR = Regex("""([一-龥]{2,8}(?:T\d)?)\s*(?:[-—→至]|开往|飞往|前往)\s*([一-龥]{2,8}(?:T\d)?)""")
    private val TRAIN_SEAT = Regex("""(\d{1,2}车\d{1,3}[A-DFa-df号]*(?:[上下中]铺)?|无座)""")
    private val TRAIN_GATE = Regex("""检票口[:：\s]*([A-Za-z0-9\-]+)""")
    private val FLIGHT_GATE = Regex("""登机口[:：\s]*([A-Za-z0-9\-]+)""")
    private val FLIGHT_SEAT = Regex("""座位[:：\s]*(\d{1,2}[A-Ka-k])""")
    private val AIRPORT_DEP = Regex("""([一-龥]{2,8}(?:国际)?机场(?:T\d)?)""")

    fun parseParcel(text: String): ParcelItem? {
        val clean = text.trim()
        if (clean.length < 6 || OTP_GUARD.containsMatchIn(clean) || !PARCEL_CUE.containsMatchIn(clean)) return null
        val code = EXPLICIT_CODE.find(clean)?.groupValues?.get(1)
            ?: SHELF_CODE.find(clean)?.groupValues?.get(1)
            ?: return null
        val carrier = CARRIERS.firstOrNull { clean.contains(it, ignoreCase = true) }.orEmpty()
        val station = STATION_AFTER_VERB.find(clean)?.groupValues?.get(1)?.trim()
            ?: STATION_PLAIN.find(clean)?.groupValues?.get(1)?.trim()
            ?: Regex("""【([^】]{2,16})】""").find(clean)?.groupValues?.get(1)?.takeIf { it.contains("驿站") || it.contains("丰巢") || it.contains("兔喜") }.orEmpty()
        val tracking = TRACKING.find(clean)?.groupValues?.get(1)?.uppercase().orEmpty()
        return ParcelItem(code = code, station = station, carrier = carrier, trackingNo = tracking, rawNote = clean.take(180))
    }

    fun parseTrip(text: String, today: LocalDate = LocalDate.now(ZoneId.systemDefault())): TripItem? {
        val clean = text.trim()
        if (clean.length < 8 || OTP_GUARD.containsMatchIn(clean)) return null

        val flightMatch = if (FLIGHT_CUE.containsMatchIn(clean)) FLIGHT_NO.find(clean) else null
        if (flightMatch != null) {
            val no = flightMatch.groupValues[1]
            val dt = extractDateTime(clean, today)
            val pair = STATION_PAIR.find(clean)
            val dep = pair?.groupValues?.get(1) ?: AIRPORT_DEP.find(clean)?.groupValues?.get(1).orEmpty()
            val arr = pair?.groupValues?.get(2).orEmpty()
            val gate = FLIGHT_GATE.find(clean)?.groupValues?.get(1).orEmpty()
            val seat = FLIGHT_SEAT.find(clean)?.groupValues?.get(1).orEmpty()
            return TripItem(kind = TripKind.FLIGHT, tripNo = no, departStation = dep, arriveStation = arr, departTime = dt, seat = seat, gate = gate, rawNote = clean.take(200))
        }

        val trainMatch = if (TRAIN_CUE.containsMatchIn(clean)) TRAIN_NO.find(clean) else null
        if (trainMatch != null) {
            val no = trainMatch.groupValues[1]
            val openMatch = DEPART_STATION_OPEN.find(clean)
            val pair = STATION_PAIR.find(clean)
            val dep = openMatch?.groupValues?.get(1) ?: pair?.groupValues?.get(1).orEmpty()
            val arr = pair?.groupValues?.get(2).orEmpty()
            val dt = extractDateTime(clean, today, fallbackTime = openMatch?.groupValues?.get(2))
            val seat = TRAIN_SEAT.find(clean)?.groupValues?.get(1).orEmpty()
            val gate = TRAIN_GATE.find(clean)?.groupValues?.get(1).orEmpty()
            return TripItem(kind = TripKind.TRAIN, tripNo = no, departStation = dep, arriveStation = arr, departTime = dt, seat = seat, gate = gate, rawNote = clean.take(200))
        }
        return null
    }

    internal fun extractDateTime(text: String, today: LocalDate, fallbackTime: String? = null): String {
        ISO_DATE_TIME.find(text)?.let { m ->
            val d = LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            val t = LocalTime.parse(padTime(m.groupValues[4]))
            return "%s %02d:%02d".format(d, t.hour, t.minute)
        }
        CN_DATE_TIME.find(text)?.let { m ->
            val d = resolveYear(m.groupValues[1].toIntOrNull(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), today)
            val t = LocalTime.parse(padTime(m.groupValues[4]))
            return "%s %02d:%02d".format(d, t.hour, t.minute)
        }
        if (fallbackTime != null) {
            CN_DATE_ONLY.find(text)?.let { m ->
                val d = resolveYear(m.groupValues[1].toIntOrNull(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), today)
                val t = LocalTime.parse(padTime(fallbackTime))
                return "%s %02d:%02d".format(d, t.hour, t.minute)
            }
        }
        return ""
    }

    private fun resolveYear(explicitYear: Int?, month: Int, day: Int, today: LocalDate): LocalDate {
        if (explicitYear != null) return LocalDate.of(explicitYear, month, day)
        val candidate = LocalDate.of(today.year, month, day)
        return if (candidate.isBefore(today.minusDays(30))) candidate.plusYears(1) else candidate
    }

    private fun padTime(t: String): String = if (t.indexOf(':') == 1) "0$t" else t

    /** Suggested reminder lead time before departure (minutes). */
    fun leadMinutes(kind: TripKind): Int = if (kind == TripKind.FLIGHT) 150 else 75

    @Suppress("unused")
    fun departLocalDateTime(departTime: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(departTime.trim().replace(' ', 'T')) }.getOrNull()
}
