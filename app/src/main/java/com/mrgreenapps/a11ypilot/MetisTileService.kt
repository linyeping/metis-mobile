package com.mrgreenapps.a11ypilot

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mrgreenapps.a11ypilot.phoneuse.PhoneUseService

/**
 * Quick Settings tile that launches Metis and reflects the accessibility-service state. Tapping
 * the tile opens the workspace; the tile's active state mirrors whether the PhoneUse accessibility
 * service is connected, giving a one-tap status indicator + launcher from the notification shade.
 */
class MetisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(intent)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val active = PhoneUseService.isRunning()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) "Metis 已就绪" else "打开 Metis"
        tile.updateTile()
    }
}
