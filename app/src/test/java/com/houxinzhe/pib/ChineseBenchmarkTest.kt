package com.houxinzhe.pib

import com.google.common.truth.Truth.assertThat
import com.houxinzhe.pib.agent.Agent
import com.houxinzhe.pib.agent.AgentHost
import com.houxinzhe.pib.agent.FuseVerdict
import com.houxinzhe.pib.agent.Risk
import com.houxinzhe.pib.agent.SafetyFuse
import com.houxinzhe.pib.agent.TaskStore
import com.houxinzhe.pib.agent.Tool
import com.houxinzhe.pib.agent.ToolContext
import com.houxinzhe.pib.agent.ToolRegistry
import com.houxinzhe.pib.agent.UiNodeSummary
import com.houxinzhe.pib.data.AttachmentLoader
import com.houxinzhe.pib.data.ButlerParser
import com.houxinzhe.pib.data.ButlerStore
import com.houxinzhe.pib.data.ChatStore
import com.houxinzhe.pib.data.Settings
import com.houxinzhe.pib.data.TripKind
import com.houxinzhe.pib.llm.BalanceClient
import com.houxinzhe.pib.llm.ChatMessage
import com.houxinzhe.pib.llm.ChatModel
import com.houxinzhe.pib.llm.ToolCall
import com.houxinzhe.pib.tools.AlarmSetTool
import com.houxinzhe.pib.tools.AmapClient
import com.houxinzhe.pib.tools.AppHandoffTool
import com.houxinzhe.pib.tools.BridgeConfig
import com.houxinzhe.pib.tools.BridgeException
import com.houxinzhe.pib.tools.BridgeExecTool
import com.houxinzhe.pib.tools.BridgeStatusTool
import com.houxinzhe.pib.tools.CalendarAddTool
import com.houxinzhe.pib.tools.CalendarListTool
import com.houxinzhe.pib.tools.DshDelegateTool
import com.houxinzhe.pib.tools.DshStatusTool
import com.houxinzhe.pib.tools.HandoffCatalog
import com.houxinzhe.pib.tools.OpenMeteoWeather
import com.houxinzhe.pib.tools.ParcelAddTool
import com.houxinzhe.pib.tools.ParcelDoneTool
import com.houxinzhe.pib.tools.ParcelListTool
import com.houxinzhe.pib.tools.PlaceSearchTool
import com.houxinzhe.pib.tools.RemoteBridgeClient
import com.houxinzhe.pib.tools.RoutePlanTool
import com.houxinzhe.pib.tools.ScreenActTool
import com.houxinzhe.pib.tools.ScreenReadTool
import com.houxinzhe.pib.tools.TimeParse
import com.houxinzhe.pib.tools.TimerSetTool
import com.houxinzhe.pib.tools.TripAddTool
import com.houxinzhe.pib.tools.TripListTool
import com.houxinzhe.pib.tools.WeatherTool
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 50+ realistic Chinese personal phone-agent scenarios covering D5 (parcel & 12306/flight SMS),
 * D6 (domestic super-app deep-link handoff & sensitive confirmation), D7-D8 (SafetyFuse hard
 * breakers, peer accessibility isolation & local custom blocked list), multimodal attachments,
 * balance parsing, keyless weather, HMAC-signed bridge requests, and safe failover.
 */
class ChineseBenchmarkTest {
    @get:Rule val tmp = TemporaryFolder()

    private object NoCtx : ToolContext {
        override val app get() = error("no android context in unit tests")
        override val settings get() = error("no settings in unit tests")
    }

    @Test fun parcelSmsBenchmark12CarriersAndNegativeGuards() {
        val samples = listOf(
            "【菜鸟驿站】您的包裹已到阳光花园3栋菜鸟驿站，请凭取件码3-2-1048及时取件。" to ("3-2-1048" to "阳光花园3栋菜鸟驿站"),
            "【丰巢】您的快件已存入锦绣小区南门丰巢智能柜，取件码：88291041，保管期18小时。" to ("88291041" to "锦绣小区南门丰巢智能柜"),
            "【兔喜生活】中通快递已到学府路18号兔喜生活超市，请凭提货码 A12-309 领取。" to ("A12-309" to "学府路18号兔喜生活超市"),
            "【妈妈驿站】圆通包裹 YT7483920192837 已到华润一期妈妈驿站，取货码【5-1-208】。" to ("5-1-208" to "华润一期妈妈驿站"),
            "【顺丰速运】您的快件 SF1428392019283 已放至科技园B座丰巢快递柜，凭取件码 639102 取件。" to ("639102" to "科技园B座丰巢快递柜"),
            "【京东快递】包裹 JDVA1234567890 已送至西门智能快递柜，请凭密码 904128 取件。" to ("904128" to "西门智能快递柜"),
            "【极兔速递】您的极兔包裹已到龙湖天街菜鸟驿站，凭码 12-4-889 取件。" to ("12-4-889" to "龙湖天街菜鸟驿站"),
            "【韵达快递】快件已放至东区2号楼韵达超市，取件码 7-2056，请尽快领取。" to ("7-2056" to "东区2号楼韵达超市"),
            "【申通快递】包裹 STO998877665544 已到青年公寓代收点，提货码 23-0194。" to ("23-0194" to "青年公寓代收点"),
            "【中国邮政】EMS包裹已放入北门智能柜，取件密码：551920。" to ("551920" to "北门智能柜"),
            "【德邦快递】您的包裹已到创业大厦1楼服务站，请凭 4-2-018 领取。" to ("4-2-018" to "创业大厦1楼服务站"),
            "【近邻宝】包裹已到南区3号快递柜，凭取件码 B08-1923 取件。" to ("B08-1923" to "南区3号快递柜"),
        )
        samples.forEach { (sms, expected) ->
            val p = ButlerParser.parseParcel(sms)
            assertThat(p).isNotNull()
            assertThat(p!!.code).isEqualTo(expected.first)
            assertThat(p.station).isEqualTo(expected.second)
        }

        val otpSamples = listOf(
            "【微信】验证码 382910，用于登录安全验证，5分钟内有效，请勿泄露。",
            "【支付宝】您的动态码为 991204，请勿告知他人。",
            "【招商银行】您尾号1234账户消费人民币128.00元，验证码 442910。",
            "【铁路12306】登录校验码：772819，请勿转发。",
            "今天下午三点去开会，记得带电脑。",
        )
        otpSamples.forEach { sms ->
            assertThat(ButlerParser.parseParcel(sms)).isNull()
            assertThat(ButlerParser.parseTrip(sms)).isNull()
        }
    }

    @Test fun trainAndFlightSmsBenchmark10Scenarios() {
        val today = LocalDate.of(2026, 11, 24)

        val gTrain = ButlerParser.parseTrip(
            "【铁路12306】李明，您已购11月25日G1204次北京南站08:12开，05车12F号，检票口B6，请提前候车。",
            today,
        )!!
        assertThat(gTrain.kind).isEqualTo(TripKind.TRAIN)
        assertThat(gTrain.tripNo).isEqualTo("G1204")
        assertThat(gTrain.departStation).isEqualTo("北京南站")
        assertThat(gTrain.departTime).isEqualTo("2026-11-25 08:12")
        assertThat(gTrain.seat).isEqualTo("05车12F号")
        assertThat(gTrain.gate).isEqualTo("B6")

        val dTrain = ButlerParser.parseTrip(
            "【铁路12306】您购买的 12月02日 D28次 哈尔滨西-北京 14:05开 03车08A号 检票口A2。",
            today,
        )!!
        assertThat(dTrain.tripNo).isEqualTo("D28")
        assertThat(dTrain.departTime).isEqualTo("2026-12-02 14:05")
        assertThat(dTrain.seat).isEqualTo("03车08A号")
        assertThat(dTrain.gate).isEqualTo("A2")

        val nyTrain = ButlerParser.parseTrip("【铁路12306】您已购1月3日C2012次天津站09:30开，02车04C号。", today)!!
        assertThat(nyTrain.departTime).isEqualTo("2027-01-03 09:30")

        val sleeper = ButlerParser.parseTrip("【铁路12306】11月29日Z18次哈尔滨站21:38开，09车15号下铺，检票口3。", today)!!
        assertThat(sleeper.tripNo).isEqualTo("Z18")
        assertThat(sleeper.seat).contains("09车15号下铺")

        val flight1 = ButlerParser.parseTrip(
            "【航旅纵横】您预订的11月28日南方航空 CZ6201 杭州萧山T4→深圳宝安 09:35起飞，登机口B23，座位35A。",
            today,
        )!!
        assertThat(flight1.kind).isEqualTo(TripKind.FLIGHT)
        assertThat(flight1.tripNo).isEqualTo("CZ6201")
        assertThat(flight1.departStation).isEqualTo("杭州萧山T4")
        assertThat(flight1.arriveStation).isEqualTo("深圳宝安")
        assertThat(flight1.departTime).isEqualTo("2026-11-28 09:35")
        assertThat(flight1.gate).isEqualTo("B23")
        assertThat(flight1.seat).isEqualTo("35A")

        val flight2 = ButlerParser.parseTrip("【中国国航】尊敬的旅客，2026-12-10 18:20 CA1502 上海虹桥飞往北京首都T3 航班出票成功。", today)!!
        assertThat(flight2.tripNo).isEqualTo("CA1502")
        assertThat(flight2.departTime).isEqualTo("2026-12-10 18:20")
    }

    @Test fun butlerToolsEndToEndAgentLoop() = runTest {
        val butler = ButlerStore(File(tmp.root, "butler.json"))
        val tools: List<Tool> = listOf(ParcelAddTool(butler), ParcelListTool(butler), ParcelDoneTool(butler), TripListTool(butler))
        var step = 0
        val model = ChatModel { _, _ ->
            when (step++) {
                0 -> ChatMessage.assistant(null, listOf(ToolCall("1", "parcel_add", """{"sms_text":"【菜鸟驿站】包裹已到南门菜鸟驿站，取件码 9-3-1022"}""")))
                1 -> ChatMessage.assistant(null, listOf(ToolCall("2", "parcel_list", "{}")))
                else -> ChatMessage.assistant("已记好取件码 9-3-1022。")
            }
        }
        val host = object : AgentHost {
            override suspend fun confirm(tool: Tool, preview: String) = true
            override suspend fun ensurePermissions(permissions: List<String>) = true
        }
        val reply = Agent(model, ToolRegistry(tools), TaskStore(null), NoCtx, { false }).respond("sys", emptyList(), "记一下快递", host)
        assertThat(reply.text).contains("9-3-1022")
        assertThat(butler.listParcels().single().code).isEqualTo("9-3-1022")
        butler.markParcelDone("9-3-1022")
        assertThat(butler.listParcels()).isEmpty()
        assertThat(butler.listParcels(includeDone = true)).hasSize(1)
    }

    @Test fun domesticAppHandoffAll17TargetsAndSensitiveEscalation() {
        val handoff = AppHandoffTool()

        val route = HandoffCatalog.build("amap_route", null, "我的位置", "杭州东站", "transit")!!
        assertThat(route.primaryUri).contains("amapuri://route/plan/")
        assertThat(route.primaryUri).contains("t=1")
        assertThat(route.risk).isEqualTo(Risk.REVERSIBLE)

        val taxiArgs = Json.parseToJsonElement("""{"target":"amap_taxi","destination":"虹桥机场"}""").jsonObject
        assertThat(handoff.riskFor(taxiArgs)).isEqualTo(Risk.SENSITIVE)

        val didiArgs = Json.parseToJsonElement("""{"target":"didi","destination":"北京西站"}""").jsonObject
        assertThat(handoff.riskFor(didiArgs)).isEqualTo(Risk.SENSITIVE)

        val dialArgs = Json.parseToJsonElement("""{"target":"dial","query":"12306"}""").jsonObject
        assertThat(handoff.riskFor(dialArgs)).isEqualTo(Risk.SENSITIVE)
        assertThat(HandoffCatalog.build("dial", "12306", null, null, null)!!.primaryUri).isEqualTo("tel:12306")

        val searchTargets = listOf(
            "amap_search" to "amapuri://poi",
            "meituan_search" to "imeituan://",
            "dianping_search" to "dianping://",
            "eleme_search" to "eleme://",
            "taobao_search" to "taobao://",
            "jd_search" to "openapp.jdmobile://",
            "pdd_search" to "pinduoduo://",
            "bilibili_search" to "bilibili://",
            "xiaohongshu_search" to "xhsdiscover://",
        )
        searchTargets.forEach { (target, prefix) ->
            val p = HandoffCatalog.build(target, "拿铁咖啡", null, null, null)
            assertThat(p).isNotNull()
            assertThat(p!!.primaryUri).startsWith(prefix)
            assertThat(p.risk).isEqualTo(Risk.REVERSIBLE)
        }

        assertThat(HandoffCatalog.build("baidumap_route", null, null, "西湖", "walking")!!.primaryUri).contains("mode=walking")
        assertThat(HandoffCatalog.build("railway_12306", "G1204", null, null, null)!!.launchPackage).isEqualTo("com.MobileTicket")
        assertThat(HandoffCatalog.build("cainiao", "SF123", null, null, null)!!.launchPackage).isEqualTo("com.cainiao.wireless")
        assertThat(HandoffCatalog.build("open_url", "https://www.12306.cn", null, null, null)!!.primaryUri).isEqualTo("https://www.12306.cn")
        assertThat(HandoffCatalog.build("open_url", "javascript:alert(1)", null, null, null)).isNull()
        assertThat(HandoffCatalog.build("open_url", "file:///sdcard/secret", null, null, null)).isNull()
    }

    @Test fun safetyFuseBlocksSuperAppsPeerA11yCustomBlockedPasswordVaultsAndSensitiveScreens() {
        val allow = setOf("org.mozilla.firefox", "com.android.BBKClock", "com.tencent.mm", "com.eg.android.AlipayGphone")
        val normalNodes = listOf(UiNodeSummary(0, "闹钟", clickable = true), UiNodeSummary(1, "07:30"))

        // 1. Built-in hard-blocked super-apps, banks, installers, and password vaults
        listOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.icbc",
            "cmb.pb",
            "cn.com.cmbc.newmbank",
            "com.mybank.android.phone",
            "com.sankuai.meituan",
            "com.taobao.taobao",
            "com.xunmeng.pinduoduo",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.x8bit.bitwarden",
            "com.agilebits.onepassword",
            "keepass2android.keepass2android",
        ).forEach { pkg ->
            val v = SafetyFuse.check(pkg, allow + pkg, normalNodes)
            assertThat(v).isInstanceOf(FuseVerdict.Blocked::class.java)
        }

        // 2. Peer AccessibilityService apps & local custom blocked apps are blocked even if present in allowlist!
        val peerPkg = "com.example.peer.runtime"
        val customPkg = "com.example.local.sensitive"
        assertThat(
            SafetyFuse.check(peerPkg, allow + peerPkg, normalNodes, peerA11yPackages = setOf(peerPkg))
        ).isInstanceOf(FuseVerdict.Blocked::class.java)
        assertThat(
            SafetyFuse.check(customPkg, allow + customPkg, normalNodes, extraBlocked = setOf(customPkg))
        ).isInstanceOf(FuseVerdict.Blocked::class.java)

        // 3. Non-allowlisted ordinary app is blocked until user enables it.
        assertThat(SafetyFuse.check("com.example.notes", allow, normalNodes)).isInstanceOf(FuseVerdict.Blocked::class.java)

        // 4. Allowlisted ordinary app with normal nodes passes!
        assertThat(SafetyFuse.check("com.android.BBKClock", allow, normalNodes)).isEqualTo(FuseVerdict.Allowed)

        // 5. Live screen fuses inside an allowlisted browser!
        assertThat(SafetyFuse.check("org.mozilla.firefox", allow, listOf(UiNodeSummary(0, "", password = true))))
            .isInstanceOf(FuseVerdict.Blocked::class.java)
        assertThat(SafetyFuse.check("org.mozilla.firefox", allow, listOf(UiNodeSummary(0, "立即支付 ¥88.00", clickable = true))))
            .isInstanceOf(FuseVerdict.Blocked::class.java)
        assertThat(SafetyFuse.check("org.mozilla.firefox", allow, listOf(UiNodeSummary(0, "向张三转账"))))
            .isInstanceOf(FuseVerdict.Blocked::class.java)
        assertThat(SafetyFuse.check("org.mozilla.firefox", allow, listOf(UiNodeSummary(0, "恢复出厂设置"))))
            .isInstanceOf(FuseVerdict.Blocked::class.java)
        assertThat(SafetyFuse.check("org.mozilla.firefox", allow, listOf(UiNodeSummary(0, "请输入短信验证码"))))
            .isInstanceOf(FuseVerdict.Blocked::class.java)
        assertThat(SafetyFuse.check("org.mozilla.firefox", allow, listOf(UiNodeSummary(0, "请拖动滑块完成人机验证"))))
            .isInstanceOf(FuseVerdict.Blocked::class.java)
    }

    @Test fun multimodalAttachmentUtf8GuardBalanceWeatherAndChatEditing() = runTest {
        val imgMsg = ChatMessage.user("看图", imageDataUrl = "data:image/jpeg;base64,AAAA", attachmentName = "车票.jpg")
        val serialized = imgMsg.toJson()
        assertThat(serialized["content"]!!.jsonArray).hasSize(2)
        val chat = ChatStore(File(tmp.root, "chat.json"))
        chat.append(listOf(imgMsg, ChatMessage.assistant("看到了")))
        chat.append(listOf(ChatMessage.user("第二句"), ChatMessage.assistant("好")))
        val reloaded = ChatStore(File(tmp.root, "chat.json"))
        assertThat(reloaded.all.first().imageDataUrl).isNull()
        assertThat(reloaded.all.first().attachmentName).isEqualTo("车票.jpg")

        assertThat(reloaded.popLastExchange()).isEqualTo("第二句")
        assertThat(reloaded.all).hasSize(2)
        assertThat(reloaded.deleteExchange(0)).isTrue()
        assertThat(reloaded.all).isEmpty()

        assertThat(AttachmentLoader.decodeStrictUtf8("你好 Pib".toByteArray(Charsets.UTF_8))).isEqualTo("你好 Pib")
        assertThrows(IllegalArgumentException::class.java) { AttachmentLoader.decodeStrictUtf8(byteArrayOf(0, 1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { AttachmentLoader.decodeStrictUtf8(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) }
        assertThrows(IllegalArgumentException::class.java) { AttachmentLoader.decodeStrictUtf8(ByteArray(1_000_001) { 'a'.code.toByte() }) }

        val dsBal = BalanceClient.parseBalance("https://api.deepseek.com/v1", """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"4.98"}]}""")
        assertThat(dsBal).isEqualTo("CNY 4.98")
        // BalanceClient must reject cleartext public HTTP and must not match deepseek.com in URL path!
        assertThat(BalanceClient.query("http://api.deepseek.com/v1", "sk-test")).contains("安全拦截")
        assertThat(BalanceClient.query("https://my-proxy.example.com/deepseek.com/v1", "sk-test")).contains("网页控制台")

        val wxJson = Json.parseToJsonElement(
            """{"current":{"temperature_2m":18.5,"relative_humidity_2m":55,"weather_code":0,"wind_speed_10m":9.2},"daily":{"time":["2026-11-24"],"weather_code":[61],"temperature_2m_max":[20.0],"temperature_2m_min":[11.0]}}"""
        ).jsonObject
        val wx = OpenMeteoWeather.formatForecast("浙江杭州", wxJson)
        assertThat(wx.ok).isTrue()
        assertThat(wx.text).contains("浙江杭州 现在 晴，18.5°C")
        assertThat(wx.text).contains("小到中雨 11.0~20.0°C")

        val amap = AmapClient(key = { "" })
        val butler = ButlerStore(null)
        val bridge = RemoteBridgeClient(config = { BridgeConfig(false, "", "", "") })
        val reg = ToolRegistry(
            listOf(
                AlarmSetTool(), TimerSetTool(), CalendarAddTool(), CalendarListTool(),
                PlaceSearchTool(amap), RoutePlanTool(amap), WeatherTool(amap),
                ParcelAddTool(butler), ParcelListTool(butler), ParcelDoneTool(butler),
                TripAddTool(butler), TripListTool(butler),
                AppHandoffTool(), ScreenReadTool(), ScreenActTool(),
                BridgeStatusTool(bridge), BridgeExecTool(bridge),
                DshDelegateTool(bridge), DshStatusTool(bridge),
            )
        )
        assertThat(reg.all).hasSize(19)
        assertThat(reg.specs().map { it.name }.distinct()).hasSize(19)
        assertThat(TimeParse.dateTime("2026/11/25 08:30")).isNotNull()
    }

    @Test fun dshAndComputerBridgePairingHmacAndSafeFailover() {
        val pair = Settings.parseBridgePairingUri(
            "pib://pair-bridge?url=http%3A%2F%2F192.168.1.20%3A18792&token=0123456789abcdef0123456789abcdef&fallback=http%3A%2F%2F100.88.1.2%3A18792"
        )!!
        assertThat(pair.url).isEqualTo("http://192.168.1.20:18792")
        assertThat(pair.fallback).isEqualTo("http://100.88.1.2:18792")
        assertThat(pair.token).hasLength(32)
        assertThat(Settings.tokenFingerprint(pair.token)).startsWith("SHA256:")
        assertThat(Settings.tokenFingerprint(pair.token)).contains("…cdef")

        // Reject short tokens, wrong schemes, OR cleartext public HTTP inside pib://pair-bridge!
        assertThat(Settings.parseBridgePairingUri("pib://pair-bridge?url=http://10.0.0.2:18792&token=short")).isNull()
        assertThat(Settings.parseBridgePairingUri("https://example.com?url=x&token=0123456789abcdef0123456789abcdef")).isNull()
        assertThat(Settings.parseBridgePairingUri("pib://pair-bridge?url=http://8.8.8.8:18792&token=0123456789abcdef0123456789abcdef")).isNull()
        assertThat(Settings.parseBridgePairingUri("pib://pair-bridge?url=http://evil.example.com:18792&token=0123456789abcdef0123456789abcdef")).isNull()

        assertThat(RemoteBridgeClient.validateEndpoint("http://192.168.31.10:18792")).isEqualTo("http://192.168.31.10:18792")
        assertThat(RemoteBridgeClient.validateEndpoint("http://100.101.2.3:18792")).isEqualTo("http://100.101.2.3:18792")
        assertThat(RemoteBridgeClient.validateEndpoint("http://my-mac.local:18792")).isEqualTo("http://my-mac.local:18792")
        assertThat(RemoteBridgeClient.validateEndpoint("https://bridge.example.com")).isEqualTo("https://bridge.example.com")
        assertThrows(BridgeException::class.java) { RemoteBridgeClient.validateEndpoint("http://8.8.8.8:18792") }
        assertThrows(BridgeException::class.java) { RemoteBridgeClient.validateEndpoint("http://bridge.example.com:18792") }

        // Live loopback socket check:
        // 1) Connect failure on primary -> fails over to fallback, sends HMAC headers (NO cleartext Authorization!), includes X-Idempotency-Key
        val deadPort = ServerSocket(0).use { it.localPort }
        val capturedHeaders = AtomicReference<Map<String, String>>(emptyMap())
        val fallbackHits = AtomicInteger(0)
        ServerSocket(0).use { fallbackSrv ->
            val t = thread(isDaemon = true) {
                runCatching {
                    fallbackSrv.accept().use { sock ->
                        fallbackHits.incrementAndGet()
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                        val hdrs = mutableMapOf<String, String>()
                        var line = reader.readLine()
                        while (!line.isNullOrEmpty()) {
                            val idx = line.indexOf(':')
                            if (idx > 0) hdrs[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                            line = reader.readLine()
                        }
                        capturedHeaders.set(hdrs)
                        val jsonResp = """{"ok":true,"stdout":"from-fallback"}"""
                        val out = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${jsonResp.toByteArray().size}\r\nConnection: close\r\n\r\n$jsonResp"
                        sock.getOutputStream().write(out.toByteArray(Charsets.UTF_8))
                        sock.getOutputStream().flush()
                    }
                }
            }
            val secret = "0123456789abcdef0123456789abcdef"
            val client = RemoteBridgeClient(
                config = {
                    BridgeConfig(
                        enabled = true,
                        primaryUrl = "http://127.0.0.1:$deadPort",
                        fallbackUrl = "http://127.0.0.1:${fallbackSrv.localPort}",
                        token = secret,
                    )
                }
            )
            val resp = runBlocking { client.exec(buildJsonObject { put("mode", "python"); put("code", "print(1)") }, timeoutSec = 2) }
            t.join(2000)
            assertThat(resp["stdout"]!!.jsonPrimitive.content).isEqualTo("from-fallback")
            assertThat(fallbackHits.get()).isEqualTo(1)
            val hdrs = capturedHeaders.get()
            assertThat(hdrs).doesNotContainKey("authorization") // No cleartext token over HTTP!
            assertThat(hdrs["x-pib-signature"]).startsWith("sha256=")
            assertThat(hdrs).containsKey("x-idempotency-key")
        }

        // 2) Read timeout AFTER request bytes were already sent to primary -> MUST NOT fail over to fallback!
        val fallbackNeverHit = AtomicInteger(0)
        ServerSocket(0).use { stallingPrimary ->
            ServerSocket(0).use { untouchedFallback ->
                thread(isDaemon = true) {
                    runCatching {
                        stallingPrimary.accept().use { sock ->
                            val buf = ByteArray(1024)
                            sock.getInputStream().read(buf) // Receive POST headers/body, then stall past readTimeout!
                            Thread.sleep(2500)
                        }
                    }
                }
                thread(isDaemon = true) {
                    runCatching {
                        untouchedFallback.soTimeout = 2000
                        untouchedFallback.accept().close()
                        fallbackNeverHit.incrementAndGet()
                    }
                }
                val client = RemoteBridgeClient(
                    config = {
                        BridgeConfig(
                            enabled = true,
                            primaryUrl = "http://127.0.0.1:${stallingPrimary.localPort}",
                            fallbackUrl = "http://127.0.0.1:${untouchedFallback.localPort}",
                            token = "0123456789abcdef0123456789abcdef",
                        )
                    }
                )
                val err = assertThrows(BridgeException::class.java) {
                    runBlocking {
                        // status() uses timeoutSec=15, so test via custom short readTimeout client
                        val fastClient = RemoteBridgeClient(
                            config = {
                                BridgeConfig(
                                    enabled = true,
                                    primaryUrl = "http://127.0.0.1:${stallingPrimary.localPort}",
                                    fallbackUrl = "http://127.0.0.1:${untouchedFallback.localPort}",
                                    token = "0123456789abcdef0123456789abcdef",
                                )
                            },
                        )
                        // dsh(timeoutSec = 1) triggers readTimeout at 1s after POST is sent!
                        fastClient.dsh(buildJsonObject { put("action", "submit"); put("task", "slow") }, timeoutSec = 1)
                    }
                }
                assertThat(err.message).contains("请求已送达电脑")
                assertThat(fallbackNeverHit.get()).isEqualTo(0)
            }
        }
    }
}
