package com.nikkeauto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 首頁的「一鍵日常」卡片：開始按鈕、項目開關、上下排序、次數設定。 */
@Composable
fun DailyCard(prefs: Prefs, connected: Boolean, running: Boolean) {
    val order = remember { mutableStateListOf<DailyTask>().apply { addAll(prefs.dailyOrder) } }
    val enabled = remember {
        mutableStateMapOf<DailyTask, Boolean>().apply { DailyTask.entries.forEach { put(it, prefs.dailyEnabled(it)) } }
    }
    var arena by remember { mutableIntStateOf(prefs.arenaCount) }
    var tower by remember { mutableIntStateOf(prefs.towerCount) }
    val cs = MaterialTheme.colorScheme

    fun move(i: Int, d: Int) {
        val j = i + d
        if (j !in order.indices) return
        val t = order[i]; order[i] = order[j]; order[j] = t
        prefs.dailyOrder = order.toList()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("一鍵日常", style = MaterialTheme.typography.titleSmall, color = cs.primary)
            Text(
                "依下面的順序跑完勾選的項目，完成後發通知列出結果。失敗的項目會存截圖到相簿的 MarianHelper 資料夾。",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Button(
                onClick = { AutoService.instance?.startDaily() },
                enabled = connected && !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PlaylistPlay, null)
                Spacer(Modifier.width(8.dp))
                Text("開始一鍵日常")
            }

            // 基礎項目：固定最先執行
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = cs.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                    Icon(Icons.Filled.Lock, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("基礎項目：啟動遊戲", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "每次最先執行：打開妮姬、點進遊戲、關閉公告與活動彈窗、回到大廳",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }

            order.forEachIndexed { i, t ->
                if (i > 0) HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = enabled[t] == true,
                        onCheckedChange = { enabled[t] = it; prefs.setDailyEnabled(t, it) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text("${i + 1}. ${t.label}", style = MaterialTheme.typography.bodyLarge)
                        Text(t.desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        when (t) {
                            DailyTask.ARENA -> Stepper("每天打", arena, "場", 1, 5) { arena = it; prefs.arenaCount = it }
                            DailyTask.TOWER -> Stepper("每座打", tower, "次", 1, 3) { tower = it; prefs.towerCount = it }
                            else -> {}
                        }
                    }
                    Column {
                        IconButton(onClick = { move(i, -1) }, enabled = i > 0, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.KeyboardArrowUp, "上移")
                        }
                        IconButton(onClick = { move(i, 1) }, enabled = i < order.size - 1, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.KeyboardArrowDown, "下移")
                        }
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
