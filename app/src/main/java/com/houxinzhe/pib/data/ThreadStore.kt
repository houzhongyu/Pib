package com.houxinzhe.pib.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
enum class ThreadKind { ASSISTANT, COMPANION }

@Serializable
data class ThreadInfo(
    val id: String,
    val kind: ThreadKind,
    var title: String,
    var pinned: Boolean = false,
    var updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Conversation threads, like the iOS sidebar: many assistant chats (pin / search / new)
 * plus one long-running companion chat with Pib. Each thread keeps its own history file.
 */
class ThreadStore(private val dir: File?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val indexFile = dir?.let { File(it, "threads.json") }
    private val infos: MutableList<ThreadInfo> = indexFile?.takeIf { it.exists() }
        ?.let { runCatching { json.decodeFromString<MutableList<ThreadInfo>>(it.readText()) }.getOrNull() }
        ?: mutableListOf()
    private val chats = mutableMapOf<String, ChatStore>()

    val companionId: String = infos.firstOrNull { it.kind == ThreadKind.COMPANION }?.id
        ?: ThreadInfo(COMPANION_ID, ThreadKind.COMPANION, "Pib").also { infos += it; saveIndex() }.id

    fun assistantThreads(query: String = ""): List<ThreadInfo> {
        val q = query.trim()
        return infos.filter { it.kind == ThreadKind.ASSISTANT }
            .filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) || chat(it.id).all.any { m -> m.content?.contains(q, true) == true } }
            .sortedWith(compareByDescending<ThreadInfo> { it.pinned }.thenByDescending { it.updatedAt })
    }

    fun info(id: String): ThreadInfo? = infos.firstOrNull { it.id == id }

    fun chat(id: String): ChatStore = chats.getOrPut(id) { ChatStore(dir?.let { File(it, "$id.json") }) }

    fun create(firstMessage: String): ThreadInfo =
        ThreadInfo(UUID.randomUUID().toString(), ThreadKind.ASSISTANT, titleFrom(firstMessage)).also { infos += it; saveIndex() }

    fun touch(id: String) { info(id)?.updatedAt = System.currentTimeMillis(); saveIndex() }

    fun togglePin(id: String) { info(id)?.let { it.pinned = !it.pinned }; saveIndex() }

    fun delete(id: String) {
        if (id == companionId) { chat(id).clear(); return }
        infos.removeAll { it.id == id }
        chats.remove(id)
        dir?.let { File(it, "$id.json").delete() }
        saveIndex()
    }

    fun clearAll() {
        infos.filter { it.kind == ThreadKind.ASSISTANT }.map { it.id }.forEach { delete(it) }
        chat(companionId).clear()
    }

    private fun saveIndex() {
        val f = indexFile ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(infos))
        tmp.renameTo(f)
    }

    companion object {
        const val COMPANION_ID = "companion"
        fun titleFrom(text: String) = text.trim().lineSequence().first().take(24).ifBlank { "新对话" }
    }
}

/** In-memory chat for 临时聊天: never written to disk, gone when the user ends it. */
fun ephemeralChat() = ChatStore(null)
