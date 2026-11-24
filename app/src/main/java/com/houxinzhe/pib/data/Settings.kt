package com.houxinzhe.pib.data

import android.content.Context

/** OpenAI-compatible providers available in mainland China. Users bring their own key. */
data class ProviderPreset(val id: String, val label: String, val baseUrl: String, val model: String, val keyUrl: String)

/** Same five stops as the iOS composer slider. */
data class ThinkingTier(val id: String, val title: String)

object ThinkingTiers {
    val all = listOf(
        ThinkingTier("off", "关闭"),
        ThinkingTier("low", "轻快"),
        ThinkingTier("medium", "自适应"),
        ThinkingTier("high", "深入"),
        ThinkingTier("max", "极致"),
    )
    fun title(id: String) = all.firstOrNull { it.id == id }?.title ?: "自适应"
}

object Providers {
    val all = listOf(
        ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-flash", "https://platform.deepseek.com/api_keys"),
        ProviderPreset("qwen", "通义千问（阿里云百炼）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "https://bailian.console.aliyun.com/"),
        ProviderPreset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "https://open.bigmodel.cn/usercenter/apikeys"),
        ProviderPreset("moonshot", "Kimi（月之暗面）", "https://api.moonshot.cn/v1", "moonshot-v1-8k", "https://platform.moonshot.cn/console/api-keys"),
        ProviderPreset("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3", "https://cloud.siliconflow.cn/account/ak"),
        ProviderPreset("custom", "自定义（OpenAI 兼容）", "", "", ""),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}

/** Non-secret preferences. Secrets live in [SecureStore]. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("pib_settings", Context.MODE_PRIVATE)
    val secrets = SecureStore(context)

    var providerId: String
        get() = p.getString("provider", "deepseek")!!
        set(v) = p.edit().putString("provider", v).apply()
    var baseUrl: String
        get() = p.getString("base_url", null) ?: Providers.byId(providerId).baseUrl
        set(v) = p.edit().putString("base_url", v.trim()).apply()
    var model: String
        get() = p.getString("model", null) ?: Providers.byId(providerId).model
        set(v) = p.edit().putString("model", v.trim()).apply()

    /** Assistant display name. Original character "Pib"; user can rename. */
    var assistantName: String
        get() = p.getString("assistant_name", null)?.takeIf { it.isNotBlank() } ?: "Pib"
        set(v) = p.edit().putString("assistant_name", v.trim()).apply()
    var userName: String
        get() = p.getString("user_name", "")!!
        set(v) = p.edit().putString("user_name", v.trim()).apply()
    var homeCity: String
        get() = p.getString("home_city", "")!!
        set(v) = p.edit().putString("home_city", v.trim()).apply()

    /** Strict mode: confirm every non-read action, not only irreversible ones. */
    var confirmAllWrites: Boolean
        get() = p.getBoolean("confirm_all_writes", false)
        set(v) = p.edit().putBoolean("confirm_all_writes", v).apply()

    /** Reasoning depth shown on the composer: off, low, medium, high, max. */
    var thinkingTier: String
        get() = p.getString("thinking_tier", "medium")!!
        set(v) = p.edit().putString("thinking_tier", v).apply()

    var lastThread: String?
        get() = p.getString("last_thread", null)
        set(v) = p.edit().putString("last_thread", v).apply()

    var privacyAccepted: Boolean
        get() = p.getBoolean("privacy_accepted", false)
        set(v) = p.edit().putBoolean("privacy_accepted", v).apply()

    /** Opt-in local notification parser for parcel pickup codes & 12306/flight notices. Default off. */
    var autoParseNotifications: Boolean
        get() = p.getBoolean("auto_parse_notifications", false)
        set(v) = p.edit().putBoolean("auto_parse_notifications", v).apply()

    /** Optional Android system TTS readout on the Companion tab. Default off. */
    var speakReplies: Boolean
        get() = p.getBoolean("speak_replies", false)
        set(v) = p.edit().putBoolean("speak_replies", v).apply()

    /** Secondary model for quick switching via the ⇄ button in the model popover. */
    var altModel: String
        get() = p.getString("alt_model", null)?.takeIf { it.isNotBlank() } ?: defaultAltModel(providerId, model)
        set(v) = p.edit().putString("alt_model", v.trim()).apply()

    /** Packages the user explicitly allows Pib's AccessibilityService to inspect/tap. */
    var accessibilityAllowlist: Set<String>
        get() = p.getStringSet("a11y_allowlist", null)?.toSet() ?: DEFAULT_ALLOWED_PACKAGES
        set(v) = p.edit().putStringSet("a11y_allowlist", v.toSet()).apply()

    fun swapModel() {
        val cur = model
        val next = altModel.takeIf { it.isNotBlank() && it != cur } ?: defaultAltModel(providerId, cur)
        altModel = cur
        model = next
    }

    var llmKey: String
        get() = secrets.get(SecureStore.LLM_KEY)
        set(v) = secrets.put(SecureStore.LLM_KEY, v)
    var amapKey: String
        get() = secrets.get(SecureStore.AMAP_KEY)
        set(v) = secrets.put(SecureStore.AMAP_KEY, v)

    // ---- Remote Computer & DSH Bridge (optional) ----
    var bridgeEnabled: Boolean
        get() = p.getBoolean("bridge_enabled", false)
        set(v) = p.edit().putBoolean("bridge_enabled", v).apply()
    var bridgeUrl: String
        get() = p.getString("bridge_url", "")!!
        set(v) = p.edit().putString("bridge_url", v.trim()).apply()
    var bridgeFallbackUrl: String
        get() = p.getString("bridge_fallback_url", "")!!
        set(v) = p.edit().putString("bridge_fallback_url", v.trim()).apply()
    var bridgeDefaultCwd: String
        get() = p.getString("bridge_cwd", "")!!
        set(v) = p.edit().putString("bridge_cwd", v.trim()).apply()
    var bridgeToken: String
        get() = secrets.get(SecureStore.BRIDGE_TOKEN)
        set(v) = secrets.put(SecureStore.BRIDGE_TOKEN, v)

    fun importBridgePairing(rawUri: String): Boolean {
        val parsed = parseBridgePairingUri(rawUri) ?: return false
        bridgeEnabled = true
        bridgeUrl = parsed.url
        if (parsed.fallback.isNotBlank()) bridgeFallbackUrl = parsed.fallback
        if (parsed.cwd.isNotBlank()) bridgeDefaultCwd = parsed.cwd
        bridgeToken = parsed.token
        return true
    }

    /** Display name for the composer, e.g. "DeepSeek Flash". */
    val modelDisplayName: String get() = when {
        model == "deepseek-flash" -> "DeepSeek Flash"
        model == "deepseek-reasoner" -> "DeepSeek R1"
        model == "deepseek-chat" -> "DeepSeek Chat"
        model.startsWith("deepseek-v4-pro") || model == "deepseek-pro" -> "DeepSeek Pro"
        else -> model.substringAfterLast('/')
    }

    fun applyPreset(id: String) {
        providerId = id
        val preset = Providers.byId(id)
        p.edit().putString("base_url", preset.baseUrl).putString("model", preset.model).remove("alt_model").apply()
    }

    val isLlmReady: Boolean get() = llmKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        val DEFAULT_ALLOWED_PACKAGES = setOf(
            "com.android.BBKClock",
            "com.android.deskclock",
            "com.miui.clock",
            "com.huawei.deskclock",
            "com.coloros.alarmclock",
            "com.android.calendar",
            "com.bbk.calendar",
            "com.android.calculator2",
            "com.vivo.calculator",
            "org.mozilla.firefox",
            "com.android.chrome",
            "com.vivo.browser",
            "com.netease.cloudmusic",
        )

        fun defaultAltModel(providerId: String, current: String): String = when (providerId) {
            "deepseek" -> if (current == "deepseek-flash") "deepseek-chat" else "deepseek-flash"
            "qwen" -> if (current == "qwen-plus") "qwen-max" else "qwen-plus"
            "zhipu" -> if (current == "glm-4-flash") "glm-4-plus" else "glm-4-flash"
            else -> current
        }

        fun parseBridgePairingUri(raw: String): BridgePairing? = runCatching {
            val uri = java.net.URI(raw.trim())
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (scheme != "pib" || host != "pair-bridge") return null
            val params = uri.rawQuery.orEmpty().split('&').mapNotNull { kv ->
                val idx = kv.indexOf('=')
                if (idx <= 0) null
                else java.net.URLDecoder.decode(kv.substring(0, idx), "UTF-8") to java.net.URLDecoder.decode(kv.substring(idx + 1), "UTF-8")
            }.toMap()
            val url = params["url"]?.trim().orEmpty()
            val token = params["token"]?.trim().orEmpty()
            if (url.isEmpty() || token.length < 16) return null
            BridgePairing(url = url, token = token, fallback = params["fallback"]?.trim().orEmpty(), cwd = params["cwd"]?.trim().orEmpty())
        }.getOrNull()
    }
}

data class BridgePairing(val url: String, val token: String, val fallback: String = "", val cwd: String = "")
