package com.nikkeauto

import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** 一次截圖加上 OCR 結果。座標都是寬 720 的縮小畫面。 */
class Frame(val bmp: Bitmap, val lines: List<TextLine>) {
    val w: Float get() = bmp.width.toFloat()
    val h: Float get() = bmp.height.toFloat()

    /** 找整行文字含有任一關鍵字的行；yMin/yMax 是畫面高度比例。 */
    fun find(vararg keys: String, yMin: Float = 0f, yMax: Float = 1f): TextLine? =
        lines.firstOrNull { l ->
            !l.isWord && keys.any { l.norm.contains(it) } &&
                l.box.exactCenterY() >= h * yMin && l.box.exactCenterY() <= h * yMax
        }

    /** 找文字完全等於任一關鍵字的行（例如只要「商店」不要「付費商店」）。 */
    fun exact(vararg keys: String, yMin: Float = 0f, yMax: Float = 1f): List<TextLine> =
        lines.filter { l ->
            !l.isWord && keys.any { l.norm == it } &&
                l.box.exactCenterY() >= h * yMin && l.box.exactCenterY() <= h * yMax
        }

    fun has(vararg keys: String) = find(*keys) != null
}

/** 一鍵日常的項目。順序與開關存在 Prefs 裡。 */
enum class DailyTask(val key: String, val label: String, val desc: String) {
    MAIL("mail", "郵件", "全部領取"),
    DEFENSE("defense", "前哨基地防禦", "領取獎勵＋免費一舉殲滅"),
    DISPATCH("dispatch", "派遣", "全部領取、全部派遣"),
    FRIEND("friend", "好友點數", "全部贈送"),
    PAID_SHOP("paidshop", "付費商店免費禮包", "只拿每日免費禮包"),
    SHOP("shop", "普通商店", "只拿 0 元的免費物品"),
    NIKKE_LV("nikkelv", "妮姬升級", "最低戰力妮姬升 1 級後等級重置"),
    ARENA("arena", "競技場", "新人競技場，只用免費次數"),
    TOWER("tower", "無盡之塔", "每座開放的塔，打輸就停止"),
    MISSION("mission", "每日任務", "全部領取（建議放最後）"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/**
 * 一鍵日常：先確保遊戲在大廳，再照設定的順序跑每個項目。
 * 每項都有時間上限；失敗或卡住就存下截圖、回到大廳，繼續下一項。
 */
class DailyRunner(private val prefs: Prefs, private val act: Actions) {

    private sealed class Result {
        object Done : Result()
        class Skip(val why: String) : Result()
        class Fail(val why: String) : Result()
    }

    private class StopDaily(msg: String) : Exception(msg)

    /** only != null 時只跑那一項（單項測試）。 */
    suspend fun run(only: DailyTask? = null): String {
        act.status("一鍵日常：準備進入大廳")
        if (!toLobby(timeoutMs = 150_000, launch = true)) {
            snap("daily_start")
            return "無法進入遊戲大廳，一鍵日常已停止。"
        }
        val report = ArrayList<String>()
        val doneToday = prefs.dailyDoneToday()
        val all = if (only != null) listOf(only) else prefs.dailyOrder.filter { prefs.dailyEnabled(it) }
        // 一鍵日常會跳過今天已完成的項目（中斷後接著做）；單項測試一律執行
        val tasks = if (only != null) all else all.filter { it !in doneToday }
        if (only == null && tasks.size < all.size) {
            report += "（接續上次：已跳過今天完成的 ${all.size - tasks.size} 項）"
        }
        for (task in tasks) {
            act.status("一鍵日常：${task.label}")
            val limit = if (task == DailyTask.TOWER) 20 * 60_000L else 4 * 60_000L
            val r = try {
                withTimeoutOrNull(limit) { runTask(task) } ?: Result.Fail("超過時間")
            } catch (e: StopDaily) {
                Result.Fail(e.message ?: "")
            }
            when (r) {
                is Result.Done -> { report += "✓ ${task.label}"; prefs.markDailyDone(task) }
                is Result.Skip -> { report += "－ ${task.label}：${r.why}"; prefs.markDailyDone(task) }
                is Result.Fail -> {
                    report += "✗ ${task.label}：${r.why}"
                    snap("daily_${task.key}")
                }
            }
            if (!toLobby(timeoutMs = 60_000)) {
                report += "回不到大廳，後面的項目已停止"
                snap("daily_lobby")
                break
            }
        }
        return report.joinToString("\n")
    }

    private suspend fun runTask(t: DailyTask): Result = when (t) {
        DailyTask.MAIL -> mail()
        DailyTask.DEFENSE -> defense()
        DailyTask.DISPATCH -> dispatch()
        DailyTask.FRIEND -> friend()
        DailyTask.PAID_SHOP -> paidShop()
        DailyTask.SHOP -> shop()
        DailyTask.NIKKE_LV -> nikkeLevel()
        DailyTask.ARENA -> arena()
        DailyTask.TOWER -> tower()
        DailyTask.MISSION -> mission()
    }

    // =============== 共用工具 ===============

    private suspend fun look(): Frame {
        while (true) {
            act.look()?.let { return it }
            delay(500)
        }
    }

    /** 每次點擊後至少等這麼久，讓遊戲有時間反應、播完轉場。 */
    private val AFTER_TAP = 1_200L

    private suspend fun tap(l: TextLine) {
        act.tap(l.box.exactCenterX(), l.box.exactCenterY())
        act.humanDelay()
        delay(AFTER_TAP)
    }

    private suspend fun tapAt(x: Float, y: Float, precise: Boolean = true) {
        act.tap(x, y, precise)
        act.humanDelay()
        delay(AFTER_TAP)
    }

    /**
     * 點一下，等畫面出現預期的東西；沒反應就再點，最多 tries 次。
     * 解決「轉場還沒播完就點，點擊被吃掉」的問題。
     */
    private suspend fun tapUntil(
        x: Float, y: Float,
        tries: Int = 3, waitMs: Long = 8_000, precise: Boolean = false,
        cond: (Frame) -> Boolean,
    ): Frame? {
        repeat(tries) {
            tapAt(x, y, precise)
            waitFor(waitMs, cond = cond)?.let { return it }
        }
        return null
    }

    private suspend fun tapUntil(l: TextLine, tries: Int = 3, waitMs: Long = 8_000, cond: (Frame) -> Boolean) =
        tapUntil(l.box.exactCenterX(), l.box.exactCenterY(), tries, waitMs, false, cond)

    private fun snap(name: String) {
        lastFrame?.let { act.saveDebug(it.bmp, name) }
    }

    private var lastFrame: Frame? = null

    /** 等到條件成立；handle = true 時順便處理領獎、點擊繼續之類的提示。 */
    private suspend fun waitFor(
        timeoutMs: Long = 12_000,
        handle: Boolean = true,
        cond: (Frame) -> Boolean,
    ): Frame? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val f = look()
            lastFrame = f
            if (cond(f)) {
                delay(700)
                val again = look()
                lastFrame = again
                if (cond(again)) return again
                continue
            }
            if (handle && handleCommon(f)) continue
            delay(500)
        }
        return null
    }

    private val CONTINUE = arrayOf(
        "點擊領取獎勵", "点击领取奖励", "點擊任意處", "点击任意处", "點擊任意", "点击任意",
        "點擊進行下一步", "点击进行下一步", "點擊繼續", "点击继续",
        "點擊關閉畫面", "点击关闭画面",
    )

    /** 各種「點一下就好」的畫面。有處理就回傳 true。 */
    private suspend fun handleCommon(f: Frame): Boolean {
        // 結束遊戲的確認視窗：一律取消
        if (f.has("結束遊戲", "结束游戏", "退出遊戲", "是否退出")) {
            f.exact("取消").firstOrNull()?.let { tap(it); return true }
        }
        // 限時禮包視窗關閉後的確認
        if (f.has("關閉視窗後", "关闭视窗后")) {
            f.find("確認", "确认", yMin = 0.3f)?.let { tap(it); return true }
        }
        // 評分視窗（操控妮姬的感覺怎麼樣？）
        if (f.has("感覺怎麼樣", "感觉怎么样")) {
            f.find("關閉", "关闭")?.let { tap(it); return true }
        }
        f.find(*CONTINUE)?.let { tap(it); delay(600); return true }
        return false
    }

    private val POPUP_TITLES = arrayOf(
        "達成PASS任務", "达成PASS任务", "購買PASS", "购买PASS",
        "郵箱", "邮箱", "公告", "通知", "防禦前哨基地", "防御前哨基地", "派遣公告欄", "派遣公告栏",
        "贈送結果", "赠送结果", "今日不再顯示", "今日不再显示",
    )

    /** 任務視窗標題（大廳有「MISSION PASS」橫幅，所以要完全相等）。 */
    private fun missionTitle(f: Frame): TextLine? =
        f.exact("MISSION", yMax = 0.4f).minByOrNull { it.box.top }

    /** 好友視窗標題在畫面中間（大廳右側也有「好友」按鈕，要排除）。 */
    private fun friendTitle(f: Frame): TextLine? =
        f.exact("好友", yMax = 0.3f).firstOrNull { it.box.exactCenterX() in f.w * 0.3f..f.w * 0.7f }

    private fun popupTitle(f: Frame): TextLine? =
        f.exact("公告", yMax = 0.6f).minByOrNull { it.box.top }
            ?: f.find(*POPUP_TITLES, yMax = 0.6f) ?: missionTitle(f) ?: friendTitle(f)

    private fun isLobby(f: Frame): Boolean =
        f.find("方舟", yMin = 0.5f) != null &&
            f.find("前哨基地", yMin = 0.5f) != null &&
            f.find("作戰出擊", "作战出击", "大廳", "大厅", yMin = 0.5f) != null &&
            !isDimmed(f) &&
            popupTitle(f) == null &&
            f.find(*CONTINUE) == null

    /**
     * 大廳是不是被彈窗蓋暗了：看底部「大廳」文字的亮度。
     * 正常是白字（亮度約 220），被彈窗蓋住會降到 100 以下。
     */
    private fun isDimmed(f: Frame): Boolean {
        val label = f.exact("大廳", "大厅", yMin = 0.9f).firstOrNull()
        val x0: Int; val y0: Int; val x1: Int; val y1: Int
        if (label != null) {
            x0 = label.box.left; y0 = label.box.top; x1 = label.box.right; y1 = label.box.bottom
        } else {
            x0 = (f.w * 0.42f).toInt(); y0 = (f.h * 0.955f).toInt(); x1 = (f.w * 0.58f).toInt(); y1 = (f.h * 0.99f).toInt()
        }
        val hist = IntArray(256)
        var n = 0
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(f.bmp.height)) {
            for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(f.bmp.width)) {
                val c = f.bmp.getPixel(x, y)
                val v = maxOf((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff)
                hist[v]++; n++
            }
        }
        if (n == 0) return false
        // 取第 98 百分位的亮度（文字筆畫最亮的部分）
        var acc = 0
        for (v in 255 downTo 0) {
            acc += hist[v]
            if (acc >= n * 0.02f) return v < 150
        }
        return true
    }

    /** 用圖片比對找彈窗的 X 鈕。 */
    private fun findCloseX(f: Frame, x0: Float = 0.5f, y0: Float = 0.03f, x1: Float = 1f, y1: Float = 0.8f): Vision.Pt? =
        listOf("close_blue", "close_dark")
            .mapNotNull { Templates.find(f.bmp, it, x0, y0, x1, y1) }
            .filter { it.score >= 0.8f }
            .maxByOrNull { it.score }
            ?.let { Vision.Pt(it.x, it.y) }

    /** 點視窗右上角的 X：先猜位置，再找附近的白色叉叉。 */
    private var closeTries = 0
    private var closeTitle = ""

    /**
     * 關掉彈窗：用圖片比對找標題附近（偏上）的 X；找不到、或同一個彈窗試兩次還在，就按返回鍵。
     * 不再用「猜位置」點，避免點到旁邊的「機率資訊」之類按鈕。
     */
    private suspend fun closePopup(f: Frame, title: TextLine, dy: Float = 0f) {
        val ty = (title.box.exactCenterY() + dy) / f.h
        if (title.norm == closeTitle) closeTries++ else { closeTitle = title.norm; closeTries = 0 }
        val p = if (closeTries >= 2) null
        else findCloseX(f, 0.55f, (ty - 0.12f).coerceAtLeast(0f), 1f, (ty + 0.06f).coerceAtMost(1f))
        if (p != null) tapAt(p.x, p.y) else { act.back(); delay(AFTER_TAP) }
        delay(500)
    }

    /**
     * 回到大廳：有「返回」就點旁邊的房子，有視窗就關，畫面一直不動就按返回鍵。
     * launch = true 時，若妮姬不在前景會先打開遊戲。
     */
    /**
     * 登入流程：等標題畫面的 TOUCH TO CONTINUE → 點下去進遊戲 → 載入完才開始關彈窗。
     * 已經在遊戲裡（看到大廳或任何遊戲畫面）就直接略過。
     */
    private suspend fun login(timeoutMs: Long): Boolean {
        act.launchGame()
        val end = System.currentTimeMillis() + timeoutMs
        var touched = false
        while (System.currentTimeMillis() < end) {
            val f = look()
            lastFrame = f
            val touch = f.find("TOUCHTOCONTINUE", "TOUCHTO")
            when {
                touch != null -> {
                    act.status("登入：點擊進入遊戲")
                    tap(touch)
                    touched = true
                    delay(3000)
                }
                // 還沒按過 TOUCH 但已經看得到大廳（被遮住也算），表示遊戲早就開著
                !touched && f.find("方舟", "前哨基地", "作戰出擊", "作战出击", yMin = 0.5f) != null -> return true
                // 按過 TOUCH：等讀取完，看到大廳的字就交給 toLobby 關彈窗
                touched && f.find("方舟", "前哨基地", "作戰出擊", "作战出击", yMin = 0.5f) != null -> return true
                // 其他遊戲內頁面（商店、方舟…）：也交給 toLobby
                f.find("返回", yMin = 0.85f) != null -> return true
                else -> {
                    act.status(if (touched) "登入：讀取中…" else "登入：等待標題畫面")
                    delay(1500)
                }
            }
        }
        return false
    }

    private suspend fun toLobby(timeoutMs: Long, launch: Boolean = false): Boolean {
        if (launch && !login(timeoutMs)) return false
        val end = System.currentTimeMillis() + timeoutMs
        var prev: Bitmap? = null
        var still = 0
        while (System.currentTimeMillis() < end) {
            val f = look()
            lastFrame = f
            if (isLobby(f)) return true
            if (handleCommon(f)) { still = 0; continue }

            // 有返回鍵：點右邊的房子圖示直接回大廳
            val back = f.find("返回", yMin = 0.85f)
            if (back != null) {
                tapAt(back.box.exactCenterX() + f.w * 0.14f, back.box.exactCenterY())
                delay(1500)
                still = 0
                continue
            }
            // 已知標題的視窗：點 X
            val title = popupTitle(f)
            if (title != null) {
                val dy = if (title.norm == "MISSION") -f.h * 0.035f else 0f
                closePopup(f, title, dy)
                still = 0
                continue
            }
            // 不認得的彈窗：用圖片比對找 X
            val x = findCloseX(f)
            if (x != null) {
                act.status("一鍵日常：關閉彈窗")
                tapAt(x.x, x.y)
                still = 0
                continue
            }
            // 一段時間沒有任何進展：按返回鍵（載入畫面字很少，那時不按）
            prev = f.bmp
            if (f.lines.count { !it.isWord } >= 4) still++
            if (still >= 6) {
                act.status("一鍵日常：關閉不認得的畫面")
                act.back()
                still = 0
                delay(1200)
            } else {
                delay(800)
            }
        }
        return false
    }

    private suspend fun lobby(): Frame {
        val end = System.currentTimeMillis() + 25_000
        var prev: Frame? = null
        while (System.currentTimeMillis() < end) {
            val f = look()
            lastFrame = f
            if (isLobby(f)) {
                if (prev != null && isLobby(prev)) return f
            } else if (handleCommon(f)) {
                prev = null
                continue
            }
            prev = f
            delay(700)
        }
        throw StopDaily("不在大廳")
    }

    // =============== 各項目 ===============

    /** 郵件：信封在資源列「150 M」右邊約 18% 螢幕寬。 */
    private suspend fun mail(): Result {
        val f = lobby()
        val env = Templates.find(f.bmp, "mail", 0.5f, 0f, 1f, 0.08f)?.takeIf { it.score >= 0.8f }
        val (ex, ey) = if (env != null) env.x to env.y else {
            val money = f.lines.firstOrNull { l ->
                !l.isWord && l.box.exactCenterY() < f.h * 0.08f && l.box.exactCenterX() > f.w * 0.5f &&
                    Regex("""\d[\d,.]*\s*[MK]$""").containsMatchIn(l.text.trim())
            } ?: return Result.Fail("找不到信封")
            money.box.right + f.w * 0.178f to money.box.exactCenterY()
        }
        val box = tapUntil(ex, ey, precise = true) { it.has("郵箱", "邮箱") } ?: return Result.Fail("郵箱沒有打開")
        val all = box.find("全部領取", "全部领取", yMin = 0.6f) ?: return Result.Skip("沒有郵件")
        tap(all)
        // 領獎畫面由 handleCommon 點掉，回到郵箱
        val after = waitFor(10_000) { it.has("郵箱", "邮箱") && it.find(*CONTINUE) == null }
        after?.find("郵箱", "邮箱")?.let { closePopup(after, it) }
        return Result.Done
    }

    /** 前哨基地防禦：獲得獎勵，再用每日免費的一舉殲滅。 */
    private suspend fun defense(): Result {
        val f = lobby()
        val label = f.lines.firstOrNull {
            !it.isWord && it.norm.contains("前哨基地") && !it.norm.contains("防") && it.box.exactCenterY() > f.h * 0.6f
        } ?: return Result.Fail("找不到前哨基地")
        // 「OUTPOST DEFENSE 100%」是藝術字讀不到：先用圖片比對，找不到再用「前哨基地」文字位置推算
        val icon = Templates.find(f.bmp, "defense", 0f, 0.7f, 0.5f, 0.95f)?.takeIf { it.score >= 0.7f }
        val (dx, dy) = if (icon != null) icon.x to icon.y
        else label.box.exactCenterX() - f.w * 0.085f to label.box.exactCenterY() + f.w * 0.097f
        var d = tapUntil(dx, dy) {
            it.has("防禦前哨基地", "防御前哨基地")
        } ?: return Result.Fail("視窗沒有打開")
        d.find("獲得獎勵", "获得奖励", yMin = 0.6f)?.let { tap(it) }
        d = waitFor(10_000) { it.has("防禦前哨基地", "防御前哨基地") && it.find(*CONTINUE) == null } ?: d

        var note = ""
        val wipe = d.lines.firstOrNull { l ->
            !l.isWord && l.box.exactCenterY() > d.h * 0.6f && l.box.exactCenterX() < d.w * 0.5f &&
                listOf("殲滅", "歼灭", "殲灭", "歼滅", "一舉", "一举", "一擧").any { l.norm.contains(it) }
        } ?: d.find("獲得獎勵", "获得奖励", yMin = 0.6f)?.let { r ->
            // 讀不到字：一舉殲滅在「獲得獎勵」左邊
            TextLine("一舉殲滅", android.graphics.Rect(
                (r.box.left - d.w * 0.33f).toInt(), r.box.top, (r.box.right - d.w * 0.33f).toInt(), r.box.bottom))
        }
        if (wipe != null) {
            tap(wipe)
            val w = waitFor(8_000) {
                it.has("進行殲滅", "进行歼灭", "沒有敵人", "没有敌人", "無法", "无法", "次數不足", "次数不足", "已用完")
            }
            if (w != null && !w.has("進行殲滅", "进行歼灭")) {
                // 沒有可殲滅的敵人：關掉提示，算完成
                (w.exact("確認", "确认", "取消", "關閉", "关闭").firstOrNull())?.let { tap(it) } ?: act.back()
                note = "目前沒有可殲滅的敵人"
            } else if (w != null) {
                val btn = w.find("進行殲滅", "进行歼灭")!!
                // 要花珠寶時按鈕上會有數字（例如「50 進行殲滅」）
                val paid = btn.norm.any { it.isDigit() } || w.lines.any { l ->
                    !l.isWord && l.norm.all { it.isDigit() } && l.norm.isNotEmpty() &&
                        kotlin.math.abs(l.box.exactCenterY() - btn.box.exactCenterY()) < w.h * 0.02f &&
                        l.box.exactCenterX() < btn.box.exactCenterX() && l.box.exactCenterX() > w.w * 0.45f
                }
                if (paid) {
                    w.exact("取消").firstOrNull()?.let { tap(it) }
                    note = "今天的免費一舉殲滅已用過"
                } else {
                    tap(btn)
                    waitFor(10_000) { it.has("防禦前哨基地", "防御前哨基地") && it.find(*CONTINUE) == null }
                }
            }
        }
        val last = look()
        last.find("防禦前哨基地", "防御前哨基地")?.let { closePopup(last, it) }
        return if (note.isEmpty()) Result.Done else Result.Skip(note)
    }

    /** 派遣：前哨基地 → 派遣公告欄 → 全部領取 → 全部派遣 → 派遣。 */
    private suspend fun dispatch(): Result {
        val f = lobby()
        val base = f.lines.firstOrNull {
            !it.isWord && it.norm.contains("前哨基地") && !it.norm.contains("防") && it.box.exactCenterY() > f.h * 0.6f
        } ?: return Result.Fail("找不到前哨基地")
        val inOutpost = { fr: Frame ->
            fr.find("派遣公告", "指揮中心", "指挥中心", yMin = 0.7f) != null ||
                (Templates.find(fr.bmp, "dispatch", 0f, 0.8f, 1f, 1f)?.score ?: 0f) >= 0.8f
        }
        val o = tapUntil(base, waitMs = 30_000) { inOutpost(it) } ?: return Result.Fail("沒有進入前哨基地")
        delay(2000)   // 進場鏡頭動畫
        val isBoard = { fr: Frame -> fr.find("派遣公告欄", "派遣公告栏", "派遣等級", "派遣等级", "派遣目錄", "派遣目录") != null &&
            fr.find("全部派遣", "全部領取", "全部领取", yMin = 0.6f) != null }
        val pic = Templates.find(o.bmp, "dispatch", 0f, 0.8f, 1f, 1f)?.takeIf { it.score >= 0.75f }
        val lb = o.find("派遣公告", yMin = 0.7f)
        val (bx, by) = when {
            pic != null -> pic.x to pic.y
            lb != null -> lb.box.exactCenterX() to lb.box.exactCenterY() - o.w * 0.06f
            else -> return Result.Fail("找不到派遣公告欄")
        }
        var b = tapUntil(bx, by, tries = 4) { isBoard(it) } ?: return Result.Fail("派遣公告欄沒有打開")
        b.find("全部領取", "全部领取", yMin = 0.6f)?.let {
            tap(it)
            b = waitFor(10_000) { f2 -> isBoard(f2) && f2.find(*CONTINUE) == null } ?: b
        }
        val claim = b.find("全部領取", "全部领取", yMin = 0.6f)
        val sendAll = b.lines.firstOrNull { l ->
            !l.isWord && l.box.exactCenterY() > b.h * 0.6f && l.norm.contains("派遣") &&
                !l.norm.contains("公告") && !l.norm.contains("等級")
        } ?: claim?.let { c ->
            TextLine("全部派遣", android.graphics.Rect(
                (c.box.left - b.w * 0.25f).toInt(), c.box.top, (c.box.right - b.w * 0.25f).toInt(), c.box.bottom))
        }
        if (sendAll == null) return Result.Fail("找不到全部派遣")
        sendAll.let {
            tap(it)
            // 確認視窗（全部派遣）：按右下的「派遣」
            val c = waitFor(6_000) { f2 -> f2.find("取消", yMin = 0.6f) != null && f2.lines.any { l ->
                !l.isWord && l.box.exactCenterY() > f2.h * 0.6f && l.norm.endsWith("派遣") && !l.norm.contains("全部") } }
            if (c == null) return Result.Skip("沒有可以派遣的項目")
            c.lines.filter { l -> !l.isWord && l.box.exactCenterY() > c.h * 0.6f && l.norm.endsWith("派遣") && !l.norm.contains("全部") }
                .maxByOrNull { it.box.right }?.let { btn -> tap(btn) }
            delay(1500)
        }
        val last = look()
        last.find("派遣公告欄", "派遣公告栏", "派遣等級", "派遣等级")?.let { closePopup(last, it) }
        return Result.Done
    }

    /** 好友：贈送 → 確認。 */
    private suspend fun friend(): Result {
        val f = lobby()
        val btn = f.exact("好友").firstOrNull { it.box.exactCenterX() > f.w * 0.75f }
            ?: return Result.Fail("找不到好友按鈕")
        val p = tapUntil(btn.box.exactCenterX(), btn.box.exactCenterY() - f.w * 0.05f) {
            it.find("贈送", "赠送", yMin = 0.7f) != null
        } ?: return Result.Fail("好友視窗沒有打開")
        tap(p.find("贈送", "赠送", yMin = 0.7f)!!)
        val r = waitFor(6_000) { it.has("贈送結果", "赠送结果") }
        var note = ""
        if (r != null) r.find("確認", "确认", yMin = 0.6f)?.let { tap(it) } else note = "今天已經送過了"
        delay(800)
        val last = look()
        friendTitle(last)?.let { closePopup(last, it) }
        return if (note.isEmpty()) Result.Done else Result.Skip(note)
    }

    /** 付費商店：普通禮包分頁 → 每日 → 每日免費禮包（只點標示「免費」的）。 */
    private suspend fun paidShop(): Result {
        val f = lobby()
        // 大廳的「付費商店」是藍色藝術字，OCR 常讀不到：先用圖片比對，再用「商店」上方推算
        val pic = Templates.find(f.bmp, "paid_shop", 0f, 0.6f, 0.55f, 0.85f)?.takeIf { it.score >= 0.7f }
        val ocr = f.find("付費商店", "付费商店", yMin = 0.5f)
        val shop = f.exact("商店", yMin = 0.5f).firstOrNull()
        val (px, py) = when {
            pic != null -> pic.x to pic.y
            ocr != null -> ocr.box.exactCenterX() to ocr.box.exactCenterY()
            shop != null -> shop.box.exactCenterX() - f.w * 0.06f to shop.box.exactCenterY() - f.w * 0.1f
            else -> return Result.Fail("找不到付費商店")
        }
        val s = tapUntil(px, py, waitMs = 20_000) { it.find("付費商店", "付费商店", yMax = 0.08f) != null }
            ?: return Result.Fail("付費商店沒有打開")
        val label = s.find("付費商店", "付费商店", yMax = 0.08f)!!
        // 分頁列沒有文字：「普通禮包」是第 3 個，在「付費商店」標籤下方約 34% 螢幕寬
        val isGift = { fr: Frame -> fr.find("普通禮包", "普通礼包", yMax = 0.3f) != null }
        val tabY = label.box.exactCenterY() + s.w * 0.335f
        var g: Frame? = if (isGift(s)) s else null
        if (g == null) {
            // 先試圖片比對找到的禮物分頁，再從左到右逐一試
            val pic = Templates.find(s.bmp, "gift_tab", 0f, (tabY / s.h) - 0.05f, 1f, (tabY / s.h) + 0.05f)
            val xs = listOfNotNull(pic?.takeIf { it.score >= 0.7f }?.x) + (0 until 7).map { s.w * (0.065f + 0.13f * it) }
            for (x in xs) {
                tapAt(x, pic?.y ?: tabY)
                g = waitFor(4_000) { isGift(it) }
                if (g != null) break
            }
        }
        if (g == null) return Result.Fail("找不到普通禮包分頁")
        val daily = g.exact("每日", yMax = 0.5f).firstOrNull()
        val d = (if (daily != null) tapUntil(daily) { it.has("每日免費禮包", "每日免费礼包") }
        else waitFor(8_000) { it.has("每日免費禮包", "每日免费礼包") })
            ?: return Result.Fail("找不到每日免費禮包")
        val name = d.find("每日免費禮包", "每日免费礼包")!!
        val col = { l: TextLine -> kotlin.math.abs(l.box.exactCenterX() - name.box.exactCenterX()) < d.w * 0.12f }
        if (d.lines.any { !it.isWord && it.norm.contains("SOLD") && col(it) }) return Result.Skip("今天已經領過")
        val free = d.lines.firstOrNull {
            !it.isWord && (it.norm.contains("免費") || it.norm.contains("免费")) && !it.norm.contains("禮包") &&
                !it.norm.contains("礼包") && col(it) && it.box.top > name.box.bottom
        } ?: return Result.Skip("沒有看到免費標示")
        tap(free)
        waitFor(8_000) { it.find(*CONTINUE) == null && !it.has("感覺怎麼樣", "感觉怎么样") }
        return Result.Done
    }

    /** 普通商店：只買價格 0 的物品，購買視窗確認消耗資金為 0 才按購買。 */
    private suspend fun shop(): Result {
        val f = lobby()
        val btn = f.exact("商店", yMin = 0.5f).firstOrNull() ?: return Result.Fail("找不到商店")
        val isShop = { fr: Frame -> fr.has("普通商店", "可隨時購買", "可随时购买", "距離商品更新", "距离商品更新", "購買全部", "购买全部") }
        val s = tapUntil(btn, waitMs = 20_000) { isShop(it) } ?: return Result.Fail("普通商店沒有打開")
        // 免費物品卡片上有橘色「100% SALE」標籤；點標籤上方的物品圖
        val sale = s.lines.firstOrNull { l ->
            !l.isWord && l.box.exactCenterY() in s.h * 0.2f..s.h * 0.9f &&
                (l.norm.contains("100%") || l.norm.contains("100%SALE"))
        } ?: return Result.Skip("沒有免費物品（可能已買過）")
        tapAt(sale.box.exactCenterX(), sale.box.exactCenterY() - s.h * 0.05f, precise = false)
        val d = waitFor(6_000) { it.has("購買道具", "购买道具") } ?: return Result.Skip("已經買過了")
        val cost = d.find("消耗資金", "消耗资金", "消耗")
        // 消耗資金那一列：去掉「100%」徽章後，只剩數字 0 才算免費
        val isFree = cost != null && run {
            val nums = d.lines.filter { l ->
                !l.isWord && kotlin.math.abs(l.box.exactCenterY() - cost.box.exactCenterY()) < d.h * 0.03f
            }.flatMap { l -> Regex("""\d+""").findAll(l.norm.replace("100%", "").replace("消耗資金", "")).map { it.value }.toList() }
            nums.isNotEmpty() && nums.all { it.toIntOrNull() == 0 }
        }
        if (!isFree) {
            d.exact("取消").firstOrNull()?.let { tap(it) }
            return Result.Skip("不是免費的，已取消")
        }
        val buy = d.exact("購買", "购买").maxByOrNull { it.box.top } ?: return Result.Fail("找不到購買按鈕")
        tap(buy)
        waitFor(8_000) { it.find(*CONTINUE) == null && isShop(it) }
        return Result.Done
    }

    /** 排序下拉框的文字（遊戲字型的「戰鬥力」常被讀成「戰門力」）。 */
    private fun sortBox(f: Frame): TextLine? =
        f.lines.firstOrNull { l ->
            !l.isWord && l.box.exactCenterY() < f.h * 0.3f && l.box.exactCenterX() > f.w * 0.55f &&
                listOf("戰鬥力", "战斗力", "戰力", "等級", "等级", "好感度", "級別").any { l.norm.contains(it) }
        }

    /** 下拉框右端的箭頭：白色像素重心偏上是 ▼（由高到低），偏下是 ▲（由低到高）。 */
    private fun isAscending(f: Frame, sort: TextLine): Boolean? {
        val cx = f.w * 0.935f
        val cy = sort.box.exactCenterY()
        val r = (f.w * 0.03f).toInt()
        val hsv = FloatArray(3)
        var sy = 0f; var n = 0; var top = Int.MAX_VALUE; var bot = Int.MIN_VALUE
        for (y in (cy.toInt() - r)..(cy.toInt() + r)) for (x in (cx.toInt() - r)..(cx.toInt() + r)) {
            if (x !in 0 until f.bmp.width || y !in 0 until f.bmp.height) continue
            android.graphics.Color.colorToHSV(f.bmp.getPixel(x, y), hsv)
            if (hsv[1] < 0.2f && hsv[2] > 0.8f) { sy += y; n++; if (y < top) top = y; if (y > bot) bot = y }
        }
        if (n < 10 || bot <= top) return null
        // ▼ 的白色像素集中在上半部；▲ 集中在下半部（和箭頭自己的上下範圍比，不受文字位置影響）
        return sy / n > (top + bot) / 2f
    }

    /** 妮姬升級：排序改成戰鬥力由低到高 → 第一隻（必須是 LV.1）升 1 級 → 等級重置拿回材料。 */
    private suspend fun nikkeLevel(): Result {
        val f = lobby()
        val btn = f.exact("妮姬", yMin = 0.9f).firstOrNull() ?: return Result.Fail("找不到妮姬按鈕")
        // 妮姬列表：右上有排序框，上方有「解放／諮詢／圖鑑」其中之一，或「可查看同伴妮姬」說明
        val isList = { fr: Frame ->
            sortBox(fr) != null &&
                fr.find("圖鑑", "图鉴", "諮詢", "咨询", "解放", "可查看同伴", yMax = 0.3f) != null
        }
        var list = tapUntil(btn, waitMs = 15_000) { isList(it) } ?: return Result.Fail("妮姬列表沒有打開")
        var sort = sortBox(list)!!

        if (isAscending(list, sort) != true) {
            // 打開排序面板 → 再點一次已選的「戰鬥力」切換成由低到高 → 關閉面板
            val panel = tapUntil(list.w * 0.935f, sort.box.exactCenterY(), waitMs = 5_000) { it.has("排序") }
                ?: return Result.Fail("排序面板沒有打開")
            val chip = panel.lines.firstOrNull { l ->
                !l.isWord && (l.norm.contains("戰鬥力") || l.norm.contains("战斗力")) &&
                    l.box.top > sort.box.bottom && l.box.exactCenterX() < panel.w * 0.6f
            }
                ?: return Result.Fail("找不到戰鬥力排序")
            tap(chip)
            val now = look()
            if (now.has("排序")) tapAt(now.w * 0.935f, sort.box.exactCenterY())
            list = waitFor(6_000) { isList(it) && !it.has("排序") } ?: look()
            sort = sortBox(list) ?: return Result.Fail("排序後找不到列表")
            if (isAscending(list, sort) == false) return Result.Skip("無法切換成由低到高，為了安全不升級")
        }

        // 第一張卡在排序框左下方
        val d = tapUntil(list.w * 0.14f, sort.box.exactCenterY() + list.w * 0.215f, waitMs = 10_000) {
            it.find("等級重置", "等级重置") != null || it.lines.any { l -> l.norm.contains("升級") && l.norm.contains("LV") }
        } ?: return Result.Fail("妮姬詳細頁沒有打開")
        val isLv1 = d.lines.any { Regex("""LV\.?1/""").containsMatchIn(it.norm) }
        if (!isLv1) return Result.Skip("第一隻不是 LV.1，為了安全不升級")
        return upgradeAndReset(d)
    }

    private suspend fun upgradeAndReset(d: Frame): Result {
        val up = d.lines.firstOrNull { !it.isWord && it.norm.contains("升級") && !it.norm.contains("等級") }
            ?: return Result.Fail("找不到升級按鈕")
        tap(up)
        val dlg = waitFor(6_000) { it.exact("升級", "升级", yMin = 0.5f).isNotEmpty() } ?: return Result.Fail("升級視窗沒有打開")
        tap(dlg.exact("升級", "升级", yMin = 0.5f).maxByOrNull { it.box.top }!!)
        delay(1500)
        // 關掉升級視窗
        val after = look()
        after.exact("升級", "升级", yMax = 0.3f).firstOrNull()?.let { closePopup(after, it) } ?: act.back()
        delay(1000)
        val det = waitFor(8_000) { it.has("等級重置", "等级重置") } ?: return Result.Fail("找不到等級重置")
        tap(det.find("等級重置", "等级重置")!!)
        val r = waitFor(6_000) { it.exact("等級重置", "等级重置", yMin = 0.5f).isNotEmpty() } ?: return Result.Fail("重置視窗沒有打開")
        tap(r.exact("等級重置", "等级重置", yMin = 0.5f).maxByOrNull { it.box.top }!!)
        waitFor(8_000) { it.find(*CONTINUE) == null && it.has("等級重置", "等级重置") }
        return Result.Done
    }

    /** 進方舟裡的某個設施（競技場、無限之塔）。 */
    private suspend fun enterArk(vararg facility: String): Frame? {
        val f = lobby()
        val ark = f.find("方舟", yMin = 0.5f) ?: return null
        val a = tapUntil(ark, waitMs = 25_000) { it.find(*facility, yMin = 0.15f) != null } ?: return null
        val fac = a.find(*facility, yMin = 0.15f)!!
        // 設施名稱在卡片下方，點卡片中間比較穩
        return tapUntil(fac.box.exactCenterX(), fac.box.exactCenterY() - a.w * 0.1f, waitMs = 25_000) {
            it.find(*facility, yMin = 0.15f) == null
        } ?: a
    }

    private fun numbers(f: Frame, yFrom: Float, yTo: Float, xMax: Float = Float.MAX_VALUE): List<Pair<Long, TextLine>> =
        f.lines.filter { !it.isWord && it.box.exactCenterY() in yFrom..yTo && it.box.exactCenterX() < xMax }
            .mapNotNull { l -> Regex("""\d{5,}""").find(l.norm.replace(",", ""))?.value?.toLongOrNull()?.let { it to l } }

    /** 競技場：有比自己戰力低的就打最低的，沒有就打第三個；只用免費次數。 */
    private suspend fun arena(): Result {
        enterArk("競技場", "竞技场") ?: return Result.Fail("找不到競技場")
        val a = waitFor(20_000) { it.has("新人競技場", "新人竞技场", "ROOKIE") } ?: return Result.Fail("競技場沒有打開")
        tapUntil(a.find("新人競技場", "新人竞技场", "ROOKIE")!!) { fr ->
            fr.lines.any { l -> !l.isWord && (l.norm.contains("進入戰") || l.norm.contains("进入战")) }
        }
        var done = 0
        val want = prefs.arenaCount
        while (done < want) {
            val list = waitFor(20_000) { fr ->
                fr.lines.any { l -> !l.isWord && (l.norm.contains("進入戰") || l.norm.contains("进入战")) } &&
                    fr.has("新人競技場", "新人竞技场", "ROOKIE")
            }
                ?: return if (done > 0) Result.Skip("打了 $done 場後找不到對手列表") else Result.Fail("找不到對手列表")
            val buttons = list.lines.filter { !it.isWord && (it.norm.contains("進入戰") || it.norm.contains("进入战")) && it.box.exactCenterY() > list.h * 0.35f }
                .sortedBy { it.box.top }
            if (buttons.isEmpty()) return Result.Fail("找不到進入戰鬥按鈕")
            if (list.exact("免費", "免费").isEmpty()) return Result.Skip("免費次數用完（打了 $done 場）")

            val top = buttons.first().box.top - list.h * 0.12f
            val mine = numbers(list, list.h * 0.1f, top).maxByOrNull { it.second.box.top }?.first
            var prevY = top
            val powers = buttons.map { b ->
                val p = numbers(list, prevY, b.box.bottom.toFloat(), b.box.left.toFloat()).maxOfOrNull { it.first }
                prevY = b.box.bottom.toFloat()
                p
            }
            val lower = if (mine != null) powers.withIndex().filter { (_, p) -> p != null && p < mine }.minByOrNull { it.value!! }?.index else null
            val pick = lower ?: minOf(2, buttons.size - 1)
            act.status("競技場第 ${done + 1} 場：選第 ${pick + 1} 個對手" + if (lower != null) "（戰力較低）" else "")
            tap(buttons[pick])

            val vs = waitFor(10_000) { it.has("快速戰鬥", "快速战斗") } ?: return Result.Fail("對戰視窗沒有打開")
            val quick = vs.find("快速戰鬥", "快速战斗")!!
            vs.lines.firstOrNull { !it.isWord && it.norm == "OFF" && kotlin.math.abs(it.box.exactCenterY() - quick.box.exactCenterY()) < vs.h * 0.04f }
                ?.let { tap(it) }
            val now = look()
            val goLine = now.lines.filter { !it.isWord && (it.norm.contains("進入戰") || it.norm.contains("进入战") || it.norm.contains("進入")) }
                .maxByOrNull { it.box.top }
            val q2 = now.find("快速戰鬥", "快速战斗") ?: quick
            // 讀不到字：「進入戰鬥」是快速戰鬥右邊的藍色大按鈕
            val go = goLine?.let { Vision.Pt(it.box.exactCenterX(), it.box.exactCenterY()) }
                ?: Vision.colorCenter(now.bmp, (now.w * 0.45f).toInt(), (q2.box.top - now.h * 0.02f).toInt(),
                    (now.w * 0.95f).toInt(), (q2.box.bottom + now.h * 0.05f).toInt(), 195f, 225f, 0.5f, 0.5f, 150)
                ?: return Result.Fail("找不到進入戰鬥")
            act.status("競技場：進入戰鬥")
            tapAt(go.x, go.y, precise = false)
            // 等勝負畫面，「點擊進行下一步」由 handleCommon 點掉
            waitFor(180_000, handle = false) { it.has("WIN", "DEFEAT", "點擊進行下一步", "点击进行下一步") }
                ?: return Result.Fail("戰鬥沒有結束")
            done++
            waitFor(15_000) { it.has("新人競技場", "新人竞技场", "ROOKIE") }
        }
        return Result.Done
    }

    /** 無盡之塔：依序打每座 OPEN 的塔，每座最多設定的次數；打輸就停止。 */
    private suspend fun tower(): Result {
        val doneCols = ArrayList<Float>()
        var total = 0
        repeat(4) {
            enterArk("無限之塔", "无限之塔", "無盡之塔") ?: return Result.Fail("找不到無限之塔")
            val t = waitFor(20_000) { it.exact("OPEN").isNotEmpty() || it.has("TRIBE") } ?: return Result.Fail("塔列表沒有打開")
            val open = t.exact("OPEN").sortedBy { it.box.left }
                .firstOrNull { o -> doneCols.none { kotlin.math.abs(it - o.box.exactCenterX()) < t.w * 0.08f } }
                ?: return if (total > 0) Result.Done else Result.Skip("今天沒有開放的塔")
            doneCols += open.box.exactCenterX()
            tapAt(open.box.exactCenterX(), open.box.exactCenterY() + t.w * 0.25f, precise = false)

            val inside = waitFor(15_000) { it.has("剩餘次數", "剩余次数") } ?: return Result.Fail("進不去塔")
            val left = inside.lines.firstNotNullOfOrNull { Regex("""(\d)/3""").find(it.norm)?.groupValues?.get(1)?.toIntOrNull() } ?: 3
            val runs = minOf(prefs.towerCount, left)
            if (runs <= 0) { toLobby(60_000); return@repeat }

            tapAt(inside.w * 0.5f, inside.h * 0.43f, precise = false)
            val prep = waitFor(10_000) { it.has("進入戰", "进入战") } ?: return Result.Fail("找不到進入戰鬥")
            tap(prep.lines.filter { !it.isWord && (it.norm.contains("進入戰") || it.norm.contains("进入战")) }.maxByOrNull { it.box.top }!!)

            for (i in 1..runs) {
                act.status("無盡之塔：第 ${total + 1} 場")
                // 結算畫面同時有「下一關卡」和「點擊任意處」，所以這裡不自動處理
                val end = waitFor(300_000, handle = false) {
                    it.has("下一關卡", "下一关卡", "FAILED", "點擊任意處", "点击任意处")
                } ?: return Result.Fail("戰鬥沒有結束")
                if (end.has("FAILED")) {
                    end.find("返回")?.let { tap(it) }
                    return Result.Fail("第 ${total + 1} 場打輸，已停止無盡之塔")
                }
                total++
                val next = end.find("下一關卡", "下一关卡")
                if (i < runs && next != null) {
                    tap(next)
                } else {
                    end.find("點擊任意處", "点击任意处", "點擊進行下一步")?.let { tap(it) } ?: act.back()
                    break
                }
            }
            toLobby(60_000)
        }
        return if (total > 0) Result.Done else Result.Skip("沒有可以打的塔")
    }

    /** 每日任務：點任務列左邊的 ! 圓鈕 → 全部領取。 */
    private suspend fun mission(): Result {
        val f = lobby()
        val quest = ScreenReader.questLine(f.lines, f.bmp.width, f.bmp.height)
        val event = f.find("EVENT", yMax = 0.15f)
        val q = Templates.find(f.bmp, "quest", 0.25f, 0f, 0.85f, 0.15f)?.takeIf { it.score >= 0.78f }
        val (x, y) = when {
            q != null -> q.x to q.y
            quest != null -> quest.box.left - f.w * 0.055f to quest.box.exactCenterY()
            event != null -> event.box.exactCenterX() + f.w * 0.04f to event.box.bottom + f.w * 0.045f
            else -> return Result.Fail("找不到任務列")
        }
        val m = tapUntil(x, y, precise = true) { missionTitle(it) != null } ?: return Result.Fail("任務視窗沒有打開")
        val all = m.find("全部領取", "全部领取", yMin = 0.6f) ?: return Result.Skip("沒有可領的任務")
        tap(all)
        val after = waitFor(10_000) { missionTitle(it) != null && it.find(*CONTINUE) == null }
        after?.let { a -> missionTitle(a)?.let { closePopup(a, it, -a.h * 0.035f) } }
        return Result.Done
    }
}
