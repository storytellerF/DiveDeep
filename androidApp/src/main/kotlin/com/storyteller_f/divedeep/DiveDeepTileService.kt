package com.storyteller_f.divedeep

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DiveDeepTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        serviceScope.launch {
            DiveDeepState.toggle(this@DiveDeepTileService)
            updateTile()
        }
    }

    private fun updateTile() {
        serviceScope.launch {
            val enabled = DiveDeepState.isEnabled(this@DiveDeepTileService)
            qsTile?.apply {
                state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    subtitle = if (enabled) "正在翻译" else "已停止"
                }
                updateTile()
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
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
