package com.nikkeauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
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

class AutoService : AccessibilityService(), Actions {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private var loop: Job? = null
    private lateinit var prefs: Prefs
    private lateinit var reader: ScreenReader

    /** 縮小畫面（720 寬）→ 實際螢幕的比例。 */
    @Volatile private var scale = 1f

    private var overlay: LinearLayout? = null
    private var btn: TextView? = null
    private var statusView: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    /** 懸浮窗在實際螢幕上的範圍（多留邊距，涵蓋狀態列偏移）。 */
    private val overlayRect: android.graphics.Rect?
        get() {
            val p = overlayParams ?: return null
            val v = overlay ?: return null
            val m = 80
            return android.graphics.Rect(p.x - m, p.y - m, p.x + v.width + m, p.y + v.height + m * 2)
        }

    override fun onServiceConnected() {
        instance = this
        prefs = Prefs(this)
        reader = ScreenReader()
        createChannel()
        showOverlay()
        connected.value = true
    }

    override fun onDestroy() {
        stopBot()
        scope.cancel()
        reader.close()
        overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        instance = null
        connected.value = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------------- 開始 / 停止 ----------------

    fun toggle() = if (loop?.isActive == true) stopBot() else startBot()

    fun startBot() {
        if (loop?.isActive == true) return
        val bot = Bot(prefs, this)
        running.value = true
        updateButton()
        loop = scope.launch {
            status("啟動中…")
            while (isActive) {
                try {
                    val shot = capture()
                    if (shot == null) { delay(700); continue }
                    scale = shot.width / Vision.WIDTH.toFloat()
                    val small = Vision.normalize(shot)
                    shot.recycle()
                    // 懸浮窗自己的文字也會被截到，要排除
                    val ov = overlayRect
                    val lines = reader.read(small).filter { l ->
                        ov == null || !ov.contains(
                            (l.box.exactCenterX() * scale).toInt(),
                            (l.box.exactCenterY() * scale).toInt(),
                        )
                    }
                    bot.step(small, lines)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status("錯誤：${e.message}")
                }
                delay(600)
            }
        }
    }

    fun stopBot() {
        loop?.cancel()
        loop = null
        running.value = false
        updateButton()
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

    override suspend fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo((x + jitter()) * scale, (y + jitter()) * scale) }
        gesture(GestureDescription.StrokeDescription(path, 0, Random.nextLong(40, 90)))
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
        main.post { statusView?.text = text }
    }

    override fun stopWithAlert(title: String, body: String) {
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

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(Color.argb(200, 30, 30, 36))
            }
        }
        val b = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            text = "▶ 開始"
        }
        val s = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.argb(220, 200, 200, 210))
            maxWidth = (140 * dp).toInt()
            text = "待命"
        }
        root.addView(b)
        root.addView(s)

        // 拖曳移動；沒拖動就當作點擊切換開始/停止
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (dx * dx + dy * dy > 100) moved = true
                    params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                    wm.updateViewLayout(root, params)
                }
                MotionEvent.ACTION_UP -> if (!moved) toggle()
            }
            true
        }

        wm.addView(root, params)
        overlay = root; btn = b; statusView = s
        overlayParams = params
    }

    private fun updateButton() = main.post {
        btn?.text = if (running.value) "■ 停止" else "▶ 開始"
    }

    companion object {
        private const val CHANNEL = "alerts"
        @Volatile var instance: AutoService? = null
        val connected = MutableStateFlow(false)
        val running = MutableStateFlow(false)
        val lastStatus = MutableStateFlow("待命")
    }
}
