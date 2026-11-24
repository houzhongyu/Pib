package com.houxinzhe.pib.tools

import com.houxinzhe.pib.agent.Risk
import com.houxinzhe.pib.agent.Tool
import com.houxinzhe.pib.agent.ToolContext
import com.houxinzhe.pib.agent.ToolResult
import com.houxinzhe.pib.agent.i
import com.houxinzhe.pib.agent.s
import com.houxinzhe.pib.llm.int
import com.houxinzhe.pib.llm.schema
import com.houxinzhe.pib.llm.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.net.URI
import java.util.concurrent.TimeUnit

data class BridgeConfig(
    val enabled: Boolean,
    val primaryUrl: String,
    val fallbackUrl: String = "",
    val token: String,
    val defaultCwd: String = "",
)

class BridgeException(message: String) : Exception(message)

/**
 * Authenticated HTTP client for `pib_bridge.py` (and backward-compatible `:18791` Mac bridges).
 * Security invariant: cleartext `http://` is permitted ONLY for loopback, `.local` Bonjour,
 * RFC1918 LAN, or Tailscale CGNAT (`100.64.0.0/10`); public internet hosts MUST use `https://`.
 */
class RemoteBridgeClient(
    private val config: () -> BridgeConfig,
    private val http: OkHttpClient = defaultHttp,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val media = "application/json; charset=utf-8".toMediaType()

    suspend fun health(): JsonObject = call("GET", "/health", null, timeoutSec = 10)
    suspend fun status(body: JsonObject): JsonObject = call("POST", "/status", body, timeoutSec = 15)
    suspend fun exec(body: JsonObject, timeoutSec: Int): JsonObject = call("POST", "/exec", body, timeoutSec = timeoutSec + 8)
    suspend fun dsh(body: JsonObject, timeoutSec: Int = 30): JsonObject = call("POST", "/dsh", body, timeoutSec = timeoutSec)

    private suspend fun call(method: String, path: String, payload: JsonObject?, timeoutSec: Int): JsonObject = withContext(Dispatchers.IO) {
        val cfg = config()
        if (!cfg.enabled) throw BridgeException("尚未开启「远程电脑与 DSH 桥」，请到「更多 → 远程电脑与 DSH 桥」开启。")
        if (cfg.token.isBlank()) throw BridgeException("尚未配置电脑桥接令牌，请到「更多 → 远程电脑与 DSH 桥」粘贴 pib:// 配对链接。")
        val bases = listOf(cfg.primaryUrl.trim(), cfg.fallbackUrl.trim()).filter { it.isNotEmpty() }.distinct()
        if (bases.isEmpty()) throw BridgeException("尚未填写电脑桥地址。")

        val client = http.newBuilder().readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS).build()
        var lastError = "无法连接电脑桥"
        for (base in bases) {
            val safeBase = validateEndpoint(base)
            val url = safeBase.trimEnd('/') + path
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${cfg.token.trim()}")
            val req = if (method == "GET") reqBuilder.get().build()
            else reqBuilder.post((payload ?: buildJsonObject {}).toString().toRequestBody(media)).build()
            try {
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (resp.code == 401) throw BridgeException("电脑桥接令牌不匹配（401），请重新复制配对链接。")
                    if (resp.code == 404 && path == "/dsh") {
                        throw BridgeException("当前电脑端运行的是旧版桥，不支持 /dsh；请在电脑运行新版 python3 bridge/pib_bridge.py。")
                    }
                    if (!resp.isSuccessful) throw BridgeException("电脑桥返回 HTTP ${resp.code}：${raw.take(140)}")
                    return@withContext json.parseToJsonElement(raw).jsonObject
                }
            } catch (e: BridgeException) {
                throw e
            } catch (e: Exception) {
                lastError = "${base}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw BridgeException("连接电脑失败（请确认手机与电脑在同一局域网/Tailscale 且已启动 pib_bridge.py）：$lastError")
    }

    companion object {
        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        /** Ensures Bearer tokens are never sent over cleartext HTTP to public internet hosts. */
        fun validateEndpoint(raw: String): String {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: throw BridgeException("电脑桥地址格式无效：$raw")
            val scheme = uri.scheme?.lowercase() ?: throw BridgeException("电脑桥地址缺少协议（http:// 或 https://）")
            val host = uri.host?.lowercase()?.trim('[', ']') ?: throw BridgeException("电脑桥地址缺少主机名或 IP")
            if (scheme == "https") return raw.trim()
            if (scheme != "http") throw BridgeException("不支持的协议：$scheme")
            if (!isPrivateOrLocalHost(host)) {
                throw BridgeException("安全拦截：公网地址必须使用 https://（仅局域网 IP、Tailscale 100.x 或 .local 允许 http://）。")
            }
            return raw.trim()
        }

        fun isPrivateOrLocalHost(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h.endsWith(".local") || h.endsWith(".ts.net")) return true
            val parts = h.split('.')
            if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
                val a = parts[0].toInt()
                val b = parts[1].toInt()
                if (a == 10 || a == 127) return true
                if (a == 192 && b == 168) return true
                if (a == 172 && b in 16..31) return true
                if (a == 100 && b in 64..127) return true // Tailscale / CGNAT
            }
            return false
        }
    }
}

class BridgeStatusTool(private val bridge: RemoteBridgeClient) : Tool {
    override val name = "bridge_status"
    override val title = "查看电脑状态"
    override val risk = Risk.READ
    override val description = "只读查询已配对电脑的状态（主机内存/磁盘/可用 DSH 与 Codex 引擎、工作目录 Git 状态、文件列表或日志末尾）。"
    override val parameters = schema(
        str("kind", "查询类型", enum = listOf("overview", "git", "files", "tail_log")),
        str("cwd", "电脑上的工作目录路径，可省略使用默认目录"),
        str("file", "tail_log 时相对于 cwd 的日志文件名"),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
        val kind = args.s("kind") ?: "overview"
        val cwd = args.s("cwd") ?: ctx.settings.bridgeDefaultCwd.ifBlank { null }
        val resp = bridge.status(
            buildJsonObject {
                put("kind", kind)
                if (cwd != null) put("cwd", cwd)
                args.s("file")?.let { put("file", it) }
            }
        )
        if (resp["ok"]?.jsonPrimitive?.contentOrNull != "true") {
            ToolResult.fail(resp["error"]?.jsonPrimitive?.contentOrNull ?: "查询失败")
        } else {
            when (kind) {
                "git" -> ToolResult.ok("目录 ${resp.str("cwd")}\n${resp.str("status").orEmpty()}\n最近提交：\n${resp.str("recent_commits").orEmpty()}".trim())
                "files" -> {
                    val items = resp["entries"]?.jsonArray?.joinToString("\n") { "• " + it.jsonPrimitive.content }.orEmpty()
                    ToolResult.ok("目录 ${resp.str("cwd")}：\n$items")
                }
                "tail_log" -> ToolResult.ok("日志 ${resp.str("file")}：\n${resp.str("tail").orEmpty()}")
                else -> {
                    val agents = resp["agents"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content }?.ifBlank { "无" } ?: "未知"
                    ToolResult.ok(
                        "电脑 ${resp.str("hostname")}（${resp.str("platform")}）在线｜可用内存 ${resp.str("available_ram_gb")}/${resp.str("total_ram_gb")} GB｜剩余磁盘 ${resp.str("free_disk_gb")} GB｜本地 Agent 引擎：$agents" +
                            (resp.str("git")?.let { "\nGit：$it" } ?: "")
                    )
                }
            }
        }
    } catch (e: BridgeException) {
        ToolResult.fail(e.message ?: "电脑状态查询失败")
    }
}

class BridgeExecTool(private val bridge: RemoteBridgeClient) : Tool {
    override val name = "bridge_exec"
    override val title = "在电脑运行代码或命令"
    override val risk = Risk.SENSITIVE
    override val description = "在已配对的电脑上执行一段 Python 脚本、Node.js 脚本或终端命令（每次必须经用户在手机弹窗点击确认后才会真正运行）。"
    override val parameters = schema(
        str("mode", "运行方式", required = true, enum = listOf("python", "node", "command")),
        str("code", "要运行的脚本或终端命令", required = true),
        str("cwd", "工作目录，可省略"),
        int("timeout_seconds", "超时秒数（2-60，默认 25）"),
    )

    override fun preview(args: JsonObject): String {
        val mode = args.s("mode") ?: "python"
        val cwd = args.s("cwd") ?: "默认目录"
        val snippet = args.s("code").orEmpty().take(180)
        return "在电脑（$cwd）执行 $mode：\n$snippet"
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
        val code = args.s("code") ?: return ToolResult.fail("缺少 code")
        val mode = args.s("mode") ?: "python"
        val timeout = (args.i("timeout_seconds") ?: 25).coerceIn(2, 60)
        val cwd = args.s("cwd") ?: ctx.settings.bridgeDefaultCwd.ifBlank { null }
        val resp = bridge.exec(
            buildJsonObject {
                put("mode", mode)
                put("code", code)
                put("timeout", timeout)
                if (cwd != null) put("cwd", cwd)
            },
            timeoutSec = timeout,
        )
        if (resp["ok"]?.jsonPrimitive?.contentOrNull == "true") {
            val out = resp.str("stdout")?.trim().orEmpty().ifEmpty { "（无标准输出）" }
            ToolResult.ok("电脑执行完成（耗时 ${resp.str("elapsed_sec")}s）：\n${out.take(1500)}")
        } else {
            val err = resp.str("error") ?: resp.str("stderr") ?: "退出码 ${resp.str("exit_code")}"
            ToolResult.fail("电脑执行未通过：$err")
        }
    } catch (e: BridgeException) {
        ToolResult.fail(e.message ?: "电脑执行失败")
    }
}

class DshDelegateTool(private val bridge: RemoteBridgeClient) : Tool {
    override val name = "dsh_delegate"
    override val title = "委派给电脑 DSH/Codex"
    override val risk = Risk.IRREVERSIBLE
    override val description = "把复杂的编程、代码检索、测试修复或长日志分析任务通过 DSH 桥委派给电脑上的 DSH / Codex / Claude CLI 异步执行。任务若在 20 秒内完成会直接返回总结，否则返回任务编号供后续用 dsh_status 查询。"
    override val parameters = schema(
        str("task", "清晰独立的任务指令与验收要求", required = true),
        str("profile", "路由档位：flash（常规实现与检索）、pro（复杂调试与高难分析）、auto", enum = listOf("auto", "flash", "pro")),
        str("agent", "优先使用的电脑端引擎", enum = listOf("auto", "dsh", "codex", "claude")),
        str("cwd", "电脑上的目标项目目录（在允许目录内），省略则在独立沙盒目录运行"),
    )

    override fun preview(args: JsonObject): String {
        val prof = args.s("profile") ?: "auto"
        val cwd = args.s("cwd") ?: "独立沙盒目录"
        return "委派电脑 DSH/Codex（档位 $prof · 目录 $cwd）：\n${args.s("task").orEmpty().take(160)}"
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
        val task = args.s("task") ?: return ToolResult.fail("缺少任务描述 task")
        val resp = bridge.dsh(
            buildJsonObject {
                put("action", "submit")
                put("task", task)
                put("profile", args.s("profile") ?: "auto")
                put("agent", args.s("agent") ?: "auto")
                args.s("cwd")?.let { put("cwd", it) }
                put("wait_seconds", 20)
            },
            timeoutSec = 30,
        )
        if (resp["ok"]?.jsonPrimitive?.contentOrNull != "true") {
            ToolResult.fail(resp.str("error") ?: "DSH 委派失败")
        } else {
            val id = resp.str("id").orEmpty()
            val status = resp.str("status").orEmpty()
            val agent = resp.str("agent").orEmpty()
            when (status) {
                "completed" -> ToolResult.ok("电脑 $agent 已完成任务 [$id]：\n${resp.str("summary").orEmpty()}")
                "failed" -> ToolResult.fail("电脑 $agent 执行任务 [$id] 失败：${resp.str("summary").orEmpty()}")
                else -> ToolResult.ok("已委派给电脑 $agent 后台运行，任务编号：$id。稍后可随时调用 dsh_status(action=\"poll\", task_id=\"$id\") 查看结果。")
            }
        }
    } catch (e: BridgeException) {
        ToolResult.fail(e.message ?: "DSH 桥连接失败")
    }
}

class DshStatusTool(private val bridge: RemoteBridgeClient) : Tool {
    override val name = "dsh_status"
    override val title = "查询或取消 DSH 任务"
    override val risk = Risk.READ
    override val description = "查询电脑 DSH/Codex 任务的执行进度与结果（poll）、列出最近任务（list）或取消运行中的任务（cancel）。"
    override val parameters = schema(
        str("action", "操作：poll（查单条）、list（查最近列表）、cancel（取消）", enum = listOf("poll", "list", "cancel")),
        str("task_id", "poll 或 cancel 时的任务编号，如 dsh-a1b2c3d4e5f6"),
    )

    override fun riskFor(args: JsonObject): Risk =
        if (args.s("action") == "cancel") Risk.REVERSIBLE else Risk.READ

    override fun preview(args: JsonObject) = "电脑 DSH 任务 ${args.s("action") ?: "poll"} ${args.s("task_id").orEmpty()}"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
        val action = args.s("action") ?: if (args.s("task_id") != null) "poll" else "list"
        val resp = bridge.dsh(
            buildJsonObject {
                put("action", action)
                args.s("task_id")?.let { put("task_id", it) }
            },
            timeoutSec = 15,
        )
        if (resp["ok"]?.jsonPrimitive?.contentOrNull != "true") {
            ToolResult.fail(resp.str("error") ?: "查询失败")
        } else if (action == "list") {
            val arr = resp["tasks"]?.jsonArray.orEmpty()
            if (arr.isEmpty()) ToolResult.ok("电脑上还没有 DSH 任务记录。")
            else ToolResult.ok(
                arr.joinToString("\n") { el ->
                    val o = el.jsonObject
                    "• [${o.str("id")}] ${o.str("status")} · ${o.str("agent")} · ${o.str("task")?.take(40).orEmpty()}"
                }
            )
        } else {
            val st = resp.str("status").orEmpty()
            val id = resp.str("id").orEmpty()
            val summary = resp.str("summary").orEmpty()
            val elapsed = resp.str("elapsed_sec")?.let { "（已运行 ${it}s）" }.orEmpty()
            ToolResult.ok("任务 [$id] 状态：$st$elapsed${if (summary.isNotBlank()) "\n$summary" else ""}")
        }
    } catch (e: BridgeException) {
        ToolResult.fail(e.message ?: "DSH 任务查询失败")
    }
}
