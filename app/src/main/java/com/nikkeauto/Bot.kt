package com.nikkeauto

import android.graphics.Bitmap
import kotlin.math.hypot

/** 機器人對外需要的動作，由 AutoService 實作。座標都是 720 寬的縮小畫面座標。 */
interface Actions {
    /** precise = true 時不加隨機偏移（小按鈕用）。 */
    suspend fun tap(x: Float, y: Float, precise: Boolean = false)
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 400)
    suspend fun pinchOut(cx: Float, cy: Float)
    suspend fun humanDelay()
    fun status(text: String)
    fun stopWithAlert(title: String, body: String)
}

/** 每一輪：截圖 → 讀字 → 判斷畫面 → 做一個動作。 */
class Bot(private val prefs: Prefs, private val act: Actions) {

    private var lastScreen = ScreenType.UNKNOWN
    private var lastTarget: Vision.Pt? = null
    private var sameTargetCount = 0
    private var patrolStep = 0
    private var zoomedOut = false
    private var minimapTries = 0
    private var prevMapFrame: Bitmap? = null

    suspend fun step(bmp: Bitmap, lines: List<TextLine>) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val screen = ScreenReader.classify(lines, bmp.height)
        if (screen != ScreenType.MAP) {
            prevMapFrame = null
            if (lastScreen == ScreenType.MAP) resetMapState()
        }
        lastScreen = screen

        when (screen) {
            ScreenType.FAILED -> {
                val stage = ScreenReader.stageName(lines)
                act.stopWithAlert(
                    "戰鬥失敗，已停止",
                    if (stage != null) "關卡 $stage 挑戰失敗，請檢查隊伍或練度。" else "挑戰失敗，請檢查隊伍或練度。",
                )
            }

            ScreenType.STORY -> {
                if (!prefs.skipStory) return act.status("劇情（跳過已關閉）")
                val skip = ScreenReader.findWord(lines, "SKIP")!!
                act.status("劇情 → 點 SKIP")
                // SKIP 文字左邊有 ▶▶ 圖示，點文字中心略偏左
                act.tap(skip.box.exactCenterX() - skip.box.width() * 0.15f, skip.box.exactCenterY())
                act.humanDelay()
            }

            ScreenType.DIALOG_LOG -> {
                // 誤開了對話紀錄，點右上角 X 關掉
                val dialog = ScreenReader.find(lines, "DIALOGUE")!!
                act.status("關閉對話紀錄")
                act.tap(w * 0.94f, dialog.box.exactCenterY())
                act.humanDelay()
            }

            ScreenType.TAP_ANY -> {
                if (!prefs.skipStory) return act.status("結算（跳過已關閉）")
                act.status("結算 → 點擊繼續")
                act.tap(w * 0.5f, h * 0.72f)
                act.humanDelay()
            }

            ScreenType.PREPARE -> {
                if (!prefs.prepAuto) return act.status("出擊準備（自動點擊已關閉）")
                val quick = ScreenReader.find(lines, "快速戰鬥", "快速战斗")
                if (quick != null && ScreenReader.isOrangeButton(bmp, quick.box)) {
                    act.status("出擊準備 → 快速戰鬥")
                    act.tap(quick.box.exactCenterX(), quick.box.exactCenterY())
                } else {
                    val enter = ScreenReader.find(lines, "進入戰鬥", "进入战斗")!!
                    act.status("出擊準備 → 進入戰鬥")
                    act.tap(enter.box.exactCenterX(), enter.box.exactCenterY())
                }
                act.humanDelay()
            }

            ScreenType.MAP -> {
                if (!prefs.mapNav) return act.status("大地圖（尋敵已關閉）")
                val counter = ScreenReader.minimapCounter(lines, bmp.width, bmp.height)
                if (counter != null && minimapTries < 4) {
                    minimapTries++
                    val x = Vision.minimapClosePoint(bmp, counter.box)
                    act.status("關閉小地圖（第 $minimapTries 次）")
                    act.tap(x.x, x.y, precise = true)
                    act.humanDelay()
                    return
                }
                handleMap(bmp)
            }

            ScreenType.UNKNOWN -> act.status("戰鬥中 / 讀取中…")
        }
    }

    private suspend fun handleMap(bmp: Bitmap) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val scan = Vision.scanMap(bmp)
        val me = Vision.player(bmp.width, bmp.height)

        // 1. 主線驚嘆號（離隊伍最近的優先）
        val main = scan.mains.minByOrNull { (d, _) -> hypot(d.cx - me.x, d.cy - me.y) }
        if (main != null) {
            val (diamond, ring) = main
            if (ring == null) {
                act.status("點驚嘆號，跳轉視角")
                tapTarget(Vision.Pt(diamond.cx, diamond.cy), bmp)
                return
            }
            val target = Vision.Pt(ring.cx, ring.cy)

            // 2. 卡住偵測：連續點同一個目標但隊伍沒動
            if (isStuck(target)) {
                val near = Vision.nearest(scan.redRings, me)
                if (near != null) {
                    act.status("路被擋住 → 先打最近的紅圈怪")
                    sameTargetCount = 0
                    tapTarget(Vision.Pt(near.cx, near.cy), bmp)
                    return
                }
            }

            // 3. 路線上有紅圈怪就先打
            val block = Vision.blocker(scan, me, target)
            if (block != null) {
                act.status("前方有怪擋路 → 先打")
                tapTarget(Vision.Pt(block.cx, block.cy), bmp)
                return
            }

            act.status("點主線怪")
            tapTarget(target, bmp)
            return
        }

        // 4. 不限主線時，打普通紅圈怪
        if (!prefs.onlyMain) {
            val near = Vision.nearest(scan.redRings, me)
            if (near != null) {
                act.status("點普通怪")
                tapTarget(Vision.Pt(near.cx, near.cy), bmp)
                return
            }
        }

        // 5. 探路
        if (!prefs.patrol) return act.status("找不到目標（巡弋已關閉）")
        patrol(w, h)
    }

    private fun isStuck(target: Vision.Pt): Boolean {
        val last = lastTarget ?: return false
        return if (hypot(last.x - target.x, last.y - target.y) < 25f) {
            sameTargetCount++
            sameTargetCount >= 3
        } else {
            sameTargetCount = 0
            false
        }
    }

    private suspend fun tapTarget(p: Vision.Pt, bmp: Bitmap) {
        lastTarget = p
        prevMapFrame = bmp
        patrolStep = 0
        act.tap(p.x, p.y)
        act.humanDelay()
    }

    private suspend fun patrol(w: Float, h: Float) {
        val cx = w / 2; val cy = h / 2
        if (!zoomedOut) {
            act.status("找不到目標 → 縮小視角")
            act.pinchOut(cx, cy)
            zoomedOut = true
            act.humanDelay()
            return
        }
        val d = h * 0.22f
        // 上、回、下、回、左、回、右、回
        val moves = listOf(
            0f to d, 0f to -d, 0f to -d, 0f to d,
            d to 0f, -d to 0f, -d to 0f, d to 0f,
        )
        val (dx, dy) = moves[patrolStep % moves.size]
        act.status("探路中（${patrolStep % moves.size + 1}/${moves.size}）")
        act.swipe(cx, cy, cx + dx, cy + dy, 500)
        patrolStep++
        act.humanDelay()
    }

    private fun resetMapState() {
        lastTarget = null
        sameTargetCount = 0
        patrolStep = 0
        zoomedOut = false
        minimapTries = 0
    }
}
