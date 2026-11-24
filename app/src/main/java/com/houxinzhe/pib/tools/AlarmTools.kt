package com.houxinzhe.pib.tools

import android.content.Intent
import android.provider.AlarmClock
import com.houxinzhe.pib.agent.Risk
import com.houxinzhe.pib.agent.Tool
import com.houxinzhe.pib.agent.ToolContext
import com.houxinzhe.pib.agent.ToolResult
import com.houxinzhe.pib.agent.i
import com.houxinzhe.pib.agent.s
import com.houxinzhe.pib.llm.int
import com.houxinzhe.pib.llm.schema
import com.houxinzhe.pib.llm.str
import kotlinx.serialization.json.JsonObject
import java.util.Calendar

/**
 * Some ROMs (vivo BBKClock) ignore EXTRA_SKIP_UI and bring the clock to the front.
 * Bring Pib back shortly after so the user sees the receipt.
 */
internal suspend fun returnToPib(ctx: ToolContext) {
    kotlinx.coroutines.delay(1200)
    val launch = ctx.app.packageManager.getLaunchIntentForPackage(ctx.app.packageName) ?: return
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    runCatching { ctx.app.startActivity(launch) }
}

/** Uses the system clock app via the public AlarmClock intents (works on vivo/小米/华为/OPPO). */
class AlarmSetTool : Tool {
    override val name = "alarm_set"
    override val title = "设闹钟"
    override val risk = Risk.REVERSIBLE
    override val description = "用系统时钟设一个闹钟（在下一个该时刻响）。可设重复。只能设 24 小时内的单次闹钟或按星期重复的闹钟。"
    override val parameters = schema(
        int("hour", "0-23", required = true),
        int("minute", "0-59", required = true),
        str("label", "闹钟名称，例如：起床、开会"),
        str("repeat", "重复：none、daily、weekdays、weekends，或用逗号分隔的星期数字 1-7（1=周一）"),
    )

    override fun preview(args: JsonObject) = "设闹钟 %02d:%02d %s".format(args.i("hour") ?: 0, args.i("minute") ?: 0, args.s("label").orEmpty())

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val h = args.i("hour")?.takeIf { it in 0..23 } ?: return ToolResult.fail("小时不在 0-23")
        val m = args.i("minute")?.takeIf { it in 0..59 } ?: return ToolResult.fail("分钟不在 0-59")
        val days = parseRepeat(args.s("repeat"))
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            args.s("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_VIBRATE, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(ctx.app.packageManager) == null) return ToolResult.fail("这台手机的时钟 App 不接受设闹钟请求")
        ctx.app.startActivity(intent)
        returnToPib(ctx)
        val rep = if (days.isEmpty()) "单次" else "重复（${days.joinToString(",") { dayName(it) }}）"
        return ToolResult.ok("已把闹钟 %02d:%02d %s 交给系统时钟，%s。系统时钟没有回执，如需确认请打开时钟查看。".format(h, m, args.s("label").orEmpty(), rep))
    }

    companion object {
        /** Returns java.util.Calendar day constants. */
        fun parseRepeat(v: String?): List<Int> {
            val s = v?.trim()?.lowercase().orEmpty()
            return when (s) {
                "", "none", "once" -> emptyList()
                "daily" -> (1..7).map(::isoToCal)
                "weekdays" -> (1..5).map(::isoToCal)
                "weekends" -> listOf(6, 7).map(::isoToCal)
                else -> s.split(',', '，', ' ').mapNotNull { it.trim().toIntOrNull()?.takeIf { d -> d in 1..7 } }.distinct().map(::isoToCal)
            }
        }
        private fun isoToCal(iso: Int) = if (iso == 7) Calendar.SUNDAY else iso + 1
        private fun dayName(cal: Int) = listOf("", "周日", "周一", "周二", "周三", "周四", "周五", "周六")[cal]
    }
}

class TimerSetTool : Tool {
    override val name = "timer_set"
    override val title = "设倒计时"
    override val risk = Risk.REVERSIBLE
    override val description = "用系统时钟开始一个倒计时。"
    override val parameters = schema(
        int("seconds", "倒计时总秒数，1-86400", required = true),
        str("label", "名称，例如：煮面"),
    )

    override fun preview(args: JsonObject) = "倒计时 ${(args.i("seconds") ?: 0) / 60} 分钟 ${args.s("label").orEmpty()}"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val sec = args.i("seconds")?.takeIf { it in 1..86400 } ?: return ToolResult.fail("秒数需在 1-86400")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, sec)
            args.s("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(ctx.app.packageManager) == null) return ToolResult.fail("这台手机的时钟 App 不接受倒计时请求")
        ctx.app.startActivity(intent)
        returnToPib(ctx)
        return ToolResult.ok("已开始 ${sec / 60} 分 ${sec % 60} 秒倒计时。")
    }
}
