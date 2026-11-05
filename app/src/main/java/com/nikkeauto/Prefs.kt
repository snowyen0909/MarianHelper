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

    /** 競技場每天打幾場（不論輸贏），預設 2。 */
    var arenaCount: Int
        get() = sp.getInt(K_ARENA, 2)
        set(v) = sp.edit().putInt(K_ARENA, v.coerceIn(1, 5)).apply()

    /** 無盡之塔每座打幾次，預設 3（每天上限）。 */
    var towerCount: Int
        get() = sp.getInt(K_TOWER, 3)
        set(v) = sp.edit().putInt(K_TOWER, v.coerceIn(1, 3)).apply()

    var delayMin: Int
        get() = sp.getInt(K_DMIN, 500)
        set(v) = sp.edit().putInt(K_DMIN, v).apply()

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
        private const val K_ARENA = "daily_arena_count"
        private const val K_TOWER = "daily_tower_count"
        private const val K_SHOW_OV = "show_overlay"
        private const val K_OV_GAME = "overlay_only_in_game"
        private const val K_DMAX = "delay_max"
    }
}
