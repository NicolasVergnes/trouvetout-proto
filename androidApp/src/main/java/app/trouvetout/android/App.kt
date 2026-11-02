package app.trouvetout.android

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.core.content.edit
import app.trouvetout.android.notif.ReminderNotifications
import app.trouvetout.android.notif.ReminderScheduler
import app.trouvetout.android.ui.common.clearCameraCaptures
import app.trouvetout.android.ui.settings.ExportSharing
import app.trouvetout.core.capture.Lang
import app.trouvetout.core.data.db.AndroidDatabaseKeys
import app.trouvetout.core.data.db.AndroidDriverFactory
import app.trouvetout.core.di.SharedConfig
import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.di.StoreFailure
import app.trouvetout.core.log.LogcatLogger
import app.trouvetout.core.log.NoopLogger
import app.trouvetout.core.ml.AssetTextModelSource
import app.trouvetout.core.ml.BundledModelAsset
import app.trouvetout.core.platform.AndroidDeviceInfo
import app.trouvetout.core.platform.AndroidImageCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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

    /** The core whose store opened, if any (lifecycle callbacks that cannot wait). */
    val current: SharedFactory? get() = readyState.value

    /** Why the store could not open (lot 8: a lost key is said, with the way out). */
    val storeFailure = MutableStateFlow<StoreFailure?>(null)

    /** H3: whether the content must stay covered, for the core that is open. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val locked: Flow<Boolean> get() = readyShared.flatMapLatest { it.appLock.locked }

    /** The privacy switch « keep the keyboard from learning what I type » (lot 8). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val noKeyboardLearning: Flow<Boolean>
        get() = readyShared.flatMapLatest { shared -> shared.privacySettings.state }.map { it.noKeyboardLearning }

    /** H4: sensitive items are shown (after a request, and the owner's authentication with the lock on). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sensitiveRevealed: Flow<Boolean> get() = readyShared.flatMapLatest { it.appLock.sensitiveRevealed }

    /** H3: whether the app lock is on (screens of this app hidden from screenshots and the recent apps). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lockEnabled: Flow<Boolean>
        get() = readyShared.flatMapLatest { shared -> shared.privacySettings.state }.map { it.lockEnabled }

    /** The next trip to the background is a system screen the user comes back from (camera app, share sheet). */
    fun expectReturn() {
        current?.appLock?.expectReturn()
    }

    /** Display preferences only (A5 history toggle); never business data (rule 3, H2 scope). */
    val uiPrefs: UiPrefs by lazy { UiPrefs(getSharedPreferences("ui", MODE_PRIVATE)) }

    /**
     * A question asked from the quick capture, waiting for the home screen (C5). Kept in process memory: no
     * Intent extra, so no other app can send one and nothing is replayed after the process dies.
     */
    val searchRequest = MutableStateFlow<String?>(null)

    /**
     * A sentence the quick capture could not finish while the app was locked (it asked a question): shown in the
     * home field once the app is unlocked. Process memory only, like [searchRequest].
     */
    val captureDraft = MutableStateFlow<String?>(null)

    /**
     * The item sheet a tapped reminder opens (E2), once the app is unlocked. Set only from the non-exported
     * notification alias, with a valid ULID; process memory, like [searchRequest].
     */
    val itemRequest = MutableStateFlow<String?>(null)

    /** Reminder alarms and notifications (lot 9). */
    val reminderScheduler: ReminderScheduler by lazy { ReminderScheduler(this) }

    /** Process-wide scope for work that must not die with an Activity (warm-up, releasing the model). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        shared = createShared()
        // Opens the database off the main thread so the first capture (lot 3) is instant; every entry point
        // (activity, widget, tile, reminder receiver) benefits. warmUp() never throws.
        appScope.launch {
            ExportSharing.clear(this@App)
            clearCameraCaptures(this@App)
            warmUp(shared)
        }
        // Whatever woke the process (screen, widget, alarm, restart), the alarms follow the journal: a reminder
        // set, removed, renamed, made sensitive or sent to the bin is handed to the system again.
        ReminderNotifications.ensureChannel(this)
        appScope.launch {
            readyShared.collectLatest { factory ->
                // A wiped core closes its store under the collector; the next core takes over.
                runCatching { factory.reminders.observeSchedule().collect { reminderScheduler.reconcile(it) } }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            }
        }
    }

    /** After a total wipe (H6) the spent factory is replaced by a fresh one over an empty directory. */
    fun recreateShared(): SharedFactory {
        shared.close()
        readyState.value = null
        storeFailure.value = null
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
        } else if (factory === shared) {
            storeFailure.value = factory.storeFailure
        }
    }

    /** H6 from anywhere (settings, unreadable store): core files and key, then the app's caches and preferences. */
    suspend fun wipeAll() {
        // Whatever happens, the spent core is replaced; a failure still reaches the caller, which says so.
        try {
            reminderScheduler.clear()
            shared.wipeAllData()
        } finally {
            kotlinx.coroutines.withContext(Dispatchers.IO) { cacheDir.deleteRecursively() }
            uiPrefs.clear()
            recreateShared()
        }
    }

    /**
     * Once no screen is visible, or memory runs critically low, the search stops computing vectors and frees the model
     * (N6: no background work; the model only stays resident while the app is in the foreground). The next search
     * loads it again.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION") // the only signal of « UI hidden » that exists on API 29
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            val factory = readyState.value ?: return
            appScope.launch { factory.search.release() }
        }
    }

    private fun createShared(): SharedFactory {
        // Excluded from Auto Backup: the only backup channel is the app's own encrypted one (A9).
        val dataDirectory = noBackupFilesDir
        val databaseDirectory = File(dataDirectory, SharedFactory.DATABASE_DIRECTORY)
        // H2: the database key, wrapped by the Keystore, next to the database and as far from backups.
        val keys = AndroidDatabaseKeys(this, File(dataDirectory, AndroidDatabaseKeys.DIRECTORY))
        return SharedFactory(
            SharedConfig(
                dataDirectory = dataDirectory.absolutePath,
                deviceInfo = AndroidDeviceInfo(this),
                driverFactory = AndroidDriverFactory(
                    this,
                    File(databaseDirectory, SharedFactory.DATABASE_FILE).absolutePath,
                    keys,
                ),
                imageCodec = AndroidImageCodec(),
                logger = if (BuildConfig.DEBUG) LogcatLogger else NoopLogger,
                storageReady = databaseDirectory.isDirectory || databaseDirectory.mkdirs(),
                appVersion = BuildConfig.VERSION_NAME,
                captureLang = if (Locale.getDefault().language == "fr") Lang.FR else Lang.EN,
                databaseKeys = keys,
            ).apply {
                textModel = bundledModels(this@App)
                // « 12/03 » in a reminder sentence: month first only where the phone writes dates that way.
                dayFirstDates = Locale.getDefault().country != "US"
            },
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

    private val simpleModeState = MutableStateFlow(prefs.getBoolean(KEY_SIMPLE_MODE, false))

    /** I7: the simplified home (two large buttons). */
    val simpleMode: kotlinx.coroutines.flow.StateFlow<Boolean> get() = simpleModeState

    fun setSimpleMode(value: Boolean) {
        prefs.edit { putBoolean(KEY_SIMPLE_MODE, value) }
        simpleModeState.value = value
    }

    /** H6: display preferences go with the data. */
    fun clear() {
        prefs.edit { clear() }
        simpleModeState.value = false
    }

    private companion object {
        const val KEY_SHOW_HISTORY = "show_history"
        const val KEY_SIMPLE_MODE = "simple_mode"
    }
}

/** The models of this build (ADR-021, ADR-022): text file, text+vision file, label catalog; none without models. */
fun bundledModels(context: android.content.Context): AssetTextModelSource = AssetTextModelSource(
    context,
    asset(BuildConfig.TEXT_MODEL_ASSET, BuildConfig.TEXT_MODEL_ID, BuildConfig.TEXT_MODEL_BYTES),
    asset(BuildConfig.VISION_MODEL_ASSET, BuildConfig.VISION_MODEL_ID, BuildConfig.VISION_MODEL_BYTES),
    BuildConfig.LABELS_ASSET,
)

/** The asset fields are null in a build without models (`-Ptrouvetout.bundleModel=false`). */
private fun asset(
    path: String?,
    id: String,
    bytes: Long,
): BundledModelAsset? = path?.let { BundledModelAsset(it, id, bytes) }
