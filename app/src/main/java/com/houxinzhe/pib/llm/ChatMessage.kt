package com.houxinzhe.pib.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
data class ToolCall(val id: String, val name: String, val arguments: String)

/** One message in OpenAI chat-completions format. */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val time: Long = System.currentTimeMillis(),
    /** Thinking-mode trace. DeepSeek requires it echoed back inside the same tool loop. */
    val reasoning: String? = null,
    /** Transient base64 data URL for multimodal user turns; stripped before persisting to disk. */
    val imageDataUrl: String? = null,
    /** Display filename of an attached photo or text file. */
    val attachmentName: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("role", role)
        if (role == "user" && imageDataUrl != null) {
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", content?.takeIf { it.isNotBlank() } ?: "请看这张图片。") })
                add(buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", imageDataUrl) }
                })
            })
        } else {
            if (content != null) put("content", content) else put("content", JsonPrimitive(null as String?))
        }
        if (!toolCalls.isNullOrEmpty()) put("tool_calls", buildJsonArray {
            toolCalls.forEach { tc ->
                add(buildJsonObject {
                    put("id", tc.id)
                    put("type", "function")
                    putJsonObject("function") { put("name", tc.name); put("arguments", tc.arguments) }
                })
            }
        })
        if (toolCallId != null) put("tool_call_id", toolCallId)
        if (role == "assistant" && reasoning != null) put("reasoning_content", reasoning)
    }

    companion object {
        fun system(text: String) = ChatMessage("system", text)
        fun user(text: String, imageDataUrl: String? = null, attachmentName: String? = null) =
            ChatMessage("user", text, imageDataUrl = imageDataUrl, attachmentName = attachmentName)
        fun assistant(text: String?, calls: List<ToolCall>? = null, reasoning: String? = null) = ChatMessage("assistant", text, calls, reasoning = reasoning)
        fun tool(id: String, text: String) = ChatMessage("tool", text, toolCallId = id)
    }
}

/** JSON-schema description of a function the model may call. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        }
    }
}

/** One parameter in a tool schema. */
data class Param(val name: String, val description: String, val type: String = "string", val required: Boolean = false, val enum: List<String>? = null)

fun str(name: String, description: String, required: Boolean = false, enum: List<String>? = null) = Param(name, description, "string", required, enum)
fun int(name: String, description: String, required: Boolean = false) = Param(name, description, "integer", required)
fun bool(name: String, description: String, required: Boolean = false) = Param(name, description, "boolean", required)

/** Small helper to write parameter schemas without boilerplate. */
fun schema(vararg params: Param): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        params.forEach { p ->
            putJsonObject(p.name) {
                put("type", p.type)
                put("description", p.description)
                if (p.enum != null) put("enum", buildJsonArray { p.enum.forEach { add(it) } })
            }
        }
    }
    put("required", buildJsonArray { params.filter { it.required }.forEach { add(it.name) } })
}
