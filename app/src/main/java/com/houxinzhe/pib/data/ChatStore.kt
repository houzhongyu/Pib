package com.houxinzhe.pib.data

import com.houxinzhe.pib.llm.ChatMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Conversation history in OpenAI format, kept on device only. */
class ChatStore(private val file: File?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val items: MutableList<ChatMessage> = file?.takeIf { it.exists() }
        ?.let { f -> runCatching { json.decodeFromString<MutableList<ChatMessage>>(f.readText()) }.getOrNull() }
        ?: mutableListOf()

    val all: List<ChatMessage>
        @Synchronized get() = items.toList()

    /** Reasoning traces and raw image bytes are only needed inside the live turn, so they are not stored on disk. */
    @Synchronized
    fun append(messages: List<ChatMessage>) {
        items += messages.map {
            if (it.reasoning != null || it.imageDataUrl != null) it.copy(reasoning = null, imageDataUrl = null) else it
        }
        while (items.size > MAX_STORED) items.removeAt(0)
        save()
    }

    /** Deletes the Nth user-initiated exchange (0-based) including its tool calls and assistant reply. */
    @Synchronized
    fun deleteExchange(exchangeIndex: Int): Boolean {
        val userIndices = items.indices.filter { items[it].role == "user" }
        if (exchangeIndex !in userIndices.indices) return false
        val start = userIndices[exchangeIndex]
        val endExclusive = if (exchangeIndex + 1 < userIndices.size) userIndices[exchangeIndex + 1] else items.size
        items.subList(start, endExclusive).clear()
        save()
        return true
    }

    /** Pops the last user-initiated exchange and returns its user text so it can be regenerated. */
    @Synchronized
    fun popLastExchange(): String? {
        val lastUser = items.indexOfLast { it.role == "user" }
        if (lastUser < 0) return null
        val text = items[lastUser].content
        items.subList(lastUser, items.size).clear()
        save()
        return text
    }

    @Synchronized
    fun clear() {
        items.clear()
        save()
    }

    /**
     * Recent context for the model. Always starts at a user turn so no orphan
     * tool result or dangling tool_call is sent (providers reject those).
     */
    @Synchronized
    fun contextWindow(maxMessages: Int = 30): List<ChatMessage> {
        val tail = items.takeLast(maxMessages)
        val firstUser = tail.indexOfFirst { it.role == "user" }
        return if (firstUser < 0) emptyList() else tail.drop(firstUser)
    }

    private fun save() {
        val f = file ?: return
        AtomicFile.writeText(f, json.encodeToString(items))
    }

    companion object { const val MAX_STORED = 400 }
}
