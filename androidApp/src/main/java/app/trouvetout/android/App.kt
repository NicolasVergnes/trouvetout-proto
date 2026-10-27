package app.trouvetout.android

import android.app.Application
import androidx.core.content.edit
import app.trouvetout.android.ui.settings.ExportSharing
import app.trouvetout.core.capture.Lang
import app.trouvetout.core.data.db.AndroidDriverFactory
import app.trouvetout.core.di.SharedConfig
import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.log.LogcatLogger
import app.trouvetout.core.log.NoopLogger
import app.trouvetout.core.platform.AndroidDeviceInfo
import app.trouvetout.core.platform.AndroidImageCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class App : Application() {
    @Volatile
    lateinit var shared: SharedFactory
        private set

    private val readyState = MutableStateFlow<SharedFactory?>(null)

    /**
     * The cores whose store opened, in order: screens observe their flows through this (never through [shared]
     * directly), so a core replaced by a total wipe (H6) or a store that cannot open never reaches a collector.
     */
    val readyShared: Flow<SharedFactory> get() = readyState.filterNotNull()

    /** Display preferences only (A5 history toggle); never business data (rule 3, H2 scope). */
    val uiPrefs: UiPrefs by lazy { UiPrefs(getSharedPreferences("ui", MODE_PRIVATE)) }

    /** Process-wide scope for work that must not die with an Activity (warm-up, later: reindexing). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        shared = createShared()
        // Opens the database off the main thread so the first capture (lot 3) is instant; every entry point
        // (activity, widget, tile, reminder receiver) benefits. warmUp() never throws.
        appScope.launch {
            ExportSharing.clear(this@App)
            warmUp(shared)
        }
    }

    /** After a total wipe (H6) the spent factory is replaced by a fresh one over an empty directory. */
    fun recreateShared(): SharedFactory {
        shared.close()
        readyState.value = null
        return createShared().also { fresh ->
            shared = fresh
            appScope.launch { warmUp(fresh) }
        }
    }

    /** Opens the store off the main thread, then runs the lot 2 housekeeping (bin, old exports, imports). */
    private suspend fun warmUp(factory: SharedFactory) {
        if (factory.warmUp()) {
            readyState.value = factory
            factory.housekeeping()
        }
    }

    private fun createShared(): SharedFactory {
        // Excluded from Auto Backup: the only backup channel is the app's own encrypted one (A9).
        val dataDirectory = noBackupFilesDir
        val databaseDirectory = File(dataDirectory, SharedFactory.DATABASE_DIRECTORY)
        return SharedFactory(
            SharedConfig(
                dataDirectory = dataDirectory.absolutePath,
                deviceInfo = AndroidDeviceInfo(this),
                driverFactory = AndroidDriverFactory(
                    this,
                    File(databaseDirectory, SharedFactory.DATABASE_FILE).absolutePath,
                ),
                imageCodec = AndroidImageCodec(),
                logger = if (BuildConfig.DEBUG) LogcatLogger else NoopLogger,
                storageReady = databaseDirectory.isDirectory || databaseDirectory.mkdirs(),
                appVersion = BuildConfig.VERSION_NAME,
                captureLang = if (Locale.getDefault().language == "fr") Lang.FR else Lang.EN,
            ),
        )
    }
}

/** Thin wrapper over the UI preference file. */
class UiPrefs(
    private val prefs: android.content.SharedPreferences,
) {
    var showHistory: Boolean
        get() = prefs.getBoolean(KEY_SHOW_HISTORY, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_HISTORY, value) }

    private companion object {
        const val KEY_SHOW_HISTORY = "show_history"
    }
}
