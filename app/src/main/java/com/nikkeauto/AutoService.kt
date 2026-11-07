package com.nikkeauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.random.Random

class AutoService : AccessibilityService(), Actions, HudCallbacks {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private var loop: Job? = null
    private lateinit var prefs: Prefs
    private lateinit var reader: ScreenReader

    /** 縮小畫面（720 寬）→ 實際螢幕的比例。 */
    @Volatile private var scale = 1f

    private var overlay: ComposeView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var hudLifecycle: OverlayLifecycleOwner? = null
    private var hudExpanded by mutableStateOf(false)
    private var hudOnRight by mutableStateOf(false)

    /** HUD 在實際螢幕上的範圍（多留邊距）。截圖辨識時排除這塊。 */
    private val overlayRect: android.graphics.Rect?
        get() {
            val v = overlay ?: return null
            if (v.visibility != View.VISIBLE || v.width == 0) return null
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val m = 24
            return android.graphics.Rect(loc[0] - m, loc[1] - m, loc[0] + v.width + m, loc[1] + v.height + m)
        }

    override fun onServiceConnected() {
        instance = this
        prefs = Prefs(this)
        reader = ScreenReader()
        Templates.load(assets)
        createChannel()
        showOverlay()
        prefs.listen(prefListener)
        updateOverlayVisibility()
        connected.value = true
    }

    /** 設定頁切換開關時立即更新懸浮窗。 */
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        prefVersion.value++
        main.post { updateOverlayVisibility() }
    }

    /** 目前前景的 App 是不是妮姬（或本 App 自己）。 */
    @Volatile private var inGame = true

    /** HUD 一直顯示，只有使用者自己關掉（長按圓鈕、面板的 X、App 或快速設定）才隱藏。 */
    private fun updateOverlayVisibility() {
        val show = prefs.showOverlay
        overlay?.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) hudExpanded = false
    }

    override fun onDestroy() {
        stopBot()
        prefs.unlisten(prefListener)
        scope.cancel()
        reader.close()
        overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        hudLifecycle?.stop()
        instance = null
        connected.value = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // 系統介面（通知欄、輸入法、權限視窗）不算切換 App
        // 系統介面、Google 登入／Play 遊戲橫幅、本 App 自己，都不算離開遊戲
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard") ||
            pkg.startsWith("com.google.android.gms") || pkg.startsWith("com.google.android.play") ||
            pkg == "com.android.vending" || pkg == packageName || pkg == "android"
        ) return
        val now = pkg.contains("nikke", ignoreCase = true)
        if (now == inGame) return
        inGame = now
        if (!now && running.value) {
            // 離開遊戲 8 秒後還沒回來才停止（遊戲啟動、登入時會短暫切到別的畫面）
            val stamp = System.currentTimeMillis()
            leaveStamp = stamp
            main.postDelayed({
                if (!inGame && leaveStamp == stamp && running.value && System.currentTimeMillis() > graceUntil) {
                    stopBot()
                    status("已離開遊戲，腳本已停止")
                }
            }, 8_000)
        }
    }
    override fun onInterrupt() {}

    @Volatile private var leaveStamp = 0L
    /** 這個時間之前不因「離開遊戲」而停止（剛從 App 啟動遊戲時）。 */
    @Volatile private var graceUntil = 0L

    /** 從 App 按開始時：先跳到妮姬，給遊戲 90 秒啟動。 */
    fun jumpToGame() {
        graceUntil = System.currentTimeMillis() + 90_000
        inGame = false
        launchGame()
    }

    // ---------------- 開始 / 停止 ----------------

    fun toggle() = if (loop?.isActive == true) stopBot() else startBot()

    /** 截圖並辨識文字；畫面縮成寬 720，左上角另外用原解析度讀小字。 */
    private suspend fun snapshot(): Frame? {
        val shot = capture() ?: return null
        scale = shot.width / Vision.WIDTH.toFloat()
        val small = Vision.normalize(shot)
        val crop = Bitmap.createBitmap(shot, 0, 0, shot.width / 2, (shot.height * 0.4f).toInt())
        val extra = reader.read(crop).map { l ->
            val b = l.box
            TextLine(
                l.text,
                android.graphics.Rect(
                    (b.left / scale).toInt(), (b.top / scale).toInt(),
                    (b.right / scale).toInt(), (b.bottom / scale).toInt(),
                ),
                l.isWord,
            )
        }
        crop.recycle()
        shot.recycle()
        // HUD 自己的文字和顏色也會被截到，要排除
        val ov = overlayRect
        Vision.hudRect = ov?.let {
            android.graphics.RectF(it.left / scale, it.top / scale, it.right / scale, it.bottom / scale)
        }
        val lines = (reader.read(small) + extra).filter { l ->
            ov == null || !ov.contains(
                (l.box.exactCenterX() * scale).toInt(),
                (l.box.exactCenterY() * scale).toInt(),
            )
        }
        return Frame(small, lines)
    }

    override suspend fun look(): Frame? = snapshot()

    /** 開始時：依設定打開 HUD，並讓螢幕在執行期間保持亮著。 */
    private fun onStartRun() {
        if (prefs.autoHud && !prefs.showOverlay) prefs.showOverlay = true
        setKeepScreenOn(true)
    }

    private fun setKeepScreenOn(on: Boolean) = main.post {
        val p = overlayParams ?: return@post
        val v = overlay ?: return@post
        p.flags = if (on) p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(v, p) } catch (_: Exception) {}
    }

    /** 定時任務叫醒時：切到妮姬並開始一鍵日常（今天做過的會跳過）。 */
    fun scheduledStart() {
        if (loop?.isActive == true) return
        log("定時任務：開始一鍵日常", important = true)
        startDaily()
    }

    fun startBot() {
        if (loop?.isActive == true) return
        onStartRun()
        val bot = Bot(prefs, this)
        running.value = true
        hudExpanded = false   // 開始後收起面板，避免擋住遊戲畫面
        loop = scope.launch {
            log("開始推圖", important = true)
            status("啟動中…")
            while (isActive) {
                try {
                    val f = snapshot()
                    if (f == null) { delay(700); continue }
                    bot.step(f.bmp, f.lines)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status("錯誤：${e.message}")
                }
                delay(600)
            }
        }
    }

    /** 一鍵日常：跑完後發通知列出每一項的結果。 */
    override fun startDaily() { jumpToGame(); runDaily(null) }

    /** only != null 時只跑那一項（App 上的單項測試）。 */
    fun runDaily(only: DailyTask?) {
        if (loop?.isActive == true) return
        onStartRun()
        running.value = true
        hudExpanded = false
        loop = scope.launch {
            log(if (only != null) "單項測試：${only.label}" else "開始一鍵日常", important = true)
            val summary = try {
                DailyRunner(prefs, this@AutoService).run(only)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "發生錯誤：${e.message}"
            }
            notify(if (only != null) "單項測試完成：${only.label}" else "一鍵日常完成", summary)
            main.post {
                loop = null
                running.value = false
                setKeepScreenOn(false)
                status("一鍵日常完成")
            }
        }
    }

    fun stopBot() {
        setKeepScreenOn(false)
        loop?.cancel()
        loop = null
        running.value = false
        status("已停止")
    }

    // ---------------- 截圖 ----------------

    private suspend fun capture(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    result.hardwareBuffer.close()
                    if (cont.isActive) cont.resume(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    // 截圖太頻繁時會回傳錯誤，下一輪再試
                    if (cont.isActive) cont.resume(null)
                }
            })
    }

    // ---------------- 手勢（Actions 實作） ----------------

    private fun jitter() = Random.nextFloat() * 8f - 4f

    override suspend fun tap(x: Float, y: Float, precise: Boolean) {
        val jx = if (precise) 0f else jitter()
        val jy = if (precise) 0f else jitter()
        val path = Path().apply { moveTo((x + jx) * scale, (y + jy) * scale) }
        gesture(GestureDescription.StrokeDescription(path, 0, Random.nextLong(40, 90)))
    }

    override suspend fun back() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    override suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val path = Path().apply {
            moveTo(x1 * scale, y1 * scale)
            lineTo(x2 * scale, y2 * scale)
        }
        gesture(GestureDescription.StrokeDescription(path, 0, ms))
    }

    /** 雙指往中間捏 = 縮小地圖、擴大視野。 */
    override suspend fun pinchOut(cx: Float, cy: Float) {
        val d1 = 230f; val d2 = 40f
        val a = Path().apply {
            moveTo((cx - d1) * scale, (cy - d1) * scale)
            lineTo((cx - d2) * scale, (cy - d2) * scale)
        }
        val b = Path().apply {
            moveTo((cx + d1) * scale, (cy + d1) * scale)
            lineTo((cx + d2) * scale, (cy + d2) * scale)
        }
        gesture(
            GestureDescription.StrokeDescription(a, 0, 500),
            GestureDescription.StrokeDescription(b, 0, 500),
        )
    }

    private suspend fun gesture(vararg strokes: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine<Boolean> { cont ->
            val g = GestureDescription.Builder().apply { strokes.forEach { addStroke(it) } }.build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }

    override suspend fun humanDelay() {
        val lo = prefs.delayMin
        val hi = maxOf(prefs.delayMax, lo + 1)
        delay(Random.nextLong(lo.toLong(), hi.toLong()))
    }

    override fun status(text: String) {
        lastStatus.value = text
        if (!prefs.logBrief) log(text)
    }

    /** 寫一行日誌（相同內容連續出現只記一次）。 */
    fun log(text: String, important: Boolean = false) {
        if (prefs.logBrief && !important) return
        val cur = logs.value
        if (cur.isNotEmpty() && cur.last().text == text) return
        val max = prefs.logMax.coerceAtLeast(50)
        logs.value = (cur + LogEntry(System.currentTimeMillis(), text, important)).takeLast(max)
    }

    override fun launchGame() {
        if (inGame) return
        val pm = packageManager
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val app = pm.queryIntentActivities(launcher, 0).firstOrNull { ri ->
            ri.activityInfo.packageName.contains("nikke", ignoreCase = true) ||
                ri.loadLabel(pm).toString().let { it.contains("NIKKE", ignoreCase = true) || it.contains("妮姬") }
        } ?: return
        pm.getLaunchIntentForPackage(app.activityInfo.packageName)?.let {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    override fun saveDebug(bmp: Bitmap, name: String) {
        if (!prefs.saveShots) return
        log("已存失敗截圖：$name")
        try {
            val time = java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "${name}_$time.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MarianHelper")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {
        }
    }

    override fun notify(title: String, body: String) {
        log("【$title】\n$body", important = true)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2, n)
    }

    override fun stopWithAlert(title: String, body: String) {
        log("【$title】$body", important = true)
        main.post { stopBot(); status(title) }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, n)
    }

    // ---------------- 懸浮窗 ----------------

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "腳本提醒", NotificationManager.IMPORTANCE_HIGH)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        }

        val owner = OverlayLifecycleOwner().also { it.start() }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                MaterialTheme(colorScheme = appColorScheme(this@AutoService, dark)) {
                    Hud(prefs, hudExpanded, hudOnRight, this@AutoService)
                }
            }
        }
        wm.addView(view, params)
        overlay = view
        overlayParams = params
        hudLifecycle = owner
    }

    // ---------------- HUD 回呼 ----------------

    override fun toggleExpanded() { hudExpanded = !hudExpanded }

    override fun startStop() { if (!running.value) jumpToGame(); toggle() }

    override fun hide() {
        hudExpanded = false
        prefs.showOverlay = false
        android.widget.Toast.makeText(this, "HUD 已隱藏，可在 App 首頁或快速設定面板重新開啟", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun drag(dx: Float, dy: Float) {
        val p = overlayParams ?: return
        val v = overlay ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // 靠右時 x 是距離右邊的距離，拖動方向相反
        val right = (p.gravity and Gravity.END) == Gravity.END
        p.x = (p.x + if (right) -dx else dx).toInt().coerceAtLeast(0)
        p.y = (p.y + dy).toInt().coerceIn(0, resources.displayMetrics.heightPixels - v.height)
        wm.updateViewLayout(v, p)
    }

    /** 放開後貼齊最近的左右邊緣，面板會往螢幕內側展開。 */
    override fun dragEnd() {
        val p = overlayParams ?: return
        val v = overlay ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val sw = resources.displayMetrics.widthPixels
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val onRight = loc[0] + v.width / 2 > sw / 2
        p.gravity = Gravity.TOP or (if (onRight) Gravity.END else Gravity.START)
        p.x = 0
        hudOnRight = onRight
        wm.updateViewLayout(v, p)
    }

    companion object {
        private const val CHANNEL = "alerts"
        @Volatile var instance: AutoService? = null
        val connected = MutableStateFlow(false)
        val running = MutableStateFlow(false)
        val lastStatus = MutableStateFlow("待命")
        /** App 首頁顯示的日誌。 */
        val logs = MutableStateFlow<List<LogEntry>>(emptyList())
        /** 設定有變動就 +1，讓 HUD 重新讀取開關狀態。 */
        val prefVersion = MutableStateFlow(0)
    }
}

/** 一行日誌。 */
data class LogEntry(val time: Long, val text: String, val important: Boolean)
