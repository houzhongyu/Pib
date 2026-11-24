package com.houxinzhe.pib.agent

import com.houxinzhe.pib.llm.ChatMessage
import com.houxinzhe.pib.llm.ChatModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** Host-side hooks the agent needs from the UI. */
interface AgentHost {
    /** Ask the user to approve a call. Return true to run it. */
    suspend fun confirm(tool: Tool, preview: String): Boolean
    /** Ensure runtime permissions. Return true when all are granted. */
    suspend fun ensurePermissions(permissions: List<String>): Boolean
    /** Live progress line, e.g. "正在查路线…". */
    fun progress(text: String) {}
}

data class AgentReply(val text: String, val newMessages: List<ChatMessage>, val task: TaskRecord?)

/**
 * Plan → act → observe loop over OpenAI-style tool calls.
 * The model only learns what happened from receipts, so it cannot claim an action
 * succeeded unless the tool actually returned success.
 */
class Agent(
    private val model: ChatModel,
    private val registry: ToolRegistry,
    private val tasks: TaskStore,
    private val ctx: ToolContext,
    private val confirmAllWrites: () -> Boolean,
    private val maxRounds: Int = 8,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun respond(
        system: String,
        history: List<ChatMessage>,
        userText: String,
        host: AgentHost,
        imageDataUrl: String? = null,
        attachmentName: String? = null,
    ): AgentReply {
        val added = mutableListOf(ChatMessage.user(userText, imageDataUrl = imageDataUrl, attachmentName = attachmentName))
        var task: TaskRecord? = null
        repeat(maxRounds) {
            val reply = model.complete(listOf(ChatMessage.system(system)) + history + added, registry.specs())
            added += reply
            val calls = reply.toolCalls
            if (calls.isNullOrEmpty()) {
                task?.let { tasks.finish(it) }
                return AgentReply(reply.content.orEmpty().trim(), added, task)
            }
            val t = task ?: tasks.begin(UUID.randomUUID().toString(), userText).also { task = it }
            for (call in calls) {
                added += ChatMessage.tool(call.id, execute(t, call.name, call.arguments, host))
            }
        }
        task?.let { tasks.finish(it) }
        val text = "这件事步骤比较多，我先停在这里。已经做完的步骤可以在「任务记录」里查看。"
        added += ChatMessage.assistant(text)
        return AgentReply(text, added, task)
    }

    private suspend fun execute(task: TaskRecord, name: String, rawArgs: String, host: AgentHost): String {
        val tool = registry[name] ?: return "失败：没有名为 $name 的工具。"
        val args: JsonObject = runCatching { json.parseToJsonElement(rawArgs.ifBlank { "{}" }).jsonObject }
            .getOrElse { return "失败：参数不是合法 JSON。" }
        val callRisk = tool.riskFor(args)
        val step = TaskStep(tool.name, tool.title, callRisk, args.toString())

        if (callRisk.needsConfirm(confirmAllWrites())) {
            val approved = host.confirm(tool, tool.preview(args))
            if (!approved) {
                tasks.startStep(task, step)
                tasks.endStep(task, step, StepStatus.DENIED, "用户没有同意这一步。")
                return "用户拒绝了这一步，未执行。不要重试，询问用户想怎么调整。"
            }
        }
        val perms = tool.permissionsFor(args)
        if (perms.isNotEmpty() && !host.ensurePermissions(perms)) {
            tasks.startStep(task, step)
            tasks.endStep(task, step, StepStatus.FAILED, "没有获得所需权限。")
            return "失败：用户没有授予所需权限（${perms.joinToString { it.substringAfterLast('.') }}）。"
        }

        host.progress("${tool.title}…")
        tasks.startStep(task, step)
        val result = runCatching { tool.run(args, ctx) }.getOrElse { ToolResult.fail(it.message ?: it.javaClass.simpleName) }
        tasks.endStep(task, step, if (result.ok) StepStatus.DONE else StepStatus.FAILED, result.text)
        return result.text
    }
}
