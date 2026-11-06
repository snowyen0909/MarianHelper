package com.nikkeauto

import android.content.Context
import android.content.SharedPreferences

/** 所有開關與參數，App 介面寫入、服務每一輪讀取。 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var skipStory: Boolean
        get() = sp.getBoolean(K_SKIP, true)
        set(v) = sp.edit().putBoolean(K_SKIP, v).apply()

    var mapNav: Boolean
        get() = sp.getBoolean(K_NAV, true)
        set(v) = sp.edit().putBoolean(K_NAV, v).apply()

    var prepAuto: Boolean
        get() = sp.getBoolean(K_PREP, true)
        set(v) = sp.edit().putBoolean(K_PREP, v).apply()

    var patrol: Boolean
        get() = sp.getBoolean(K_PATROL, true)
        set(v) = sp.edit().putBoolean(K_PATROL, v).apply()

    /** 只打主線引導目標；紅圈怪只在擋路時打。 */
    var onlyMain: Boolean
        get() = sp.getBoolean(K_ONLY_MAIN, true)
        set(v) = sp.edit().putBoolean(K_ONLY_MAIN, v).apply()

    /** 顯示懸浮窗。 */
    var showOverlay: Boolean
        get() = sp.getBoolean(K_SHOW_OV, true)
        set(v) = sp.edit().putBoolean(K_SHOW_OV, v).apply()

    /** 只在妮姬遊戲畫面顯示懸浮窗；離開遊戲時自動隱藏並停止腳本。 */
    var overlayOnlyInGame: Boolean
        get() = sp.getBoolean(K_OV_GAME, true)
        set(v) = sp.edit().putBoolean(K_OV_GAME, v).apply()

    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)

    // ---------- 一鍵日常 ----------

    /** 項目順序；新版加入的項目自動接在最後。 */
    var dailyOrder: List<DailyTask>
        get() {
            val saved = sp.getString(K_DAILY_ORDER, "")!!.split(",").mapNotNull { DailyTask.of(it) }.distinct()
            return saved + DailyTask.entries.filter { it !in saved }
        }
        set(v) = sp.edit().putString(K_DAILY_ORDER, v.joinToString(",") { it.key }).apply()

    fun dailyEnabled(t: DailyTask) = sp.getBoolean("daily_on_${t.key}", true)
    fun setDailyEnabled(t: DailyTask, on: Boolean) = sp.edit().putBoolean("daily_on_${t.key}", on).apply()

    /** 遊戲每天 04:00（台灣時間）重置，以此切日。 */
    private fun gameDay(): String {
        val tz = java.util.TimeZone.getTimeZone("Asia/Taipei")
        val c = java.util.Calendar.getInstance(tz)
        c.add(java.util.Calendar.HOUR_OF_DAY, -4)
        return "%04d%02d%02d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }

    /** 今天（遊戲日）已完成或已跳過的項目，中斷後重跑會略過。 */
    fun dailyDoneToday(): Set<DailyTask> {
        if (sp.getString(K_DONE_DAY, "") != gameDay()) return emptySet()
        return sp.getString(K_DONE_LIST, "")!!.split(",").mapNotNull { DailyTask.of(it) }.toSet()
    }

    fun markDailyDone(t: DailyTask) {
        val set = dailyDoneToday() + t
        sp.edit().putString(K_DONE_DAY, gameDay()).putString(K_DONE_LIST, set.joinToString(",") { it.key }).apply()
    }

    fun resetDailyDone() = sp.edit().remove(K_DONE_DAY).remove(K_DONE_LIST).apply()

    /** 競技場每天打幾場（不論輸贏），預設 2。 */
    var arenaCount: Int
        get() = sp.getInt(K_ARENA, 2)
        set(v) = sp.edit().putInt(K_ARENA, v.coerceIn(1, 5)).apply()

    /** 無盡之塔每座打幾次，預設 3（每天上限）。 */
    var towerCount: Int
        get() = sp.getInt(K_TOWER, 3)
        set(v) = sp.edit().putInt(K_TOWER, v.coerceIn(1, 3)).apply()

    // ---------- 日誌 ----------
    /** 失敗時存截圖到相簿。 */
    var saveShots: Boolean
        get() = sp.getBoolean("save_shots", true)
        set(v) = sp.edit().putBoolean("save_shots", v).apply()

    var delayMin: Int
        get() = sp.getInt(K_DMIN, 500)
        set(v) = sp.edit().putInt(K_DMIN, v).apply()

    /** 日誌最多保留幾行。 */
    var logMax: Int
        get() = sp.getInt(K_LOG_MAX, 300)
        set(v) = sp.edit().putInt(K_LOG_MAX, v).apply()

    /** 日誌是否顯示時間。 */
    var logTime: Boolean
        get() = sp.getBoolean(K_LOG_TIME, true)
        set(v) = sp.edit().putBoolean(K_LOG_TIME, v).apply()

    /** 日誌只記錄重要事件（開始、完成、失敗），不記錄每一步。 */
    var logBrief: Boolean
        get() = sp.getBoolean(K_LOG_BRIEF, false)
        set(v) = sp.edit().putBoolean(K_LOG_BRIEF, v).apply()

    var delayMax: Int
        get() = sp.getInt(K_DMAX, 1200)
        set(v) = sp.edit().putInt(K_DMAX, v).apply()

    companion object {
        private const val K_SKIP = "skip_story"
        private const val K_NAV = "map_nav"
        private const val K_PREP = "prep_auto"
        private const val K_PATROL = "patrol"
        private const val K_ONLY_MAIN = "only_main"
        private const val K_DMIN = "delay_min"
        private const val K_DAILY_ORDER = "daily_order"
        private const val K_DONE_DAY = "daily_done_day"
        private const val K_DONE_LIST = "daily_done_list"
        private const val K_ARENA = "daily_arena_count"
        private const val K_TOWER = "daily_tower_count"
        private const val K_SHOW_OV = "show_overlay"
        private const val K_OV_GAME = "overlay_only_in_game"
        private const val K_DMAX = "delay_max"
        private const val K_LOG_MAX = "log_max"
        private const val K_LOG_TIME = "log_time"
        private const val K_LOG_BRIEF = "log_brief"
    }
}
