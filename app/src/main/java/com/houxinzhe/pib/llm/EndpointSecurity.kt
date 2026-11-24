package com.houxinzhe.pib.llm

import java.net.URI

/**
 * Shared transport-security guard for LLM providers, balance lookups, and the computer bridge.
 *
 * Security invariant: API keys and bridge credentials must NEVER cross the public internet over
 * cleartext `http://`. Cleartext `http://` is allowed strictly for loopback, `.local` mDNS,
 * `.ts.net` Tailscale MagicDNS, RFC1918 private LAN, and Tailscale CGNAT (`100.64.0.0/10`).
 */
object EndpointSecurity {

    fun validateUrl(raw: String, label: String = "接口地址"): String {
        val trimmed = raw.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: throw IllegalArgumentException("${label}格式无效：$raw")
        val scheme = uri.scheme?.lowercase()
            ?: throw IllegalArgumentException("${label}缺少协议（https:// 或 http://）")
        if (uri.userInfo != null) {
            throw IllegalArgumentException("${label}不允许在 URL 中内嵌账号密码")
        }
        val host = uri.host?.lowercase()?.trim('[', ']')?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("${label}缺少主机名或 IP")
        if (scheme == "https") return trimmed
        if (scheme != "http") throw IllegalArgumentException("不支持的协议：$scheme")
        if (!isPrivateOrLocalHost(host)) {
            throw IllegalArgumentException("安全拦截：公网${label}必须使用 https://（仅本机、局域网 IP、Tailscale 100.x 或 .local 允许 http://）。")
        }
        return trimmed
    }

    fun hostOf(raw: String): String? =
        runCatching { URI(raw.trim()).host?.lowercase()?.trim('[', ']') }.getOrNull()

    fun isPrivateOrLocalHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".ts.net")) return true
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            if (a == 10 || a == 127) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
            if (a == 100 && b in 64..127) return true // Tailscale CGNAT 100.64.0.0/10
        }
        return false
    }
}
