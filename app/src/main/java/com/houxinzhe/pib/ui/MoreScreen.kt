package com.houxinzhe.pib.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.houxinzhe.pib.agent.SafetyFuse
import com.houxinzhe.pib.agent.StepStatus
import com.houxinzhe.pib.data.Providers
import com.houxinzhe.pib.llm.ChatMessage
import com.houxinzhe.pib.llm.LlmClient
import com.houxinzhe.pib.llm.LlmConfig
import com.houxinzhe.pib.service.PibAccessibilityService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Destination(val title: String, val keywords: String, val icon: ImageVector, val tint: Color, val page: Page)

private val destinations = listOf(
    Destination("生活管家", "快递 取件码 驿站 丰巢 高铁 火车 12306 航班 飞机 车票", Icons.Outlined.LocalShipping, Color(0xFF007AFF), Page.BUTLER),
    Destination("远程电脑与 DSH", "电脑 Mac PC DSH Codex 远程 编程 脚本 桥接", Icons.Outlined.Computer, Color(0xFF00C7BE), Page.BRIDGE),
    Destination("任务记录", "回执 执行 历史 日志 待核对", Icons.AutoMirrored.Outlined.ListAlt, Color(0xFFFF9F0A), Page.TASKS),
    Destination("工具权限", "工具 权限 授权 能力 闹钟 日历 高德 受限设置 通知", Icons.Outlined.Tune, Color(0xFF30D158), Page.TOOLS),
    Destination("无障碍白名单", "无障碍 屏幕 代操作 白名单 熔断 安全", Icons.Outlined.Shield, Color(0xFF5E5CE6), Page.ALLOWLIST),
    Destination("设置", "模型 key 余额 高德 名字 城市 朗读 安全", Icons.Outlined.Settings, Color(0xFF8E8E93), Page.SETTINGS),
    Destination("关于与隐私", "许可证 源码 版本 隐私 用户协议 安装 映迹", Icons.Outlined.Info, Color(0xFFAF52DE), Page.ABOUT),
)

/** 更多 = iOS DailyMoreView: two-column cards + bottom search capsule. */
@Composable
fun MoreScreen(vm: PibViewModel) {
    val p = LocalPalette.current
    var query by remember { mutableStateOf("") }
    val matches = destinations.filter { query.isBlank() || (it.title + " " + it.keywords).contains(query.trim(), ignoreCase = true) }
    val speech = rememberSpeechInput { query = it }
    Column(Modifier.fillMaxSize().background(p.moreBackground).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            MenuButton { vm.sidebar = true }
            Spacer(Modifier.weight(1f))
            CircleButton("设置", { vm.page = Page.SETTINGS }) { Icon(Icons.Outlined.Settings, null, tint = p.label) }
        }
        LazyVerticalGrid(
            GridCells.Fixed(2),
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 17.dp),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            items(matches) { d ->
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 110.dp).clip(RoundedCornerShape(23.dp)).background(p.background)
                        .border(0.8.dp, p.quaternary, RoundedCornerShape(23.dp)).clickable { vm.page = d.page }.padding(18.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(d.title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = p.label)
                    Spacer(Modifier.height(17.dp))
                    Icon(d.icon, null, tint = d.tint, modifier = Modifier.size(27.dp))
                }
            }
        }
        if (matches.isEmpty()) Text("没有找到「$query」", color = p.secondaryLabel, modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = TextAlign.Center)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp).clip(CircleShape).background(p.background)
                .border(0.8.dp, p.quaternary, CircleShape).padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Search, "搜索工具", tint = p.label) }
            Box(Modifier.weight(1f), contentAlignment = if (query.isEmpty()) Alignment.Center else Alignment.CenterStart) {
                if (query.isEmpty()) Text("搜索", color = p.tertiaryLabel.copy(alpha = 0.9f), fontSize = 17.sp)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = p.label, fontSize = 17.sp), cursorBrush = SolidColor(p.blue), modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.size(44.dp).clickable { if (query.isEmpty()) speech.toggle() else query = "" }, contentAlignment = Alignment.Center) {
                if (query.isEmpty()) {
                    if (speech.recording) WaveformGlyph(p.blue) else Icon(Icons.Outlined.Mic, "语音搜索", tint = p.label)
                } else {
                    Icon(Icons.Outlined.Cancel, "清除搜索", tint = p.label)
                }
            }
        }
    }
}

// ---------- iOS grouped-list building blocks ----------

@Composable
fun PageScaffold(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().background(p.groupedBackground).statusBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Row(
                Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(horizontal = 8.dp, vertical = 8.dp).semantics { contentDescription = "返回" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, null, tint = p.blue, modifier = Modifier.size(20.dp))
                Text("返回", color = p.blue, fontSize = 17.sp)
            }
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.label, modifier = Modifier.align(Alignment.Center))
            trailing?.let { Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { it() } }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(), content = content)
    }
}

@Composable
fun Group(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    header?.let { Text(it, color = p.secondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 7.dp)) }
    if (header == null) Spacer(Modifier.height(22.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.groupedCell), content = content)
    footer?.let { Text(it, color = p.secondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp)) }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(LocalPalette.current.separator))
}

@Composable
fun FieldRow(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", secret: Boolean = false) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = p.label, fontSize = 17.sp, modifier = Modifier.padding(end = 12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (value.isEmpty()) Text(placeholder, color = p.tertiaryLabel, fontSize = 17.sp)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyle(color = p.secondaryLabel, fontSize = 17.sp, textAlign = TextAlign.End),
                cursorBrush = SolidColor(p.blue),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun LinkRow(text: String, color: Color = LocalPalette.current.blue, trailing: String? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, fontSize = 17.sp, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, color = p.secondaryLabel, fontSize = 15.sp) }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = p.label, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = p.green, checkedThumbColor = Color.White,
                uncheckedTrackColor = p.quaternary, uncheckedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White,
            ),
        )
    }
}

// ---------- Butler Page (生活管家：快递取件码 + 12306/航班) ----------

@Composable
fun ButlerPage(vm: PibViewModel) {
    val p = LocalPalette.current
    val rev = vm.revision
    val parcels = remember(rev) { vm.butler.listParcels(includeDone = false) }
    val trips = remember(rev) { vm.butler.listTrips() }
    var smsInput by remember { mutableStateOf("") }
    var parseTip by remember { mutableStateOf<String?>(null) }

    PageScaffold("生活管家", onBack = { vm.page = null }) {
        Group("粘贴短信快速识别", footer = "支持菜鸟驿站、丰巢、兔喜、顺丰、京东等快递短信，以及铁路 12306 和国内航司出票短信。全部在手机本地离线识别。") {
            Box(Modifier.fillMaxWidth().padding(14.dp)) {
                if (smsInput.isEmpty()) Text("粘贴快递取件或 12306 / 航班短信…", color = p.tertiaryLabel, fontSize = 15.sp)
                BasicTextField(
                    smsInput, { smsInput = it }, maxLines = 4,
                    textStyle = TextStyle(color = p.label, fontSize = 15.sp),
                    cursorBrush = SolidColor(p.blue),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                )
            }
            Divider()
            LinkRow("识别并记入管家") {
                if (smsInput.isNotBlank()) {
                    parseTip = vm.parseManualSms(smsInput)
                    smsInput = ""
                }
            }
            parseTip?.let {
                Divider()
                Text(it, color = p.secondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }

        Group("待取快递（${parcels.size}）") {
            if (parcels.isEmpty()) {
                Text("没有待取的快递。", color = p.secondaryLabel, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
            } else {
                parcels.forEachIndexed { i, item ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("取件码 ${item.code}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = p.label)
                            val loc = listOf(item.carrier, item.station).filter { it.isNotBlank() }.joinToString(" · ")
                            if (loc.isNotBlank()) Text(loc, fontSize = 14.sp, color = p.secondaryLabel)
                            if (item.trackingNo.isNotBlank()) Text("单号 ${item.trackingNo}", fontSize = 12.sp, color = p.secondaryLabel)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clip(CircleShape).background(p.secondaryBackground)
                                .clickable { vm.butler.markParcelDone(item.id); vm.touch() }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = p.green, modifier = Modifier.size(16.dp))
                            Text(" 已取", fontSize = 13.sp, color = p.label)
                        }
                    }
                }
            }
        }

        Group("火车与航班行程（${trips.size}）") {
            if (trips.isEmpty()) {
                Text("还没有记录的行程。", color = p.secondaryLabel, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
            } else {
                trips.forEachIndexed { i, t ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val route = listOf(t.departStation, t.arriveStation).filter { it.isNotBlank() }.joinToString(" → ")
                            Text("${t.tripNo}  $route", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.label)
                            if (t.departTime.isNotBlank()) Text("出发：${t.departTime}", fontSize = 14.sp, color = p.secondaryLabel)
                            val extra = listOfNotNull(
                                t.seat.takeIf { it.isNotBlank() },
                                t.gate.takeIf { it.isNotBlank() }?.let { "${if (t.kind.name == "FLIGHT") "登机口" else "检票口"} $it" },
                            ).joinToString(" · ")
                            if (extra.isNotBlank()) Text(extra, fontSize = 13.sp, color = p.secondaryLabel)
                        }
                        Text("移除", fontSize = 14.sp, color = p.red, modifier = Modifier.clickable { vm.butler.removeTrip(t.id); vm.touch() })
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

// ---------- Settings Page ----------

@Composable
fun SettingsPage(vm: PibViewModel) {
    val s = vm.settings
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var provider by remember { mutableStateOf(s.providerId) }
    var baseUrl by remember { mutableStateOf(s.baseUrl) }
    var model by remember { mutableStateOf(s.model) }
    var altModel by remember { mutableStateOf(s.altModel) }
    var llmKey by remember { mutableStateOf(s.llmKey) }
    var amapKey by remember { mutableStateOf(s.amapKey) }
    var name by remember { mutableStateOf(s.assistantName) }
    var userName by remember { mutableStateOf(s.userName) }
    var city by remember { mutableStateOf(s.homeCity) }
    var strict by remember { mutableStateOf(s.confirmAllWrites) }
    var test by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (s.llmKey.isNotBlank() && vm.balanceText == null) vm.refreshBalance()
    }

    fun save() {
        s.providerId = provider; s.baseUrl = baseUrl; s.model = model; s.altModel = altModel
        s.llmKey = llmKey; s.amapKey = amapKey
        s.assistantName = name; s.userName = userName; s.homeCity = city; s.confirmAllWrites = strict
        vm.touch()
    }

    PageScaffold("设置", onBack = { save(); vm.page = null }) {
        Group("模型与账户", footer = "Key 用 Android Keystore 加密，只保存在这台手机上。点模型选择器左上角 ⇄ 可在主模型与备选模型之间快速切换。") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Providers.all.forEach { pr ->
                    val sel = provider == pr.id
                    Text(
                        pr.label, fontSize = 15.sp, color = if (sel) Color.White else p.label,
                        modifier = Modifier.clip(CircleShape).background(if (sel) p.blue else p.secondaryBackground)
                            .clickable {
                                provider = pr.id
                                if (pr.id != "custom") {
                                    baseUrl = pr.baseUrl; model = pr.model
                                    altModel = com.houxinzhe.pib.data.Settings.defaultAltModel(pr.id, pr.model)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
            Divider(); FieldRow("API Key", llmKey, { llmKey = it }, "粘贴 Key", secret = true)
            Divider(); FieldRow("接口地址", baseUrl, { baseUrl = it })
            Divider(); FieldRow("主模型", model, { model = it })
            Divider(); FieldRow("备选模型", altModel, { altModel = it }, "点 ⇄ 切换")
            Divider()
            Row(
                Modifier.fillMaxWidth().clickable { save(); vm.refreshBalance() }.heightIn(min = 44.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("账户余额", color = p.label, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text(vm.balanceText ?: "点此查询", color = p.secondaryLabel, fontSize = 15.sp)
            }
            Divider()
            Providers.byId(provider).keyUrl.takeIf { it.isNotBlank() }?.let { LinkRow("去申请 Key") { uri.openUri(it) }; Divider() }
            Row(
                Modifier.fillMaxWidth().clickable {
                    save(); test = "测试中…"
                    scope.launch {
                        test = runCatching {
                            LlmClient { LlmConfig(baseUrl, llmKey, model, "off") }.complete(listOf(ChatMessage.user("只回复两个字：收到")), emptyList()); "连接成功"
                        }.getOrElse { "失败：${it.message}" }
                        vm.refreshBalance()
                    }
                }.heightIn(min = 44.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("测试连接", color = p.blue, fontSize = 17.sp, modifier = Modifier.weight(1f))
                test?.let { Text(it, color = if (it == "连接成功") p.green else p.secondaryLabel, fontSize = 15.sp, maxLines = 2) }
            }
        }
        Group("高德地图（可选）", footer = "不填也能查全国天气、跳转高德地图导航。填写高德「Web服务」Key 后，可在对话里直接算驾车里程、公交方案和打车估价。") {
            FieldRow("Web 服务 Key", amapKey, { amapKey = it }, "粘贴 Key", secret = true)
            Divider(); LinkRow("去高德开放平台免费申请") { uri.openUri("https://console.amap.com/dev/key/app") }
        }
        Group("个性与语音") {
            FieldRow("助理名字", name, { name = it }, "Pib")
            Divider(); FieldRow("怎么称呼你", userName, { userName = it }, "可不填")
            Divider(); FieldRow("常住城市", city, { city = it }, "例如：杭州")
            Divider(); SwitchRow("Pib 聊天自动语音朗读", vm.speakReplies) { vm.toggleSpeakReplies() }
        }
        Group("安全", footer = "关闭时，只有不可撤销、涉及钱或社交的操作需要你确认。") {
            SwitchRow("每一步写操作都先问我", strict) { strict = it }
        }
        Group {
            LinkRow("清空所有聊天记录", p.red) { vm.clearAllChats() }
        }
        Spacer(Modifier.height(30.dp))
    }
}

// ---------- Tasks Page ----------

@Composable
fun TasksPage(vm: PibViewModel) {
    val p = LocalPalette.current
    val fmt = remember { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA) }
    val rev = vm.revision
    val tasks = remember(rev) { vm.tasks }
    PageScaffold("任务记录", onBack = { vm.page = null }, trailing = {
        if (tasks.isNotEmpty()) Text("清空", color = p.blue, fontSize = 17.sp, modifier = Modifier.clickable { vm.clearTasks() })
    }) {
        if (tasks.isEmpty()) Text("还没有执行过任务。", color = p.secondaryLabel, fontSize = 15.sp, modifier = Modifier.padding(24.dp))
        tasks.forEach { t ->
            Group(fmt.format(Date(t.createdAt))) {
                Text(t.request, color = p.label, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
                t.steps.forEach { st ->
                    Divider()
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) {
                        val c = when (st.status) { StepStatus.DONE -> p.green; StepStatus.RUNNING -> p.secondaryLabel; else -> p.red }
                        Text("${st.title} · ${st.risk.label} · ${st.status.label}", color = c, fontSize = 15.sp)
                        Text(st.receipt, color = p.secondaryLabel, fontSize = 13.sp, maxLines = 4)
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

// ---------- Tools & Restricted Settings Page ----------

@Composable
fun ToolsPage(vm: PibViewModel) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var autoNoti by remember { mutableStateOf(vm.settings.autoParseNotifications) }
    val a11yOn = PibAccessibilityService.isConnected

    PageScaffold("工具权限", onBack = { vm.page = null }) {
        Group("进阶系统辅助（可选）", footer = "安卓 13+ 安装外部 APK 时，系统默认锁住「无障碍」与「通知使用权」（显示灰色受限设置）。解锁方法：先点下方「1. 打开应用信息解锁受限设置」→ 点页面右上角 ⋮ 或「更多」→ 点「允许受限设置」，再回来开启对应开关。") {
            LinkRow("1. 打开应用信息解锁「受限设置」") {
                ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Divider()
            LinkRow("2. 无障碍辅助服务", trailing = if (a11yOn) "已开启" else "去开启") {
                ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Divider()
            LinkRow("配置无障碍应用白名单", trailing = "${vm.settings.accessibilityAllowlist.size} 个应用") {
                vm.page = Page.ALLOWLIST
            }
            Divider()
            SwitchRow("自动识别取件码与行程通知", autoNoti) {
                autoNoti = it; vm.settings.autoParseNotifications = it
            }
            if (autoNoti) {
                Divider()
                LinkRow("授予通知读取权限（仅本地匹配快递/12306）") {
                    ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }

        Group("全部内置工具（${vm.registry.all.size} 项）", footer = "不可撤销、涉及钱或社交的操作每次都会先弹窗问你。") {
            vm.registry.all.forEachIndexed { i, t ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = p.label, fontSize = 17.sp)
                        Text("${t.name} · ${t.risk.label}", color = p.secondaryLabel, fontSize = 13.sp)
                    }
                    val perms = t.permissions
                    if (perms.isNotEmpty()) {
                        val ok = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
                        Text(if (ok) "已授权" else "按需询问", color = if (ok) p.green else p.secondaryLabel, fontSize = 14.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

// ---------- Accessibility Allowlist Page ----------

private data class InstalledAppItem(val pkg: String, val label: String, val hardBlocked: Boolean)

@Composable
fun AllowlistPage(vm: PibViewModel) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var allowlist by remember { mutableStateOf(vm.settings.accessibilityAllowlist) }
    val apps = remember {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(main, 0)
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                InstalledAppItem(pkg, ri.loadLabel(pm).toString(), SafetyFuse.isHardBlockedPackage(pkg))
            }
            .distinctBy { it.pkg }
            .sortedWith(compareBy<InstalledAppItem> { it.hardBlocked }.thenBy { it.label })
    }

    PageScaffold("无障碍白名单", onBack = { vm.page = Page.TOOLS }) {
        Group("硬熔断保护（永久禁止代操作）", footer = "为保护你的社交账号与资金安全，微信、支付宝、银行及主流电商外卖 App 永久禁止屏幕代操作；Pib 对这些应用只使用官方深链预填跳转。") {
            val blocked = apps.filter { it.hardBlocked }
            if (blocked.isEmpty()) {
                Text("微信、支付宝、银行及系统权限页均已内置硬熔断保护。", color = p.secondaryLabel, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            } else {
                blocked.forEachIndexed { i, a ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.label, color = p.secondaryLabel, fontSize = 16.sp)
                            Text(a.pkg, color = p.tertiaryLabel, fontSize = 12.sp)
                        }
                        Text("已熔断保护", color = p.red, fontSize = 13.sp)
                    }
                }
            }
        }

        Group("可勾选加入白名单的普通应用") {
            val candidates = apps.filterNot { it.hardBlocked }
            if (candidates.isEmpty()) {
                Text("未读取到其他桌面应用。", color = p.secondaryLabel, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            } else {
                candidates.forEachIndexed { i, a ->
                    if (i > 0) Divider()
                    val on = allowlist.any { it.equals(a.pkg, ignoreCase = true) }
                    SwitchRow("${a.label} (${a.pkg.substringAfterLast('.')})", on) { checked ->
                        allowlist = if (checked) allowlist + a.pkg else allowlist.filterNot { it.equals(a.pkg, true) }.toSet()
                        vm.settings.accessibilityAllowlist = allowlist
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

// ---------- Remote Computer & DSH Bridge Page ----------

@Composable
fun BridgePage(vm: PibViewModel) {
    val s = vm.settings
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(s.bridgeEnabled) }
    var url by remember { mutableStateOf(s.bridgeUrl) }
    var fallback by remember { mutableStateOf(s.bridgeFallbackUrl) }
    var token by remember { mutableStateOf(s.bridgeToken) }
    var cwd by remember { mutableStateOf(s.bridgeDefaultCwd) }
    var status by remember { mutableStateOf<String?>(null) }

    fun save() {
        s.bridgeEnabled = enabled
        s.bridgeUrl = url
        s.bridgeFallbackUrl = fallback
        s.bridgeToken = token
        s.bridgeDefaultCwd = cwd
        vm.touch()
    }

    PageScaffold("远程电脑与 DSH 桥", onBack = { save(); vm.page = null }) {
        Group("快速配对", footer = "在你的 Mac / Windows / Linux 终端运行仓库内的 python3 bridge/pib_bridge.py，复制输出的 pib://pair-bridge?... 链接，点下方按钮一键导入即可。") {
            SwitchRow("启用远程电脑与 DSH 桥", enabled) { enabled = it; save() }
            Divider()
            LinkRow("从剪贴板粘贴 pib:// 配对链接") {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val raw = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                if (s.importBridgePairing(raw)) {
                    enabled = s.bridgeEnabled; url = s.bridgeUrl; fallback = s.bridgeFallbackUrl
                    token = s.bridgeToken; cwd = s.bridgeDefaultCwd; vm.touch()
                    status = "已成功导入配对配置，点下方「测试电脑与 DSH 连接」验证。"
                } else {
                    status = "剪贴板里不是有效的 pib://pair-bridge 配对链接。"
                }
            }
        }

        Group("连接参数", footer = "令牌用 Android Keystore 加密保存。为保护令牌安全，仅局域网 IP、Tailscale（100.x）或 .local 允许 http://，公网地址必须走 https://。") {
            FieldRow("主地址", url, { url = it }, "http://192.168.x.x:18792")
            Divider(); FieldRow("备用地址", fallback, { fallback = it }, "如 Tailscale 地址，可空")
            Divider(); FieldRow("配对令牌", token, { token = it }, "粘贴 64 位十六进制令牌", secret = true)
            Divider(); FieldRow("默认目录", cwd, { cwd = it }, "留空用电脑默认工作区")
            Divider()
            Row(
                Modifier.fillMaxWidth().clickable {
                    enabled = true; save(); status = "正在连接电脑…"
                    scope.launch {
                        status = runCatching {
                            val h = vm.bridge.health()
                            val host = h["hostname"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                            val ram = h["available_ram_gb"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                            val agents = (h["agents"] as? kotlinx.serialization.json.JsonArray)?.joinToString(",") { (it as kotlinx.serialization.json.JsonPrimitive).content }.orEmpty()
                            "已连接 $host（可用内存 ${ram}GB · 引擎：${agents.ifBlank { "Python/Shell" }}）"
                        }.getOrElse { "失败：${it.message}" }
                    }
                }.heightIn(min = 44.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("测试电脑与 DSH 连接", color = p.blue, fontSize = 17.sp)
            }
            status?.let {
                Divider()
                Text(it, color = if (it.startsWith("已连接")) p.green else p.secondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }

        Group("支持的能力与安全边界") {
            Text(
                "• 查看电脑状态（只读）：一句话查电脑可用内存、磁盘、Git 分支状态或最新日志。\n" +
                    "• 委派 DSH / Codex 编程任务（逐次确认）：把代码检索、重构、跑单测、长日志排查通过 /dsh 交给电脑上的 DSH / Codex / Claude CLI 后台异步执行，随时轮询回执。\n" +
                    "• 运行脚本或命令（逐次确认）：每次在手机弹窗确认工作目录与具体代码后才执行，电脑端内置目录白名单与高危命令拦截。",
                color = p.label,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(30.dp))
    }
}

// ---------- About & Privacy Page ----------

@Composable
fun AboutPage(vm: PibViewModel) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    PageScaffold("关于与隐私", onBack = { vm.page = null }) {
        Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PibAvatar(96.dp)
            Text("Pib", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.label)
            Text("版本 $version · 作者：侯欣哲", fontSize = 15.sp, color = p.secondaryLabel)
        }
        Group("本地隐私与数据处理声明") {
            Text(
                "1. 无中转服务器：Pib 不设开发者后台服务器，不收集通讯录、位置或聊天记录。\n" +
                    "2. 自带密钥（BYOK）：你填写的模型 API Key 与高德 Key 经 Android Keystore 硬件级加密保存在本机，且排除在云备份之外。对话请求由你的手机直连你所选的模型服务商。\n" +
                    "3. 本地短信与通知识别：快递取件码与 12306/航班识别默认关闭，开启后仅在手机本地通过正则提取，不上传任何通知文本，且自动跳过微信、支付宝、银行与验证码通知。\n" +
                    "4. 屏幕安全熔断：无障碍辅助仅限你勾选的白名单普通应用；遇到微信、支付宝、银行、支付密码、转账、验证码或恢复出厂设置页面立即熔断停手。",
                color = p.label,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
        Group("源码许可与免责声明", footer = "Copyright (c) 2026 侯欣哲 (@houzhongyu). 保留所有商业权利。") {
            Text(
                "Pib 源码按 PolyForm Noncommercial License 1.0.0 公开，仅供个人学习、修改与非商业分发；未经作者书面授权禁止任何商业用途（包括售卖安装包或预装收费）。\n\n" +
                    "AI 模型输出可能存在误差，涉及日程、出行时刻、金额与外部应用跳转结果请以实际页面为准。",
                color = p.label,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(16.dp),
            )
            Divider()
            LinkRow("查看公开源码（GitHub）", trailing = "@houzhongyu") {
                uri.openUri("https://github.com/houzhongyu/Pib")
            }
        }
        Group("作者的其他独立作品") {
            Column(Modifier.fillMaxWidth().clickable { uri.openUri("https://yingjiapp.cn") }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("喜欢拍照？试试我的另一款作品「映迹」 →", color = p.blue, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("专注影像审美的 AI 摄影点评与作品鉴赏 App（iOS / Android 双端 · yingjiapp.cn）", color = p.secondaryLabel, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyConsentSheet(onAccept: () -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.background,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PibAvatar(72.dp)
            Text("欢迎使用 Pib", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.label)
            Text(
                "• 纯本机存储：Pib 没有开发者服务器。你的 API Key（硬件加密）、聊天记录、快递与行程只保存在这台手机上。\n" +
                    "• 直连服务商：对话与查路线时，由你的手机直接请求你所选的模型厂商或气象/地图接口。\n" +
                    "• 安全硬边界：Pib 不会读取或代操作微信、支付宝、银行及任何密码支付页面；涉及打车、拨号或不可撤销操作每次都会先征得你确认。",
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = p.secondaryLabel,
            )
            Box(
                Modifier.fillMaxWidth().clip(CircleShape).background(p.blue).clickable(onClick = onAccept).padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("同意并继续", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}
