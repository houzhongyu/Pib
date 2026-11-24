package com.houxinzhe.pib.tools

import android.content.Intent
import android.net.Uri
import com.houxinzhe.pib.agent.Risk
import com.houxinzhe.pib.agent.Tool
import com.houxinzhe.pib.agent.ToolContext
import com.houxinzhe.pib.agent.ToolResult
import com.houxinzhe.pib.agent.s
import com.houxinzhe.pib.llm.schema
import com.houxinzhe.pib.llm.str
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

data class HandoffPlan(
    val label: String,
    val primaryUri: String? = null,
    val launchPackage: String? = null,
    val fallbackWebUrl: String? = null,
    val risk: Risk = Risk.REVERSIBLE,
    val action: String = Intent.ACTION_VIEW,
)

object HandoffCatalog {
    val TARGETS = listOf(
        "amap_route", "amap_taxi", "amap_search", "baidumap_route", "didi",
        "meituan_search", "dianping_search", "eleme_search",
        "taobao_search", "jd_search", "pdd_search",
        "railway_12306", "cainiao", "bilibili_search", "xiaohongshu_search",
        "dial", "open_url",
    )

    private fun enc(s: String): String = URLEncoder.encode(s.trim(), "UTF-8")

    fun build(target: String, query: String?, origin: String?, destination: String?, mode: String?): HandoffPlan? {
        val q = query?.trim().orEmpty()
        val dst = (destination ?: query)?.trim().orEmpty()
        val org = origin?.trim().orEmpty()
        return when (target) {
            "amap_route" -> {
                if (dst.isEmpty()) return null
                val t = when (mode) { "transit" -> 1; "walking" -> 2; else -> 0 }
                val sPart = if (org.isNotEmpty() && org != "我的位置" && org != "当前位置") "&sname=${enc(org)}" else ""
                HandoffPlan(
                    label = "打开高德地图路线（去 $dst）",
                    primaryUri = "amapuri://route/plan/?sourceApplication=pib$sPart&dname=${enc(dst)}&dev=0&t=$t",
                    fallbackWebUrl = "https://uri.amap.com/search?keyword=${enc(dst)}",
                )
            }
            "amap_taxi" -> {
                if (dst.isEmpty()) return null
                HandoffPlan(
                    label = "打开高德打车（目的地：$dst，需你在高德内点确认呼叫）",
                    primaryUri = "amapuri://drive/takeTaxi?sourceApplication=pib&dname=${enc(dst)}",
                    fallbackWebUrl = "https://uri.amap.com/search?keyword=${enc(dst)}",
                    risk = Risk.SENSITIVE,
                )
            }
            "amap_search" -> {
                if (q.isEmpty()) return null
                HandoffPlan(
                    label = "打开高德地图搜索「$q」",
                    primaryUri = "amapuri://poi?sourceApplication=pib&keywords=${enc(q)}",
                    fallbackWebUrl = "https://uri.amap.com/search?keyword=${enc(q)}",
                )
            }
            "baidumap_route" -> {
                if (dst.isEmpty()) return null
                val m = when (mode) { "transit" -> "transit"; "walking" -> "walking"; else -> "driving" }
                val oPart = if (org.isNotEmpty() && org != "我的位置") "origin=${enc(org)}&" else ""
                HandoffPlan(
                    label = "打开百度地图路线（去 $dst）",
                    primaryUri = "baidumap://map/direction?${oPart}destination=${enc(dst)}&mode=$m&src=pib",
                    fallbackWebUrl = "https://map.baidu.com/mobile/webapp/search/search/qt=s&wd=${enc(dst)}",
                )
            }
            "didi" -> HandoffPlan(
                label = "打开滴滴出行${if (dst.isNotEmpty()) "（去 $dst）" else ""}（需你自行确认呼叫）",
                primaryUri = "diditaxi://",
                launchPackage = "com.sdu.didi.psnger",
                fallbackWebUrl = "https://common.diditaxi.com.cn/general/webEntry",
                risk = Risk.SENSITIVE,
            )
            "meituan_search" -> {
                if (q.isEmpty()) return null
                HandoffPlan(
                    label = "打开美团搜索「$q」",
                    primaryUri = "imeituan://www.meituan.com/search?q=${enc(q)}",
                    fallbackWebUrl = "https://i.meituan.com/s/${enc(q)}",
                )
            }
            "dianping_search" -> {
                if (q.isEmpty()) return null
                HandoffPlan(
                    label = "打开大众点评搜索「$q」",
                    primaryUri = "dianping://searchshoplist?keyword=${enc(q)}",
                    fallbackWebUrl = "https://m.dianping.com/shoplist/search?keyword=${enc(q)}",
                )
            }
            "eleme_search" -> {
                if (q.isEmpty()) return null
                HandoffPlan(
                    label = "打开饿了么搜索「$q」",
                    primaryUri = "eleme://search?keyword=${enc(q)}",
                    fallbackWebUrl = "https://h5.ele.me/",
                )
            }
            "taobao_search" -> {
                if (q.isEmpty()) return null
                HandoffPlan(
                    label = "打开淘宝搜索「$q」",
                    primaryUri = "taobao://s.taobao.com/?q=${enc(q)}",
                    fallbackWebUrl = "https://s.m.taobao.com/h5?q=${enc(q)}",
                )
            }
            "jd_search" -> {
                if (q.isEmpty()) return null
                val params = enc("""{"category":"jump","des":"search","keyWord":"${q.replace("\"", "")}"}""")
                HandoffPlan(
                    label = "打开京东搜索「$q」",
                    primaryUri = "openapp.jdmobile://virtual?params=$params",
                    fallbackWebUrl = "https://so.m.jd.com/ware/search.action?keyword=${enc(q)}",
                )
            }
            "pdd_search" -> {
                if (q.isEmpty()) return null
                HandoffPlan(
                    label = "打开拼多多搜索「$q」",
                    primaryUri = "pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key=${enc(q)}",
                    fallbackWebUrl = "https://mobile.yangkeduo.com/search_result.html?search_key=${enc(q)}",
                )
            }
            "railway_12306" -> HandoffPlan(
                label = "打开铁路 12306${if (q.isNotEmpty()) "（$q）" else ""}",
                launchPackage = "com.MobileTicket",
                fallbackWebUrl = "https://mobile.12306.cn/",
            )
            "cainiao" -> HandoffPlan(
                label = "打开菜鸟查快递${if (q.isNotEmpty()) "（单号 $q）" else ""}",
                launchPackage = "com.cainiao.wireless",
                fallbackWebUrl = if (q.isNotEmpty()) "https://m.kuaidi100.com/result.jsp?nu=${enc(q)}" else "https://m.kuaidi100.com/",
            )
            "bilibili_search" -> {
                if (q.isEmpty()) return null
                HandoffPlan(
                    label = "打开哔哩哔哩搜索「$q」",
                    primaryUri = "bilibili://search?keyword=${enc(q)}",
                    fallbackWebUrl = "https://m.bilibili.com/search?keyword=${enc(q)}",
                )
            }
            "xiaohongshu_search" -> {
                if (q.isEmpty()) return null
                HandoffPlan(
                    label = "打开小红书搜索「$q」",
                    primaryUri = "xhsdiscover://search/result?keyword=${enc(q)}",
                    fallbackWebUrl = "https://www.xiaohongshu.com/search_result?keyword=${enc(q)}",
                )
            }
            "dial" -> {
                val phone = q.filter { it.isDigit() || it == '+' || it == '-' }
                if (phone.length < 3) return null
                HandoffPlan(
                    label = "打开拨号盘预填号码 $phone（不自动拨出，需你点拨打）",
                    primaryUri = "tel:$phone",
                    risk = Risk.SENSITIVE,
                    action = Intent.ACTION_DIAL,
                )
            }
            "open_url" -> {
                if (!q.startsWith("https://") && !q.startsWith("http://")) return null
                HandoffPlan(label = "在浏览器打开 $q", primaryUri = q)
            }
            else -> null
        }
    }
}

/**
 * Safe domestic super-app handoff: pre-fills parameters via official URI schemes and
 * hands the final tap (order / hail / pay / call) to the user. Zero screen scraping.
 */
class AppHandoffTool : Tool {
    override val name = "app_handoff"
    override val title = "跳转应用预填"
    override val risk = Risk.REVERSIBLE
    override val description = "打开手机上的国内常用 App 并预填参数（导航去某地、打车、美团/点评搜店、淘宝/京东/拼多多比价、打开12306/菜鸟、预填电话拨号盘）。涉及打车或拨号会先征得用户确认，下单付款由用户自己点最后一步。"
    override val parameters = schema(
        str("target", "目标入口", required = true, enum = HandoffCatalog.TARGETS),
        str("query", "搜索词、快递单号、电话号码或网址"),
        str("origin", "出发地（路线导航可选）"),
        str("destination", "目的地（路线或打车必填）"),
        str("mode", "路线出行方式", enum = listOf("driving", "transit", "walking")),
    )

    override fun riskFor(args: JsonObject): Risk =
        buildPlan(args)?.risk ?: Risk.REVERSIBLE

    override fun preview(args: JsonObject): String =
        buildPlan(args)?.label ?: "跳转外部应用：${args.s("target")}"

    private fun buildPlan(args: JsonObject): HandoffPlan? =
        HandoffCatalog.build(args.s("target").orEmpty(), args.s("query"), args.s("origin"), args.s("destination"), args.s("mode"))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val plan = buildPlan(args) ?: return ToolResult.fail("缺少必要参数（如搜索词、目的地或合法网址）。")
        val pm = ctx.app.packageManager

        plan.primaryUri?.let { uriStr ->
            val intent = Intent(plan.action, Uri.parse(uriStr)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(pm) != null) {
                val started = runCatching { ctx.app.startActivity(intent); true }.getOrDefault(false)
                if (started) return ToolResult.ok("已为你${plan.label}。")
            }
        }
        plan.launchPackage?.let { pkg ->
            val launch = pm.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch != null) {
                val started = runCatching { ctx.app.startActivity(launch); true }.getOrDefault(false)
                if (started) return ToolResult.ok("已为你${plan.label}。")
            }
        }
        plan.fallbackWebUrl?.let { web ->
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val started = runCatching { ctx.app.startActivity(webIntent); true }.getOrDefault(false)
            if (started) return ToolResult.ok("手机未安装对应客户端，已用浏览器打开网页版：${plan.label}。")
        }
        return ToolResult.fail("这台手机没有安装对应应用，也无法打开网页版。")
    }
}
