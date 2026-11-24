package com.houxinzhe.pib.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Companion tab = iOS B-style chat layout, with Pib as an original character + optional TTS voice. */
@Composable
fun CompanionScreen(vm: PibViewModel) {
    val p = LocalPalette.current
    val focus = LocalFocusManager.current
    val ctx = LocalContext.current
    var draft by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(vm.companionRestoredDraft) {
        vm.consumeCompanionRestoredDraft()?.let { restored -> draft = restored }
    }
    val rev = vm.revision
    val bubbles = remember(rev) { vm.companionBubbles() }
    val busy = vm.busyKey == vm.companionKey
    val pending = vm.pending?.takeIf { it.first == vm.companionKey }?.second
    val streamed = vm.streamingText?.takeIf { busy && it.isNotBlank() }
    val list = rememberLazyListState()
    val rows = bubbles + listOfNotNull(pending?.let { Bubble.Mine(-1, it) })
    val maxExIdx = bubbles.maxOfOrNull { it.exchangeIndex } ?: -1
    LaunchedEffect(rows.size, busy, streamed != null) { if (rows.isNotEmpty()) list.animateScrollToItem(rows.size - 1 + if (busy) 1 else 0) }

    Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            MenuButton { focus.clearFocus(); vm.sidebar = true }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(vm.settings.assistantName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.label, modifier = Modifier.semantics { heading() })
                Text(if (busy) "思考中…" else "在线 · 随时都在", fontSize = 11.sp, color = p.secondaryLabel)
            }
            Spacer(Modifier.weight(1f))
            CircleButton(
                label = if (vm.speakReplies) "关闭语音朗读" else "开启语音朗读",
                onClick = { vm.toggleSpeakReplies() },
            ) {
                Icon(
                    if (vm.speakReplies) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                    null,
                    tint = if (vm.speakReplies) p.blue else p.label,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (rows.isEmpty()) item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 72.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BreathingPibAvatar(88.dp)
                    Text("我是 ${vm.settings.assistantName}", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = p.label)
                    Text("想聊什么都行，要办的事也可以直接交给我。", fontSize = 15.sp, color = p.secondaryLabel)
                }
            }
            itemsIndexed(rows) { i, b ->
                when (b) {
                    is Bubble.Theirs -> TheirBubble(
                        text = b.text,
                        canRegenerate = !busy && b.exchangeIndex == maxExIdx,
                        onReply = { draft = "「${b.text.take(40)}」\n$draft" },
                        onCopy = { copyToClipboard(ctx, b.text) },
                        onRegenerate = { vm.regenerateCompanion() },
                        onRemind = { vm.sendCompanion("帮我把这件事记进日历提醒：${b.text.take(80)}") },
                        onDelete = { vm.deleteCompanionExchange(b.exchangeIndex) },
                    )
                    is Bubble.Mine -> MyBubble(
                        text = b.text,
                        attachmentName = b.attachmentName,
                        status = if (i == rows.lastIndex) (if (pending != null) "已发出" else "已读") else null,
                        onCopy = { copyToClipboard(ctx, b.text) },
                        onDelete = { if (b.exchangeIndex >= 0) vm.deleteCompanionExchange(b.exchangeIndex) },
                    )
                    is Bubble.Action -> Box(Modifier.fillMaxWidth().padding(horizontal = 46.dp)) { ReceiptLine(b.receipt) }
                }
            }
            if (busy) item {
                if (streamed != null) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PibAvatar(34.dp)
                        Text(
                            streamed,
                            color = p.label,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier.widthIn(max = 270.dp).clip(RoundedCornerShape(20.dp)).background(p.secondaryBackground)
                                .padding(horizontal = 13.dp, vertical = 9.dp),
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PibAvatar(34.dp)
                        Text(vm.progress?.let { "正在$it" } ?: "正在输入…", color = p.secondaryLabel, fontSize = 13.sp)
                    }
                }
            }
        }
        CompanionComposer(vm = vm, text = draft, onTextChange = { draft = it }, busy = busy)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TheirBubble(
    text: String,
    canRegenerate: Boolean,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onRemind: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PibAvatar(34.dp)
        Box {
            Text(
                text,
                color = p.label,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                modifier = Modifier.widthIn(max = 270.dp).clip(RoundedCornerShape(20.dp)).background(p.secondaryBackground)
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            )
            MessageActionMenu(menuOpen, { menuOpen = false }, canRegenerate, onReply, onCopy, onRegenerate, onRemind, onDelete)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MyBubble(text: String, attachmentName: String?, status: String?, onCopy: () -> Unit, onDelete: () -> Unit) {
    val p = LocalPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.widthIn(max = 270.dp).clip(RoundedCornerShape(20.dp)).background(p.blue)
                        .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                ) {
                    if (text.isNotBlank()) Text(text, color = Color.White, fontSize = 16.sp, lineHeight = 22.sp)
                    if (attachmentName != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Outlined.AttachFile, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(14.dp))
                            Text(attachmentName, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("复制") }, onClick = { menuOpen = false; onCopy() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("删除", color = p.red) }, onClick = { menuOpen = false; onDelete() })
                }
            }
            Icon(Icons.Filled.AccountCircle, "我", tint = p.secondaryLabel.copy(alpha = 0.72f), modifier = Modifier.size(34.dp))
        }
        status?.let { Text(it, color = p.secondaryLabel, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp, end = 46.dp)) }
    }
}

/** 24pt capsule input: + menu (照片 / 文件 / 剪贴板), centred placeholder, speech or up-arrow send. */
@Composable
private fun CompanionComposer(vm: PibViewModel, text: String, onTextChange: (String) -> Unit, busy: Boolean) {
    val p = LocalPalette.current
    var plus by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.attachImage(uri, forCompanion = true)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.attachFile(uri, forCompanion = true)
    }
    val speech = rememberSpeechInput { spoken -> onTextChange((text + spoken).trim()) }
    val hasInput = text.isNotBlank() || vm.companionAttachment != null

    Column(Modifier.fillMaxWidth()) {
        AttachmentBar(vm.companionAttachment, vm.attachmentIssue) { vm.clearAttachment(forCompanion = true) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp)).border(0.7.dp, p.separator.copy(alpha = 0.65f), RoundedCornerShape(24.dp)).background(p.background),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Box(Modifier.size(44.dp).clickable(enabled = !busy) { plus = true }.semantics { contentDescription = "添加附件" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, null, tint = if (busy) p.secondaryLabel else p.label)
                }
                PlusMenu(
                    expanded = plus,
                    onDismiss = { plus = false },
                    onPickPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onPickFile = { filePicker.launch(arrayOf("text/*", "application/json", "application/xml", "*/*")) },
                    onPaste = { clip -> onTextChange((text + clip).trim()) },
                )
            }
            Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                if (text.isEmpty()) Text("给 ${vm.settings.assistantName} 发消息", color = p.tertiaryLabel.copy(alpha = 0.9f), fontSize = 16.sp)
                BasicTextField(text, onTextChange, enabled = !busy, maxLines = 5, textStyle = TextStyle(color = p.label, fontSize = 16.sp), cursorBrush = SolidColor(p.blue), modifier = Modifier.fillMaxWidth())
            }
            if (busy) {
                Box(Modifier.size(44.dp).clickable { vm.stopGeneration() }.semantics { contentDescription = "停止生成" }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(p.secondaryBackground), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.5.dp)).background(p.label))
                    }
                }
            } else if (!hasInput) {
                Box(
                    Modifier.size(44.dp).clickable { speech.toggle() }
                        .semantics { contentDescription = if (speech.recording) "停止语音" else "语音输入" },
                    contentAlignment = Alignment.Center,
                ) {
                    if (speech.recording) WaveformGlyph(p.blue) else Icon(Icons.Outlined.Mic, null, tint = p.label)
                }
            } else {
                Box(Modifier.size(44.dp).clickable { vm.sendCompanion(text); onTextChange("") }.semantics { contentDescription = "发送" }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(p.blue), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.ArrowUpward, null, tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
    }
}
