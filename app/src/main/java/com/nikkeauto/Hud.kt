package com.nikkeauto

import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/** App 與 HUD 共用的 Material 3 配色：Android 12 以上用動態配色，Android 11 用預設配色。 */
fun appColorScheme(context: Context, dark: Boolean): ColorScheme =
    if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }

/** 讓 ComposeView 能放在無障礙服務的懸浮視窗裡（服務本身沒有 Activity 的生命週期）。 */
class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun start() {
        controller.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

/** HUD 需要的動作，由 AutoService 實作。 */
interface HudCallbacks {
    fun toggleExpanded()
    fun startStop()
    fun hide()
    fun drag(dx: Float, dy: Float)
    fun dragEnd()
}

/**
 * 懸浮 HUD：收合時是一顆圓鈕，點一下展開控制面板，再點一下收起。
 * 圓鈕可拖曳，放開後會貼齊左右邊緣；長按圓鈕隱藏 HUD。
 */
@Composable
fun Hud(prefs: Prefs, expanded: Boolean, onRight: Boolean, cb: HudCallbacks) {
    val running by AutoService.running.collectAsState()
    val status by AutoService.lastStatus.collectAsState()

    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(6.dp),
    ) {
        if (onRight) {
            HudPanel(expanded, running, status, prefs, cb, fromRight = true)
            HudBubble(running, expanded, cb)
        } else {
            HudBubble(running, expanded, cb)
            HudPanel(expanded, running, status, prefs, cb, fromRight = false)
        }
    }
}

@Composable
private fun HudBubble(running: Boolean, expanded: Boolean, cb: HudCallbacks) {
    val cs = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { cb.dragEnd() }) { change, amount ->
                    change.consume()
                    cb.drag(amount.x, amount.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { cb.toggleExpanded() },
                    onLongPress = { cb.hide() },
                )
            },
    ) {
        // 瑪麗安頭像；展開時外圈加上主題色邊框
        Surface(
            shape = CircleShape,
            shadowElevation = 6.dp,
            border = if (expanded && !running) BorderStroke(2.dp, cs.primary) else null,
            modifier = Modifier.size(48.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.marian_avatar),
                contentDescription = "瑪麗安助手",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(CircleShape),
            )
        }
        // 運行中時，圓鈕外圈有轉動的進度環
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(56.dp),
                strokeWidth = 3.dp,
                color = cs.primary,
                trackColor = cs.primaryContainer,
            )
        }
    }
}

@Composable
private fun HudPanel(
    expanded: Boolean,
    running: Boolean,
    status: String,
    prefs: Prefs,
    cb: HudCallbacks,
    fromRight: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val ver by AutoService.prefVersion.collectAsState()
    val skip = remember(ver) { prefs.skipStory }
    val nav = remember(ver) { prefs.mapNav }
    val prep = remember(ver) { prefs.prepAuto }
    val patrol = remember(ver) { prefs.patrol }

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandHorizontally(expandFrom = if (fromRight) Alignment.End else Alignment.Start),
        exit = fadeOut() + shrinkHorizontally(shrinkTowards = if (fromRight) Alignment.End else Alignment.Start),
    ) {
        ElevatedCard(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = cs.surfaceContainer),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
            modifier = Modifier.width(264.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 標題與狀態標籤
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.marian_avatar),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp).clip(CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("瑪麗安助手", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "MarianHelper",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (running) cs.primaryContainer else cs.surfaceContainerHighest,
                        contentColor = if (running) cs.onPrimaryContainer else cs.onSurfaceVariant,
                    ) {
                        Text(
                            if (running) "運行中" else "待命",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    // 關閉 HUD
                    IconButton(onClick = { cb.hide() }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "關閉 HUD")
                    }
                }

                // 目前動作
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = cs.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }

                // 開始／停止
                Button(
                    onClick = { cb.startStop() },
                    colors = if (running) ButtonDefaults.buttonColors(
                        containerColor = cs.errorContainer, contentColor = cs.onErrorContainer,
                    ) else ButtonDefaults.buttonColors(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) "停止" else "開始推圖")
                }

                // 功能開關
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudChip("跳劇情", Icons.Filled.FastForward, skip, Modifier.weight(1f)) { prefs.skipStory = it }
                        HudChip("尋敵", Icons.Filled.Explore, nav, Modifier.weight(1f)) { prefs.mapNav = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudChip("自動出擊", Icons.Filled.Bolt, prep, Modifier.weight(1f)) { prefs.prepAuto = it }
                        HudChip("探路", Icons.Filled.TravelExplore, patrol, Modifier.weight(1f)) { prefs.patrol = it }
                    }
                }

            }
        }
    }
}

@Composable
private fun HudChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier,
    onChange: (Boolean) -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text(label, maxLines = 1) },
        leadingIcon = {
            Icon(if (selected) Icons.Filled.Check else icon, null, modifier = Modifier.size(18.dp))
        },
        modifier = modifier,
    )
}
