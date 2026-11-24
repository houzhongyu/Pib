package com.houxinzhe.pib.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

data class UiNodeSummary(
    val index: Int,
    val text: String,
    val desc: String = "",
    val className: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val password: Boolean = false,
) {
    val label: String get() = listOf(text.trim(), desc.trim()).filter { it.isNotEmpty() }.distinct().joinToString(" / ")
}

sealed interface FuseVerdict {
    data object Allowed : FuseVerdict
    data class Blocked(val reason: String) : FuseVerdict
}

/**
 * Immutable hard safety boundaries for Android UI assistance:
 * 1. Never touch WeChat, QQ, Alipay, banking/wallet apps, password managers, super-apps, or permission installers.
 * 2. Automatically yield/fuse any other app currently running an active AccessibilityService (zero cross-agent collision),
 *    plus any on-device custom blocked package configured by the user.
 * 3. Only operate inside packages explicitly enabled in the user's allowlist.
 * 4. Trip immediately if the screen contains payment, transfer, password, OTP, CAPTCHA, or factory-reset controls.
 */
object SafetyFuse {
    val HARD_BLOCKED_PACKAGES = setOf(
        // Social / IM (account ban risk & privacy boundary)
        "com.tencent.mm",
        "com.tencent.wework",
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.alibaba.android.rimet",
        "com.ss.android.lark",
        // Payment & Finance
        "com.eg.android.alipaygphone",
        "com.unionpay",
        "com.tencent.qianbao",
        "com.icbc",
        "com.chinamworld.main",
        "com.chinamworld.bocmbci",
        "com.android.bankabc",
        "cmb.pb",
        "com.bankcomm.bankcomm",
        "com.yitong.mbank.psbc",
        "com.pingan.paces.ccms",
        "cn.com.spdb.mobilebank.per",
        "com.ecitic.bank.mobile",
        "com.cebbank.mobile.cemb",
        "cn.com.cmbc.newmbank",
        // Domestic e-commerce / local-life super-apps -> use app_handoff deep links instead
        "com.taobao.taobao",
        "com.jingdong.app.mall",
        "com.xunmeng.pinduoduo",
        "com.sankuai.meituan",
        "me.ele",
        "com.dianping.v1",
        "com.sdu.didi.psnger",
        // System privilege / installer / keyguard / self
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.vivo.permissionmanager",
        "com.android.packageinstaller",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.android.systemui",
        "com.houxinzhe.pib",
        "com.houxinzhe.pib.debug",
    )

    private val BLOCKED_PKG_PATTERN = Regex(
        """(?i)(\.bank|bank\.|alipay|wallet|qianbao|unionpay|securities|stock|permissioncontroller|packageinstaller|bitwarden|1password|onepassword|keepass|authenticator|lastpass)"""
    )

    private val PAYMENT_FUSE = Regex("""立即支付|确认支付|去支付|支付密码|输入密码|免密支付|指纹支付|面容支付|确认转账|立即转账|向.{1,12}转账|收银台|付款码|发红包""")
    private val RESET_FUSE = Regex("""恢复出厂设置|清除所有数据|抹掉所有内容|格式化手机|永久删除账号|注销账号""")
    private val AUTH_CAPTCHA_FUSE = Regex("""短信验证码|输入验证码|图形验证码|滑动验证|安全验证|人机验证|拖动滑块|请完成验证""")

    fun isHardBlockedPackage(pkg: String): Boolean {
        val low = pkg.trim().lowercase()
        if (low.isEmpty()) return true
        return low in HARD_BLOCKED_PACKAGES || BLOCKED_PKG_PATTERN.containsMatchIn(low)
    }

    /**
     * Discovers other apps that currently have an active AccessibilityService enabled on this device
     * (e.g. another on-device assistant runtime, screen reader, autofill vault, or automation service),
     * ensuring Pib never reads or taps inside them.
     */
    fun peerAccessibilityPackages(context: Context): Set<String> = runCatching {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return emptySet()
        val selfPkg = context.packageName.lowercase()
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.resolveInfo?.serviceInfo?.packageName?.trim()?.lowercase() }
            .filter { it.isNotEmpty() && it != selfPkg && it != "com.houxinzhe.pib" && it != "com.houxinzhe.pib.debug" }
            .toSet()
    }.getOrDefault(emptySet())

    fun check(
        pkg: String,
        allowlist: Set<String>,
        nodes: List<UiNodeSummary>,
        extraBlocked: Set<String> = emptySet(),
        peerA11yPackages: Set<String> = emptySet(),
    ): FuseVerdict {
        val cleanPkg = pkg.trim()
        if (isHardBlockedPackage(cleanPkg)) {
            return FuseVerdict.Blocked("安全熔断：禁止读取或操作「$cleanPkg」（社交、支付金融、密码库、超级 App 或系统权限页）。请改用 app_handoff 跳转或由用户本人操作。")
        }
        if (peerA11yPackages.any { it.equals(cleanPkg, ignoreCase = true) }) {
            return FuseVerdict.Blocked("安全熔断：应用「$cleanPkg」正在运行其他无障碍服务，Pib 已自动熔断保护互不干扰。")
        }
        if (extraBlocked.any { it.equals(cleanPkg, ignoreCase = true) }) {
            return FuseVerdict.Blocked("安全熔断：应用「$cleanPkg」已被列入本机熔断保护名单。")
        }
        if (allowlist.none { it.equals(cleanPkg, ignoreCase = true) }) {
            return FuseVerdict.Blocked("应用「$cleanPkg」不在无障碍辅助白名单中。如需允许，请让用户在「更多 → 工具权限 → 无障碍白名单」中勾选。")
        }
        if (nodes.any { it.password }) {
            return FuseVerdict.Blocked("安全熔断：当前界面包含密码输入框，Pib 已停止读取与操作，请由你本人输入。")
        }
        val allText = nodes.joinToString("\n") { it.label }
        PAYMENT_FUSE.find(allText)?.let {
            return FuseVerdict.Blocked("安全熔断：检测到支付或转账界面（${it.value}），已交还用户本人确认。")
        }
        RESET_FUSE.find(allText)?.let {
            return FuseVerdict.Blocked("安全熔断：检测到系统重置或高危清除界面（${it.value}），拒绝执行。")
        }
        AUTH_CAPTCHA_FUSE.find(allText)?.let {
            return FuseVerdict.Blocked("安全熔断：检测到验证码或人机验证界面（${it.value}），请由用户本人完成。")
        }
        return FuseVerdict.Allowed
    }
}
