package com.houxinzhe.pib.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class LlmException(val code: Int, message: String) : IOException(message)

data class LlmConfig(val baseUrl: String, val apiKey: String, val model: String, val thinking: String = "medium")

/** Abstraction so the agent loop can be unit-tested with a fake model while supporting live SSE streaming. */
fun interface ChatModel {
    suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage

    suspend fun stream(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onDelta: (String) -> Unit = {},
        onReasoning: () -> Unit = {},
    ): ChatMessage = complete(messages, tools).also { msg ->
        msg.content?.takeIf { it.isNotEmpty() }?.let(onDelta)
    }
}

class LlmClient(private val config: () -> LlmConfig) : ChatModel {
    private val json = Json { ignoreUnknownKeys = true }
    private val media = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage =
        request(messages, tools, streamMode = false, onDelta = {}, onReasoning = {})

    override suspend fun stream(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onDelta: (String) -> Unit,
        onReasoning: () -> Unit,
    ): ChatMessage = request(messages, tools, streamMode = true, onDelta = onDelta, onReasoning = onReasoning)

    private suspend fun request(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        streamMode: Boolean,
        onDelta: (String) -> Unit,
        onReasoning: () -> Unit,
    ): ChatMessage = withContext(Dispatchers.IO) {
        val cfg = config()
        if (cfg.apiKey.isBlank()) throw LlmException(0, "还没有填写模型 API Key，请到「设置」里填写。")
        val targetUrl = endpoint(cfg.baseUrl)
        val body = buildRequestBody(cfg, messages, tools, streamMode)
        val reqBuilder = Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer ${cfg.apiKey.trim()}")
        if (streamMode) reqBuilder.header("Accept", "text/event-stream")
        val httpCall = http.newCall(reqBuilder.post(body.toString().toRequestBody(media)).build())
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) httpCall.cancel()
        }
        try {
            httpCall.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errText = resp.body?.string().orEmpty()
                    throw LlmException(resp.code, httpMessage(resp.code, errText))
                }
                val respBody = resp.body ?: throw LlmException(200, "模型返回为空")
                val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
                if (!streamMode || (!contentType.contains("event-stream") && contentType.contains("json"))) {
                    val full = respBody.string()
                    val msg = parse(full)
                    if (streamMode) msg.content?.takeIf { it.isNotEmpty() }?.let(onDelta)
                    return@withContext msg
                }
                respBody.charStream().buffered().use { reader ->
                    parseSseOrJson(reader, onDelta, onReasoning)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            if (coroutineContext[Job]?.isActive == false) throw CancellationException("已停止生成")
            throw e
        } finally {
            cancelHandle?.dispose()
        }
    }

    internal fun buildRequestBody(
        cfg: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        streamMode: Boolean,
    ) = buildJsonObject {
        put("model", cfg.model)
        put("messages", JsonArray(messages.map { it.toJson() }))
        if (tools.isNotEmpty()) {
            put("tools", JsonArray(tools.map { it.toJson() }))
            put("tool_choice", "auto")
        }
        val host = EndpointSecurity.hostOf(cfg.baseUrl).orEmpty()
        if (host == "api.deepseek.com" || host.endsWith(".deepseek.com")) {
            if (cfg.thinking == "off") {
                put("thinking", buildJsonObject { put("type", "disabled") })
            } else {
                // DeepSeek reasoning_effort accepts low / medium / high; map UI "max" to "high" + 6000 max_tokens.
                val effort = if (cfg.thinking == "max") "high" else cfg.thinking
                put("thinking", buildJsonObject { put("type", "enabled") })
                put("reasoning_effort", effort)
            }
        }
        put("temperature", 0.4)
        put("max_tokens", if (cfg.thinking == "max") 6000 else if (cfg.thinking == "high") 4000 else 2000)
        put("stream", streamMode)
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

    internal fun parseSseString(raw: String, onDelta: (String) -> Unit = {}, onReasoning: () -> Unit = {}): ChatMessage =
        parseSseOrJson(BufferedReader(StringReader(raw)), onDelta, onReasoning)

    private data class PartialToolCall(
        var id: String? = null,
        val name: StringBuilder = StringBuilder(),
        val args: StringBuilder = StringBuilder(),
    )

    private fun parseSseOrJson(
        reader: BufferedReader,
        onDelta: (String) -> Unit,
        onReasoning: () -> Unit,
    ): ChatMessage {
        val firstNonBlank = buildString {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isNotEmpty()) {
                    append(line)
                    break
                }
            }
        }
        if (firstNonBlank.isEmpty()) throw LlmException(200, "模型返回为空")
        // Fallback if server ignored stream=true and returned a single JSON object
        if (firstNonBlank.trimStart().startsWith("{")) {
            val rest = reader.readText()
            val msg = parse(firstNonBlank + "\n" + rest)
            msg.content?.takeIf { it.isNotEmpty() }?.let(onDelta)
            return msg
        }

        val contentBuf = StringBuilder()
        val reasoningBuf = StringBuilder()
        val toolMap = TreeMap<Int, PartialToolCall>()
        var announcedReasoning = false

        fun handleLine(rawLine: String) {
            val line = rawLine.trim()
            if (!line.startsWith("data:")) return
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return
            val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
            val delta = choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject ?: return

            delta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let { r ->
                if (r.isNotEmpty()) {
                    reasoningBuf.append(r)
                    if (!announcedReasoning && contentBuf.isEmpty()) {
                        announcedReasoning = true
                        onReasoning()
                    }
                }
            }
            delta["content"]?.jsonPrimitive?.contentOrNull?.let { c ->
                if (c.isNotEmpty()) {
                    contentBuf.append(c)
                    onDelta(contentBuf.toString())
                }
            }
            delta["tool_calls"]?.jsonArray?.forEachIndexed { fallbackIdx, el ->
                val o = el.jsonObject
                val idx = o["index"]?.jsonPrimitive?.intOrNull ?: fallbackIdx
                val slot = toolMap.getOrPut(idx) { PartialToolCall() }
                o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { slot.id = it }
                o["function"]?.jsonObject?.let { fn ->
                    fn["name"]?.jsonPrimitive?.contentOrNull?.let { slot.name.append(it) }
                    fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { slot.args.append(it) }
                }
            }
        }

        handleLine(firstNonBlank)
        var nextLine: String?
        while (reader.readLine().also { nextLine = it } != null) {
            handleLine(nextLine!!)
        }

        val calls = toolMap.entries.mapNotNull { (idx, tc) ->
            val fnName = tc.name.toString().trim()
            if (fnName.isEmpty()) null
            else ToolCall(
                id = tc.id ?: "call_$idx",
                name = fnName,
                arguments = tc.args.toString().ifBlank { "{}" },
            )
        }.takeIf { it.isNotEmpty() }

        val finalContent = contentBuf.toString().takeIf { it.isNotEmpty() || calls == null }
        val finalReasoning = reasoningBuf.toString().takeIf { it.isNotEmpty() }
        return ChatMessage.assistant(finalContent, calls, finalReasoning)
    }

    companion object {
        fun endpoint(baseUrl: String): String {
            val safe = try {
                EndpointSecurity.validateUrl(baseUrl, "模型接口地址")
            } catch (e: IllegalArgumentException) {
                throw LlmException(0, e.message ?: "模型接口地址无效")
            }
            val b = safe.trimEnd('/')
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
