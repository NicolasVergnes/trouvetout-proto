package app.trouvetout.android.widget

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.trouvetout.android.capture.CaptureActivity

/** Quick settings tile (B5): one tap opens the quick capture, listening; on a locked phone, after unlocking. */
class CaptureTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(::open) else open()
    }

    // The Intent overload is the only one before Android 14 and is never reached from 14 on.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun open() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(CaptureActivity.pendingIntent(this, CaptureActivity.MODE_VOICE))
        } else {
            startActivityAndCollapse(CaptureActivity.intent(this, CaptureActivity.MODE_VOICE))
        }
    }
}
