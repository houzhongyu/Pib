package com.houxinzhe.pib.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class LlmException(val code: Int, message: String) : IOException(message)

data class LlmConfig(val baseUrl: String, val apiKey: String, val model: String, val thinking: String = "medium")

/** Abstraction so the agent loop can be unit-tested with a fake model. */
fun interface ChatModel {
    suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage
}

class LlmClient(private val config: () -> LlmConfig) : ChatModel {
    private val json = Json { ignoreUnknownKeys = true }
    private val media = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage = withContext(Dispatchers.IO) {
        val cfg = config()
        if (cfg.apiKey.isBlank()) throw LlmException(0, "还没有填写模型 API Key，请到「设置」里填写。")
        val body = buildJsonObject {
            put("model", cfg.model)
            put("messages", JsonArray(messages.map { it.toJson() }))
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(tools.map { it.toJson() }))
                put("tool_choice", "auto")
            }
            if (cfg.baseUrl.contains("api.deepseek.com")) {
                // DeepSeek V4.x thinking switch, same mapping as the iOS app.
                if (cfg.thinking == "off") put("thinking", buildJsonObject { put("type", "disabled") })
                else { put("thinking", buildJsonObject { put("type", "enabled") }); put("reasoning_effort", cfg.thinking) }
            }
            put("temperature", 0.4)
            put("max_tokens", if (cfg.thinking == "max") 6000 else if (cfg.thinking == "high") 4000 else 2000)
            put("stream", false)
        }
        val req = Request.Builder()
            .url(endpoint(cfg.baseUrl))
            .header("Authorization", "Bearer ${cfg.apiKey.trim()}")
            .post(body.toString().toRequestBody(media))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmException(resp.code, httpMessage(resp.code, text))
            parse(text)
        }
    }

    internal fun parse(text: String): ChatMessage {
        val msg = json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject ?: throw LlmException(200, "模型返回格式无法识别")
        val content = msg["content"]?.jsonPrimitive?.contentOrNull
        val calls = msg["tool_calls"]?.jsonArray?.mapIndexed { i, el ->
            val o = el.jsonObject
            val fn = o["function"]!!.jsonObject
            ToolCall(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: "call_$i",
                name = fn["name"]!!.jsonPrimitive.content,
                arguments = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
            )
        }
        val reasoning = msg["reasoning_content"]?.jsonPrimitive?.contentOrNull
        return ChatMessage.assistant(content, calls?.takeIf { it.isNotEmpty() }, reasoning)
    }

    companion object {
        fun endpoint(baseUrl: String): String {
            val b = baseUrl.trim().trimEnd('/')
            return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
        }

        fun httpMessage(code: Int, body: String): String = when (code) {
            401 -> "API Key 不对或已失效（401），请到「设置」重新填写。"
            402 -> "模型账户余额不足（402）。"
            403 -> "这个 Key 没有权限使用当前模型（403）。"
            404 -> "接口地址或模型名不对（404），请检查设置。"
            429 -> "请求太频繁或额度用完（429），稍后再试。"
            in 500..599 -> "模型服务暂时出错（$code），稍后再试。"
            else -> "接口返回 $code：${body.take(160)}"
        }
    }
}
