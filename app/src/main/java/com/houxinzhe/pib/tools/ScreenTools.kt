package com.houxinzhe.pib.tools

import android.content.Intent
import com.houxinzhe.pib.agent.Risk
import com.houxinzhe.pib.agent.SafetyFuse
import com.houxinzhe.pib.agent.Tool
import com.houxinzhe.pib.agent.ToolContext
import com.houxinzhe.pib.agent.ToolResult
import com.houxinzhe.pib.agent.s
import com.houxinzhe.pib.llm.schema
import com.houxinzhe.pib.llm.str
import com.houxinzhe.pib.service.PibAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

class ScreenReadTool : Tool {
    override val name = "screen_read"
    override val title = "读取白名单界面"
    override val risk = Risk.READ
    override val description = "读取当前前台应用的可见文字和按钮（仅限用户在无障碍白名单中允许的普通工具应用；微信、支付宝、银行、美团、淘宝及密码支付页一律拒绝）。"
    override val parameters = schema()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = PibAccessibilityService.instance
            ?: return ToolResult.fail("尚未开启 Pib 无障碍辅助服务。请让用户到「更多 → 工具权限」按指引开启（若遇安卓13+受限设置，先在系统应用信息右上角允许受限设置）。")
        return svc.readAllowedScreen(ctx.settings.accessibilityAllowlist, extraBlocked = ctx.settings.customBlockedPackages)
    }
}

class ScreenActTool : Tool {
    override val name = "screen_act"
    override val title = "辅助操作白名单应用"
    override val risk = Risk.IRREVERSIBLE
    override val description = "在白名单应用里执行一步界面操作（打开白名单应用、点击指定文字按钮、在非密码框填入文字、上下滚动、返回）。严禁用于微信、支付宝、银行、支付或验证码页。"
    override val parameters = schema(
        str("action", "动作类型", required = true, enum = listOf("open_app", "click", "input", "scroll_down", "scroll_up", "back")),
        str("package_name", "open_app 时的应用包名，必须在白名单中"),
        str("target", "click 或 input 时匹配的目标控件文字"),
        str("text", "input 时要填入的文字"),
    )

    override fun preview(args: JsonObject): String = when (args.s("action")) {
        "open_app" -> "打开白名单应用 ${args.s("package_name")}"
        "click" -> "在白名单应用内点击「${args.s("target")}」"
        "input" -> "在白名单应用内输入「${args.s("text")}」"
        "scroll_down" -> "向下滚动当前白名单界面"
        "scroll_up" -> "向上滚动当前白名单界面"
        "back" -> "在白名单应用内返回上一页"
        else -> "辅助界面操作"
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val act = args.s("action") ?: return ToolResult.fail("缺少 action")
        val allowlist = ctx.settings.accessibilityAllowlist
        val extraBlocked = ctx.settings.customBlockedPackages
        if (act == "open_app") {
            val pkg = args.s("package_name")?.trim() ?: return ToolResult.fail("缺少 package_name")
            if (SafetyFuse.isHardBlockedPackage(pkg)) return ToolResult.fail("安全熔断：禁止自动化操作 $pkg，请改用 app_handoff。")
            if (SafetyFuse.peerAccessibilityPackages(ctx.app).any { it.equals(pkg, ignoreCase = true) }) {
                return ToolResult.fail("安全熔断：应用 $pkg 正在运行其他无障碍服务，Pib 已自动避让。")
            }
            if (extraBlocked.any { it.equals(pkg, ignoreCase = true) }) {
                return ToolResult.fail("安全熔断：应用 $pkg 已被列入本机熔断保护名单。")
            }
            if (allowlist.none { it.equals(pkg, ignoreCase = true) }) return ToolResult.fail("应用 $pkg 不在无障碍白名单中。")
            val launch = ctx.app.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return ToolResult.fail("手机未安装 $pkg。")
            ctx.app.startActivity(launch)
            delay(800)
            return ToolResult.ok("已打开白名单应用 $pkg。")
        }
        val svc = PibAccessibilityService.instance
            ?: return ToolResult.fail("尚未开启 Pib 无障碍辅助服务，请到「更多 → 工具权限」开启。")
        return svc.actOnAllowedScreen(act, args.s("target"), args.s("text"), allowlist, extraBlocked = extraBlocked)
    }
}
