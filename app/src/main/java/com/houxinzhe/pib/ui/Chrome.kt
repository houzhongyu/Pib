package com.houxinzhe.pib.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.houxinzhe.pib.data.ThreadInfo

/** iOS DailyCircleButtonStyle: 44pt circle, hairline border, pressed fill. */
@Composable
fun CircleButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(44.dp).clip(CircleShape)
            .background(if (pressed) p.secondaryBackground else p.background, CircleShape)
            .border(0.7.dp, p.separator.copy(alpha = 0.45f), CircleShape)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        content = content,
    )
}

/** Two-line hamburger (18pt + 12pt capsules), opens the sidebar. */
@Composable
fun MenuButton(onClick: () -> Unit) {
    val p = LocalPalette.current
    CircleButton("打开历史与菜单", onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.width(18.dp)) {
            Box(Modifier.width(18.dp).height(2.dp).background(p.label, CircleShape))
            Box(Modifier.width(12.dp).height(2.dp).background(p.label, CircleShape))
        }
    }
}

@Composable
fun TabBar(current: Tab, onSelect: (Tab) -> Unit) {
    val p = LocalPalette.current
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(p.background).navigationBarsPadding().padding(horizontal = 48.dp, vertical = 4.dp),
    ) {
        Tab.entries.forEach { tab ->
            val selected = tab == current
            val color = if (selected) p.label else p.secondaryLabel
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.widthIn(max = 70.dp).weight(1f).heightIn(min = 44.dp).clip(CircleShape)
                    .background(if (selected) p.secondaryBackground else Color.Transparent, CircleShape)
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .semantics { contentDescription = tab.label; this.selected = selected },
            ) {
                when (tab) {
                    Tab.HOME -> DoorGlyph(color, selected)
                    Tab.COMPANION -> BirdFishGlyph(color, selected)
                    Tab.MORE -> GridGlyph(color, selected)
                }
            }
        }
    }
}

val Tab.label get() = when (this) { Tab.HOME -> "首页"; Tab.COMPANION -> "Pib"; Tab.MORE -> "更多" }

/** Full-screen sidebar: destinations, searchable assistant history, 新建 + search/settings capsules. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Sidebar(vm: PibViewModel) {
    val p = LocalPalette.current
    BackHandler { vm.sidebar = false }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val rows = remember(vm.revision, query) { vm.threads.assistantThreads(query) }
    val pinned = rows.filter { it.pinned }
    val regular = rows.filterNot { it.pinned }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.horizontalGradient(listOf(p.secondaryBackground, p.background)))
            .statusBarsPadding().navigationBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(44.dp).clickable { vm.sidebar = false }.semantics { contentDescription = "关闭菜单" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, null, tint = p.secondaryLabel)
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp)) {
                item { Destination("首页", { DoorGlyph(p.label, false) }) { vm.page = null; vm.tab = Tab.HOME; vm.sidebar = false } }
                item { Destination("Pib", { BirdFishGlyph(p.label, false) }) { vm.page = null; vm.tab = Tab.COMPANION; vm.sidebar = false } }
                item { Destination("更多", { GridGlyph(p.label, false) }) { vm.page = null; vm.tab = Tab.MORE; vm.sidebar = false } }
                if (searching) item {
                    Box(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.quaternary).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        if (query.isEmpty()) Text("搜索助手对话", color = p.secondaryLabel, fontSize = 16.sp)
                        BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = p.label, fontSize = 16.sp), cursorBrush = SolidColor(p.blue), modifier = Modifier.fillMaxWidth())
                    }
                }
                if (pinned.isNotEmpty()) {
                    item { SectionTitle("置顶") }
                    items(pinned, key = { "p" + it.id }) { ThreadRow(it, vm) }
                }
                item { SectionTitle("聊天") }
                if (rows.isEmpty()) item {
                    Text(if (query.isEmpty()) "还没有助手对话" else "没有找到匹配的对话", color = p.secondaryLabel, fontSize = 15.sp, modifier = Modifier.padding(vertical = 10.dp))
                } else items(regular, key = { "c" + it.id }) { ThreadRow(it, vm) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(48.dp).clip(CircleShape).background(p.blue).clickable { vm.newChat() }.padding(horizontal = 21.dp),
                ) {
                    Icon(Icons.Outlined.EditNote, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Text("新建", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(CircleShape).background(p.background.copy(alpha = 0.92f)).border(0.7.dp, p.separator.copy(alpha = 0.6f), CircleShape).padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(44.dp).clickable { searching = !searching; if (!searching) query = "" }.semantics { contentDescription = "搜索" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Search, null, tint = p.label)
                    }
                    Box(Modifier.size(44.dp).clickable { vm.sidebar = false; vm.page = Page.SETTINGS }.semantics { contentDescription = "设置" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Settings, null, tint = p.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LocalPalette.current.label, modifier = Modifier.padding(top = 28.dp, bottom = 8.dp))
}

@Composable
private fun Destination(title: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(17.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(onClick = onClick),
    ) {
        icon()
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Medium, color = LocalPalette.current.label)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(info: ThreadInfo, vm: PibViewModel) {
    val p = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).combinedClickable(onClick = { vm.openThread(info.id) }, onLongClick = { menu = true }),
        ) {
            Text(info.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, color = p.label, modifier = Modifier.weight(1f))
            if (info.pinned) Icon(Icons.Outlined.PushPin, "已置顶", tint = p.secondaryLabel, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(if (info.pinned) "取消置顶" else "置顶") }, onClick = { menu = false; vm.togglePin(info) })
            DropdownMenuItem(text = { Text("删除", color = p.red) }, onClick = { menu = false; vm.deleteThread(info) })
        }
    }
}
