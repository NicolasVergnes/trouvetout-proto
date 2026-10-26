package app.trouvetout.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.trouvetout.android.ui.capture.CaptureRoute
import app.trouvetout.android.ui.settings.SettingsRoute
import app.trouvetout.android.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                // Two screens for now; a NavHost arrives with the places screens (lot 4).
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                if (settingsOpen) {
                    BackHandler { settingsOpen = false }
                    SettingsRoute(onBack = { settingsOpen = false })
                } else {
                    CaptureRoute(onOpenSettings = { settingsOpen = true })
                }
            }
        }
    }
}
