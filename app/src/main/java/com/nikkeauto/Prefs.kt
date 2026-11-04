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
        private const val K_SHOW_OV = "show_overlay"
        private const val K_OV_GAME = "overlay_only_in_game"
        private const val K_DMAX = "delay_max"
    }
}
