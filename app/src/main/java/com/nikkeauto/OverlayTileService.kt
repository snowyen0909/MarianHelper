package com.nikkeauto

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 下拉通知欄的快速設定面板開關：顯示／隱藏懸浮窗。 */
class OverlayTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        val prefs = Prefs(this)
        prefs.showOverlay = !prefs.showOverlay
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val prefs = Prefs(this)
        val serviceOn = AutoService.connected.value
        tile.state = when {
            !serviceOn -> Tile.STATE_UNAVAILABLE
            prefs.showOverlay -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "NIKKE 懸浮窗"
        tile.subtitle = when {
            !serviceOn -> "服務未啟用"
            prefs.showOverlay -> "顯示中"
            else -> "已隱藏"
        }
        tile.updateTile()
    }
}
