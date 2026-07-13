package com.storyteller_f.divedeep

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class DiveDeepTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        DiveDeepState.toggle(this)
        updateTile()
    }

    private fun updateTile() {
        val enabled = DiveDeepState.isEnabled(this)
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (enabled) "正在翻译" else "已停止"
            }
            updateTile()
        }
    }

    companion object {
        fun requestTileRefresh(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                requestListeningState(
                    context,
                    ComponentName(context, DiveDeepTileService::class.java),
                )
            }
        }
    }
}
