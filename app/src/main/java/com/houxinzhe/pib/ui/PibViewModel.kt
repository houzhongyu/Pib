package com.houxinzhe.pib.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.houxinzhe.pib.PibApp
import com.houxinzhe.pib.agent.AgentHost
import com.houxinzhe.pib.agent.Prompt
import com.houxinzhe.pib.agent.Risk
import com.houxinzhe.pib.agent.Tool
import com.houxinzhe.pib.data.AttachmentLoader
import com.houxinzhe.pib.data.ButlerParser
import com.houxinzhe.pib.data.ChatStore
import com.houxinzhe.pib.data.DraftAttachment
import com.houxinzhe.pib.data.ThinkingTiers
import com.houxinzhe.pib.data.ThreadInfo
import com.houxinzhe.pib.data.ephemeralChat
import com.houxinzhe.pib.llm.BalanceClient
import com.houxinzhe.pib.llm.ChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

data class ConfirmRequest(val title: String, val risk: Risk, val preview: String, val answer: CompletableDeferred<Boolean>)
data class PermissionRequest(val permissions: List<String>, val answer: CompletableDeferred<Boolean>)

data class Receipt(val title: String, val text: String, val ok: Boolean)

/** One assistant exchange, rendered like the iOS OwnerTurnList. */
data class Turn(
    val exchangeIndex: Int,
    val request: String,
    val receipts: List<Receipt>,
    val answer: String?,
    val failed: Boolean,
    val attachmentName: String? = null,
)

/** One companion chat row, rendered like the iOS B-style bubbles. */
sealed interface Bubble {
    val exchangeIndex: Int
    data class Mine(override val exchangeIndex: Int, val text: String, val attachmentName: String? = null) : Bubble
    data class Theirs(override val exchangeIndex: Int, val text: String, val timeMillis: Long = 0L) : Bubble
    data class Action(override val exchangeIndex: Int, val receipt: Receipt) : Bubble
}

enum class Tab { HOME, COMPANION, MORE }
enum class Page { SETTINGS, TASKS, TOOLS, ALLOWLIST, BUTLER, BRIDGE, ABOUT }

class PibViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PibApp
    val settings get() = app.settings
    val registry get() = app.registry
    val butler get() = app.butler
    val bridge get() = app.bridge

    private val speaker = PibSpeaker(application)

    var tab by mutableStateOf(Tab.HOME)
    var page by mutableStateOf<Page?>(if (app.settings.isLlmReady) null else Page.SETTINGS)
    var sidebar by mutableStateOf(false)
    var temporary by mutableStateOf(false); private set
    var assistantThread by mutableStateOf(app.settings.lastThread?.takeIf { app.threads.info(it) != null }); private set
    var thinkingTier by mutableStateOf(app.settings.thinkingTier); private set
    var privacyAccepted by mutableStateOf(app.settings.privacyAccepted); private set
    var speakReplies by mutableStateOf(app.settings.speakReplies); private set

    var homeAttachment by mutableStateOf<DraftAttachment?>(null); private set
    var companionAttachment by mutableStateOf<DraftAttachment?>(null); private set
    var attachmentIssue by mutableStateOf<String?>(null); private set
    var balanceText by mutableStateOf<String?>(null); private set

    var busyKey by mutableStateOf<String?>(null); private set
    var pending by mutableStateOf<Pair<String, String>?>(null); private set
    var progress by mutableStateOf<String?>(null); private set
    var confirm by mutableStateOf<ConfirmRequest?>(null); private set
    var permission by mutableStateOf<PermissionRequest?>(null); private set
    var lastError by mutableStateOf<String?>(null); private set

    /** Bumped whenever stored conversations or butler items change so composables re-read them. */
    var revision by mutableIntStateOf(0); private set

    private var tempChat: ChatStore = ephemeralChat()

    val threads get() = app.threads
    val tasks get() = app.tasks.all

    private val host = object : AgentHost {
        override suspend fun confirm(tool: Tool, preview: String): Boolean {
            val d = CompletableDeferred<Boolean>()
            confirm = ConfirmRequest(tool.title, tool.risk, preview, d)
            return d.await().also { confirm = null }
        }

        override suspend fun ensurePermissions(permissions: List<String>): Boolean {
            val d = CompletableDeferred<Boolean>()
            permission = PermissionRequest(permissions, d)
            return d.await().also { permission = null }
        }

        override fun progress(text: String) { progress = text }
    }

    val homeKey get() = if (temporary) "temp" else assistantThread ?: "new"
    val companionKey get() = threads.companionId

    private fun currentHomeChat(): ChatStore? =
        if (temporary) tempChat else assistantThread?.let { threads.chat(it) }

    // ---- Attachments ----

    fun attachImage(uri: Uri, forCompanion: Boolean) {
        attachmentIssue = null
        viewModelScope.launch {
            runCatching { AttachmentLoader.loadImage(app, uri) }
                .onSuccess { if (forCompanion) companionAttachment = it else homeAttachment = it }
                .onFailure { attachmentIssue = it.message ?: "照片读取失败，请重新选择。" }
        }
    }

    fun attachFile(uri: Uri, forCompanion: Boolean) {
        attachmentIssue = null
        viewModelScope.launch {
            runCatching { AttachmentLoader.loadText(app, uri) }
                .onSuccess { if (forCompanion) companionAttachment = it else homeAttachment = it }
                .onFailure { attachmentIssue = it.message ?: "请选用 1 MB 以内的 UTF-8 文本或代码文件。" }
        }
    }

    fun clearAttachment(forCompanion: Boolean) {
        if (forCompanion) companionAttachment = null else homeAttachment = null
        attachmentIssue = null
    }

    // ---- Assistant (首页) ----

    fun homeTurns(): List<Turn> {
        val chat = currentHomeChat() ?: return emptyList()
        return turns(chat.all, ::titleOf)
    }

    fun sendHome(text: String) {
        val att = homeAttachment
        val raw = text.trim()
        if ((raw.isEmpty() && att == null) || busyKey != null) return
        if (!settings.isLlmReady) { page = Page.SETTINGS; return }
        homeAttachment = null; attachmentIssue = null
        val userPrompt = buildPromptWithAttachment(raw, att)
        val chat: ChatStore
        if (temporary) chat = tempChat
        else {
            val id = assistantThread ?: threads.create(raw.ifBlank { att?.name ?: "附件" }).id.also { assistantThread = it; settings.lastThread = it }
            chat = threads.chat(id)
        }
        val key = homeKey
        run(key, userPrompt, chat, Prompt.system(settings), att, speakAfter = false) {
            if (key != "temp") threads.touch(key)
        }
    }

    fun deleteHomeTurn(exchangeIndex: Int) {
        if (busyKey != null) return
        if (currentHomeChat()?.deleteExchange(exchangeIndex) == true) revision++
    }

    fun regenerateHome() {
        if (busyKey != null) return
        val chat = currentHomeChat() ?: return
        val last = chat.popLastExchange() ?: return
        revision++
        val key = homeKey
        run(key, last, chat, Prompt.system(settings), null, speakAfter = false) {
            if (key != "temp") threads.touch(key)
        }
    }

    fun newChat() { temporary = false; assistantThread = null; settings.lastThread = null; tab = Tab.HOME; sidebar = false; page = null; revision++ }

    fun openThread(id: String) { temporary = false; assistantThread = id; settings.lastThread = id; tab = Tab.HOME; sidebar = false; page = null; revision++ }

    fun toggleTemporary() {
        temporary = !temporary
        tempChat = ephemeralChat()
        revision++
    }

    fun togglePin(info: ThreadInfo) { threads.togglePin(info.id); revision++ }
    fun deleteThread(info: ThreadInfo) { threads.delete(info.id); if (assistantThread == info.id) assistantThread = null; revision++ }

    // ---- Companion (Pib) ----

    fun companionBubbles(): List<Bubble> = bubbles(threads.chat(companionKey).all, ::titleOf)

    fun sendCompanion(text: String) {
        val att = companionAttachment
        val raw = text.trim()
        if ((raw.isEmpty() && att == null) || busyKey != null) return
        if (!settings.isLlmReady) { page = Page.SETTINGS; return }
        companionAttachment = null; attachmentIssue = null
        val userPrompt = buildPromptWithAttachment(raw, att)
        run(companionKey, userPrompt, threads.chat(companionKey), Prompt.companion(settings), att, speakAfter = true) {
            threads.touch(companionKey)
        }
    }

    fun deleteCompanionExchange(exchangeIndex: Int) {
        if (busyKey != null) return
        if (threads.chat(companionKey).deleteExchange(exchangeIndex)) revision++
    }

    fun regenerateCompanion() {
        if (busyKey != null) return
        val chat = threads.chat(companionKey)
        val last = chat.popLastExchange() ?: return
        revision++
        run(companionKey, last, chat, Prompt.companion(settings), null, speakAfter = true) {
            threads.touch(companionKey)
        }
    }

    fun toggleSpeakReplies() {
        speakReplies = !speakReplies
        settings.speakReplies = speakReplies
        if (!speakReplies) speaker.stop()
    }

    // ---- Shared ----

    private fun buildPromptWithAttachment(text: String, att: DraftAttachment?): String = when {
        att == null -> text
        att.isImage -> text.ifBlank { "帮我看看这张图。" }
        else -> (text.ifBlank { "帮我看看这份文件。" }) + "\n\n[附件 ${att.name}]\n${att.textContent.orEmpty()}"
    }

    private fun run(
        key: String,
        text: String,
        chat: ChatStore,
        system: String,
        att: DraftAttachment?,
        speakAfter: Boolean,
        after: () -> Unit,
    ) {
        busyKey = key; pending = key to displayRequest(text, att?.name); lastError = null; progress = null
        viewModelScope.launch {
            try {
                val reply = app.agent.respond(
                    system = system,
                    history = chat.contextWindow(),
                    userText = text,
                    host = host,
                    imageDataUrl = att?.imageDataUrl,
                    attachmentName = att?.name,
                )
                chat.append(reply.newMessages)
                if (speakAfter && speakReplies && reply.text.isNotBlank()) speaker.speak(reply.text)
            } catch (e: Exception) {
                chat.append(listOf(ChatMessage.user(text, attachmentName = att?.name), ChatMessage.assistant("失败：${e.message ?: "请求没有完成"}")))
                lastError = e.message ?: e.javaClass.simpleName
            } finally {
                after()
                busyKey = null; pending = null; progress = null; revision++
            }
        }
    }

    fun setTier(id: String) { thinkingTier = id; settings.thinkingTier = id }
    val tierTitle get() = ThinkingTiers.title(thinkingTier)

    fun swapModel() { settings.swapModel(); revision++ }

    fun refreshBalance() {
        balanceText = "查询中…"
        viewModelScope.launch {
            balanceText = BalanceClient.query(settings.baseUrl, settings.llmKey)
        }
    }

    fun acceptPrivacy() {
        privacyAccepted = true
        settings.privacyAccepted = true
    }

    fun parseManualSms(raw: String): String {
        ButlerParser.parseParcel(raw)?.let { p ->
            butler.addParcel(p); revision++
            return "已识别取件码【${p.code}】${p.station}"
        }
        ButlerParser.parseTrip(raw)?.let { t ->
            butler.addTrip(t); revision++
            return "已识别${t.kind.label}：${t.tripNo} ${t.departTime}"
        }
        return "未识别出取件码或车次航班信息，也可直接发给助手处理。"
    }

    fun answerConfirm(ok: Boolean) { confirm?.answer?.complete(ok) }
    fun answerPermission(ok: Boolean) { permission?.answer?.complete(ok) }

    fun clearAllChats() { threads.clearAll(); assistantThread = null; revision++ }
    fun clearTasks() { app.tasks.clear(); revision++ }
    fun touch() { revision++ }

    fun titleOf(tool: String) = app.registry[tool]?.title ?: tool

    override fun onCleared() {
        speaker.shutdown()
        super.onCleared()
    }

    companion object {
        private fun displayRequest(raw: String, attName: String?): String =
            if (attName != null) raw.substringBefore("\n\n[附件 ") else raw

        private fun receiptsOf(messages: List<ChatMessage>, titleOf: (String) -> String): Map<String, Receipt> {
            val names = mutableMapOf<String, String>()
            val out = mutableMapOf<String, Receipt>()
            messages.forEach { m ->
                m.toolCalls?.forEach { names[it.id] = it.name }
                if (m.role == "tool" && m.toolCallId != null) {
                    val text = m.content.orEmpty()
                    out[m.toolCallId] = Receipt(
                        names[m.toolCallId]?.let(titleOf) ?: "工具",
                        text.lineSequence().first().take(90),
                        !text.startsWith("失败") && !text.startsWith("用户拒绝"),
                    )
                }
            }
            return out
        }

        fun turns(messages: List<ChatMessage>, titleOf: (String) -> String = { it }): List<Turn> {
            val receipts = receiptsOf(messages, titleOf)
            val out = mutableListOf<Turn>()
            var exIdx = -1
            var req: String? = null
            var att: String? = null
            var rs = mutableListOf<Receipt>()
            var answer: String? = null
            fun flush() {
                req?.let { out += Turn(exIdx, displayRequest(it, att), rs, answer, answer?.startsWith("失败：") == true, att) }
                req = null; att = null; rs = mutableListOf(); answer = null
            }
            messages.forEach { m ->
                when (m.role) {
                    "user" -> { flush(); exIdx++; req = m.content.orEmpty(); att = m.attachmentName }
                    "tool" -> receipts[m.toolCallId]?.let { rs += it }
                    "assistant" -> m.content?.trim()?.takeIf { it.isNotEmpty() }?.let { answer = answer?.let { a -> "$a\n$it" } ?: it }
                }
            }
            flush()
            return out
        }

        fun bubbles(messages: List<ChatMessage>, titleOf: (String) -> String = { it }): List<Bubble> {
            val receipts = receiptsOf(messages, titleOf)
            var exIdx = -1
            return messages.mapNotNull { m ->
                when (m.role) {
                    "user" -> { exIdx++; Bubble.Mine(exIdx, displayRequest(m.content.orEmpty(), m.attachmentName), m.attachmentName) }
                    "assistant" -> m.content?.trim()?.takeIf { it.isNotEmpty() }?.let { Bubble.Theirs(exIdx.coerceAtLeast(0), it, m.time) }
                    "tool" -> receipts[m.toolCallId]?.let { Bubble.Action(exIdx.coerceAtLeast(0), it) }
                    else -> null
                }
            }
        }
    }
}
