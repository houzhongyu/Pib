package com.houxinzhe.pib.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Queries account balance on providers that expose a standard balance endpoint (DeepSeek, SiliconFlow, OpenRouter). */
object BalanceClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

    suspend fun query(baseUrl: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) return@withContext "未填写 API Key"
        val host = baseUrl.lowercase()
        val url = when {
            "deepseek.com" in host -> "https://api.deepseek.com/user/balance"
            "siliconflow.cn" in host -> "https://api.siliconflow.cn/v1/user/info"
            "openrouter.ai" in host -> "https://openrouter.ai/api/v1/key"
            else -> return@withContext "该厂商请前往网页控制台查看余额"
        }
        runCatching {
            val req = Request.Builder().url(url).header("Authorization", "Bearer $key").get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext "暂无法读取余额（HTTP ${resp.code}）"
                parseBalance(host, body)
            }
        }.getOrElse { "查询失败：${it.message}" }
    }

    internal fun parseBalance(host: String, body: String): String {
        val obj = json.parseToJsonElement(body).jsonObject
        return when {
            "deepseek.com" in host -> {
                val infos = obj["balance_infos"]?.jsonArray.orEmpty()
                if (infos.isEmpty()) "账户可用"
                else infos.joinToString(" · ") {
                    val o = it.jsonObject
                    "${o["currency"]?.jsonPrimitive?.contentOrNull ?: "CNY"} ${o["total_balance"]?.jsonPrimitive?.contentOrNull ?: "0.00"}"
                }
            }
            "siliconflow.cn" in host -> {
                val bal = obj["data"]?.jsonObject?.get("totalBalance")?.jsonPrimitive?.contentOrNull
                if (bal != null) "CNY $bal" else "账户可用"
            }
            "openrouter.ai" in host -> {
                val rem = obj["data"]?.jsonObject?.get("limit_remaining")?.jsonPrimitive?.doubleOrNull
                if (rem != null) "USD %.2f".format(rem) else "不限额或请在官网查看"
            }
            else -> "账户可用"
        }
    }
}
