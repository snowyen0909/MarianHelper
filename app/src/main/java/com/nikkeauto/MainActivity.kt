package com.nikkeauto

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val prefs = Prefs(this)
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) {
                SettingsScreen(prefs) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(prefs: Prefs, openA11y: () -> Unit) {
    val connected by AutoService.connected.collectAsState()
    val running by AutoService.running.collectAsState()
    val status by AutoService.lastStatus.collectAsState()
    var overlayOn by remember { mutableStateOf(prefs.showOverlay) }
    DisposableEffect(Unit) {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            overlayOn = prefs.showOverlay
        }
        prefs.listen(l)
        onDispose { prefs.unlisten(l) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("NIKKE 推圖助手") }) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 服務狀態
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (connected) "無障礙服務已啟用" else "無障礙服務未啟用",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (connected) "目前狀態：$status"
                        else "請到「無障礙」設定中開啟「NIKKE 推圖助手」。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!connected) {
                            Button(onClick = openA11y) { Text("前往設定") }
                        } else {
                            Button(onClick = { AutoService.instance?.toggle() }) {
                                Icon(if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (running) "停止" else "開始")
                            }
                            FilledTonalButton(onClick = openA11y) { Text("無障礙設定") }
                        }
                    }
                    if (connected) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (overlayOn) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                null,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("懸浮窗", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (overlayOn) "顯示中（長按懸浮窗 1 秒可隱藏）"
                                    else "已隱藏，打開這裡或用快速設定面板重新顯示",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(checked = overlayOn, onCheckedChange = { prefs.showOverlay = it })
                        }
                    }
                }
            }

            // 功能開關
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "功能開關",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                )
                ToggleRow(Icons.Filled.FastForward, "跳過劇情與結算", "自動點 SKIP、點擊任意處繼續",
                    prefs.skipStory) { prefs.skipStory = it }
                HorizontalDivider()
                ToggleRow(Icons.Filled.Explore, "大地圖自動尋敵", "主線驚嘆號優先，擋路的紅圈怪先打",
                    prefs.mapNav) { prefs.mapNav = it }
                HorizontalDivider()
                ToggleRow(Icons.Filled.Bolt, "出擊準備自動點擊", "可用時點快速戰鬥，否則進入戰鬥",
                    prefs.prepAuto) { prefs.prepAuto = it }
                HorizontalDivider()
                ToggleRow(Icons.Filled.TravelExplore, "地圖巡弋探路", "找不到目標時縮小視角並上下左右滑動",
                    prefs.patrol) { prefs.patrol = it }
                HorizontalDivider()
                ToggleRow(Icons.Filled.Flag, "只打主線目標", "關閉後沒有主線時也會打普通紅圈怪",
                    prefs.onlyMain) { prefs.onlyMain = it }
            }

            // 懸浮窗
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "懸浮窗",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                )
                Text(
                    "下拉通知欄的快速設定面板也能加入「NIKKE 懸浮窗」開關，遊戲中隨時切換。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ToggleRow(Icons.Filled.SportsEsports, "只在遊戲中顯示", "離開妮姬時自動隱藏並停止腳本",
                    prefs.overlayOnlyInGame) { prefs.overlayOnlyInGame = it }
            }

            // 延遲
            Card(Modifier.fillMaxWidth()) {
                var range by remember {
                    mutableStateOf(prefs.delayMin.toFloat()..prefs.delayMax.toFloat())
                }
                Column(Modifier.padding(16.dp)) {
                    Text("點擊後隨機延遲", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text("${range.start.toInt()} ～ ${range.endInclusive.toInt()} ms",
                        style = MaterialTheme.typography.bodyLarge)
                    RangeSlider(
                        value = range,
                        onValueChange = { range = it },
                        onValueChangeFinished = {
                            prefs.delayMin = range.start.toInt()
                            prefs.delayMax = range.endInclusive.toInt()
                        },
                        valueRange = 200f..3000f,
                        steps = 27,
                    )
                }
            }

            Text(
                "使用方式：開啟無障礙服務後，遊戲畫面左側會出現懸浮按鈕，進入章節大地圖後點「▶ 開始」。" +
                    "懸浮按鈕可拖曳。戰鬥失敗時會自動停止並發出通知。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    desc: String,
    initial: Boolean,
    onChange: (Boolean) -> Unit,
) {
    // 以 initial 為 key：長按懸浮窗隱藏後，回到 App 開關會跟著更新
    var checked by remember(initial) { mutableStateOf(initial) }
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(desc) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = { checked = it; onChange(it) })
        },
    )
}
