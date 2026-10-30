package app.trouvetout.android.widget

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.trouvetout.android.capture.CaptureActivity
import app.trouvetout.android.capture.SpeechController
import app.trouvetout.core.capture.SpeechAvailability

/** Quick settings tile (B5): one tap opens the quick capture, listening if possible; when locked, after unlocking. */
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
        // Text right away when the phone cannot dictate on the device (Android 10–11, no recogniser): one gesture.
        val mode =
            if (SpeechController(this).availability() == SpeechAvailability.ON_DEVICE) {
                CaptureActivity.MODE_VOICE
            } else {
                CaptureActivity.MODE_TEXT
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(CaptureActivity.pendingIntent(this, mode))
        } else {
            startActivityAndCollapse(CaptureActivity.intent(this, mode))
        }
    }
}
