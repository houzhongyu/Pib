package com.houxinzhe.pib.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.houxinzhe.pib.agent.Risk
import com.houxinzhe.pib.data.DraftAttachment
import com.houxinzhe.pib.data.ThinkingTiers
import java.util.Calendar
import kotlin.math.roundToInt

fun greeting(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) = when (hour) {
    in 5..10 -> "早上好！ ☕"
    in 11..13 -> "中午好！ ☀️"
    in 14..17 -> "下午好！ 🍵"
    else -> "晚上好！ 🌙"
}

/** 首页 = iOS OwnerAssistantView. */
@Composable
fun AssistantScreen(vm: PibViewModel) {
    val p = LocalPalette.current
    val focus = LocalFocusManager.current
    var explains by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val rev = vm.revision
    val turns = remember(rev, vm.homeKey, vm.temporary) { vm.homeTurns() }
    val busy = vm.busyKey != null && vm.busyKey == vm.homeKey
    val pendingText = vm.pending?.takeIf { it.first == vm.homeKey }?.second

    Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            MenuButton { focus.clearFocus(); vm.sidebar = true }
            Spacer(Modifier.weight(1f))
            CircleButton(if (vm.temporary) "结束临时聊天" else "开始临时聊天", {
                focus.clearFocus(); vm.toggleTemporary(); if (vm.temporary) explains = true
            }) {
                if (vm.temporary) GlassesGlyph(p.label) else Icon(Icons.Outlined.Forum, null, tint = p.label, modifier = Modifier.size(22.dp))
            }
        }
        if (turns.isEmpty() && pendingText == null) {
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(if (vm.temporary) "临时聊天" else greeting(), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = p.label)
                if (vm.temporary) {
                    Text("本次聊天仅在这里显示。", fontSize = 15.sp, color = p.secondaryLabel)
                    Text("了解更多", fontSize = 15.sp, color = p.label, textDecoration = TextDecoration.Underline, modifier = Modifier.clickable { explains = true })
                } else {
                    Text(if (vm.thinkingTier == "max" || vm.thinkingTier == "high") "想深入想想什么？" else "今天有什么计划？", fontSize = 20.sp, color = p.secondaryLabel)
                }
            }
            Spacer(Modifier.weight(1f))
        } else {
            TurnList(
                vm = vm,
                turns = turns,
                pending = pendingText,
                onQuote = { snippet -> draft = "「${snippet.take(60)}」\n$draft" },
                modifier = Modifier.weight(1f),
            )
        }
        if (!busy) {
            AssistantComposer(
                vm = vm,
                text = draft,
                onTextChange = { draft = it },
                followUp = turns.isNotEmpty(),
            )
        }
    }
    if (explains) TemporaryChatInfo { explains = false }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TurnList(
    vm: PibViewModel,
    turns: List<Turn>,
    pending: String?,
    onQuote: (String) -> Unit,
    modifier: Modifier,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val state = rememberLazyListState()
    val total = turns.size + if (pending != null) 1 else 0
    LaunchedEffect(total, vm.progress) { if (total > 0) state.animateScrollToItem(total - 1) }
    val caption = "${vm.settings.modelDisplayName} · ${vm.tierTitle}"
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        itemsIndexed(turns) { idx, t ->
            val isLatest = idx == turns.lastIndex && pending == null
            var menuOpen by remember { mutableStateOf(false) }
            TurnView(t.request, t.attachmentName, caption) {
                t.receipts.forEach { ReceiptLine(it) }
                if (t.failed) {
                    Text(t.answer!!.removePrefix("失败："), color = p.secondaryLabel, fontSize = 16.sp)
                    Text("重试", color = p.blue, fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp).clickable {
                        if (isLatest) vm.regenerateHome() else vm.sendHome(t.request)
                    })
                } else t.answer?.let { ans ->
                    Box {
                        Text(
                            ans,
                            color = p.label,
                            fontSize = 16.sp,
                            lineHeight = 23.sp,
                            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
                        )
                        MessageActionMenu(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            canRegenerate = isLatest,
                            onReply = { onQuote(ans) },
                            onCopy = { copyToClipboard(ctx, ans) },
                            onRegenerate = { vm.regenerateHome() },
                            onRemind = { vm.sendHome("帮我把这件事记进日历提醒：${ans.take(80)}") },
                            onDelete = { vm.deleteHomeTurn(t.exchangeIndex) },
                        )
                    }
                }
            }
        }
        if (pending != null) item {
            TurnView(pending, null, caption) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = p.secondaryLabel, strokeWidth = 2.dp)
                    Text(vm.progress ?: "处理中", color = p.secondaryLabel, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun TurnView(request: String, attachmentName: String?, caption: String, body: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(17.dp)).background(p.secondaryBackground).padding(12.dp),
            ) {
                if (request.isNotBlank()) Text(request, color = p.label, fontSize = 16.sp)
                if (attachmentName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.AttachFile, null, tint = p.secondaryLabel, modifier = Modifier.size(14.dp))
                        Text(attachmentName, color = p.secondaryLabel, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(caption, color = p.secondaryLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            body()
        }
    }
}

@Composable
fun MessageActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    canRegenerate: Boolean,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onRemind: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalPalette.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("回复") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Reply, null) }, onClick = { onDismiss(); onReply() })
        DropdownMenuItem(text = { Text("复制") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { onDismiss(); onCopy() })
        if (canRegenerate) {
            DropdownMenuItem(text = { Text("重新生成") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = { onDismiss(); onRegenerate() })
        }
        DropdownMenuItem(text = { Text("设为提醒") }, leadingIcon = { Icon(Icons.Outlined.EventAvailable, null) }, onClick = { onDismiss(); onRemind() })
        DropdownMenuItem(text = { Text("删除", color = p.red) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = p.red) }, onClick = { onDismiss(); onDelete() })
    }
}

fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Pib", text))
    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
}

@Composable
fun ReceiptLine(r: Receipt) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.Top) {
        Icon(if (r.ok) Icons.Outlined.Check else Icons.Outlined.Close, null, tint = if (r.ok) p.green else p.red, modifier = Modifier.padding(top = 2.dp).size(13.dp))
        Text(" ${r.title} · ${r.text}", color = p.secondaryLabel, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Shared attachment strip shown above the composer when a photo or file is staged. */
@Composable
fun AttachmentBar(attachment: DraftAttachment?, issue: String?, onClear: () -> Unit) {
    val p = LocalPalette.current
    if (attachment != null) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.AttachFile, null, tint = p.secondaryLabel, modifier = Modifier.size(16.dp))
            Text(attachment.name, fontSize = 13.sp, color = p.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
            Text("移除", fontSize = 13.sp, color = p.blue, modifier = Modifier.clickable(onClick = onClear))
        }
    }
    issue?.let { Text(it, fontSize = 12.sp, color = p.secondaryLabel, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) }
}

/** Shared + dropdown menu (照片 / 文本或代码文件 / 粘贴剪贴板). */
@Composable
fun PlusMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onPaste: (String) -> Unit,
) {
    val ctx = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("照片") }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { onDismiss(); onPickPhoto() })
        DropdownMenuItem(text = { Text("文本或代码文件") }, leadingIcon = { Icon(Icons.Outlined.Description, null) }, onClick = { onDismiss(); onPickFile() })
        DropdownMenuItem(text = { Text("粘贴剪贴板") }, leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) }, onClick = {
            onDismiss()
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.takeIf { it.isNotBlank() }?.let(onPaste)
        })
    }
}

/** iOS OwnerTaskComposer: bordered card, text on top, + / model / mic-or-send below. */
@Composable
private fun AssistantComposer(
    vm: PibViewModel,
    text: String,
    onTextChange: (String) -> Unit,
    followUp: Boolean,
) {
    val p = LocalPalette.current
    var plus by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.attachImage(uri, forCompanion = false)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.attachFile(uri, forCompanion = false)
    }
    val speech = rememberSpeechInput { spoken -> onTextChange((text + " " + spoken).trim()) }
    val hasInput = text.isNotBlank() || vm.homeAttachment != null

    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp)) {
        AttachmentBar(vm.homeAttachment, vm.attachmentIssue) { vm.clearAttachment(forCompanion = false) }
        vm.lastError?.let { Text(it, fontSize = 13.sp, color = p.secondaryLabel, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).border(0.7.dp, p.separator.copy(alpha = 0.65f), RoundedCornerShape(24.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
            Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp)) {
                if (text.isEmpty()) Text(if (followUp) "继续问…" else "有问题，随便问", color = p.tertiaryLabel.copy(alpha = 0.9f), fontSize = 17.sp)
                BasicTextField(text, onTextChange, maxLines = 5, textStyle = TextStyle(color = p.label, fontSize = 17.sp), cursorBrush = SolidColor(p.blue), modifier = Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box {
                    Box(Modifier.size(44.dp).clickable { plus = true }.semantics { contentDescription = "添加附件" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Add, null, tint = p.label, modifier = Modifier.size(24.dp))
                    }
                    PlusMenu(
                        expanded = plus,
                        onDismiss = { plus = false },
                        onPickPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onPickFile = { filePicker.launch(arrayOf("text/*", "application/json", "application/xml", "*/*")) },
                        onPaste = { clip -> onTextChange((text + "\n" + clip).trim()) },
                    )
                }
                Spacer(Modifier.weight(1f))
                ModelButton(vm)
                if (!hasInput) {
                    Box(
                        Modifier.size(44.dp).clickable { speech.toggle() }
                            .semantics { contentDescription = if (speech.recording) "停止录入" else "语音输入" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (speech.recording) WaveformGlyph(p.blue) else Icon(Icons.Outlined.Mic, null, tint = p.label, modifier = Modifier.size(24.dp))
                    }
                } else {
                    Box(Modifier.size(44.dp).clickable { vm.sendHome(text); onTextChange("") }.semantics { contentDescription = "发送给助手" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(p.label), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.ArrowUpward, null, tint = p.background, modifier = Modifier.size(19.dp))
                        }
                    }
                }
            }
        }
    }
}

/** iOS AssistantModelButton + popover with the tier slider and quick model swap. */
@Composable
private fun ModelButton(vm: PibViewModel) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val rev = vm.revision
    val displayName = remember(rev, vm.settings.model) { vm.settings.modelDisplayName }
    val brainTint = if (vm.settings.model.contains("reasoner") || vm.settings.model.contains("pro") || vm.settings.model.contains("max")) p.purple else p.blue
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.heightIn(min = 44.dp).clickable { open = !open }.padding(horizontal = 8.dp)
                .semantics { contentDescription = "选择模型与推理强度，$displayName，${vm.tierTitle}" },
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.label, maxLines = 1)
                Text(vm.tierTitle, fontSize = 11.sp, color = p.secondaryLabel)
            }
            BrainGlyph(brainTint)
        }
        if (open) {
            val density = LocalDensity.current
            Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(with(density) { 40.dp.roundToPx() }, -with(density) { 52.dp.roundToPx() }),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(280.dp).shadow(18.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp))
                        .background(if (p.dark) Color(0xFF1C1C1E) else Color.White).padding(12.dp),
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.size(44.dp).align(Alignment.TopStart).clickable { vm.swapModel() }.semantics { contentDescription = "切换备选模型" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.CompareArrows, null, tint = p.secondaryLabel)
                        }
                        Box(
                            Modifier.size(44.dp).align(Alignment.TopEnd).clickable { open = false; vm.page = Page.SETTINGS }.semantics { contentDescription = "模型设置" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.Tune, null, tint = p.secondaryLabel, modifier = Modifier.size(19.dp))
                        }
                        TierSlider(vm.thinkingTier, displayName) { vm.setTier(it) }
                    }
                }
            }
        }
    }
}

/** Port of iOS ModelTierSlider (compact style). */
@Composable
private fun TierSlider(selected: String, model: String, choose: (String) -> Unit) {
    val p = LocalPalette.current
    val options = ThinkingTiers.all
    val last = options.lastIndex
    val current = options.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = drag?.let { (it * last).roundToInt() } ?: current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 3.dp)) {
        Text(options[shown].title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.label)
        Text(model, fontSize = 15.sp, color = p.secondaryLabel, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 44.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp)) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val inset = with(density) { 15.dp.toPx() }
            val travel = (widthPx - 2 * inset).coerceAtLeast(1f)
            fun frac(x: Float) = ((x - inset) / travel).coerceIn(0f, 1f)
            fun commit(f: Float) { val i = (f * last).roundToInt(); if (i != current) choose(options[i].id) }
            val fraction = drag ?: (current.toFloat() / last)
            val track = p.secondaryLabel.copy(alpha = 0.35f)
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(current) { detectTapGestures { commit(frac(it.x)) } }
                    .pointerInput(current) {
                        detectHorizontalDragGestures(
                            onDragStart = { drag = frac(it.x) },
                            onDragEnd = { drag?.let(::commit); drag = null },
                            onDragCancel = { drag = null },
                        ) { change, _ -> drag = frac(change.position.x) }
                    }
                    .semantics { contentDescription = "推理强度，${options[shown].title}" },
            ) {
                val h = 24.dp.toPx(); val top = (size.height - h) / 2
                val x = inset + fraction * travel
                drawRoundRect(track, Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
                drawRoundRect(p.label, Offset(0f, top), androidx.compose.ui.geometry.Size(x + 12.dp.toPx(), h), androidx.compose.ui.geometry.CornerRadius(h / 2))
                for (i in 0..last) {
                    val stop = inset + i.toFloat() / last * travel
                    if (stop > x) drawCircle(p.secondaryLabel.copy(alpha = 0.68f), 2.dp.toPx(), Offset(stop, size.height / 2))
                }
                drawCircle(Color.Black.copy(alpha = 0.16f), 14.dp.toPx(), Offset(x, size.height / 2 + 1.dp.toPx()))
                drawCircle(p.label, 14.dp.toPx(), Offset(x, size.height / 2))
            }
        }
    }
}

@Composable
private fun GlassesGlyph(color: Color) {
    Canvas(Modifier.size(28.dp, 16.dp)) {
        val r = size.height * 0.36f
        val s = Stroke(width = 1.8.dp.toPx())
        drawCircle(color, r, Offset(size.width * 0.27f, size.height / 2), style = s)
        drawCircle(color, r, Offset(size.width * 0.73f, size.height / 2), style = s)
        drawLine(color, Offset(size.width * 0.27f + r, size.height / 2), Offset(size.width * 0.73f - r, size.height / 2), 1.8.dp.toPx())
        drawLine(color, Offset(size.width * 0.27f - r, size.height / 2), Offset(0f, size.height * 0.42f), 1.8.dp.toPx())
        drawLine(color, Offset(size.width * 0.73f + r, size.height / 2), Offset(size.width, size.height * 0.42f), 1.8.dp.toPx())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemporaryChatInfo(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.background) {
        Column(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(p.purple.copy(alpha = if (p.dark) 0.22f else 0.20f), Color.Cyan.copy(alpha = 0.09f), p.background, p.background)))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Icon(Icons.Outlined.MarkChatRead, null, tint = p.label, modifier = Modifier.padding(vertical = 13.dp).size(66.dp))
            Text("临时聊天", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.label)
            Text("本次对话和附件只在当前会话中使用。结束临时聊天后会清除，也不会出现在助手历史里。", fontSize = 17.sp, color = p.secondaryLabel, textAlign = TextAlign.Center)
            Box(Modifier.fillMaxWidth().clip(CircleShape).background(p.label).clickable(onClick = onDismiss).padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
                Text("继续", color = p.background, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun ConfirmDialog(req: ConfirmRequest, answer: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val strong = req.risk == Risk.SENSITIVE || req.risk == Risk.IRREVERSIBLE
    AlertDialog(
        onDismissRequest = { answer(false) },
        containerColor = if (p.dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F2),
        title = { Text(if (strong) "这一步需要你确认" else "确认执行", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.label) },
        text = {
            Column {
                Text(req.preview, fontSize = 15.sp, color = p.label)
                Text("风险级别：${req.risk.label}", color = if (strong) p.red else p.secondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = { TextButton(onClick = { answer(true) }) { Text("执行", color = p.blue, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = { answer(false) }) { Text("不要", color = p.blue) } },
    )
}
