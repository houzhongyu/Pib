package com.houxinzhe.pib.agent

import android.content.Context
import com.houxinzhe.pib.data.Settings
import com.houxinzhe.pib.llm.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Four action tiers. Anything that spends money or speaks to another person on the
 * user's behalf is SENSITIVE and always needs an explicit tap, even in relaxed mode.
 */
@kotlinx.serialization.Serializable
enum class Risk(val label: String) {
    READ("只读"),
    REVERSIBLE("可撤销"),
    IRREVERSIBLE("不可撤销"),
    SENSITIVE("涉及钱或社交");

    fun needsConfirm(confirmAllWrites: Boolean): Boolean = when (this) {
        READ -> false
        REVERSIBLE -> confirmAllWrites
        IRREVERSIBLE, SENSITIVE -> true
    }

    /** A write that was interrupted mid-flight cannot be assumed done or not done. */
    val writes: Boolean get() = this != READ
}

data class ToolResult(val ok: Boolean, val text: String) {
    companion object {
        fun ok(text: String) = ToolResult(true, text)
        fun fail(text: String) = ToolResult(false, "失败：$text")
    }
}

interface ToolContext {
    val app: Context
    val settings: Settings
}

class AppToolContext(override val app: Context, override val settings: Settings) : ToolContext

interface Tool {
    val name: String
    /** Short Chinese label shown in task receipts and confirmation dialogs. */
    val title: String
    val description: String
    val risk: Risk
    /** Per-call risk override (e.g. ride-hailing / dialer handoffs escalate to SENSITIVE). */
    fun riskFor(args: JsonObject): Risk = risk
    val parameters: JsonObject
    /** Android runtime permissions needed before [run]. */
    val permissions: List<String> get() = emptyList()
    /** Permissions for this specific call; defaults to [permissions]. */
    fun permissionsFor(args: JsonObject): List<String> = permissions

    /** One-line human summary of what this call will do, for the confirm dialog. */
    fun preview(args: JsonObject): String = "$title：" + args.entries.joinToString("，") { "${it.key}=${it.value.str()}" }

    suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult

    fun spec() = ToolSpec(name, description, parameters)
}

fun kotlinx.serialization.json.JsonElement.str(): String = runCatching { jsonPrimitive.contentOrNull }.getOrNull() ?: toString()
fun JsonObject.s(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }
fun JsonObject.i(key: String): Int? = this[key]?.let { el -> runCatching { el.jsonPrimitive.intOrNull ?: el.jsonPrimitive.contentOrNull?.trim()?.toIntOrNull() }.getOrNull() }
fun JsonObject.b(key: String): Boolean? = s(key)?.lowercase()?.let { it == "true" || it == "1" || it == "yes" }

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }
    val all: List<Tool> = tools
    operator fun get(name: String): Tool? = byName[name]
    fun specs() = all.map { it.spec() }
}
