package com.houxinzhe.pib.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.houxinzhe.pib.agent.FuseVerdict
import com.houxinzhe.pib.agent.SafetyFuse
import com.houxinzhe.pib.agent.ToolResult
import com.houxinzhe.pib.agent.UiNodeSummary

/**
 * Whitelisted UI helper for ordinary non-blocked utility apps.
 * Never runs in the background on its own; only inspects the active window on explicit
 * tool invocation and enforces [SafetyFuse] before reading or acting.
 */
class PibAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event-free idle design: Pib never scrapes screens passively in the background.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun readAllowedScreen(allowlist: Set<String>, extraBlocked: Set<String> = emptySet()): ToolResult {
        val root = rootInActiveWindow ?: return ToolResult.fail("当前拿不到活动窗口内容。")
        val pkg = root.packageName?.toString().orEmpty()
        val peerPkgs = SafetyFuse.peerAccessibilityPackages(this)
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val summaries = mutableListOf<UiNodeSummary>()
        collectNodes(root, nodes, summaries, maxNodes = 60)
        return when (val v = SafetyFuse.check(pkg, allowlist, summaries, extraBlocked = extraBlocked, peerA11yPackages = peerPkgs)) {
            is FuseVerdict.Blocked -> ToolResult.fail(v.reason)
            FuseVerdict.Allowed -> {
                if (summaries.isEmpty()) ToolResult.ok("前台应用 $pkg 当前没有可读的文字控件。")
                else ToolResult.ok("前台应用：$pkg\n" + summaries.joinToString("\n") { n ->
                    val flags = buildList {
                        if (n.clickable) add("可点")
                        if (n.editable) add("可输入")
                        if (n.scrollable) add("可滚动")
                    }.joinToString(",")
                    "[${n.index}] ${n.label}${if (flags.isNotEmpty()) " ($flags)" else ""}"
                })
            }
        }
    }

    fun actOnAllowedScreen(
        action: String,
        target: String?,
        text: String?,
        allowlist: Set<String>,
        extraBlocked: Set<String> = emptySet(),
    ): ToolResult {
        val peerPkgs = SafetyFuse.peerAccessibilityPackages(this)
        if (action == "back") {
            val root = rootInActiveWindow ?: return ToolResult.fail("当前拿不到活动窗口。")
            val pkg = root.packageName?.toString().orEmpty()
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            val summaries = mutableListOf<UiNodeSummary>()
            collectNodes(root, nodes, summaries, maxNodes = 60)
            val v = SafetyFuse.check(pkg, allowlist, summaries, extraBlocked = extraBlocked, peerA11yPackages = peerPkgs)
            if (v is FuseVerdict.Blocked) return ToolResult.fail(v.reason)
            return if (performGlobalAction(GLOBAL_ACTION_BACK)) ToolResult.ok("已在 $pkg 执行返回。")
            else ToolResult.fail("系统拒绝执行返回。")
        }

        val root = rootInActiveWindow ?: return ToolResult.fail("当前拿不到活动窗口。")
        val pkg = root.packageName?.toString().orEmpty()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val summaries = mutableListOf<UiNodeSummary>()
        collectNodes(root, nodes, summaries, maxNodes = 80)
        val verdict = SafetyFuse.check(pkg, allowlist, summaries, extraBlocked = extraBlocked, peerA11yPackages = peerPkgs)
        if (verdict is FuseVerdict.Blocked) return ToolResult.fail(verdict.reason)

        return when (action) {
            "click" -> {
                val q = target?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolResult.fail("缺少要点击的按钮文字 target。")
                val idx = summaries.indexOfFirst { it.label.equals(q, ignoreCase = true) }
                    .takeIf { it >= 0 } ?: summaries.indexOfFirst { it.label.contains(q, ignoreCase = true) }
                if (idx < 0) return ToolResult.fail("界面上没找到包含「$q」的控件。")
                val clickable = findClickable(nodes[idx]) ?: return ToolResult.fail("控件「${summaries[idx].label}」不可点击。")
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ToolResult.ok("已在 $pkg 点击「${summaries[idx].label}」。")
                else ToolResult.fail("点击「${summaries[idx].label}」未生效。")
            }
            "input" -> {
                val value = text ?: return ToolResult.fail("缺少输入内容 text。")
                val idx = if (!target.isNullOrBlank()) {
                    summaries.indexOfFirst { it.editable && it.label.contains(target, ignoreCase = true) }
                } else {
                    summaries.indexOfFirst { it.editable }
                }
                if (idx < 0) return ToolResult.fail("当前界面没有可输入的文本框。")
                if (summaries[idx].password) return ToolResult.fail("安全熔断：禁止向密码框输入。")
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
                if (nodes[idx].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) ToolResult.ok("已在输入框填入「$value」。")
                else ToolResult.fail("填入文字失败。")
            }
            "scroll_down", "scroll_up" -> {
                val idx = summaries.indexOfFirst { it.scrollable }
                if (idx < 0) return ToolResult.fail("当前界面没有可滚动区域。")
                val op = if (action == "scroll_down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (nodes[idx].performAction(op)) ToolResult.ok("已在 $pkg ${if (action == "scroll_down") "向下" else "向上"}滚动。")
                else ToolResult.fail("已经滑到头或无法滚动。")
            }
            else -> ToolResult.fail("不支持的动作：$action")
        }
    }

    private fun findClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        repeat(4) {
            if (cur?.isClickable == true) return cur
            cur = cur?.parent
        }
        return null
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        rawOut: MutableList<AccessibilityNodeInfo>,
        sumOut: MutableList<UiNodeSummary>,
        maxNodes: Int,
    ) {
        if (node == null || sumOut.size >= maxNodes || !node.isVisibleToUser) return
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        val keep = t.isNotEmpty() || d.isNotEmpty() || node.isEditable || node.isScrollable || node.isPassword
        if (keep) {
            val idx = sumOut.size
            rawOut += node
            sumOut += UiNodeSummary(
                index = idx,
                text = t.take(80),
                desc = d.take(60),
                className = node.className?.toString()?.substringAfterLast('.').orEmpty(),
                clickable = node.isClickable || node.parent?.isClickable == true,
                editable = node.isEditable,
                scrollable = node.isScrollable,
                password = node.isPassword,
            )
        }
        for (i in 0 until node.childCount) {
            if (sumOut.size >= maxNodes) break
            collectNodes(node.getChild(i), rawOut, sumOut, maxNodes)
        }
    }

    companion object {
        @Volatile
        var instance: PibAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}
