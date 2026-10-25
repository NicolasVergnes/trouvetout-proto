package app.trouvetout.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.trouvetout.android.ui.home.EmptyHomeScreen
import app.trouvetout.android.ui.theme.AppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Opens the database off the main thread so the first capture (lot 3) is instant.
        lifecycleScope.launch { (application as App).shared.warmUp() }
        setContent {
            AppTheme {
                EmptyHomeScreen()
            }
        }
    }
}
