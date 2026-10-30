package app.trouvetout.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.trouvetout.android.ui.AppNavigation
import app.trouvetout.android.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    /** A question asked from the quick capture, waiting for the home screen to show it (C5). */
    private val searchRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        takeSearch(intent)
        setContent {
            AppTheme {
                AppNavigation(
                    searchRequest = searchRequest.value,
                    onSearchRequestHandled = { searchRequest.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSearch(intent)
    }

    /** The extra is removed once read, so a rotation does not search again. */
    private fun takeSearch(intent: Intent) {
        // The activity is exported (launcher): another app could send this extra; it only fills a search, bounded.
        intent.getStringExtra(EXTRA_SEARCH)?.take(MAX_QUERY)?.let { searchRequest.value = it }
        intent.removeExtra(EXTRA_SEARCH)
    }

    companion object {
        private const val EXTRA_SEARCH = "search"
        private const val MAX_QUERY = 500

        fun searchIntent(
            context: Context,
            query: String,
        ): Intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SEARCH, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
