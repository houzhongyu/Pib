package com.houxinzhe.pib.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import com.houxinzhe.pib.agent.Risk
import com.houxinzhe.pib.agent.Tool
import com.houxinzhe.pib.agent.ToolContext
import com.houxinzhe.pib.agent.ToolResult
import com.houxinzhe.pib.agent.b
import com.houxinzhe.pib.agent.i
import com.houxinzhe.pib.agent.s
import com.houxinzhe.pib.llm.bool
import com.houxinzhe.pib.llm.int
import com.houxinzhe.pib.llm.schema
import com.houxinzhe.pib.llm.str
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

object TimeParse {
    private val patterns = listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm")
        .map { DateTimeFormatter.ofPattern(it) }

    fun dateTime(v: String?): LocalDateTime? {
        val s = v?.trim() ?: return null
        patterns.forEach { f -> runCatching { return LocalDateTime.parse(s, f) } }
        runCatching { return LocalDate.parse(s.take(10)).atStartOfDay() }
        return null
    }

    fun millis(t: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()) = t.atZone(zone).toInstant().toEpochMilli()
    val show: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
}

class CalendarAddTool : Tool {
    override val name = "calendar_add"
    override val title = "写入日程"
    override val risk = Risk.REVERSIBLE
    override val permissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    override val description = "在手机系统日历里新建日程，可带提前提醒。也用来做「到时候提醒我」类的提醒。"
    override val parameters = schema(
        str("title", "日程标题", required = true),
        str("start", "开始时间 yyyy-MM-dd HH:mm", required = true),
        str("end", "结束时间 yyyy-MM-dd HH:mm，可省略（默认 1 小时）"),
        bool("all_day", "是否全天"),
        str("location", "地点"),
        str("notes", "备注"),
        int("remind_minutes", "提前多少分钟提醒，默认 15，0 表示准时"),
    )

    override fun preview(args: JsonObject) = "写入日程「${args.s("title")}」${args.s("start")}"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val title = args.s("title") ?: return ToolResult.fail("缺少标题")
        val start = TimeParse.dateTime(args.s("start")) ?: return ToolResult.fail("开始时间格式不对，需要 yyyy-MM-dd HH:mm")
        val allDay = args.b("all_day") == true
        val end = TimeParse.dateTime(args.s("end"))?.takeIf { it.isAfter(start) } ?: if (allDay) start.plusDays(1) else start.plusHours(1)
        val calId = writableCalendarId(ctx) ?: return ToolResult.fail("手机上没有可写入的日历账户")
        val cr = ctx.app.contentResolver
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            args.s("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            put(CalendarContract.Events.DESCRIPTION, (args.s("notes")?.plus("\n") ?: "") + "由 ${ctx.settings.assistantName} 创建")
            if (allDay) {
                val utc = ZoneId.of("UTC")
                val startDay = start.toLocalDate()
                val endDay = maxOf(end.toLocalDate(), startDay.plusDays(1))
                put(CalendarContract.Events.DTSTART, TimeParse.millis(startDay.atStartOfDay(), utc))
                put(CalendarContract.Events.DTEND, TimeParse.millis(endDay.atStartOfDay(), utc))
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                put(CalendarContract.Events.DTSTART, TimeParse.millis(start))
                put(CalendarContract.Events.DTEND, TimeParse.millis(end))
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        val uri = cr.insert(CalendarContract.Events.CONTENT_URI, values) ?: return ToolResult.fail("系统日历拒绝写入")
        val eventId = ContentUris.parseId(uri)
        val remind = (args.i("remind_minutes") ?: 15).coerceIn(0, 40320)
        cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, remind)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
        val whenText = if (allDay) "${start.toLocalDate()} 全天" else start.format(TimeParse.show)
        return ToolResult.ok("已写入日历：「$title」$whenText，提前 $remind 分钟提醒（日程编号 $eventId）。")
    }

    private fun writableCalendarId(ctx: ToolContext): Long? {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
        val sel = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1"
        ctx.app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null)?.use { c ->
            var first: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (first == null) first = id
                if (c.getInt(1) == 1) return id
            }
            return first
        }
        return null
    }
}

class CalendarListTool : Tool {
    override val name = "calendar_list"
    override val title = "查看日程"
    override val risk = Risk.READ
    override val permissions = listOf(Manifest.permission.READ_CALENDAR)
    override val description = "列出系统日历里某一天起若干天内的日程。"
    override val parameters = schema(
        str("from", "开始日期 yyyy-MM-dd，默认今天"),
        int("days_ahead", "查几天，默认 1，最多 31"),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val from = TimeParse.dateTime(args.s("from"))?.toLocalDate() ?: LocalDate.now()
        val days = (args.i("days_ahead") ?: 1).coerceIn(1, 31)
        val begin = TimeParse.millis(from.atStartOfDay())
        val end = TimeParse.millis(from.plusDays(days.toLong()).atStartOfDay())
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin); ContentUris.appendId(it, end)
        }.build()
        val proj = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION)
        val lines = mutableListOf<String>()
        ctx.app.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && lines.size < 40) {
                val t = java.time.Instant.ofEpochMilli(c.getLong(1)).atZone(ZoneId.systemDefault()).toLocalDateTime()
                val whenText = if (c.getInt(2) == 1) "${t.toLocalDate()} 全天" else t.format(TimeParse.show)
                lines += "• $whenText ${c.getString(0).orEmpty()}" + (c.getString(3)?.takeIf { it.isNotBlank() }?.let { " @$it" } ?: "")
            }
        }
        return ToolResult.ok(if (lines.isEmpty()) "$from 起 $days 天内没有日程。" else lines.joinToString("\n"))
    }
}
