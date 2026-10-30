package app.trouvetout.android.capture

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.trouvetout.android.MainActivity
import app.trouvetout.android.ui.capture.QuickCaptureRoute
import app.trouvetout.android.ui.theme.AppTheme

/**
 * Translucent capture panel started by the widget and the tile (B5), in a task of its own that stays out of
 * the recent apps. It never shows the inventory: one sentence in, one card out.
 */
class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Listening starts once per gesture, not again after a rotation.
        val voice = savedInstanceState == null && intent.getStringExtra(EXTRA_MODE) == MODE_VOICE
        setContent {
            AppTheme {
                QuickCaptureRoute(startWithVoice = voice, onClose = ::finish, onSearch = ::openSearch)
            }
        }
    }

    private fun openSearch(query: String) {
        MainActivity.openSearch(this, query)
        finish()
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        const val MODE_VOICE = "voice"
        const val MODE_TEXT = "text"

        fun intent(
            context: Context,
            mode: String,
        ): Intent = Intent(context, CaptureActivity::class.java)
            .putExtra(EXTRA_MODE, mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        /** Immutable, one per mode (the request code keeps the two extras apart). */
        fun pendingIntent(
            context: Context,
            mode: String,
        ): PendingIntent = PendingIntent.getActivity(
            context,
            if (mode == MODE_VOICE) 1 else 2,
            intent(context, mode),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
