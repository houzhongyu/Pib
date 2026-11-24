package com.houxinzhe.pib.tools

import com.houxinzhe.pib.agent.Risk
import com.houxinzhe.pib.agent.Tool
import com.houxinzhe.pib.agent.ToolContext
import com.houxinzhe.pib.agent.ToolResult
import com.houxinzhe.pib.agent.i
import com.houxinzhe.pib.agent.s
import com.houxinzhe.pib.llm.EndpointSecurity
import com.houxinzhe.pib.llm.int
import com.houxinzhe.pib.llm.schema
import com.houxinzhe.pib.llm.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

data class BridgeConfig(
    val enabled: Boolean,
    val primaryUrl: String,
    val fallbackUrl: String = "",
    val token: String,
    val defaultCwd: String = "",
)

class BridgeException(message: String) : Exception(message)

/**
 * Authenticated HTTP client for `pib_bridge.py`.
 * Security & reliability invariants:
 * 1. Cleartext `http://` is permitted ONLY for loopback, `.local`, `.ts.net`, RFC1918 LAN, or Tailscale CGNAT.
 * 2. Authenticates via HMAC-SHA256 (`X-Pib-Timestamp`, `X-Pib-Nonce`, `X-Pib-Signature`) so the raw
 *    token is never transmitted in cleartext over LAN Wi-Fi.
 * 3. Sends `X-Idempotency-Key` on POSTs and ONLY fails over to `fallbackUrl` when the failure occurred
 *    before request bytes were sent (connect/DNS error), preventing duplicate execution on read timeouts.
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
        val cleanToken = cfg.token.trim()
        if (cleanToken.isBlank()) throw BridgeException("尚未配置电脑桥接令牌，请到「更多 → 远程电脑与 DSH 桥」粘贴 pib:// 配对链接。")
        val bases = listOf(cfg.primaryUrl.trim(), cfg.fallbackUrl.trim()).filter { it.isNotEmpty() }.distinct()
        if (bases.isEmpty()) throw BridgeException("尚未填写电脑桥地址。")

        val bodyText = if (method == "GET") "" else (payload ?: buildJsonObject {}).toString()
        val bodyBytes = bodyText.toByteArray(Charsets.UTF_8)
        val idempotencyKey = if (method == "POST") UUID.randomUUID().toString() else null

        var lastError = "无法连接电脑桥"
        for (base in bases) {
            val safeBase = validateEndpoint(base)
            val url = safeBase.trimEnd('/') + path
            val ts = (System.currentTimeMillis() / 1000).toString()
            val nonce = UUID.randomUUID().toString().replace("-", "")
            val sig = signRequest(cleanToken, ts, nonce, method, path, bodyBytes)

            val reqBuilder = Request.Builder()
                .url(url)
                .header("X-Pib-Timestamp", ts)
                .header("X-Pib-Nonce", nonce)
                .header("X-Pib-Signature", sig)
            if (safeBase.lowercase().startsWith("https://")) {
                reqBuilder.header("Authorization", "Bearer $cleanToken")
            }
            if (idempotencyKey != null) {
                reqBuilder.header("X-Idempotency-Key", idempotencyKey)
            }
            val req = if (method == "GET") reqBuilder.get().build()
            else reqBuilder.post(bodyText.toRequestBody(media)).build()

            val requestSent = AtomicBoolean(false)
            val client = http.newBuilder()
                .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .eventListener(object : EventListener() {
                    override fun requestHeadersEnd(call: Call, request: Request) {
                        requestSent.set(true)
                    }
                    override fun requestBodyEnd(call: Call, byteCount: Long) {
                        requestSent.set(true)
                    }
                })
                .build()

            val httpCall = client.newCall(req)
            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) httpCall.cancel()
            }
            try {
                httpCall.execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (resp.code == 401) throw BridgeException("电脑桥接鉴权未通过（401），请确认电脑运行最新版 pib_bridge.py 或重新配对。")
                    if (resp.code == 403) throw BridgeException("电脑桥拒绝了当前网络来源（403）：${raw.take(100)}")
                    if (resp.code == 404 && path == "/dsh") {
                        throw BridgeException("当前电脑端运行的是旧版桥，不支持 /dsh；请在电脑运行新版 python3 bridge/pib_bridge.py。")
                    }
                    if (!resp.isSuccessful) throw BridgeException("电脑桥返回 HTTP ${resp.code}：${raw.take(140)}")
                    return@withContext json.parseToJsonElement(raw).jsonObject
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: BridgeException) {
                throw e
            } catch (e: Exception) {
                if (coroutineContext[Job]?.isActive == false) throw CancellationException("已取消请求")
                if (requestSent.get() && method == "POST") {
                    throw BridgeException("请求已送达电脑（$base），但等待响应超时或中断（${e.message ?: e.javaClass.simpleName}）。为防止重复执行，未自动重发到备用地址；可稍后查询状态。")
                }
                lastError = "${base}: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                cancelHandle?.dispose()
            }
        }
        throw BridgeException("连接电脑失败（请确认手机与电脑在同一 Tailscale / 局域网，且电脑已启动 pib_bridge.py）：$lastError")
    }

    companion object {
        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        /** Ensures credentials are never sent over cleartext HTTP to public internet hosts. */
        fun validateEndpoint(raw: String): String = try {
            EndpointSecurity.validateUrl(raw, "电脑桥地址")
        } catch (e: IllegalArgumentException) {
            throw BridgeException(e.message ?: "电脑桥地址无效")
        }

        fun isPrivateOrLocalHost(host: String): Boolean = EndpointSecurity.isPrivateOrLocalHost(host)

        internal fun signRequest(
            token: String,
            timestamp: String,
            nonce: String,
            method: String,
            path: String,
            bodyBytes: ByteArray,
        ): String {
            val bodySha = MessageDigest.getInstance("SHA-256")
                .digest(bodyBytes)
                .joinToString("") { "%02x".format(it) }
            val canonical = "$timestamp\n$nonce\n${method.uppercase()}\n$path\n$bodySha"
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val digest = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "sha256=$digest"
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
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: BridgeException) {
        ToolResult.fail(e.message ?: "电脑状态查询失败")
    }
}

class BridgeExecTool(private val bridge: RemoteBridgeClient) : Tool {
    override val name = "bridge_exec"
    override val title = "在电脑运行代码或命令"
    override val risk = Risk.SENSITIVE
    override val description = "在已配对的电脑上执行一段 Python 脚本、Node.js 脚本或（电脑已显式开启 --allow-shell 时的）终端命令。每次必须经用户在手机弹窗点击确认后才会真正运行。"
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
    } catch (ce: CancellationException) {
        throw ce
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
    } catch (ce: CancellationException) {
        throw ce
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
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: BridgeException) {
        ToolResult.fail(e.message ?: "DSH 任务查询失败")
    }
}
