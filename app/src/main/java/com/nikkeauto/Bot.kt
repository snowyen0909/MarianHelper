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
    private var mapFrames = 0
    /** 最近一次看到的場景（野外／前哨基地／方舟），劇情與讀取畫面不會改變它。 */
    private var lastScene = ScreenType.MAP
    private var sceneTries = 0
    private var questTaps = 0
    private var lastCounter: android.graphics.Rect? = null
    private var prevMapFrame: Bitmap? = null

    suspend fun step(bmp: Bitmap, lines: List<TextLine>) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val screen = ScreenReader.classify(lines, bmp.width, bmp.height)
        if (screen == ScreenType.MAP || screen == ScreenType.OUTPOST || screen == ScreenType.ARK) {
            if (screen != lastScene) { sceneTries = 0; questTaps = 0 }
            lastScene = screen
        } else if (screen != lastScreen) {
            sceneTries = 0
        }
        if (screen != ScreenType.MAP) {
            prevMapFrame = null
            mapFrames = 0
            lastCounter = null
            if (lastScreen == ScreenType.MAP) resetMapState()
        } else {
            mapFrames++
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

            ScreenType.CHAPTER_LIST -> {
                // 打完 Boss 會跳出章節清單，點右上角 X 關掉
                val title = ScreenReader.find(lines, "CHAPTERLIST")!!
                val guess = Vision.Pt(w * 0.9f, title.box.exactCenterY())
                val x = Vision.whiteCenter(bmp, guess.x, guess.y, (w * 0.06f).toInt()) ?: guess
                act.status("關閉章節清單")
                if (!onText(lines, x)) act.tap(x.x, x.y, precise = true)
                act.humanDelay()
            }

            ScreenType.ENTER_PANEL -> {
                val enter = lines.first { ScreenReader.isEnterButton(it) }
                act.status("設施面板 → 進入")
                act.tap(enter.box.exactCenterX(), enter.box.exactCenterY())
                act.humanDelay()
            }

            ScreenType.OUTPOST -> handleOutpost(bmp, lines)
            ScreenType.ARK -> handleArk(bmp, lines)

            ScreenType.PREPARE -> {
                if (!prefs.prepAuto) return act.status("出擊準備（自動點擊已關閉）")
                if (lastScene != ScreenType.MAP) return act.status("不在野外，不自動戰鬥")
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
                // 剛回到地圖時可能還有轉場或任務完成提示，等連續兩張都是地圖再動作
                if (mapFrames < 2) return act.status("大地圖載入中…")

                val counter = ScreenReader.minimapCounter(lines, bmp.width, bmp.height)
                val prev = lastCounter
                lastCounter = counter?.box
                if (counter != null && minimapTries < 4) {
                    // 計數要連續兩張出現在同一個位置，代表小地圖已經穩定
                    val stable = prev != null &&
                        kotlin.math.abs(prev.centerX() - counter.box.centerX()) < 15 &&
                        kotlin.math.abs(prev.centerY() - counter.box.centerY()) < 15
                    if (!stable) return act.status("確認小地圖位置…")

                    val x = Vision.minimapClosePoint(bmp, counter.box)
                    if (x != null && !onText(lines, x)) {
                        minimapTries++
                        act.status("關閉小地圖（第 $minimapTries 次）")
                        act.tap(x.x, x.y, precise = true)
                        act.humanDelay()
                        lastCounter = null
                        return
                    }
                    act.status("找不到小地圖的 X，先略過")
                }

                // 任務要求前往其他場景（方舟、前哨基地）時，點任務欄的藍色箭頭
                val quest = ScreenReader.questLine(lines, bmp.width, bmp.height)
                if (quest != null && quest.norm.contains("前往") && questTaps < 3) {
                    if (tapQuestArrow(bmp, quest)) return
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
            val (diamond, monster) = main
            if (monster == null) {
                act.status("點驚嘆號，跳轉視角")
                tapTarget(Vision.Pt(diamond.cx, diamond.cy), bmp)
                return
            }
            val target = monster

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
                ?.takeIf { hypot(it.cx - target.x, it.cy - target.y) > diamond.w }  // 主線怪自己的光圈碎片不算
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

    /** 點任務欄右邊的藍色圓形箭頭。 */
    private suspend fun tapQuestArrow(bmp: Bitmap, quest: TextLine): Boolean {
        val w = bmp.width
        val cy = quest.box.centerY()
        val r = quest.box.height()
        val p = Vision.colorCenter(bmp, (w * 0.82f).toInt(), cy - r, w - 1, cy + r,
            190f, 225f, 0.55f, 0.6f, 25)
            ?: Vision.Pt(w * 0.95f, cy.toFloat())
        questTaps++
        act.status("任務：${quest.text} → 點藍色箭頭")
        act.tap(p.x, p.y)
        act.humanDelay()
        return true
    }

    /** 前哨基地：點箭頭讓鏡頭對準目標 → 點畫面中央的指揮中心 → 面板出現後由 ENTER_PANEL 點「進入」。 */
    private suspend fun handleOutpost(bmp: Bitmap, lines: List<TextLine>) {
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val quest = ScreenReader.questLine(lines, bmp.width, bmp.height)
        when (sceneTries % 3) {
            0 -> if (quest != null) tapQuestArrow(bmp, quest) else act.status("前哨基地：沒有任務")
            1 -> {
                act.status("前哨基地 → 點中央指揮中心")
                act.tap(w * 0.5f, h * 0.45f)
                act.humanDelay()
            }
            else -> act.status("前哨基地：等待面板…")
        }
        sceneTries++
    }

    /** 方舟：照任務欄的名稱找設施，找不到就點中間亮藍色的設施。 */
    private suspend fun handleArk(bmp: Bitmap, lines: List<TextLine>) {
        val w = bmp.width; val h = bmp.height
        val quest = ScreenReader.questLine(lines, w, h)
        val target = quest?.let { ScreenReader.questTarget(it) }
        sceneTries++
        if (sceneTries > 6) return act.status("方舟：找不到目標，請手動處理")

        if (target != null) {
            val key = target.replace(" ", "")
            val hit = lines.firstOrNull { l ->
                !l.isWord && l !== quest &&
                    l.box.exactCenterY() in (h * 0.12f)..(h * 0.9f) &&
                    (l.norm.contains(key) || key.contains(l.norm) && l.norm.length >= 2)
            }
            if (hit != null) {
                act.status("方舟 → 點「${hit.text}」")
                act.tap(hit.box.exactCenterX(), hit.box.exactCenterY())
                act.humanDelay()
                return
            }
        }
        val glow = Vision.colorCenter(bmp, (w * 0.15f).toInt(), (h * 0.3f).toInt(),
            (w * 0.85f).toInt(), (h * 0.7f).toInt(), 175f, 205f, 0.5f, 0.75f, 150)
        if (glow != null) {
            act.status("方舟 → 點中間亮藍色設施")
            act.tap(glow.x, glow.y)
            act.humanDelay()
            return
        }
        if (quest != null) tapQuestArrow(bmp, quest) else act.status("方舟：沒有任務")
    }

    /** 點擊位置是否落在任何文字上（例如「完成任務」按鈕），是的話不點。 */
    private fun onText(lines: List<TextLine>, p: Vision.Pt): Boolean =
        lines.any { l ->
            !l.isWord &&
                p.x >= l.box.left - 8 && p.x <= l.box.right + 8 &&
                p.y >= l.box.top - 8 && p.y <= l.box.bottom + 8
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
