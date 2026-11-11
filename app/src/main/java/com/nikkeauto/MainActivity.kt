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
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
        Schedule.apply(this)
        setContent {
            val ctx = LocalContext.current
            val scheme = appColorScheme(ctx, isSystemInDarkTheme())
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

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var logSettings by rememberSaveable { mutableStateOf(false) }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf(
                Triple("首頁", Icons.Filled.Home, 0),
                Triple("基礎功能", Icons.Filled.Tune, 1),
                Triple("日常", Icons.Filled.PlaylistPlay, 2),
                Triple("設定", Icons.Filled.Settings, 3),
            ).forEach { (label, icon, i) ->
                NavigationBarItem(
                    selected = tab == i,
                    onClick = { tab = i; logSettings = false },
                    icon = { Icon(icon, null) },
                    label = { Text(label) },
                )
            }
        }
    }, topBar = {
        TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("瑪麗安助手")
                Spacer(Modifier.width(8.dp))
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        })
    }) { pad ->
        val page = Modifier
            .fillMaxSize()
            .padding(pad)
            .padding(horizontal = 16.dp, vertical = 8.dp)
        when (tab) {
            // ---------- 首頁：開始推圖／HUD 開關／一鍵日常／日誌 ----------
            0 -> Column(page, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!connected) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "無障礙服務未啟用，請先開啟「瑪麗安助手 MarianHelper」。",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = openA11y) { Text("前往設定") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 開始推圖：先跳到妮姬再開始
                    Button(
                        onClick = { AutoService.instance?.let { if (!running) it.jumpToGame(); it.toggle() } },
                        enabled = connected,
                        colors = if (running) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) else ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Icon(if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (running) "停止" else "開始推圖")
                    }
                    // HUD 開關
                    FilledTonalButton(
                        onClick = { prefs.showOverlay = !overlayOn },
                        enabled = connected,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Icon(if (overlayOn) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (overlayOn) "HUD 開" else "HUD 關")
                    }
                }
                // 一鍵日常：先跳到妮姬再開始
                OutlinedButton(
                    onClick = { AutoService.instance?.startDaily() },
                    enabled = connected && !running,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Filled.PlaylistPlay, null)
                    Spacer(Modifier.width(8.dp))
                    Text("一鍵日常")
                }
                if (connected) {
                    Text(
                        "目前狀態：$status",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LogPanel(prefs, Modifier.weight(1f))
            }

            // ---------- 基礎功能：推圖的功能開關 ----------
            1 -> Column(
                page.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
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
                    HorizontalDivider()
                    ToggleRow(Icons.Filled.SkipNext, "困難模式自動切章", "困難模式打完 Boss 自動進入下一章（關閉則打完就停止）",
                        prefs.hardNextChapter) { prefs.hardNextChapter = it }
                }
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "使用方式：開啟無障礙服務後，在首頁按「開始推圖」會自動切到妮姬並開始；也可以進入章節大地圖後，" +
                            "點 HUD 圓鈕展開面板按「開始推圖」。戰鬥失敗時會自動停止並發出通知。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Spacer(Modifier.padding(8.dp))
            }

            // ---------- 日常 ----------
            2 -> Column(page) {
                DailyCard(prefs, connected, running, Modifier.weight(1f))
            }

            // ---------- 設定 ----------
            else -> if (logSettings) LogSettings(prefs, page) { logSettings = false } else Column(
                page.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "懸浮窗",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                    )
                    Text(
                        "HUD 會一直顯示，切到其他 App 也在；離開遊戲時腳本會自動停止。點面板右上角 X 收起面板；" +
                            "長按圓鈕或首頁的 HUD 開關可以關閉 HUD。下拉通知欄的快速設定面板也能加入「瑪麗安 HUD」開關。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
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
                ScheduleCard(prefs)
                OutlinedButton(onClick = openA11y, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Filled.Accessibility, null)
                    Spacer(Modifier.width(8.dp))
                    Text("無障礙權限設定")
                }
                OutlinedButton(onClick = { logSettings = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Filled.Description, null)
                    Spacer(Modifier.width(8.dp))
                    Text("日誌設定")
                }
                Spacer(Modifier.padding(8.dp))
            }
        }
    }
}

/** 首頁的日誌面板：最新的在最下面，自動捲到底。 */
@Composable
private fun LogPanel(prefs: Prefs, modifier: Modifier) {
    val logs by AutoService.logs.collectAsState()
    val state = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) state.animateScrollToItem(logs.size - 1) }
    val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US) }
    val cs = MaterialTheme.colorScheme
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Description, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("日誌", style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.weight(1f))
                TextButton(onClick = { AutoService.logs.value = emptyList() }) { Text("清除") }
            }
            if (logs.isEmpty()) {
                Text("還沒有紀錄。開始推圖或一鍵日常後，每一步都會顯示在這裡。",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(logs.size) { i ->
                    val e = logs[i]
                    Row(Modifier.padding(vertical = 2.dp)) {
                        if (prefs.logTime) {
                            Text(fmt.format(java.util.Date(e.time)), style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant, modifier = Modifier.width(64.dp))
                        }
                        Text(
                            e.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (e.important) cs.primary else cs.onSurface,
                            fontWeight = if (e.important) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

/** 設定 → 日誌設定。 */
@Composable
private fun LogSettings(prefs: Prefs, modifier: Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var max by remember { mutableIntStateOf(prefs.logMax) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, null)
            Spacer(Modifier.width(4.dp))
            Text("返回設定")
        }
        Card(Modifier.fillMaxWidth()) {
            Text("日誌設定", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp))
            ToggleRow(Icons.Filled.Schedule, "顯示時間", "每一行前面顯示時:分:秒", prefs.logTime) { prefs.logTime = it }
            HorizontalDivider()
            ToggleRow(Icons.Filled.FilterList, "只記錄重要事件", "只記開始、完成、失敗，不記每一步", prefs.logBrief) { prefs.logBrief = it }
            HorizontalDivider()
            ToggleRow(Icons.Filled.PhotoCamera, "失敗時存截圖", "存到相簿的 MarianHelper 資料夾", prefs.saveShots) { prefs.saveShots = it }
            HorizontalDivider()
            Column(Modifier.padding(16.dp)) {
                Text("最多保留 $max 行", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 300, 1000).forEach { n ->
                        FilterChip(selected = max == n, onClick = { max = n; prefs.logMax = n }, label = { Text("$n") })
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = {
                    val fmt = java.text.SimpleDateFormat("MM/dd HH:mm:ss", java.util.Locale.US)
                    val text = AutoService.logs.value.joinToString("\n") { "${fmt.format(java.util.Date(it.time))}  ${it.text}" }
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, text.ifEmpty { "（沒有日誌）" })
                    ctx.startActivity(Intent.createChooser(send, "分享日誌"))
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Share, null)
                Spacer(Modifier.width(8.dp))
                Text("分享日誌")
            }
            OutlinedButton(onClick = { AutoService.logs.value = emptyList() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.DeleteOutline, null)
                Spacer(Modifier.width(8.dp))
                Text("清除日誌")
            }
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


/** 設定頁：定時自動執行、開始時自動開 HUD。 */
@Composable
private fun ScheduleCard(prefs: Prefs) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(prefs.scheduleOn) }
    var time by remember { mutableStateOf(Schedule.nextText(prefs)) }
    var autoHud by remember { mutableStateOf(prefs.autoHud) }
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Text("自動化", style = MaterialTheme.typography.titleSmall, color = cs.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp))
        ListItem(
            leadingContent = { Icon(Icons.Filled.Schedule, null) },
            headlineContent = { Text("定時自動執行一鍵日常") },
            supportingContent = { Text(if (on) "每天 $time 自動開始（今天做過的會跳過）" else "關閉") },
            trailingContent = {
                Switch(checked = on, onCheckedChange = {
                    on = it; prefs.scheduleOn = it; Schedule.apply(ctx)
                    if (it && Build.VERSION.SDK_INT >= 31) {
                        val am = ctx.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
                        if (!am.canScheduleExactAlarms()) {
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                android.net.Uri.parse("package:" + ctx.packageName)))
                        }
                    }
                })
            },
        )
        if (on) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = {
                    android.app.TimePickerDialog(ctx, { _, h, m ->
                        prefs.scheduleHour = h; prefs.scheduleMinute = m
                        time = Schedule.nextText(prefs); Schedule.apply(ctx)
                    }, prefs.scheduleHour, prefs.scheduleMinute, true).show()
                }) {
                    Icon(Icons.Filled.Schedule, null)
                    Spacer(Modifier.width(8.dp))
                    Text("時間：$time")
                }
            }
            Text(
                "到時間會點亮螢幕並打開妮姬。手機需要充電中或電量足夠、無障礙服務保持開啟；" +
                    "有設定密碼／指紋鎖的話無法自動解鎖，建議改用「無」或滑動解鎖，或讓手機在那個時間保持亮著。",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
        HorizontalDivider()
        ListItem(
            leadingContent = { Icon(Icons.Filled.Visibility, null) },
            headlineContent = { Text("開始時自動開啟 HUD") },
            supportingContent = { Text("按開始推圖或一鍵日常時，HUD 關著就自動打開") },
            trailingContent = { Switch(checked = autoHud, onCheckedChange = { autoHud = it; prefs.autoHud = it }) },
        )
    }
}
