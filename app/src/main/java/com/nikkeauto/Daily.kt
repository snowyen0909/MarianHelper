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

    suspend fun run(): String {
        act.status("一鍵日常：準備進入大廳")
        if (!toLobby(timeoutMs = 150_000, launch = true)) {
            snap("daily_start")
            return "無法進入遊戲大廳，一鍵日常已停止。"
        }
        val report = ArrayList<String>()
        for (task in prefs.dailyOrder) {
            if (!prefs.dailyEnabled(task)) continue
            act.status("一鍵日常：${task.label}")
            val limit = if (task == DailyTask.TOWER) 20 * 60_000L else 4 * 60_000L
            val r = try {
                withTimeoutOrNull(limit) { runTask(task) } ?: Result.Fail("超過時間")
            } catch (e: StopDaily) {
                Result.Fail(e.message ?: "")
            }
            when (r) {
                is Result.Done -> report += "✓ ${task.label}"
                is Result.Skip -> report += "－ ${task.label}：${r.why}"
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

    private suspend fun tap(l: TextLine) {
        act.tap(l.box.exactCenterX(), l.box.exactCenterY())
        act.humanDelay()
    }

    private suspend fun tapAt(x: Float, y: Float, precise: Boolean = true) {
        act.tap(x, y, precise)
        act.humanDelay()
    }

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
            if (cond(f)) return f
            if (handle && handleCommon(f)) continue
            delay(500)
        }
        return null
    }

    private val CONTINUE = arrayOf(
        "點擊領取獎勵", "点击领取奖励", "點擊任意處", "点击任意处", "點擊任意", "点击任意",
        "點擊進行下一步", "点击进行下一步", "點擊繼續", "点击继续", "TOUCHTOCONTINUE",
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
        f.find(*POPUP_TITLES, yMax = 0.6f) ?: missionTitle(f) ?: friendTitle(f)

    private fun isLobby(f: Frame): Boolean =
        f.find("方舟", yMin = 0.5f) != null &&
            f.find("前哨基地", yMin = 0.5f) != null &&
            f.find("作戰出擊", "作战出击", "大廳", "大厅", yMin = 0.5f) != null &&
            popupTitle(f) == null &&
            f.find(*CONTINUE) == null

    /** 點視窗右上角的 X：先猜位置，再找附近的白色叉叉。 */
    private suspend fun closePopup(f: Frame, title: TextLine, dy: Float = 0f) {
        val gx = f.w * 0.9f
        val gy = title.box.exactCenterY() + dy
        val p = Vision.whiteCenter(f.bmp, gx, gy, (f.w * 0.07f).toInt()) ?: Vision.Pt(gx, gy)
        tapAt(p.x, p.y)
        delay(500)
    }

    /**
     * 回到大廳：有「返回」就點旁邊的房子，有視窗就關，畫面一直不動就按返回鍵。
     * launch = true 時，若妮姬不在前景會先打開遊戲。
     */
    private suspend fun toLobby(timeoutMs: Long, launch: Boolean = false): Boolean {
        if (launch) act.launchGame()
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
            // 畫面一直沒變（活動彈窗、不認得的頁面）：按返回鍵
            val p = prev
            still = if (p != null && Vision.diff(p, f.bmp) < 0.015f) still + 1 else 0
            prev = f.bmp
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

    private suspend fun lobby(): Frame =
        waitFor(20_000) { isLobby(it) } ?: throw StopDaily("不在大廳")

    // =============== 各項目 ===============

    /** 郵件：信封在資源列「150 M」右邊約 18% 螢幕寬。 */
    private suspend fun mail(): Result {
        val f = lobby()
        val money = f.lines.firstOrNull { l ->
            !l.isWord && l.box.exactCenterY() < f.h * 0.08f && l.box.exactCenterX() > f.w * 0.5f &&
                Regex("""\d[\d,.]*\s*[MK]$""").containsMatchIn(l.text.trim())
        } ?: return Result.Fail("找不到資源列")
        tapAt(money.box.right + f.w * 0.178f, money.box.exactCenterY())
        val box = waitFor { it.has("郵箱", "邮箱") } ?: return Result.Fail("郵箱沒有打開")
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
        val icon = f.find("DEFENSE", "OUTPOST", yMin = 0.6f)
            ?: f.lines.firstOrNull { !it.isWord && it.norm.endsWith("%") && it.box.exactCenterY() > f.h * 0.6f && it.box.exactCenterX() < f.w * 0.4f }
            ?: return Result.Fail("找不到防禦前哨基地")
        tap(icon)
        var d = waitFor { it.has("防禦前哨基地", "防御前哨基地") } ?: return Result.Fail("視窗沒有打開")
        d.find("獲得獎勵", "获得奖励", yMin = 0.6f)?.let { tap(it) }
        d = waitFor(10_000) { it.has("防禦前哨基地", "防御前哨基地") && it.find(*CONTINUE) == null } ?: d

        var note = ""
        val wipe = d.find("一舉殲滅", "一举歼灭", yMin = 0.6f)
        if (wipe != null) {
            tap(wipe)
            val w = waitFor(8_000) { it.has("進行殲滅", "进行歼灭") }
            if (w != null) {
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
        tap(base)
        val o = waitFor(25_000) { it.find("派遣公告", yMin = 0.75f) != null } ?: return Result.Fail("沒有進入前哨基地")
        tap(o.find("派遣公告", yMin = 0.75f)!!)
        var b = waitFor { it.find("派遣公告欄", "派遣公告栏", yMax = 0.4f) != null } ?: return Result.Fail("派遣公告欄沒有打開")
        b.find("全部領取", "全部领取", yMin = 0.6f)?.let {
            tap(it)
            b = waitFor(10_000) { f2 -> f2.find("派遣公告欄", "派遣公告栏", yMax = 0.4f) != null && f2.find(*CONTINUE) == null } ?: b
        }
        b.find("全部派遣", yMin = 0.6f)?.let {
            tap(it)
            // 確認視窗的按鈕就叫「派遣」
            val c = waitFor(6_000) { f2 -> f2.exact("派遣", yMin = 0.6f).isNotEmpty() }
            c?.exact("派遣", yMin = 0.6f)?.firstOrNull()?.let { btn -> tap(btn) }
            delay(1500)
        }
        val last = look()
        last.find("派遣公告欄", "派遣公告栏", yMax = 0.4f)?.let { closePopup(last, it) }
        return Result.Done
    }

    /** 好友：贈送 → 確認。 */
    private suspend fun friend(): Result {
        val f = lobby()
        val btn = f.exact("好友").firstOrNull { it.box.exactCenterX() > f.w * 0.75f }
            ?: return Result.Fail("找不到好友按鈕")
        tapAt(btn.box.exactCenterX(), btn.box.exactCenterY() - f.w * 0.03f, precise = false)
        val p = waitFor { it.find("贈送", "赠送", yMin = 0.7f) != null } ?: return Result.Fail("好友視窗沒有打開")
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
        val btn = f.find("付費商店", "付费商店", yMin = 0.5f) ?: return Result.Fail("找不到付費商店")
        tap(btn)
        val s = waitFor(20_000) { it.find("付費商店", "付费商店", yMax = 0.08f) != null }
            ?: return Result.Fail("付費商店沒有打開")
        val label = s.find("付費商店", "付费商店", yMax = 0.08f)!!
        // 分頁列沒有文字：「普通禮包」是第 3 個，在「付費商店」標籤下方約 34% 螢幕寬
        tapAt(s.w * 0.335f, label.box.exactCenterY() + s.w * 0.343f)
        val g = waitFor(8_000) { it.has("普通禮包", "普通礼包") } ?: return Result.Fail("找不到普通禮包分頁")
        g.exact("每日", yMax = 0.5f).firstOrNull()?.let { tap(it) }
        val d = waitFor(8_000) { it.has("每日免費禮包", "每日免费礼包") } ?: return Result.Fail("找不到每日免費禮包")
        val name = d.find("每日免費禮包", "每日免费礼包")!!
        val col = { l: TextLine -> kotlin.math.abs(l.box.exactCenterX() - name.box.exactCenterX()) < d.w * 0.12f }
        if (d.lines.any { !it.isWord && it.norm.contains("SOLD") && col(it) }) return Result.Skip("今天已經領過")
        val free = d.exact("免費", "免费").firstOrNull { col(it) && it.box.top > name.box.bottom }
            ?: return Result.Skip("沒有看到免費標示")
        tap(free)
        waitFor(8_000) { it.find(*CONTINUE) == null && !it.has("感覺怎麼樣", "感觉怎么样") }
        return Result.Done
    }

    /** 普通商店：只買價格 0 的物品，購買視窗確認消耗資金為 0 才按購買。 */
    private suspend fun shop(): Result {
        val f = lobby()
        val btn = f.exact("商店", yMin = 0.5f).firstOrNull() ?: return Result.Fail("找不到商店")
        tap(btn)
        val s = waitFor(20_000) { it.has("普通商店") } ?: return Result.Fail("普通商店沒有打開")
        val zero = s.exact("0", yMin = 0.2f, yMax = 0.9f).firstOrNull { z ->
            s.lines.any { l ->
                !l.isWord && (l.norm.contains("100%") || l.norm.contains("FREE")) &&
                    kotlin.math.abs(l.box.exactCenterX() - z.box.exactCenterX()) < s.w * 0.12f &&
                    l.box.exactCenterY() < z.box.exactCenterY() && z.box.exactCenterY() - l.box.exactCenterY() < s.h * 0.06f
            }
        } ?: return Result.Skip("沒有免費物品（可能已買過）")
        tap(zero)
        val d = waitFor(6_000) { it.has("購買道具", "购买道具") } ?: return Result.Skip("已經買過了")
        val cost = d.find("消耗資金", "消耗资金")
        val isFree = cost != null && d.lines.any { l ->
            !l.isWord && l.norm == "0" && kotlin.math.abs(l.box.exactCenterY() - cost.box.exactCenterY()) < d.h * 0.025f
        }
        if (!isFree) {
            d.exact("取消").firstOrNull()?.let { tap(it) }
            return Result.Skip("不是免費的，已取消")
        }
        val buy = d.exact("購買", "购买").maxByOrNull { it.box.top } ?: return Result.Fail("找不到購買按鈕")
        tap(buy)
        waitFor(8_000) { it.find(*CONTINUE) == null && it.has("普通商店") }
        return Result.Done
    }

    /** 妮姬升級：依戰力由低到高，第一隻（必須是 LV.1）升 1 級，再等級重置拿回材料。 */
    private suspend fun nikkeLevel(): Result {
        val f = lobby()
        val btn = f.exact("妮姬", yMin = 0.9f).firstOrNull() ?: return Result.Fail("找不到妮姬按鈕")
        tap(btn)
        var list = waitFor(15_000) { it.find("戰鬥力", "战斗力", "戰力", yMax = 0.25f) != null }
            ?: return Result.Fail("妮姬列表沒有打開")
        for (attempt in 0..1) {
            val sort = list.find("戰鬥力", "战斗力", "戰力", yMax = 0.25f)!!
            // 第一張卡在左上
            tapAt(list.w * 0.125f, sort.box.exactCenterY() + list.w * 0.41f, precise = false)
            val d = waitFor(10_000) { it.find("等級重置", "等级重置") != null || it.lines.any { l -> l.norm.contains("升級") && l.norm.contains("LV") } }
                ?: return Result.Fail("妮姬詳細頁沒有打開")
            val isLv1 = d.lines.any { Regex("""LV\.?1/""").containsMatchIn(it.norm) }
            if (isLv1) return upgradeAndReset(d)
            // 不是 LV.1：排序可能是由高到低，回列表切換排序再試一次
            if (attempt == 1) return Result.Skip("找不到 LV.1 的妮姬，為了安全不升級")
            act.back(); delay(1500)
            list = waitFor(8_000) { it.find("戰鬥力", "战斗力", "戰力", yMax = 0.25f) != null } ?: return Result.Fail("回不到列表")
            val s2 = list.find("戰鬥力", "战斗力", "戰力", yMax = 0.25f)!!
            tapAt(list.w * 0.93f, s2.box.exactCenterY())
            delay(1000)
            list = look()
        }
        return Result.Fail("不明原因")
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
        tap(ark)
        val a = waitFor(25_000) { it.find(*facility, yMin = 0.15f) != null } ?: return null
        tap(a.find(*facility, yMin = 0.15f)!!)
        return a
    }

    private fun numbers(f: Frame, yFrom: Float, yTo: Float, xMax: Float = Float.MAX_VALUE): List<Pair<Long, TextLine>> =
        f.lines.filter { !it.isWord && it.box.exactCenterY() in yFrom..yTo && it.box.exactCenterX() < xMax }
            .mapNotNull { l -> Regex("""\d{5,}""").find(l.norm.replace(",", ""))?.value?.toLongOrNull()?.let { it to l } }

    /** 競技場：有比自己戰力低的就打最低的，沒有就打第三個；只用免費次數。 */
    private suspend fun arena(): Result {
        enterArk("競技場", "竞技场") ?: return Result.Fail("找不到競技場")
        val a = waitFor(20_000) { it.has("新人競技場", "新人竞技场", "ROOKIE") } ?: return Result.Fail("競技場沒有打開")
        tap(a.find("新人競技場", "新人竞技场", "ROOKIE")!!)
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
            val go = look().lines.filter { !it.isWord && (it.norm.contains("進入戰") || it.norm.contains("进入战")) }.maxByOrNull { it.box.top }
                ?: return Result.Fail("找不到進入戰鬥")
            tap(go)
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
        val quest = ScreenReader.questLine(f.lines, f.bmp.width, f.bmp.height) ?: return Result.Fail("找不到任務列")
        tapAt(quest.box.left - f.w * 0.055f, quest.box.exactCenterY())
        val m = waitFor { missionTitle(it) != null } ?: return Result.Fail("任務視窗沒有打開")
        val all = m.find("全部領取", "全部领取", yMin = 0.6f) ?: return Result.Skip("沒有可領的任務")
        tap(all)
        val after = waitFor(10_000) { missionTitle(it) != null && it.find(*CONTINUE) == null }
        after?.let { a -> missionTitle(a)?.let { closePopup(a, it, -a.h * 0.035f) } }
        return Result.Done
    }
}
