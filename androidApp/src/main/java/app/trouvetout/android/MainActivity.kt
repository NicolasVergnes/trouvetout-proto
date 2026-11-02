package app.trouvetout.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import app.trouvetout.android.notif.ReminderNotifications
import app.trouvetout.android.ui.AppNavigation
import app.trouvetout.android.ui.lock.DeviceAuth
import app.trouvetout.android.ui.lock.LocalSensitive
import app.trouvetout.android.ui.lock.LockGate
import app.trouvetout.android.ui.lock.NoKeyboardLearning
import app.trouvetout.android.ui.lock.SensitiveAccess
import app.trouvetout.android.ui.lock.StoreFailureScreen
import app.trouvetout.android.ui.theme.AppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app: App get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = app
        // A tapped reminder (E2); a recreated activity does not open it twice.
        if (savedInstanceState == null) ReminderNotifications.itemOf(intent)?.let { app.itemRequest.value = it }
        // Before the first frame (a rotation, a relaunch): never one frame without the flag.
        secure(app.current?.privacySettings?.state?.value?.lockEnabled == true)
        setContent {
            val searchRequest by app.searchRequest.collectAsStateWithLifecycle()
            val itemRequest by app.itemRequest.collectAsStateWithLifecycle()
            val failure by app.storeFailure.collectAsStateWithLifecycle()
            // Null until the store opened: nothing of the inventory is drawn before the lock is known.
            val locked by app.locked.collectAsStateWithLifecycle(initialValue = null)
            val lockEnabled by app.lockEnabled.collectAsStateWithLifecycle(initialValue = false)
            val revealed by app.sensitiveRevealed.collectAsStateWithLifecycle(initialValue = false)
            val noLearning by app.noKeyboardLearning.collectAsStateWithLifecycle(initialValue = false)
            // Kept above the lock: the screens are not composed while locked, the way back to them is kept.
            val navController = rememberNavController()
            LaunchedEffect(lockEnabled) { secure(lockEnabled) }
            AppTheme {
                val storeFailure = failure
                val lockState = locked
                when {
                    storeFailure != null ->
                        StoreFailureScreen(
                            storeFailure,
                            onWipe = { app.appScope.launch { runCatching { app.wipeAll() } } },
                            onRetry = { app.recreateShared() },
                        )
                    lockState == null -> Opening()
                    else ->
                        LockGate(locked = lockState, onUnlock = ::unlock) {
                            CompositionLocalProvider(
                                LocalSensitive provides SensitiveAccess(lockEnabled, revealed, ::revealSensitive),
                            ) {
                                NoKeyboardLearning(noLearning) {
                                    AppNavigation(
                                        controller = navController,
                                        searchRequest = searchRequest,
                                        onSearchRequestHandled = { app.searchRequest.value = null },
                                        itemRequest = itemRequest,
                                        onItemRequestHandled = { app.itemRequest.value = null },
                                    )
                                }
                            }
                        }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ReminderNotifications.itemOf(intent)?.let { app.itemRequest.value = it }
    }

    override fun onStart() {
        super.onStart()
        app.current?.appLock?.foregrounded()
    }

    override fun onStop() {
        super.onStop()
        // A rotation is not a departure.
        if (!isChangingConfigurations) app.current?.appLock?.backgrounded()
    }

    /** H3: the system prompt; a phone whose screen lock was removed has nothing to ask and opens. */
    private fun unlock() {
        val lock = app.current?.appLock ?: return
        if (!DeviceAuth.available(this)) {
            lock.unlocked()
            return
        }
        DeviceAuth.authenticate(this, getString(R.string.lock_prompt_title)) { ok -> if (ok) lock.unlocked() }
    }

    /** H4: shows the sensitive items until the app leaves, after the owner's authentication when the lock is on. */
    private fun revealSensitive() {
        val lock = app.current?.appLock ?: return
        if (lock.revealNeedsAuthentication && DeviceAuth.available(this)) {
            DeviceAuth.authenticate(this, getString(R.string.reveal_prompt_title)) { ok -> if (ok) lock.revealed() }
        } else {
            lock.revealed()
        }
    }

    /** H3: with the app lock on, no screenshot and a blank thumbnail in the recent apps. */
    private fun secure(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
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

        /** Opens the home screen (locked if the lock is on) with [sentence] in its field (H3: quick capture). */
        fun openCapture(
            context: Context,
            sentence: String,
        ) {
            (context.applicationContext as App).captureDraft.value = sentence
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@androidx.compose.runtime.Composable
private fun Opening() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}
