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
    TOWER("tower", "無盡之塔", "依星期打開放的企業塔，打輸就停止"),
    INTERCEPT("intercept", "攔截戰", "每天 3 次；有快速戰鬥就用，沒有就進入戰鬥"),
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
        if (!toLobby(timeoutMs = 300_000, launch = true)) {
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
            val limit = when (task) {
                DailyTask.TOWER -> 20 * 60_000L
                DailyTask.INTERCEPT -> 15 * 60_000L
                else -> 4 * 60_000L
            }
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
        DailyTask.INTERCEPT -> intercept()
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

    /** 關閉 REWARD 畫面：點「REWARD」標題那一帶（任何地方都能關，這裡不會有按鈕）。 */
    private suspend fun dismissReward(f: Frame) {
        // 先點 REWARD 標題那一帶；有些獎勵畫面點那裡沒反應，就改點「點擊領取獎勵」那行字（或物品下方）
        val r = f.find("REWARD")
        tapAt(f.w * 0.5f, r?.box?.exactCenterY() ?: (f.h * 0.35f), precise = false)
        delay(900)
        val now = look()
        if (!now.has("REWARD") && now.find(*CONTINUE) == null) return
        val hint = now.find(*CONTINUE)
        val y = hint?.box?.exactCenterY() ?: ((now.find("REWARD")?.box?.bottom ?: (now.h * 0.4f).toInt()) + now.h * 0.2f)
        tapAt(now.w * 0.5f, y, precise = false)
        delay(900)
    }

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
        // 只有一顆「確認」、沒有「取消」的提示視窗（道具說明、通知）→ 按確認
        val ok = f.lines.filter { l -> !l.isWord && l.norm.length <= 4 && (l.norm.endsWith("確認") || l.norm.endsWith("确认")) &&
            l.box.exactCenterY() in f.h * 0.3f..f.h * 0.85f }.maxByOrNull { it.box.top }
        if (ok != null && f.lines.none { l -> !l.isWord && l.norm.endsWith("取消") && l.box.exactCenterY() > f.h * 0.3f }) {
            tap(ok); return true
        }
        // 「通知」小視窗（例如：無可獲得的獎勵）→ 按確認
        if (f.has("無可獲得", "无可获得", "通知")) {
            f.lines.filter { !it.isWord && (it.norm.endsWith("確認") || it.norm.endsWith("确认")) && it.box.exactCenterY() > f.h * 0.4f }
                .maxByOrNull { it.box.top }?.let { tap(it); return true }
        }
        // 獎勵畫面「點擊領取獎勵」字太淡讀不到：看到 REWARD 就點畫面下方
        if (f.has("REWARD")) { dismissReward(f); return true }
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
        var titleSeen = 0
        while (System.currentTimeMillis() < end) {
            val f = look()
            lastFrame = f
            val touch = f.find("TOUCHTOCONTINUE", "TOUCHTO", "TOCONTINU", "CONTINUE")
            // 標題畫面：下方有版本號／版權字樣，讀取條跑完（沒有 x/7、x/2 進度）時就可以點
            val title = f.find("ALLRIGHTS", "RESERVED", "SHIFTUP", "VERSION", yMin = 0.85f) != null &&
                f.lines.none { l -> Regex("""^\d/\d$""").matches(l.norm) }
            titleSeen = if (title) titleSeen + 1 else 0
            when {
                touch != null -> {
                    act.status("登入：點擊進入遊戲")
                    tap(touch)
                    touched = true
                    delay(3000)
                }
                // 字讀不到但標題畫面已經停住 3 次（約 5 秒）：直接點畫面中下方
                titleSeen >= 3 -> {
                    act.status("登入：點擊進入遊戲")
                    tapAt(f.w * 0.5f, f.h * 0.78f, precise = false)
                    touched = true
                    titleSeen = 0
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
            if (still >= 3) {
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

    /** 找按鈕：文字結尾是 key（前面可能有圖示被讀成符號），排除視窗標題。 */
    private fun button(f: Frame, vararg keys: String, yMin: Float = 0.45f): TextLine? =
        f.lines.filter { l ->
            !l.isWord && l.box.exactCenterY() > f.h * yMin && l.norm.length <= 6 && keys.any { l.norm.endsWith(it) }
        }.maxByOrNull { it.box.top }

    /** 兩顆按鈕的視窗：讀不到右邊按鈕時，用左邊「取消」往右推。 */
    private fun rightOfCancel(f: Frame): TextLine? =
        f.lines.filter { !it.isWord && it.norm.endsWith("取消") && it.box.exactCenterY() > f.h * 0.45f }.maxByOrNull { it.box.top }?.let { c ->
            TextLine("確定", android.graphics.Rect(
                (c.box.left + f.w * 0.36f).toInt(), c.box.top, (c.box.right + f.w * 0.36f).toInt(), c.box.bottom))
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
        delay(1200)   // 視窗動畫
        var claimed = false
        repeat(2) {
            if (claimed) return@repeat
            val cur = look()
            val claim = cur.find("獲得獎勵", "获得奖励", yMin = 0.6f) ?: return@repeat
            act.status("前哨基地：領取掛機獎勵")
            tap(claim)
            val got = waitFor(6_000, handle = false) { it.find(*CONTINUE) != null || it.has("REWARD") }
            if (got != null) {
                claimed = true
                waitFor(10_000) { it.has("防禦前哨基地", "防御前哨基地") && it.find(*CONTINUE) == null && !it.has("REWARD") }
            } else if (cur.has("無可獲得", "无可获得") || look().has("無可獲得", "无可获得")) {
                claimed = true   // 沒有獎勵可領
            }
        }
        d = look()

        var note = ""
        val wipe = d.lines.firstOrNull { l ->
            !l.isWord && l.box.exactCenterY() > d.h * 0.6f && l.box.exactCenterX() < d.w * 0.5f &&
                listOf("殲滅", "歼灭", "殲灭", "歼滅", "一舉", "一举", "一擧").any { l.norm.contains(it) }
        } ?: d.find("獲得獎勵", "获得奖励", yMin = 0.6f)?.let { r ->
            // 讀不到字：一舉殲滅在「獲得獎勵」左邊
            TextLine("一舉殲滅", android.graphics.Rect(
                (r.box.left - d.w * 0.33f).toInt(), r.box.top, (r.box.right - d.w * 0.33f).toInt(), r.box.bottom))
        }
        val wipePic = Templates.find(d.bmp, "wipe", 0f, 0.6f, 0.6f, 0.95f)?.takeIf { it.score >= 0.75f }
        if (wipe != null || wipePic != null) {
            act.status("前哨基地：一舉殲滅")
            val wx = wipePic?.x ?: wipe!!.box.exactCenterX()
            val wy = wipePic?.y ?: wipe!!.box.exactCenterY()
            tapUntil(wx, wy, tries = 3, waitMs = 5_000) { fr ->
                fr.exact("取消", yMin = 0.5f).isNotEmpty() || fr.lines.any { !it.isWord && (it.norm.contains("進行") || it.norm.contains("进行")) } ||
                    fr.has("沒有敵人", "没有敌人", "無法", "无法", "次數不足", "次数不足", "已用完")
            }
            val isGo = { l: TextLine -> !l.isWord && (l.norm.contains("進行") || l.norm.contains("进行")) }
            val w = waitFor(8_000) { fr ->
                fr.lines.any(isGo) || fr.exact("取消", yMin = 0.6f).isNotEmpty() ||
                    fr.has("沒有敵人", "没有敌人", "無法", "无法", "次數不足", "次数不足", "已用完")
            }
            if (w != null && w.lines.none(isGo) && w.exact("取消", yMin = 0.6f).isEmpty()) {
                // 沒有可殲滅的敵人：關掉提示，算完成
                (w.exact("確認", "确认", "取消", "關閉", "关闭").firstOrNull())?.let { tap(it) } ?: act.back()
                note = "目前沒有可殲滅的敵人"
            } else if (w != null) {
                // 「進行殲滅」讀不到時：它是「取消」右邊的藍色按鈕
                val btn = w.lines.filter(isGo).maxByOrNull { it.box.top } ?: w.exact("取消", yMin = 0.6f).first().let { c ->
                    TextLine("進行殲滅", android.graphics.Rect(
                        (c.box.left + w.w * 0.42f).toInt(), c.box.top, (c.box.right + w.w * 0.42f).toInt(), c.box.bottom))
                }
                // 要花珠寶時按鈕上會有數字（例如「50 進行殲滅」）
                val paid = btn.norm.filter { it.isDigit() }.isNotEmpty() || w.lines.any { l ->
                    !l.isWord && l.norm.all { it.isDigit() } && l.norm.isNotEmpty() &&
                        kotlin.math.abs(l.box.exactCenterY() - btn.box.exactCenterY()) < w.h * 0.02f &&
                        l.box.exactCenterX() < btn.box.exactCenterX() && l.box.exactCenterX() > w.w * 0.45f
                }
                if (paid) {
                    w.exact("取消").firstOrNull()?.let { tap(it) }
                    note = "今天的免費一舉殲滅已用過"
                } else {
                    tap(btn)
                    // 等獎勵畫面，點掉（點 REWARD 字的位置，不要點下方，以免點到又出現的「進行殲滅」）
                    val rw = waitFor(8_000, handle = false) { it.has("REWARD") || it.find(*CONTINUE) != null }
                    if (rw != null) dismissReward(rw)
                    // 殲滅完遊戲會回到殲滅視窗：按「取消」關掉，絕對不要再按進行殲滅
                    delay(1200)
                    repeat(2) {
                        val fr = look()
                        val cancel = fr.lines.filter { !it.isWord && it.norm.endsWith("取消") && it.box.exactCenterY() > fr.h * 0.5f }.maxByOrNull { it.box.top }
                        if (cancel != null) { tap(cancel); delay(800) }
                        else if (fr.has("REWARD")) dismissReward(fr)
                    }
                }
            }
        }
        // 關掉防禦前哨基地視窗：右上角 X（圖片比對），找不到就按返回鍵
        repeat(2) {
            val last = look()
            val t = last.find("防禦前哨基地", "防御前哨基地") ?: return@repeat
            val ty = t.box.exactCenterY() / last.h
            val x = findCloseX(last, 0.6f, (ty - 0.06f).coerceAtLeast(0f), 1f, ty + 0.06f)
            if (x != null) tapAt(x.x, x.y) else { act.back(); delay(AFTER_TAP) }
        }
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
        // 「全部領取」是下方右邊那顆（藍色），左邊是「全部派遣」。字讀不到時用位置或藍色按鈕找
        val findClaim = { fr: Frame ->
            fr.lines.filter { l ->
                !l.isWord && l.box.exactCenterY() > fr.h * 0.6f && l.box.exactCenterX() > fr.w * 0.5f &&
                    (l.norm.contains("全部領") || l.norm.contains("全部领") || l.norm.endsWith("領取") || l.norm.endsWith("领取"))
            }.maxByOrNull { it.box.top }?.let { Vision.Pt(it.box.exactCenterX(), it.box.exactCenterY()) }
                ?: fr.lines.filter { l -> !l.isWord && l.box.exactCenterY() > fr.h * 0.6f && (l.norm.contains("全部派") || l.norm.contains("全部派遣")) }
                    .maxByOrNull { it.box.top }?.let { Vision.Pt(it.box.exactCenterX() + fr.w * 0.25f, it.box.exactCenterY()) }
        }
        // 有任何一列寫著「領取獎勵」，代表有完成的派遣可以領
        val hasDone = { fr: Frame -> fr.lines.any { l -> !l.isWord && (l.norm.contains("領取獎勵") || l.norm.contains("领取奖励")) && l.box.exactCenterX() > fr.w * 0.5f } }
        repeat(2) {
            val cur = look()
            if (!hasDone(cur) && it > 0) return@repeat
            val p = findClaim(cur) ?: return@repeat
            act.status("派遣：全部領取")
            tapAt(p.x, p.y, precise = false)
            val rw = waitFor(6_000, handle = false) { fr -> fr.has("REWARD") || fr.find(*CONTINUE) != null }
            if (rw != null) {
                dismissReward(rw)
                b = waitFor(10_000) { f2 -> isBoard(f2) && f2.find(*CONTINUE) == null && !f2.has("REWARD") } ?: b
                return@repeat
            }
        }
        b = look()
        val claim = b.find("全部領取", "全部领取", yMin = 0.6f)
        val sendAll = b.lines.filter { l ->
            !l.isWord && l.box.exactCenterY() > b.h * 0.6f && (l.norm.contains("全部派遣") || l.norm.contains("全部派"))
        }.maxByOrNull { it.box.top } ?: claim?.let { c ->
            TextLine("全部派遣", android.graphics.Rect(
                (c.box.left - b.w * 0.25f).toInt(), c.box.top, (c.box.right - b.w * 0.25f).toInt(), c.box.bottom))
        }
        if (sendAll == null) return Result.Fail("找不到全部派遣")
        sendAll.let {
            tap(it)
            // 確認視窗（全部派遣）：按右下的「派遣」
            val c = waitFor(6_000) { f2 -> f2.lines.any { l -> !l.isWord && l.norm.endsWith("取消") && l.box.exactCenterY() > f2.h * 0.6f } }
            if (c == null) return Result.Skip("沒有可以派遣的項目")
            act.status("派遣：確認派遣")
            val btn = c.lines.filter { l -> !l.isWord && l.box.exactCenterY() > c.h * 0.6f && l.norm.endsWith("派遣") && !l.norm.contains("全部") }
                .maxByOrNull { it.box.right } ?: rightOfCancel(c)
            if (btn != null) tap(btn)
            // 確認視窗還在就再按一次
            delay(1500)
            val again = look()
            if (again.lines.any { l -> !l.isWord && l.norm.endsWith("取消") && l.box.exactCenterY() > again.h * 0.6f }) {
                rightOfCancel(again)?.let { tap(it) }
                delay(1500)
            }
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
        // 卡片底部的「免費」價格鈕；讀不到就點卡片中間（名稱下方）
        val free = d.lines.firstOrNull {
            !it.isWord && (it.norm.contains("免費") || it.norm.contains("免费")) && !it.norm.contains("禮包") &&
                !it.norm.contains("礼包") && col(it) && it.box.top > name.box.bottom
        }
        if (free != null) tap(free)
        else tapAt(name.box.exactCenterX(), name.box.bottom + d.h * 0.06f, precise = false)
        // 點了可能直接領到（獎勵畫面），或跳出購買視窗：視窗要看得到「免費」或 0 才按購買
        val r = waitFor(8_000) { fr ->
            fr.find(*CONTINUE) != null || fr.find("購買", "购买", "領取", "领取", yMin = 0.5f) != null || fr.has("SOLD")
        }
        if (r != null && r.find(*CONTINUE) == null) {
            val buy = r.lines.filter { !it.isWord && (it.norm.endsWith("購買") || it.norm.endsWith("购买") || it.norm.endsWith("領取") || it.norm.endsWith("领取")) && it.box.exactCenterY() > r.h * 0.5f }
                .maxByOrNull { it.box.top }
            val isFree = r.has("免費", "免费") && r.lines.none { l -> l.norm.contains("$") && l.box.exactCenterY() > r.h * 0.4f && l.box.exactCenterY() < r.h * 0.85f && kotlin.math.abs(l.box.exactCenterY() - (buy?.box?.exactCenterY() ?: 0f)) < r.h * 0.15f }
            if (buy != null && isFree) tap(buy)
            else if (buy != null) { r.exact("取消").firstOrNull()?.let { tap(it) }; return Result.Skip("不是免費的，已取消") }
        }
        waitFor(8_000) { it.find(*CONTINUE) == null && !it.has("感覺怎麼樣", "感觉怎么样") }
        return Result.Done
    }

    /** 普通商店：只買價格 0 的物品，購買視窗確認消耗資金為 0 才按購買。 */
    private suspend fun shop(): Result {
        val f = lobby()
        val btn = f.exact("商店", yMin = 0.5f).firstOrNull() ?: return Result.Fail("找不到商店")
        val isShop = { fr: Frame -> fr.has("普通商店", "可隨時購買", "可随时购买", "距離商品更新", "距离商品更新", "購買全部", "购买全部") }
        val s = tapUntil(btn, waitMs = 20_000) { isShop(it) } ?: return Result.Fail("普通商店沒有打開")
        // 第一個商品（左上）每天免費：在「普通商店」標題下方；有「100% SALE」就用它定位
        val sale = s.lines.firstOrNull { l ->
            !l.isWord && l.box.exactCenterY() in s.h * 0.2f..s.h * 0.9f && l.norm.contains("100%")
        }
        val head = s.find("普通商店")
        val (ix, iy) = when {
            sale != null -> sale.box.exactCenterX() to sale.box.exactCenterY() - s.h * 0.05f
            head != null -> head.box.left + s.w * 0.08f to head.box.bottom + s.h * 0.1f
            else -> return Result.Fail("找不到第一個商品")
        }
        tapAt(ix, iy, precise = false)
        val d = waitFor(6_000) { it.has("購買道具", "购买道具") } ?: return Result.Skip("已經買過了")
        val cost = d.find("消耗資金", "消耗资金", "消耗")
        // 消耗資金那一列：去掉「100%」徽章後，只剩數字 0 才算免費
        val isFree = cost != null && run {
            // 只看和「消耗資金」同一列（上下有重疊）的字，排除下面一列的「持有資金」
            val row = d.lines.filter { l ->
                !l.isWord && !l.norm.contains("持有") &&
                    l.box.bottom > cost.box.top + cost.box.height() / 3 && l.box.top < cost.box.bottom - cost.box.height() / 3
            }
            val nums = row.flatMap { l ->
                Regex("""\d+""").findAll(l.norm.replace("100%", "").replace("消耗資金", "").replace("消耗资金", "")).map { it.value }.toList()
            }
            nums.isEmpty() || nums.all { it.toIntOrNull() == 0 }
        }
        if (!isFree) {
            d.exact("取消").firstOrNull()?.let { tap(it) }
            return Result.Skip("不是免費的，已取消")
        }
        val buy = button(d, "購買", "购买") ?: rightOfCancel(d) ?: return Result.Fail("找不到購買按鈕")
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
        val isDetail = { fr: Frame ->
            fr.find("等級重置", "等级重置") != null || fr.lines.any { l -> l.norm.contains("升級") && l.norm.contains("LV") } ||
                fr.has("STATUS", "EQUIPMENT")
        }
        val isLv1 = { fr: Frame ->
            fr.lines.any { l ->
                Regex("""LV\.?1/\d""").containsMatchIn(l.norm) ||
                    (l.box.exactCenterY() < fr.h * 0.5f && Regex("""^1/(80|100|120|140|160|180|200)$""").matches(l.norm))
            }
        }

        // 先切換排序（▼ 變 ▲，等級低的排前面）
        list = toggleSort(list) ?: return Result.Fail("切換排序失敗")
        for (attempt in 0..1) {
            val sort = sortBox(list) ?: return Result.Fail("找不到排序框")
            // 第一張卡：排序框左下方
            val d = tapUntil(list.w * 0.13f, sort.box.exactCenterY() + list.w * 0.33f, waitMs = 10_000) { isDetail(it) }
                ?: return Result.Fail("妮姬詳細頁沒有打開")
            if (isLv1(d)) return upgradeAndReset(d)
            if (attempt == 1) return Result.Skip("第一隻不是 LV.1，為了安全不升級")

            // 不是 LV.1：回列表，切換一次排序方向（點箭頭 → 點戰鬥力 → 點箭頭）再試
            act.status("妮姬升級：第一隻不是 LV.1，切換排序")
            act.back(); delay(1500)
            list = waitFor(10_000) { isList(it) } ?: return Result.Fail("回不到妮姬列表")
            list = toggleSort(list) ?: return Result.Fail("切換排序失敗")
        }
        return Result.Fail("不明原因")
    }

    /**
     * 切換排序方向：直接點右上「戰鬥力」框最右邊的小箭頭（▼ ↔ ▲），點一下就換，不會開面板。
     * 只點一次，避免來回切換。
     */
    private suspend fun toggleSort(list: Frame): Frame? {
        val sort = sortBox(list) ?: return null
        act.status("妮姬升級：切換排序")
        tapAt(list.w * 0.935f, sort.box.exactCenterY())
        delay(1500)
        val now = look()
        // 萬一打開的是排序面板，點同一個位置關掉
        if (now.find("篩選", "筛选", "火力型", "防禦型") != null) {
            tapAt(list.w * 0.935f, sort.box.exactCenterY()); delay(1000)
            return look()
        }
        return now
    }

    /** 點彈窗標題列右邊的 X（找不到樣本就點固定位置），不按返回鍵以免離開妮姬頁面。 */
    private suspend fun closeDialogX(f: Frame, title: TextLine) {
        val ty = title.box.exactCenterY() / f.h
        val p = findCloseX(f, 0.55f, (ty - 0.05f).coerceAtLeast(0f), 1f, (ty + 0.05f).coerceAtMost(1f))
            ?: Vision.Pt(f.w * 0.88f, title.box.exactCenterY())
        tapAt(p.x, p.y)
    }

    /**
     * 妮姬詳細頁：LV⁺升級 → 升級視窗按「升級」→ 點 X 關視窗 → 點等級旁的「等級重置」
     * → 確認視窗按「等級重置」→ 領取獎勵。
     */
    private suspend fun upgradeAndReset(d: Frame): Result {
        val up = d.lines.firstOrNull { !it.isWord && it.norm.contains("升級") && !it.norm.contains("等級") && it.box.exactCenterY() > d.h * 0.3f }
            ?: return Result.Fail("找不到 LV⁺升級 按鈕")
        val dlg = tapUntil(up, waitMs = 6_000) { button(it, "升級", "升级", yMin = 0.5f) != null }
            ?: return Result.Fail("升級視窗沒有打開")
        act.status("妮姬升級：升級 1 次")
        delay(1200)   // 視窗動畫
        // 視窗上方「升級 1 > 2」那行的數字，按了之後會變；有變就停，不要連按
        val levelRow = { fr: Frame ->
            fr.lines.firstOrNull { l -> !l.isWord && l.box.exactCenterY() < fr.h * 0.5f && l.norm.contains(">") && l.norm.any { it.isDigit() } }
                ?.norm?.filter { it.isDigit() || it == '>' }
        }
        var ok = false
        repeat(3) {
            if (ok) return@repeat
            val cur = look()
            val before = levelRow(cur)
            val b = button(cur, "升級", "升级", yMin = 0.5f) ?: return@repeat
            tap(b)
            delay(2000)
            val now = look()
            val after = levelRow(now)
            ok = button(now, "升級", "升级", yMin = 0.5f) == null ||
                (before != null && after != null && before != after) ||
                now.lines.any { l -> Regex("""LV\.?2/\d""").containsMatchIn(l.norm) || l.norm.contains("2>3") }
        }

        // 關掉升級視窗：點標題「升級」右邊的 X
        val after = look()
        val title = after.lines.filter { !it.isWord && (it.norm == "升級" || it.norm == "升级") && it.box.exactCenterY() < after.h * 0.5f }
            .minByOrNull { it.box.top }
        if (title != null) closeDialogX(after, title)
        delay(1000)

        val isReset = { fr: Frame -> fr.lines.any { !it.isWord && (it.norm.contains("等級重置") || it.norm.contains("等级重置")) && it.box.exactCenterY() < fr.h * 0.5f } }
        val det = waitFor(8_000) { isReset(it) && button(it, "升級", "升级", yMin = 0.5f) == null } ?: return Result.Fail("找不到等級重置")
        act.status("妮姬升級：等級重置")
        val chip = det.lines.first { !it.isWord && (it.norm.contains("等級重置") || it.norm.contains("等级重置")) && it.box.exactCenterY() < det.h * 0.5f }
        val r = tapUntil(chip, waitMs = 6_000) { button(it, "等級重置", "等级重置", yMin = 0.5f) != null }
            ?: return Result.Fail("重置視窗沒有打開")
        tap(button(r, "等級重置", "等级重置", yMin = 0.5f)!!)
        // 領取獎勵畫面由 handleCommon 點掉
        waitFor(8_000) { it.find(*CONTINUE) == null && button(it, "等級重置", "等级重置", yMin = 0.5f) == null }
        return Result.Done
    }

    /** 進方舟裡的某個設施（競技場、無限之塔）。 */
    private suspend fun enterArk(vararg facility: String, arrived: (Frame) -> Boolean): Frame? {
        val f = lobby()
        val ark = f.find("方舟", yMin = 0.5f) ?: return null
        val a = tapUntil(ark, waitMs = 25_000) { it.find(*facility, yMin = 0.15f) != null || arrived(it) } ?: return null
        if (arrived(a)) return a
        val fac = a.find(*facility, yMin = 0.15f)!!
        // 設施名稱在卡片下方，點卡片中間比較穩；點一次後耐心等，不要重複點
        return tapUntil(fac.box.exactCenterX(), fac.box.exactCenterY() - a.w * 0.1f, tries = 2, waitMs = 25_000) { arrived(it) }
    }

    private fun numbers(f: Frame, yFrom: Float, yTo: Float, xMax: Float = Float.MAX_VALUE): List<Pair<Long, TextLine>> =
        f.lines.filter { !it.isWord && it.box.exactCenterY() in yFrom..yTo && it.box.exactCenterX() < xMax }
            .mapNotNull { l -> Regex("""\d{5,}""").find(l.norm.replace(",", ""))?.value?.toLongOrNull()?.let { it to l } }

    /** 競技場：有比自己戰力低的就打最低的，沒有就打第三個；只用免費次數。 */
    private suspend fun arena(): Result {
        val rookiePic = { fr: Frame -> Templates.find(fr.bmp, "rookie", 0f, 0.35f, 0.6f, 0.75f)?.takeIf { it.score >= 0.8f } }
        val isMain = { fr: Frame ->
            !fr.has("ROOKIEARENA", "SPECIALARENA") &&
                ((fr.has("ROOKIE") && fr.has("SPECIAL")) || rookiePic(fr) != null)
        }
        enterArk("競技場", "竞技场") { isMain(it) } ?: return Result.Fail("找不到競技場")
        waitFor(25_000) { isMain(it) } ?: return Result.Fail("競技場沒有打開")
        delay(2000)   // 卡片進場動畫
        val a = look()
        // 「ROOKIE」和「SPECIAL」常被讀成同一行，所以用單字的位置；都讀不到就點左半邊
        val rookie = a.lines.firstOrNull { it.isWord && (it.norm.contains("ROOKIE") || it.norm.startsWith("新人")) && it.box.exactCenterX() < a.w * 0.5f }
        val special = a.lines.firstOrNull { it.isWord && it.norm.contains("SPECIAL") }
        val champ = a.lines.firstOrNull { !it.isWord && it.norm.contains("CHAMPION") }
        // 新人競技場一定在左邊那張卡：x 固定在左側 25%，y 用同一列的文字定位
        val pic = rookiePic(a)
        val rx = pic?.x ?: (a.w * 0.25f)
        val ry = pic?.y ?: rookie?.box?.exactCenterY() ?: special?.box?.exactCenterY()
            ?: champ?.let { it.box.exactCenterY() - a.h * 0.12f } ?: (a.h * 0.5f)
        val inRookie = { fr: Frame -> fr.has("ROOKIEARENA", "新人競技場", "新人竞技场") && !fr.has("SPECIALARENA", "特殊競技場", "特殊竞技场") &&
            fr.lines.any { l -> !l.isWord && (l.norm.contains("進入戰") || l.norm.contains("进入战")) } }
        var entered = false
        repeat(3) {
            if (entered) return@repeat
            tapAt(rx, ry, precise = false)
            val r = waitFor(10_000) { inRookie(it) || it.has("SPECIALARENA", "特殊競技場", "特殊竞技场") }
            when {
                r == null -> {}
                inRookie(r) -> entered = true
                else -> {
                    // 點進特殊競技場了：按返回回到競技場主畫面再點一次
                    act.status("競技場：誤入特殊競技場，返回")
                    r.find("返回", yMin = 0.85f)?.let { tap(it) } ?: act.back()
                    waitFor(10_000) { isMain(it) }
                    delay(1500)
                }
            }
        }
        if (!entered) return Result.Fail("沒有進入新人競技場")
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

            val vs = waitFor(10_000) { it.has("快速戰鬥", "快速战斗", "變更編隊", "变更编队", "快速戰", "快速战") }
                ?: return Result.Fail("對戰視窗沒有打開")
            val quick = vs.find("快速戰鬥", "快速战斗", "快速戰", "快速战")
                ?: vs.find("變更編隊", "变更编队")!!.let { c ->
                    TextLine("快速戰鬥", android.graphics.Rect(c.box.left, (c.box.top + vs.h * 0.055f).toInt(), c.box.right, (c.box.bottom + vs.h * 0.055f).toInt()))
                }
            vs.lines.firstOrNull { !it.isWord && it.norm == "OFF" && kotlin.math.abs(it.box.exactCenterY() - quick.box.exactCenterY()) < vs.h * 0.04f }
                ?.let { tap(it) }
            val now = look()
            val goLine = now.lines.filter { !it.isWord && (it.norm.contains("進入戰") || it.norm.contains("进入战") || it.norm.contains("進入")) }
                .maxByOrNull { it.box.top }
            val q2 = now.find("快速戰鬥", "快速战斗", "快速戰", "快速战") ?: quick
            // 讀不到字：「進入戰鬥」是快速戰鬥右邊的藍色大按鈕
            val go = goLine?.let { Vision.Pt(it.box.exactCenterX(), it.box.exactCenterY()) }
                ?: Vision.colorCenter(now.bmp, (now.w * 0.45f).toInt(), (q2.box.top - now.h * 0.02f).toInt(),
                    (now.w * 0.95f).toInt(), (q2.box.bottom + now.h * 0.05f).toInt(), 195f, 225f, 0.5f, 0.5f, 150)
                ?: Vision.Pt(now.w * 0.68f, q2.box.exactCenterY() + now.h * 0.015f)
            act.status("競技場：進入戰鬥")
            tapAt(go.x, go.y, precise = false)
            // 等勝負畫面，「點擊進行下一步」由 handleCommon 點掉
            waitFor(180_000, handle = false) { it.has("WIN", "DEFEAT", "點擊進行", "点击进行", "RANK") }
                ?: return Result.Fail("戰鬥沒有結束")
            done++
            act.status("競技場：第 $done 場結束，點擊進行下一步")
            // 結算畫面：點「點擊進行下一步」（讀不到就點畫面下方），直到回到對手列表
            var back = false
            repeat(6) {
                val e = look()
                if (e.has("ROOKIEARENA", "新人競技場", "新人竞技场") && e.lines.any { l -> l.norm.contains("進入戰") || l.norm.contains("进入战") }) {
                    back = true; return@repeat
                }
                val next = e.find("點擊進行", "点击进行", "進行下一步", "进行下一步")
                if (next != null) tap(next) else tapAt(e.w * 0.5f, e.h * 0.8f, precise = false)
                delay(2000)
            }
            if (!back) waitFor(15_000) { it.has("新人競技場", "新人竞技场", "ROOKIE") }
        }
        return Result.Done
    }

    /** 下方四座企業塔（由左到右），以及開放的星期（Calendar.DAY_OF_WEEK：1=日 … 7=六）。 */
    private class CorpTower(val name: String, val keys: List<String>, val xFrac: Float, val days: Set<Int>, val tpl: String)

    private val CORP_TOWERS = listOf(
        CorpTower("極樂淨土之塔", listOf("ELYSION", "極樂", "极乐"), 0.155f, setOf(3, 6, 1), "tower_elysion"),   // 二、五、日
        CorpTower("米西利斯之塔", listOf("MISSILIS", "米西利斯"), 0.38f, setOf(4, 7, 1), "tower_missilis"),        // 三、六、日
        CorpTower("泰特拉之塔", listOf("TETRA", "泰特拉"), 0.61f, setOf(2, 5, 1), "tower_tetra"),               // 一、四、日
        CorpTower("朝聖者／超標準之塔", listOf("PILGRIM", "OVERSPEC", "朝聖", "朝圣", "超標準"), 0.84f, setOf(4, 1), "tower_pilgrim"),          // 三、日
    )

    /** 遊戲日的星期（台灣時間，每天 04:00 換日）。 */
    private fun gameWeekday(): Int {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Taipei"))
        c.add(java.util.Calendar.HOUR_OF_DAY, -4)
        return c.get(java.util.Calendar.DAY_OF_WEEK)
    }

    /**
     * 無盡之塔：只打下方的企業塔（上方的 TRIBE TOWER 不打）。
     * 依星期決定今天開哪幾座，每座最多打設定的次數（每日上限 3 次，輸了也算），打輸就停止整個項目。
     */
    private suspend fun tower(): Result {
        val today = CORP_TOWERS.filter { gameWeekday() in it.days }
        if (today.isEmpty()) return Result.Skip("今天沒有開放的企業塔")
        var total = 0
        val notes = ArrayList<String>()
        for (ct in today) {
            act.status("無盡之塔：${ct.name}")
            // 塔列表：上方有「可以選擇塔進入」，或看得到下方企業塔的名稱
            val isList = { fr: Frame ->
                fr.has("可以選擇塔", "可以选择塔", "選擇塔進入") ||
                    fr.lines.count { l -> l.box.exactCenterY() > fr.h * 0.6f && listOf("ELYSION", "MISSILIS", "TETRA", "PILGRIM").any { k -> l.norm.contains(k) } } >= 2
            }
            enterArk("無限之塔", "无限之塔", "無盡之塔") { isList(it) } ?: return Result.Fail("找不到無限之塔")
            waitFor(25_000) { isList(it) } ?: return Result.Fail("塔列表沒有打開")
            delay(1500)   // 進場動畫
            val list = look()
            // 用塔名找欄位；讀不到就用固定的欄位位置
            val label = list.lines.firstOrNull { l -> !l.isWord && l.box.exactCenterY() > list.h * 0.45f && ct.keys.any { l.norm.contains(it) } }
            val cx = label?.box?.exactCenterX() ?: (list.w * ct.xFrac)
            val col = { l: TextLine -> kotlin.math.abs(l.box.exactCenterX() - cx) < list.w * 0.1f }
            if (list.exact("CLOSE").any(col)) { notes += "${ct.name} 今天沒開"; toLobby(60_000); continue }
            // 塔的圓形徽章（圖片比對，要在這座塔的欄位裡）
            val emblem = Templates.find(list.bmp, ct.tpl, 0f, 0.55f, 1f, 0.85f)
                ?.takeIf { it.score >= 0.75f && kotlin.math.abs(it.x - list.w * ct.xFrac) < list.w * 0.12f }
            // 依序試：塔的圖示（OPEN 標籤下方）、塔名、「每日通關」那行
            val tag = list.exact("OPEN").firstOrNull(col)
            val daily = list.lines.firstOrNull { l -> !l.isWord && col(l) && (l.norm.contains("每日") || l.norm.contains("通關")) && l.box.exactCenterY() > list.h * 0.6f }
            val ys = listOfNotNull(
                emblem?.y,
                tag?.let { it.box.exactCenterY() + list.w * 0.25f },
                label?.box?.exactCenterY(),
                daily?.box?.exactCenterY(),
                list.h * 0.66f,
            )
            // 進到塔裡：底部會寫塔名；如果是上方的 TRIBE TOWER（無限之塔），代表點錯了，要退回去
            val isInside = { fr: Frame -> fr.has("剩餘次數", "剩余次数") || fr.lines.any { l -> l.box.exactCenterY() > fr.h * 0.8f && l.norm.contains("TOWER") } }
            val isRight = { fr: Frame -> fr.lines.any { l -> l.box.exactCenterY() > fr.h * 0.75f && ct.keys.any { k -> l.norm.contains(k) } } }
            var inside: Frame? = null
            for (y in ys) {
                act.status("無盡之塔：進入 ${ct.name}")
                tapAt(if (y == emblem?.y) emblem.x else cx, y, precise = false)
                val fr = waitFor(12_000) { isInside(it) } ?: continue
                delay(1500)
                val now = look()
                if (isRight(now) || (now.has("剩餘次數", "剩余次数") && !now.has("無限之塔", "无限之塔", "TRIBETOWER"))) { inside = now; break }
                // 進錯塔（TRIBE TOWER）：按返回回到塔列表再試下一個位置
                act.status("無盡之塔：進錯塔，返回")
                now.find("返回", yMin = 0.85f)?.let { tap(it) } ?: act.back()
                waitFor(15_000) { isList(it) }
                delay(1500)
                if (fr === now) Unit
            }
            if (inside == null) { notes += "${ct.name} 進不去"; toLobby(60_000); continue }
            delay(1500)
            val left = look().lines.firstNotNullOfOrNull { Regex("""(\d)/3""").find(it.norm)?.groupValues?.get(1)?.toIntOrNull() } ?: 3
            val runs = minOf(prefs.towerCount, left)
            if (runs <= 0) { notes += "${ct.name} 今天次數已用完"; toLobby(60_000); continue }

            // 點中間的關卡圓盤 → 下方出現關卡資訊與「進入戰鬥」
            val isPanel = { fr: Frame ->
                fr.lines.any { l -> !l.isWord && l.box.exactCenterY() > fr.h * 0.5f &&
                    (l.norm.contains("進入戰") || l.norm.contains("进入战") || l.norm.contains("STAGE") || l.norm.contains("戰場資訊") || l.norm.contains("殲滅戰")) }
            }
            // 面板已經開著就不要再點圓盤（再點會關掉）
            val prep = (if (isPanel(inside)) inside else tapUntil(inside.w * 0.5f, inside.h * 0.42f, tries = 2, waitMs = 8_000) { isPanel(it) })
                ?: return Result.Fail("${ct.name} 找不到關卡資訊")
            delay(1000)
            val pf = look()
            val goLine = pf.lines.filter { !it.isWord && (it.norm.contains("進入戰") || it.norm.contains("进入战")) }.maxByOrNull { it.box.top }
            val go = goLine?.let { Vision.Pt(it.box.exactCenterX(), it.box.exactCenterY()) }
                ?: Vision.colorCenter(pf.bmp, (pf.w * 0.5f).toInt(), (pf.h * 0.85f).toInt(), (pf.w * 0.98f).toInt(), (pf.h * 0.98f).toInt(), 195f, 225f, 0.5f, 0.5f, 150)
                ?: Vision.Pt(pf.w * 0.75f, pf.h * 0.92f)
            act.status("無盡之塔：進入戰鬥")
            tapAt(go.x, go.y, precise = false)
            if (prep === pf) Unit

            for (i in 1..runs) {
                act.status("無盡之塔：${ct.name} 第 $i 場")
                // 結算畫面同時有「下一關卡」和「點擊任意處」，所以這裡不自動處理
                val end = waitFor(300_000, handle = false) {
                    it.has("下一關卡", "下一关卡", "FAILED", "COMPLETE", "點擊任意處", "点击任意处")
                } ?: return Result.Fail("${ct.name} 戰鬥沒有結束")
                total++
                if (end.has("FAILED")) {
                    end.find("返回")?.let { tap(it) } ?: act.back()
                    toLobby(60_000)
                    return Result.Fail("${ct.name} 第 $i 場打輸，已停止無盡之塔（共打 $total 場）")
                }
                val next = end.find("下一關卡", "下一关卡")
                if (i < runs && next != null) {
                    delay(2000)   // 結算動畫
                    val gone = tapUntil(next, tries = 3, waitMs = 8_000) { !it.has("下一關卡", "下一关卡") && !it.has("點擊任意處", "点击任意处") }
                    if (gone == null) return Result.Fail("${ct.name} 按不到下一關卡（打了 $total 場）")
                } else {
                    end.find("點擊任意處", "点击任意处", "點擊進行下一步")?.let { tap(it) } ?: tapAt(end.w * 0.5f, end.h * 0.6f, precise = false)
                    break
                }
            }
            toLobby(60_000)
        }
        if (total == 0) return Result.Skip(notes.joinToString("、").ifEmpty { "沒有可以打的塔" })
        return Result.Done
    }

    // =============== 攔截戰 ===============

    private val BOSSES = listOf("克拉肯", "鏡像容器", "茵迪維利亞", "過激派", "死神")

    /** 剩餘攔截次數（N/3 的 N）；讀不到回傳 null。 */
    private fun interceptLeft(f: Frame): Int? =
        f.lines.filter { !it.isWord && (it.norm.contains("剩餘攔截") || it.norm.contains("剩余拦截") || it.norm.contains("攔截次數")) }
            .firstNotNullOfOrNull { Regex("""(\d)/3""").find(it.norm)?.groupValues?.get(1)?.toIntOrNull() }
            ?: f.lines.filter { !it.isWord && it.box.exactCenterY() > f.h * 0.8f }
                .firstNotNullOfOrNull { Regex("""(\d)/3""").find(it.norm)?.groupValues?.get(1)?.toIntOrNull() }

    /**
     * 攔截戰：方舟 → 攔截戰。
     * 模式「異常個體」：右下按異常個體攔截戰 → 選 Boss → 挑戰；沒解鎖就改打普通。
     * 模式「普通」：點 LEVEL D／LEVEL S／特殊目標。
     * 面板裡：先試快速戰鬥（普通要打過一次、異常個體是每週快速戰鬥），沒反應就進入戰鬥。每天共 3 次。
     */
    private suspend fun intercept(): Result {
        val isMap = { fr: Frame ->
            fr.has("剩餘攔截次數", "剩余拦截次数") ||
                fr.lines.any { l -> !l.isWord && l.box.exactCenterY() < fr.h * 0.06f && l.box.exactCenterX() < fr.w * 0.35f &&
                    (l.norm.contains("攔截戰") || l.norm.contains("拦截战") || l.norm.contains("異常個體")) }
        }
        enterArk("攔截戰", "拦截战") { isMap(it) } ?: return Result.Fail("找不到攔截戰")
        delay(2500)   // 地圖進場動畫
        var f = look()
        interceptLeft(f)?.let { if (it == 0) return Result.Skip("今天的攔截次數已用完") }

        val isPanel = { fr: Frame -> fr.lines.any { l -> !l.isWord && l.box.exactCenterY() > fr.h * 0.75f &&
            (l.norm.contains("模擬戰") || l.norm.contains("模拟战") || l.norm.contains("進入戰") || l.norm.contains("进入战")) } }
        var note = ""

        // ---------- 異常個體 ----------
        var anomaly = prefs.interceptMode == 1
        if (anomaly) {
            val isBossScreen = { fr: Frame ->
                fr.lines.any { l -> !l.isWord && l.box.exactCenterY() in fr.h * 0.55f..fr.h * 0.82f &&
                    (l.norm.contains("挑戰") || l.norm.contains("挑战") || BOSSES.any { b -> l.norm.contains(b) }) }
            }
            val btn = f.lines.firstOrNull { l -> !l.isWord && l.box.exactCenterY() > f.h * 0.88f && l.norm.contains("異常") }
            val bx = btn?.box?.exactCenterX() ?: (f.w * 0.86f)
            val by = btn?.let { it.box.exactCenterY() - f.h * 0.03f } ?: (f.h * 0.92f)
            act.status("攔截戰：異常個體")
            val boss = tapUntil(bx, by, tries = 2, waitMs = 8_000) { isBossScreen(it) }
            if (boss == null) {
                anomaly = false
                note = "異常個體攔截戰未解鎖，改打普通攔截戰"
                act.status(note)
            } else {
                delay(1500)
                // 選 Boss：讀中間的名字，不是要的就按右邊箭頭
                val want = prefs.interceptBoss
                if (want in 1..BOSSES.size) {
                    val target = BOSSES[want - 1]
                    var ok = false
                    repeat(BOSSES.size + 1) {
                        if (ok) return@repeat
                        val cur = look()
                        val name = cur.lines.firstOrNull { l -> !l.isWord && l.box.exactCenterY() in cur.h * 0.55f..cur.h * 0.82f && BOSSES.any { b -> l.norm.contains(b) } }
                        if (name != null && name.norm.contains(target)) { ok = true; return@repeat }
                        val ny = name?.box?.exactCenterY() ?: (cur.h * 0.71f)
                        tapAt(cur.w * 0.8f, ny, precise = false)
                        delay(1500)
                    }
                    if (!ok) note = "找不到 $target，打目前顯示的 Boss"
                }
                val cur = look()
                val ch = cur.lines.filter { l -> !l.isWord && l.box.exactCenterY() > cur.h * 0.6f && (l.norm.contains("挑戰") || l.norm.contains("挑战")) }.maxByOrNull { it.box.top }
                val name = cur.lines.firstOrNull { l -> !l.isWord && l.box.exactCenterY() in cur.h * 0.55f..cur.h * 0.82f && BOSSES.any { b -> l.norm.contains(b) } }
                act.status("攔截戰：挑戰 ${name?.text ?: ""}")
                val cx = ch?.box?.exactCenterX() ?: (cur.w * 0.5f)
                val cy = ch?.box?.exactCenterY() ?: (name?.box?.exactCenterY()?.plus(cur.h * 0.05f) ?: (cur.h * 0.76f))
                tapUntil(cx, cy, waitMs = 10_000) { isPanel(it) } ?: return Result.Fail("異常個體面板沒有打開")
            }
        }

        // ---------- 普通 ----------
        if (!anomaly) {
            f = look()
            if (!isMap(f)) {
                // 從異常個體畫面回來：按右下「攔截戰」
                tapUntil(f.w * 0.69f, f.h * 0.92f, tries = 2, waitMs = 8_000) { isMap(it) && !it.has("挑戰", "挑战") }
                delay(2000)
                f = look()
            }
            val (name, x, y, key) = when (prefs.interceptTarget) {
                1 -> listOf("LEVEL S", f.w * 0.83f, f.h * 0.44f, "LEVELS")
                2 -> {
                    val sp = f.find("特殊目標攔截戰", "特殊目标拦截战", "特殊目標")
                    listOf("特殊目標", sp?.box?.exactCenterX() ?: (f.w * 0.6f), sp?.box?.exactCenterY() ?: (f.h * 0.68f), "特殊目標")
                }
                else -> listOf("LEVEL D", f.w * 0.19f, f.h * 0.38f, "LEVELD")
            }
            act.status("攔截戰：$name")
            val p = tapUntil(x as Float, y as Float, waitMs = 10_000) { isPanel(it) } ?: return Result.Fail("$name 面板沒有打開")
            if (!p.lines.any { l -> l.norm.contains(key as String) } && !(key == "特殊目標" && p.has("特殊目标", "SPECIAL"))) {
                note = listOf(note, "面板標題讀不到 $name，照樣挑戰").filter { it.isNotEmpty() }.joinToString("；")
            }
        }

        // ---------- 戰鬥：每天共 3 次 ----------
        var fights = 0
        for (round in 1..3) {
            val p = waitFor(20_000) { isPanel(it) } ?: break
            delay(1000)
            val pf = look()
            val left = interceptLeft(pf)
            if (left == 0) break
            val before = left

            // 1. 先試快速戰鬥（灰色時按了沒反應）
            var done = false
            val quick = pf.lines.filter { l -> !l.isWord && l.box.exactCenterY() > pf.h * 0.75f && (l.norm.contains("快速戰") || l.norm.contains("快速战")) }.maxByOrNull { it.box.top }
            val qx = quick?.box?.exactCenterX() ?: (pf.w * 0.74f)
            val qy = quick?.box?.exactCenterY() ?: (pf.h * (if (anomaly) 0.86f else 0.895f))
            act.status("攔截戰：試快速戰鬥（第 $round 次）")
            tapAt(qx, qy, precise = false)
            val q = waitFor(6_000, handle = false) { fr ->
                fr.has("TOTALDAMAGE", "REWARD") || fr.find(*CONTINUE) != null ||
                    fr.lines.any { l -> !l.isWord && l.norm.endsWith("取消") && l.box.exactCenterY() > fr.h * 0.4f } ||
                    (interceptLeft(fr)?.let { before != null && it < before } ?: false)
            }
            if (q != null) {
                // 快速戰鬥的確認視窗
                if (q.lines.any { l -> !l.isWord && l.norm.endsWith("取消") && l.box.exactCenterY() > q.h * 0.4f } && !q.has("REWARD", "TOTALDAMAGE")) {
                    val okBtn = q.lines.filter { l -> !l.isWord && l.box.exactCenterY() > q.h * 0.4f && (l.norm.endsWith("確認") || l.norm.endsWith("确认") || l.norm.contains("快速戰")) }.maxByOrNull { it.box.right }
                        ?: rightOfCancel(q)
                    okBtn?.let { tap(it) }
                }
                done = true
            } else {
                // 2. 沒有快速戰鬥：進入戰鬥（靠遊戲自動戰鬥）
                val now = look()
                val go = now.lines.filter { l -> !l.isWord && l.box.exactCenterY() > now.h * 0.8f && (l.norm.contains("進入戰") || l.norm.contains("进入战")) }.maxByOrNull { it.box.top }
                act.status("攔截戰：進入戰鬥（第 $round 次）")
                if (go != null) tap(go) else tapAt(now.w * 0.75f, now.h * 0.93f, precise = false)
                val started = waitFor(20_000, handle = false) { !isPanel(it) }
                if (started == null) return if (fights > 0) Result.Done else Result.Fail("按不下進入戰鬥")
                done = true
            }
            if (!done) break

            // 等結算（TOTAL DAMAGE／點擊任意處進行下一步），點到回面板為止
            val end = waitFor(300_000, handle = false) { fr -> fr.has("TOTALDAMAGE", "REWARD") || fr.find(*CONTINUE) != null || isPanel(fr) }
                ?: return Result.Fail("戰鬥沒有結束")
            fights++
            act.status("攔截戰：第 $fights 次完成")
            if (!isPanel(end)) {
                repeat(8) {
                    val e = look()
                    if (isPanel(e)) return@repeat
                    val c = e.find(*CONTINUE)
                    if (c != null) tap(c) else tapAt(e.w * 0.5f, e.h * 0.9f, precise = false)
                    delay(1800)
                }
            }
        }
        if (fights == 0) return Result.Skip(note.ifEmpty { "今天的攔截次數已用完" })
        return Result.Done
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
        // 要按兩次「全部領取」：第一次領各項任務獎勵，任務點數累積後第二次才領上方的點數寶箱。
        // 每次領完等獎勵畫面點掉、清單更新後再按下一次；出現 CLEARED（全部完成）就停。
        var claimed = 0
        var cur: Frame = m
        repeat(3) { i ->
            if (cur.has("CLEARED")) return@repeat
            val all = cur.find("全部領取", "全部领取", yMin = 0.6f) ?: return@repeat
            act.status("每日任務：第 ${i + 1} 次全部領取")
            tap(all)
            claimed++
            // 先等獎勵畫面真的出現（REWARD／點擊領取獎勵），點掉後再等任務視窗回來；
            // 沒出現獎勵畫面代表這次沒東西可領
            val rew = waitFor(6_000, handle = false) { it.find(*CONTINUE) != null || it.has("REWARD") }
            if (rew != null) {
                repeat(4) {
                    val r = look()
                    if (missionTitle(r) != null && r.find(*CONTINUE) == null && !r.has("REWARD")) return@repeat
                    r.find(*CONTINUE)?.let { c -> tap(c) } ?: tapAt(r.w * 0.5f, r.h * 0.8f, precise = false)
                    delay(1200)
                }
            }
            waitFor(10_000) { missionTitle(it) != null && it.find(*CONTINUE) == null && !it.has("REWARD") }
            act.status("每日任務：等待清單更新")
            delay(2500)   // 讓任務點數和寶箱狀態更新
            cur = look()
        }
        if (claimed == 0) return Result.Skip("沒有可領的任務")
        val last = look()
        missionTitle(last)?.let { closePopup(last, it, -last.h * 0.035f) }
        return Result.Done
    }
}
