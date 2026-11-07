package app.trouvetout.android.capture

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trouvetout.android.App
import app.trouvetout.android.MainActivity
import app.trouvetout.android.ui.capture.QuickCaptureRoute
import app.trouvetout.android.ui.lock.NoKeyboardLearning
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
        val app = application as App
        if (app.current?.privacySettings?.state?.value?.lockEnabled == true) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContent {
            // H3: with the app lock on, the panel is kept out of screenshots too (it shows the card it wrote).
            val lockEnabled by app.lockEnabled.collectAsStateWithLifecycle(initialValue = false)
            val noLearning by app.noKeyboardLearning.collectAsStateWithLifecycle(initialValue = false)
            LaunchedEffect(lockEnabled) {
                if (lockEnabled) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            AppTheme {
                NoKeyboardLearning(noLearning) {
                    QuickCaptureRoute(
                        startWithVoice = voice,
                        onClose = ::finish,
                        onSearch = ::openSearch,
                        onOpenApp = ::openApp,
                        onOpenPurchase = ::openPurchase,
                    )
                }
            }
        }
    }

    /** The quick capture is the app too (H3): its returns count, so the lock delay holds for the widget and tile. */
    override fun onStart() {
        super.onStart()
        (application as App).current?.appLock?.foregrounded()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) (application as App).current?.appLock?.backgrounded()
    }

    private fun openSearch(query: String) {
        MainActivity.openSearch(this, query)
        finish()
    }

    private fun openApp(sentence: String) {
        MainActivity.openCapture(this, sentence)
        finish()
    }

    private fun openPurchase(sentence: String) {
        MainActivity.openPurchase(this, sentence)
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
