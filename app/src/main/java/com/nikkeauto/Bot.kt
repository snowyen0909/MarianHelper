package com.nikkeauto

import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlin.math.hypot

/** 機器人對外需要的動作，由 AutoService 實作。座標都是 720 寬的縮小畫面座標。 */
interface Actions {
    /** precise = true 時不加隨機偏移（小按鈕用）。 */
    suspend fun tap(x: Float, y: Float, precise: Boolean = false)
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 400)
    suspend fun pinchOut(cx: Float, cy: Float)
    /** Android 返回鍵，用來關閉誤開的視窗。 */
    suspend fun back()
    suspend fun humanDelay()
    fun status(text: String)
    fun stopWithAlert(title: String, body: String)

    // ---- 一鍵日常用 ----
    /** 截一張圖並辨識文字。 */
    suspend fun look(): Frame?
    /** 妮姬不在前景時打開遊戲。 */
    fun launchGame()
    /** 存一張除錯截圖到相簿（Pictures/MarianHelper）。 */
    fun saveDebug(bmp: Bitmap, name: String)
    /** 發一則一般通知。 */
    fun notify(title: String, body: String)
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
    private var missionTries = 0
    /** 上一個動作是不是點任務箭頭。 */
    private var lastQuestTap = false
    private var lastCounter: android.graphics.Rect? = null
    private var prevMapFrame: Bitmap? = null

    // ---- 踩機關 ----
    /** 連續點同一個機關的次數；踩不亮就暫時不管它。 */
    private var plateTaps = 0
    private var plateSkip = 0

    // ---- 困難模式 ----
    /** 最近一次地圖畫面是不是困難模式。 */
    private var hardMode = false
    /** 打完 Boss、本章完成，要切到下一章。值是完成那一章的區域編號（讀不到為 0）。 */
    private var hardNext: Int? = null
    private var lastArea: Int? = null
    private var chapterOpenTries = 0
    private var chapterListLooks = 0
    /** 在放大地圖上設了路標，接下來幾步等隊伍走過去。 */
    private var hardWalk = 0
    /** 已經點過定位圖示、等地圖打開。 */
    private var hardMapOpened = false
    /** 設路標的次數（之間沒有打到怪），太多次就改用探路。 */
    private var hardMapReads = 0

    suspend fun step(bmp: Bitmap, lines: List<TextLine>) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val screen = ScreenReader.classify(lines, bmp.width, bmp.height)
        val questTapBefore = lastQuestTap
        lastQuestTap = false
        if (screen != ScreenType.MISSION) missionTries = 0
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
                // 困難模式：「CHAPTER 03 … MISSION CLEAR」→「點擊開啟下一章節」代表這章打完了
                if (hardMode && hardNext == null &&
                    (ScreenReader.find(lines, "下一章節", "下一章节") != null ||
                        (ScreenReader.find(lines, "CHAPTER") != null && ScreenReader.find(lines, "MISSIONCLEAR") != null))
                ) {
                    if (!prefs.hardNextChapter) {
                        act.stopWithAlert("困難模式本章完成", "第 ${lastArea ?: "?"} 章困難模式已完成，已停止（自動切章已關閉）。")
                        return
                    }
                    hardNext = lastArea ?: 0
                    chapterOpenTries = 0
                    chapterListLooks = 0
                    act.status("困難模式：第 ${lastArea ?: "?"} 章完成，準備切下一章")
                }
                if (!prefs.skipStory) return act.status("結算（跳過已關閉）")
                act.status("結算 → 點擊繼續")
                act.tap(w * 0.5f, h * 0.72f)
                act.humanDelay()
            }

            ScreenType.CONFIRM_SKIP -> {
                // 「是否跳過進行中的教學指南？」→ 按確認
                val ok = ScreenReader.find(lines, "確認", "确认")
                act.status("確認跳過")
                if (ok != null) act.tap(ok.box.exactCenterX(), ok.box.exactCenterY())
                else act.tap(w * 0.7f, h * 0.6f)
                act.humanDelay()
            }

            ScreenType.CHAPTER_LIST -> {
                // 困難模式切章：點「NEW CHAPTER」下面的章節
                if (hardNext != null) {
                    val nc = lines.firstOrNull { !it.isWord && it.norm.contains("NEWCHAPTER") }
                    if (nc != null) {
                        act.status("困難模式 → 進入下一章")
                        act.tap(nc.box.exactCenterX(), nc.box.exactCenterY() + nc.box.height() * 1.5f)
                        act.humanDelay()
                        return
                    }
                    chapterListLooks++
                    if (chapterListLooks < 3) return act.status("章節清單：找下一章…")
                    hardNext = null
                    act.stopWithAlert("困難模式已推到最新", "章節清單裡沒有新的困難章節，可能要先推進普通模式，已停止。")
                    return
                }
                // 打完 Boss 會跳出章節清單，點右上角 X 關掉
                val title = ScreenReader.find(lines, "CHAPTERLIST")!!
                val guess = Vision.Pt(w * 0.9f, title.box.exactCenterY())
                val x = Vision.whiteCenter(bmp, guess.x, guess.y, (w * 0.06f).toInt()) ?: guess
                act.status("關閉章節清單")
                if (!onText(lines, x)) act.tap(x.x, x.y, precise = true)
                act.humanDelay()
            }

            ScreenType.MISSION -> {
                // 誤開了任務視窗：可能是剛剛點任務箭頭點偏了，這個場景先停用箭頭
                if (questTapBefore) questTaps = 99
                missionTries++
                if (missionTries > 6) {
                    act.stopWithAlert("關不掉任務視窗", "已停止，請手動關閉 MISSION 視窗後再開始。")
                    return
                }
                if (missionTries % 2 == 1) {
                    act.status("關閉任務視窗（返回鍵）")
                    act.back()
                } else {
                    // 用最上面那個 MISSION（標題），不是下面的 MAIN／DAILY MISSION
                    val title = lines.filter { !it.isWord && it.norm.contains("MISSION") }
                        .minByOrNull { it.box.top }!!
                    val guess = Vision.Pt(w * 0.91f, title.box.exactCenterY() - h * 0.035f)
                    val x = Vision.whiteCenter(bmp, guess.x, guess.y, (w * 0.05f).toInt()) ?: guess
                    act.status("關閉任務視窗（點 X）")
                    act.tap(x.x, x.y, precise = true)
                }
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
                val quick = ScreenReader.quickBattle(lines)
                if (quick != null && ScreenReader.isOrangeButton(bmp, quick.box)) {
                    act.status("出擊準備 → 快速戰鬥")
                    act.tap(quick.box.exactCenterX(), quick.box.exactCenterY())
                } else {
                    val enter = ScreenReader.enterBattle(lines)
                    // 讀不到按鈕文字時，找右下角的藍色按鈕
                    val p = enter?.let { Vision.Pt(it.box.exactCenterX(), it.box.exactCenterY()) }
                        ?: Vision.colorCenter(bmp, (w * 0.5f).toInt(), (h * 0.88f).toInt(),
                            (w - 1).toInt(), (h - 1).toInt(), 195f, 225f, 0.55f, 0.55f, 200)
                    if (p == null) return act.status("出擊準備：找不到進入戰鬥按鈕")
                    act.status("出擊準備 → 進入戰鬥")
                    act.tap(p.x, p.y)
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
                // 放大地圖開著時畫面變暗，讀不到 HARD 標籤，沿用上一次的判斷
                val bigMap = counter != null && counter.box.exactCenterX() > w * 0.6f
                if (!bigMap) hardMode = Vision.hardLabel(bmp) ||
                    lines.any { it.norm.contains("HARD") && it.norm.length <= 6 && it.box.exactCenterY() > h * 0.85f && it.box.exactCenterX() > w * 0.55f }
                // 困難模式要用小地圖找怪，不自動關小地圖
                if (counter != null && minimapTries < 4 && !hardMode) {
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

                val area = ScreenReader.currentArea(lines, bmp.width, bmp.height)
                if (hardMode) {
                    // 困難模式沒有指引，任務欄是普通模式的任務，不能點
                    val done = hardNext
                    if (done != null) {
                        if (area != null && done != 0 && area != done) {
                            hardNext = null
                            act.status("困難模式：已進入第 $area 章")
                        } else {
                            openChapterList(bmp, lines)
                            return
                        }
                    }
                    if (area != null) lastArea = area
                    handleHardMap(bmp, lines, counter)
                    return
                }
                if (area != null) lastArea = area

                // 任務要求前往其他場景（方舟、前哨基地）時，點任務欄的藍色箭頭
                val quest = ScreenReader.questLine(lines, bmp.width, bmp.height)
                if (quest != null && quest.norm.contains("前往") && questTaps < 3) {
                    if (tapQuestArrow(bmp, quest)) return
                }
                // 任務「通關【7-1】」但目前在 06 區 → 點箭頭前往下一區
                val questCh = quest?.let { ScreenReader.questChapter(it) }
                if (questCh != null && area != null && questCh != area && questTaps < 3) {
                    act.status("任務在 $questCh 區，目前在 $area 區")
                    if (tapQuestArrow(bmp, quest!!)) return
                }
                handleMap(bmp, lines, counter)
            }

            ScreenType.UNKNOWN -> act.status("戰鬥中 / 讀取中…")
        }
    }

    private suspend fun handleMap(bmp: Bitmap, lines: List<TextLine>, counter: TextLine?) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        // 任務欄（含左邊藍色圓形 !）以上全部當 UI；讀不到任務欄時用 13%
        val quest = ScreenReader.questLine(lines, bmp.width, bmp.height)
        val topCut = quest?.let { it.box.bottom + it.box.height() * 0.8f } ?: (h * 0.13f)
        val exclude = listOfNotNull(counter?.let { Vision.minimapRect(w, it.box, h) })
        if (stepPlate(bmp, topCut, exclude)) return
        val scan = Vision.scanMap(bmp, topCut, exclude)
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
                // 沒有怪擋路還是走不過去，多半是門關著，要踩踏板
                if (sameTargetCount >= 6) {
                    act.stopWithAlert("可能被門擋住，已停止", "主線怪走不過去，可能需要先踩地上的踏板開門，請手動處理後再開始。")
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

    /**
     * 困難模式：沒有驚嘆號，自己找怪。
     * 看得到 Boss 就往 Boss 走（路上有怪先打），看不到就打最近的怪，都沒有就探路。
     */
    private suspend fun handleHardMap(bmp: Bitmap, lines: List<TextLine>, counter: TextLine?) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val quest = ScreenReader.questLine(lines, bmp.width, bmp.height)
        val topCut = quest?.let { it.box.bottom + it.box.height() * 0.8f } ?: (h * 0.13f)
        val exclude = listOfNotNull(counter?.let { Vision.minimapRect(w, it.box, h) })
        // 放大的地圖開著：讀完方向就關掉
        if (counter != null && counter.box.exactCenterX() > w * 0.6f) {
            readBigMap(bmp, counter)
            return
        }
        if (stepPlate(bmp, topCut, exclude)) return
        val scan = Vision.scanHard(bmp, topCut, exclude)
        val me = Vision.player(bmp.width, bmp.height)
        val dist = { p: Vision.Pt -> hypot(p.x - me.x, p.y - me.y) }

        var target: Vision.Pt? = null
        var label = ""
        val boss = scan.boss
        if (boss != null) {
            target = boss; label = "Boss"
            // 路線上的怪先打
            val dx = boss.x - me.x; val dy = boss.y - me.y
            val len2 = dx * dx + dy * dy
            if (len2 > 1f) {
                scan.monsters.mapNotNull { m ->
                    val t = ((m.x - me.x) * dx + (m.y - me.y) * dy) / len2
                    if (t < 0.08f || t > 0.92f) return@mapNotNull null
                    val d = hypot(m.x - (me.x + t * dx), m.y - (me.y + t * dy))
                    if (d <= 70f) m to t else null
                }.minByOrNull { it.second }?.let { target = it.first; label = "擋路的怪" }
            }
        } else {
            scan.monsters.minByOrNull(dist)?.let { target = it; label = "怪" }
        }

        val tg = target
        if (tg != null) {
            if (isStuck(tg)) {
                // 走不過去：換一隻離這個目標遠一點的怪
                val other = scan.monsters.filter { hypot(it.x - tg.x, it.y - tg.y) > 60f }.minByOrNull(dist)
                if (other != null && sameTargetCount < 6) {
                    act.status("困難模式：走不過去 → 改打別隻")
                    sameTargetCount = 0
                    tapTarget(other, bmp)
                    return
                }
                if (sameTargetCount >= 6) {
                    sameTargetCount = 0
                    lastTarget = null
                    act.status("困難模式：一直走不過去 → 探路")
                    if (prefs.patrol) patrol(w, h)
                    return
                }
            }
            act.status("困難模式 → 點$label")
            tapTarget(tg, bmp)
            return
        }
        // 畫面上沒有怪：打開地圖，在放大的地圖上點怪的位置設路標，隊伍會自己走過去
        if (hardWalk > 0) {
            hardWalk--
            act.status("困難模式：隊伍走向地圖上的目標…")
            delay(1200)
            return
        }
        if (hardMapReads < 5) {
            if (counter != null) {
                // 小地圖開著 → 按放大鏡
                val box = Vision.minimapRect(w, counter.box, bmp.height.toFloat())
                act.status("困難模式：放大地圖找怪")
                act.tap(box.left + box.width() * 0.1f, box.top + box.width() * 0.09f, precise = true)
                act.humanDelay()
                return
            }
            if (!hardMapOpened) {
                hardMapOpened = true
                // 左上角的定位圖示，和任務欄同一高度（橫屏時圖示比較小、比較靠左）
                val land = w > h
                val y = quest?.box?.exactCenterY() ?: (if (land) h * 0.1f else w * 0.173f)
                act.status("困難模式：打開地圖找怪")
                act.tap(if (land) h * 0.04f else w * 0.074f, y, precise = true)
                act.humanDelay()
                return
            }
            hardMapOpened = false   // 沒打開，下一輪再點一次
        }
        if (!prefs.patrol) return act.status("困難模式：找不到怪（巡弋已關閉）")
        patrol(w, h)
    }

    /**
     * 讀放大的地圖：剛放大時隊伍在地圖正中央。
     * 有 Boss（帶骷髏的三角）就點 Boss，沒有就點離隊伍最近的怪（粉紅小三角），
     * 點下去會出現藍色路標，關掉地圖後隊伍會自動走過去。
     */
    private suspend fun readBigMap(bmp: Bitmap, counter: TextLine) {
        val w = bmp.width.toFloat()
        val box = Vision.minimapRect(w, counter.box, bmp.height.toFloat())
        val marks = Vision.minimapMarks(bmp, box)
        val c = Vision.Pt(box.centerX(), box.centerY())
        val t = marks.boss ?: marks.monsters.minByOrNull { hypot(it.x - c.x, it.y - c.y) }
        hardMapReads++
        if (t != null) {
            // 往隊伍方向內縮一點，點在三角旁邊的路面上
            val dx = c.x - t.x; val dy = c.y - t.y
            val len = hypot(dx, dy).coerceAtLeast(1f)
            val k = minOf(10f, len) / len
            act.status("困難模式：在地圖上點${if (marks.boss != null) " Boss" else "最近的怪"}設路標")
            act.tap(t.x + dx * k, t.y + dy * k, precise = true)
            delay(900)
            hardWalk = 6
        } else {
            act.status("困難模式：地圖上找不到怪 → 改用探路")
            hardMapReads = 5
        }
        // 關掉放大地圖
        val x = Vision.minimapClosePoint(bmp, counter.box)
        if (x != null) act.tap(x.x, x.y, precise = true) else act.back()
        hardMapOpened = false
        act.humanDelay()
    }

    /**
     * 踩機關：畫面上有還沒踩的圓形機關（灰色圓盤、中間八角形）就先走過去踩。
     * 踩過的會發白光，比對不到樣本，自然就跳過。
     * 連點 4 次還是沒亮（可能走不過去），接下來 40 步先不管它。
     */
    private suspend fun stepPlate(bmp: Bitmap, topCut: Float, exclude: List<android.graphics.RectF>): Boolean {
        if (!prefs.stepPlates) return false
        if (plateSkip > 0) { plateSkip--; return false }
        val p = Vision.findPlate(bmp, topCut, exclude)
        if (p == null) { plateTaps = 0; return false }
        plateTaps++
        if (plateTaps > 4) {
            plateTaps = 0
            plateSkip = 40
            act.status("機關踩不到 → 先略過")
            return false
        }
        act.status("發現機關 → 先踩（$plateTaps）")
        lastTarget = null
        act.tap(p.x, p.y)
        act.humanDelay()
        return true
    }

    /** 困難模式本章完成：點右下角區域編號打開章節清單。 */
    private suspend fun openChapterList(bmp: Bitmap, lines: List<TextLine>) {
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        chapterOpenTries++
        if (chapterOpenTries > 6) {
            hardNext = null
            act.stopWithAlert("打不開章節清單", "困難模式本章已完成，但打不開章節清單切到下一章，已停止。")
            return
        }
        if (chapterOpenTries % 2 == 0) return act.status("困難模式：等待章節清單…")
        val ca = ScreenReader.find(lines, "CURRENTAREA")
        val x = ca?.box?.exactCenterX() ?: (if (w > h) w * 0.937f else w * 0.75f)
        val y = ca?.let { it.box.top - it.box.height() * 1.5f } ?: (h * 0.935f)
        chapterListLooks = 0
        act.status("困難模式：打開章節清單切下一章")
        act.tap(x, y)
        act.humanDelay()
    }

    /** 點任務欄右邊的藍色圓形箭頭。 */
    private suspend fun tapQuestArrow(bmp: Bitmap, quest: TextLine): Boolean {
        val w = bmp.width
        val cy = quest.box.centerY()
        val r = quest.box.height()
        // 只在真的找到藍色箭頭時才點，不用猜的位置，避免點到任務文字打開 MISSION 視窗
        val p = Vision.colorCenter(bmp, (w * 0.85f).toInt(), cy - r, w - 1, cy + r,
            190f, 225f, 0.55f, 0.6f, 25)
        questTaps++
        if (p == null || p.x < quest.box.right) {
            act.status("找不到任務箭頭")
            return false
        }
        act.status("任務：${quest.text} → 點藍色箭頭")
        act.tap(p.x, p.y, precise = true)
        lastQuestTap = true
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
        resetHardSearch()
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
        // 探路兩整輪都沒找到目標，多半是門關著或需要踩踏板
        if (patrolStep >= moves.size * 2) {
            act.stopWithAlert("找不到目標，已停止",
                if (hardMode) "困難模式探路兩輪都沒找到怪或 Boss，請手動移動到有怪的地方後再開始。"
                else "探路兩輪都沒找到主線目標，可能需要先踩地上的踏板開門，請手動處理後再開始。")
            patrolStep = 0
            return
        }
        val (dx, dy) = moves[patrolStep % moves.size]
        act.status("探路中（${patrolStep % moves.size + 1}/${moves.size}）")
        act.swipe(cx, cy, cx + dx, cy + dy, 500)
        patrolStep++
        act.humanDelay()
    }

    private fun resetHardSearch() {
        hardWalk = 0
        hardMapOpened = false
        hardMapReads = 0
    }

    private fun resetMapState() {
        resetHardSearch()
        lastTarget = null
        sameTargetCount = 0
        patrolStep = 0
        zoomedOut = false
        minimapTries = 0
    }
}
