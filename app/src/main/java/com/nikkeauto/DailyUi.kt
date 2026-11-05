package com.nikkeauto

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * 「日常」分頁：開始按鈕、基礎項目、可拖曳排序的項目清單。
 * 長按右邊的 ≡ 拖動就能調整順序，放開後自動儲存。
 */
@Composable
fun DailyCard(prefs: Prefs, connected: Boolean, running: Boolean, modifier: Modifier = Modifier) {
    val order = remember { mutableStateListOf<DailyTask>().apply { addAll(prefs.dailyOrder) } }
    val enabled = remember {
        mutableStateMapOf<DailyTask, Boolean>().apply { DailyTask.entries.forEach { put(it, prefs.dailyEnabled(it)) } }
    }
    var arena by remember { mutableIntStateOf(prefs.arenaCount) }
    var tower by remember { mutableIntStateOf(prefs.towerCount) }
    var doneToday by remember { mutableStateOf(prefs.dailyDoneToday()) }
    androidx.compose.runtime.LaunchedEffect(running) { doneToday = prefs.dailyDoneToday() }
    val cs = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val ctx = LocalContext.current

    // 拖曳中的項目與位移
    var dragging by remember { mutableStateOf<DailyTask?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { AutoService.instance?.startDaily() },
            enabled = connected && !running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.PlaylistPlay, null)
            Spacer(Modifier.width(8.dp))
            Text(if (doneToday.isEmpty()) "開始一鍵日常" else "繼續一鍵日常（今天已完成 ${doneToday.size} 項）")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "長按 ≡ 拖曳排序｜▶ 只測這一項｜中斷後再按會接著做",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (doneToday.isNotEmpty()) {
                TextButton(onClick = {
                    prefs.resetDailyDone(); doneToday = emptySet()
                    android.widget.Toast.makeText(ctx, "已清除今天的完成紀錄", android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.RestartAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重新開始")
                }
            }
        }

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            // 基礎項目：固定最先執行
            item(key = "base") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = cs.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
                        Icon(Icons.Filled.Lock, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("基礎項目：登入遊戲", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "打開妮姬 → 點 TOUCH TO CONTINUE 進遊戲 → 關閉公告與活動彈窗",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            itemsIndexed(order, key = { _, t -> t.key }) { i, t ->
                val isDragging = dragging == t
                var itemH by remember { mutableFloatStateOf(1f) }
                val on = enabled[t] == true
                val done = t in doneToday
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDragging) cs.primaryContainer else cs.surfaceContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                        .then(if (isDragging) Modifier.shadow(8.dp, RoundedCornerShape(16.dp)) else Modifier)
                        .onSizeChanged { itemH = it.height.toFloat() },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)) {
                        // 編號標籤
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (on) cs.primary else cs.surfaceContainerHighest,
                            contentColor = if (on) cs.onPrimary else cs.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t.label, style = MaterialTheme.typography.bodyLarge)
                                if (done) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("今日完成", style = MaterialTheme.typography.labelSmall, color = cs.primary)
                                }
                            }
                            Text(t.desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            when (t) {
                                DailyTask.ARENA -> Stepper("每天打", arena, "場", 1, 5) { arena = it; prefs.arenaCount = it }
                                DailyTask.TOWER -> Stepper("每座打", tower, "次", 1, 3) { tower = it; prefs.towerCount = it }
                                else -> {}
                            }
                        }
                        Switch(checked = on, onCheckedChange = { enabled[t] = it; prefs.setDailyEnabled(t, it) })
                        IconButton(
                            onClick = { AutoService.instance?.runDaily(t) },
                            enabled = connected && !running,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Filled.PlayCircleOutline, "只測試這一項", tint = cs.primary)
                        }
                        // 拖曳把手
                        Icon(
                            Icons.Filled.DragHandle,
                            "長按拖曳排序",
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier
                                .size(40.dp)
                                .padding(8.dp)
                                .pointerInput(t) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { dragging = t; dragOffset = 0f },
                                        onDragEnd = {
                                            dragging = null; dragOffset = 0f
                                            prefs.dailyOrder = order.toList()
                                        },
                                        onDragCancel = { dragging = null; dragOffset = 0f },
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val step = itemH + 8.dp.toPx()
                                        val cur = order.indexOf(t)
                                        if (dragOffset > step / 2 && cur < order.size - 1) {
                                            order.removeAt(cur); order.add(cur + 1, t); dragOffset -= step
                                        } else if (dragOffset < -step / 2 && cur > 0) {
                                            order.removeAt(cur); order.add(cur - 1, t); dragOffset += step
                                        }
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Stepper(prefix: String, value: Int, unit: String, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(prefix, style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onChange(value - 1) }, enabled = value > min, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Remove, "減少", modifier = Modifier.size(18.dp))
        }
        Text("$value", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onChange(value + 1) }, enabled = value < max, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, "增加", modifier = Modifier.size(18.dp))
        }
        Text(unit, style = MaterialTheme.typography.bodyMedium)
    }
}
