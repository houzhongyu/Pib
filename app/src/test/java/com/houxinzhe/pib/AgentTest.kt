package com.houxinzhe.pib

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import com.houxinzhe.pib.agent.Agent
import com.houxinzhe.pib.agent.AgentHost
import com.houxinzhe.pib.agent.Risk
import com.houxinzhe.pib.agent.StepStatus
import com.houxinzhe.pib.agent.TaskStore
import com.houxinzhe.pib.agent.Tool
import com.houxinzhe.pib.agent.ToolContext
import com.houxinzhe.pib.agent.ToolRegistry
import com.houxinzhe.pib.agent.ToolResult
import com.houxinzhe.pib.agent.s
import com.houxinzhe.pib.data.ChatStore
import com.houxinzhe.pib.llm.ChatMessage
import com.houxinzhe.pib.llm.ChatModel
import com.houxinzhe.pib.llm.LlmClient
import com.houxinzhe.pib.llm.LlmConfig
import com.houxinzhe.pib.llm.LlmException
import com.houxinzhe.pib.llm.ToolCall
import com.houxinzhe.pib.llm.schema
import com.houxinzhe.pib.llm.str
import com.houxinzhe.pib.tools.AlarmSetTool
import com.houxinzhe.pib.tools.AppHandoffTool
import com.houxinzhe.pib.tools.TimeParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.util.Calendar

class AgentTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeTool(override val name: String, override val risk: Risk, private val result: ToolResult = ToolResult.ok("ok")) : Tool {
        var runs = 0
        override val title = name
        override val description = name
        override val parameters = schema(str("x", "x"))
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { runs++; return result.copy(text = result.text + ":" + (args.s("x") ?: "")) }
    }

    private class Host(val approve: Boolean, val grant: Boolean = true) : AgentHost {
        var asked = 0
        var lastConfirmedRisk: Risk? = null
        val streamedSnapshots = mutableListOf<String>()
        override suspend fun confirm(tool: Tool, risk: Risk, preview: String): Boolean {
            asked++
            lastConfirmedRisk = risk
            return approve
        }
        override suspend fun ensurePermissions(permissions: List<String>) = grant
        override fun streaming(text: String) {
            if (text.isNotEmpty()) streamedSnapshots += text
        }
    }

    /** Model that calls the given tool once, then answers with the receipt it saw. */
    private fun scripted(tool: String, args: String = """{"x":"1"}""") = ChatModel { messages, _ ->
        val lastTool = messages.lastOrNull { it.role == "tool" }
        if (messages.last().role == "user") ChatMessage.assistant(null, listOf(ToolCall("c1", tool, args)))
        else ChatMessage.assistant("看到：${lastTool?.content}")
    }

    /** Fake tools never touch the Android context. */
    private object NoCtx : ToolContext {
        override val app get() = error("no android context in unit tests")
        override val settings get() = error("no settings in unit tests")
    }

    private fun agent(model: ChatModel, tools: List<Tool>, store: TaskStore, strict: Boolean = false) =
        Agent(model, ToolRegistry(tools), store, NoCtx, confirmAllWrites = { strict })

    @Test fun readToolRunsWithoutConfirmation() = runTest {
        val t = FakeTool("read", Risk.READ); val host = Host(approve = false)
        val store = TaskStore(File(tmp.root, "t.json"))
        val r = agent(scripted("read"), listOf(t), store).respond("sys", emptyList(), "hi", host)
        assertThat(t.runs).isEqualTo(1)
        assertThat(host.asked).isEqualTo(0)
        assertThat(r.text).contains("ok:1")
        assertThat(store.all.single().steps.single().status).isEqualTo(StepStatus.DONE)
    }

    @Test fun sensitiveToolDeniedIsNotRunAndModelIsTold() = runTest {
        val t = FakeTool("pay", Risk.SENSITIVE); val host = Host(approve = false)
        val store = TaskStore(File(tmp.root, "t.json"))
        val r = agent(scripted("pay"), listOf(t), store).respond("sys", emptyList(), "pay", host)
        assertThat(t.runs).isEqualTo(0)
        assertThat(host.asked).isEqualTo(1)
        assertThat(host.lastConfirmedRisk).isEqualTo(Risk.SENSITIVE)
        assertThat(r.text).contains("用户拒绝")
        assertThat(store.all.single().steps.single().status).isEqualTo(StepStatus.DENIED)
    }

    @Test fun escalatedCallRiskIsPassedToConfirmDialog() = runTest {
        // AppHandoffTool default risk is REVERSIBLE, but amap_taxi escalates to SENSITIVE.
        val handoff = AppHandoffTool()
        val host = Host(approve = false)
        val store = TaskStore(null)
        val model = scripted("app_handoff", """{"target":"amap_taxi","destination":"虹桥火车站"}""")
        agent(model, listOf(handoff), store, strict = false).respond("sys", emptyList(), "打车", host)
        assertThat(host.asked).isEqualTo(1)
        assertThat(host.lastConfirmedRisk).isEqualTo(Risk.SENSITIVE)
    }

    @Test fun cancellationDuringWriteMarksStepUncertainAndRethrows() = runTest {
        val cancellingWriteTool = object : Tool {
            override val name = "write_cancel"
            override val title = "写操作"
            override val description = "test"
            override val risk = Risk.REVERSIBLE
            override val parameters = schema()
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                throw CancellationException("user pressed stop")
            }
        }
        val store = TaskStore(File(tmp.root, "cancel.json"))
        var caughtCancel = false
        try {
            agent(scripted("write_cancel", "{}"), listOf(cancellingWriteTool), store)
                .respond("sys", emptyList(), "run", Host(approve = true))
        } catch (_: CancellationException) {
            caughtCancel = true
        }
        assertThat(caughtCancel).isTrue()
        val recorded = store.all.single()
        assertThat(recorded.finished).isTrue()
        assertThat(recorded.steps.single().status).isEqualTo(StepStatus.UNCERTAIN)
    }

    @Test fun cancellationMidBatchBalancesSiblingToolCallIds() = runTest {
        val okTool = FakeTool("first_ok", Risk.READ)
        val cancelTool = object : Tool {
            override val name = "second_cancel"
            override val title = "取消步骤"
            override val description = "test"
            override val risk = Risk.READ
            override val parameters = schema()
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = throw CancellationException("stop")
        }
        val multiCallModel = ChatModel { _, _ ->
            ChatMessage.assistant(null, listOf(ToolCall("id_1", "first_ok", """{"x":"a"}"""), ToolCall("id_2", "second_cancel", "{}"), ToolCall("id_3", "first_ok", "{}")))
        }
        var captured: com.houxinzhe.pib.agent.AgentCancelledException? = null
        try {
            agent(multiCallModel, listOf(okTool, cancelTool), TaskStore(null)).respond("sys", emptyList(), "go", Host(true))
        } catch (ce: com.houxinzhe.pib.agent.AgentCancelledException) {
            captured = ce
        }
        assertThat(captured).isNotNull()
        val toolResponses = captured!!.completedMessages.filter { it.role == "tool" }
        assertThat(toolResponses.map { it.toolCallId }).containsExactly("id_1", "id_2", "id_3").inOrder()
    }

    @Test fun reversibleNeedsConfirmOnlyInStrictMode() = runTest {
        val t = FakeTool("alarm", Risk.REVERSIBLE)
        val relaxed = Host(approve = true)
        agent(scripted("alarm"), listOf(t), TaskStore(null)).respond("sys", emptyList(), "a", relaxed)
        assertThat(relaxed.asked).isEqualTo(0)
        val strict = Host(approve = true)
        agent(scripted("alarm"), listOf(t), TaskStore(null), strict = true).respond("sys", emptyList(), "a", strict)
        assertThat(strict.asked).isEqualTo(1)
        assertThat(t.runs).isEqualTo(2)
    }

    @Test fun unknownToolAndBadJsonFailSoftly() = runTest {
        val r1 = agent(scripted("nope"), emptyList(), TaskStore(null)).respond("sys", emptyList(), "a", Host(true))
        assertThat(r1.text).contains("没有名为 nope")
        val t = FakeTool("read", Risk.READ)
        val r2 = agent(scripted("read", "{not json"), listOf(t), TaskStore(null)).respond("sys", emptyList(), "a", Host(true))
        assertThat(r2.text).contains("不是合法 JSON")
        assertThat(t.runs).isEqualTo(0)
    }

    @Test fun loopStopsAfterMaxRounds() = runTest {
        val t = FakeTool("read", Risk.READ)
        val looping = ChatModel { _, _ -> ChatMessage.assistant(null, listOf(ToolCall("c", "read", "{}"))) }
        val r = Agent(looping, ToolRegistry(listOf(t)), TaskStore(null), NoCtx, { false }, maxRounds = 3).respond("s", emptyList(), "u", Host(true))
        assertThat(t.runs).isEqualTo(3)
        assertThat(r.text).contains("步骤比较多")
    }

    @Test fun interruptedWriteBecomesUncertainOnReload() {
        val f = File(tmp.root, "ledger.json")
        val s1 = TaskStore(f)
        val task = s1.begin("id", "设闹钟")
        s1.startStep(task, com.houxinzhe.pib.agent.TaskStep("alarm_set", "设闹钟", Risk.REVERSIBLE, "{}"))
        s1.startStep(task, com.houxinzhe.pib.agent.TaskStep("weather", "查天气", Risk.READ, "{}"))
        val steps = TaskStore(f).all.single().steps
        assertThat(steps[0].status).isEqualTo(StepStatus.UNCERTAIN)
        assertThat(steps[1].status).isEqualTo(StepStatus.FAILED)
    }

    @Test fun contextWindowNeverStartsWithOrphanToolMessage() {
        val store = ChatStore(null)
        store.append(listOf(
            ChatMessage.user("a"), ChatMessage.assistant(null, listOf(ToolCall("1", "t", "{}"))), ChatMessage.tool("1", "r"), ChatMessage.assistant("done"),
            ChatMessage.user("b"), ChatMessage.assistant("ok"),
        ))
        val w = store.contextWindow(maxMessages = 4)
        assertThat(w.first().role).isEqualTo("user")
        assertThat(w.first().content).isEqualTo("b")
    }

    @Test fun parsesToolCallsSseStreamsDeepSeekMaxAndBlocksPublicHttp() {
        val client = LlmClient { throw IllegalStateException() }
        val body = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_9","type":"function","function":{"name":"alarm_set","arguments":"{\"hour\":7,\"minute\":40}"}}]}}]}"""
        val m = client.parse(body)
        assertThat(m.toolCalls!!.single().name).isEqualTo("alarm_set")
        assertThat(m.toolCalls!!.single().arguments).contains("\"hour\":7")
        val r = client.parse(body.replace("\"content\":null", "\"content\":\"\",\"reasoning_content\":\"think\""))
        assertThat(r.reasoning).isEqualTo("think")
        assertThat(r.toJson()["reasoning_content"].toString()).isEqualTo("\"think\"")
        val stored = ChatStore(null).apply { append(listOf(ChatMessage.user("u"), r)) }.all
        assertThat(stored.last().reasoning).isNull()

        // SSE streaming: incremental text + reasoning + multi-chunk tool_calls
        val deltas = mutableListOf<String>()
        var reasoned = false
        val sseText = """
            data: {"choices":[{"delta":{"reasoning_content":"先算一下"}}]}
            data: {"choices":[{"delta":{"content":"早上"}}]}
            data: {"choices":[{"delta":{"content":"好！"}}]}
            data: [DONE]
        """.trimIndent()
        val streamedMsg = client.parseSseString(sseText, onDelta = { deltas += it }, onReasoning = { reasoned = true })
        assertThat(reasoned).isTrue()
        assertThat(deltas).containsExactly("早上", "早上好！").inOrder()
        assertThat(streamedMsg.content).isEqualTo("早上好！")
        assertThat(streamedMsg.reasoning).isEqualTo("先算一下")

        val sseTool = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"alarm_","arguments":"{\"hour\":"}}]}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"set","arguments":"8}"}}]}}]}
            data: [DONE]
        """.trimIndent()
        val streamedTool = client.parseSseString(sseTool)
        assertThat(streamedTool.toolCalls!!.single().id).isEqualTo("call_a")
        assertThat(streamedTool.toolCalls!!.single().name).isEqualTo("alarm_set")
        assertThat(streamedTool.toolCalls!!.single().arguments).isEqualTo("{\"hour\":8}")

        // DeepSeek "max" tier maps to reasoning_effort="high" + max_tokens=6000
        val reqBody = client.buildRequestBody(
            LlmConfig("https://api.deepseek.com/v1", "sk-x", "deepseek-flash", thinking = "max"),
            listOf(ChatMessage.user("hi")),
            emptyList(),
            streamMode = true,
        )
        assertThat(reqBody["reasoning_effort"]!!.jsonPrimitive.content).isEqualTo("high")
        assertThat(reqBody["max_tokens"]!!.jsonPrimitive.content).isEqualTo("6000")

        // Endpoint transport security: block public cleartext HTTP, allow LAN HTTP & public HTTPS
        assertThat(LlmClient.endpoint("https://api.deepseek.com/v1/")).isEqualTo("https://api.deepseek.com/v1/chat/completions")
        assertThat(LlmClient.endpoint("http://192.168.1.50:11434/v1")).isEqualTo("http://192.168.1.50:11434/v1/chat/completions")
        assertThrows(LlmException::class.java) { LlmClient.endpoint("http://api.public-proxy.example.com/v1") }
        assertThrows(LlmException::class.java) { LlmClient.endpoint("http://8.8.4.4:8000/v1") }
    }

    @Test fun inboundIntentDeduplicationGuard() {
        assertThat(MainActivity.shouldConsumeInboundAction(Intent.ACTION_SEND, alreadyConsumed = false, isRestoredState = false)).isTrue()
        assertThat(MainActivity.shouldConsumeInboundAction(Intent.ACTION_VIEW, alreadyConsumed = false, isRestoredState = false)).isTrue()
        // Activity recreation (e.g. dark-mode switch) or already-consumed intent must NOT fire again!
        assertThat(MainActivity.shouldConsumeInboundAction(Intent.ACTION_SEND, alreadyConsumed = false, isRestoredState = true)).isFalse()
        assertThat(MainActivity.shouldConsumeInboundAction(Intent.ACTION_SEND, alreadyConsumed = true, isRestoredState = false)).isFalse()
        assertThat(MainActivity.shouldConsumeInboundAction(Intent.ACTION_MAIN, alreadyConsumed = false, isRestoredState = false)).isFalse()
    }

    @Test fun repeatAndTimeParsing() {
        assertThat(AlarmSetTool.parseRepeat("weekdays")).containsExactly(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        assertThat(AlarmSetTool.parseRepeat("6,7")).containsExactly(Calendar.SATURDAY, Calendar.SUNDAY)
        assertThat(AlarmSetTool.parseRepeat("none")).isEmpty()
        assertThat(TimeParse.dateTime("2026-11-25 15:00")).isEqualTo(LocalDateTime.of(2026, 11, 25, 15, 0))
        assertThat(TimeParse.dateTime("2026-11-25T07:40")).isEqualTo(LocalDateTime.of(2026, 11, 25, 7, 40))
        assertThat(TimeParse.dateTime("2026-11-25")).isEqualTo(LocalDateTime.of(2026, 11, 25, 0, 0))
        assertThat(TimeParse.dateTime("明天")).isNull()
    }

    @Test fun turnsGroupReceiptsUnderTheirRequest() {
        val msgs = listOf(
            ChatMessage.user("明早叫我"), ChatMessage.assistant(null, listOf(ToolCall("1", "alarm_set", "{}"))), ChatMessage.tool("1", "已设闹钟"), ChatMessage.assistant("好了"),
            ChatMessage.user("谢谢"), ChatMessage.assistant("失败：网络错误"),
        )
        val t = com.houxinzhe.pib.ui.PibViewModel.turns(msgs)
        assertThat(t).hasSize(2)
        assertThat(t[0].receipts.single().ok).isTrue()
        assertThat(t[0].answer).isEqualTo("好了")
        assertThat(t[1].failed).isTrue()
        val b = com.houxinzhe.pib.ui.PibViewModel.bubbles(msgs)
        assertThat(b.filterIsInstance<com.houxinzhe.pib.ui.Bubble.Action>()).hasSize(1)
    }

    @Test fun threadStoreKeepsPinnedFirstAndCompanionSeparate() {
        val store = com.houxinzhe.pib.data.ThreadStore(tmp.newFolder("threads"))
        val a = store.create("第一个问题"); val b = store.create("第二个问题")
        store.togglePin(a.id)
        assertThat(store.assistantThreads().first().id).isEqualTo(a.id)
        assertThat(store.assistantThreads().map { it.id }).doesNotContain(store.companionId)
        store.chat(b.id).append(listOf(ChatMessage.user("找关键词")))
        assertThat(store.assistantThreads("关键词").single().id).isEqualTo(b.id)
    }
}
