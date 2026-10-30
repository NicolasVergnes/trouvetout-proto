package app.trouvetout.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trouvetout.android.ui.AppNavigation
import app.trouvetout.android.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as App
        setContent {
            val searchRequest by app.searchRequest.collectAsStateWithLifecycle()
            AppTheme {
                AppNavigation(
                    searchRequest = searchRequest,
                    onSearchRequestHandled = { app.searchRequest.value = null },
                )
            }
        }
    }

    companion object {
        /** Opens the home screen on the results of [query] (C5); the query stays in process memory. */
        fun openSearch(
            context: Context,
            query: String,
        ) {
            (context.applicationContext as App).searchRequest.value = query
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
