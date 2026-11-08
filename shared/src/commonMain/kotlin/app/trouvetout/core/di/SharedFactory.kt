package app.trouvetout.core.di

import app.cash.sqldelight.db.SqlDriver
import app.trouvetout.core.AppInfo
import app.trouvetout.core.capture.CaptureService
import app.trouvetout.core.data.DeviceIdentity
import app.trouvetout.core.data.EventStore
import app.trouvetout.core.data.backup.BackupService
import app.trouvetout.core.data.db.DatabaseKeyLostException
import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.db.openDatabase
import app.trouvetout.core.data.export.ExportIdentity
import app.trouvetout.core.data.export.ExportService
import app.trouvetout.core.data.export.ImportService
import app.trouvetout.core.data.photo.BinService
import app.trouvetout.core.data.photo.PhotoAttacher
import app.trouvetout.core.data.photo.PhotoStore
import app.trouvetout.core.data.projection.Projector
import app.trouvetout.core.data.repository.BrowseRepository
import app.trouvetout.core.data.repository.ContainerRepository
import app.trouvetout.core.data.repository.ItemRepository
import app.trouvetout.core.data.repository.PlaceRepository
import app.trouvetout.core.domain.ids.MonotonicUlidGenerator
import app.trouvetout.core.domain.usecase.AttachmentCommands
import app.trouvetout.core.domain.usecase.CommandContext
import app.trouvetout.core.domain.usecase.ContainerCommands
import app.trouvetout.core.domain.usecase.ItemCommands
import app.trouvetout.core.domain.usecase.PlaceCommands
import app.trouvetout.core.domain.usecase.UndoService
import app.trouvetout.core.licensing.LicensingService
import app.trouvetout.core.log.LogLevel
import app.trouvetout.core.ml.ModelRuntime
import app.trouvetout.core.ml.ModelSettings
import app.trouvetout.core.platform.FileStore
import app.trouvetout.core.platform.createFileStore
import app.trouvetout.core.onboarding.WelcomeGuide
import app.trouvetout.core.privacy.AppLock
import app.trouvetout.core.privacy.PrivacySettings
import app.trouvetout.core.reminder.ReminderRepository
import app.trouvetout.core.search.PhotoFiles
import app.trouvetout.core.search.SearchService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Composition root of the core: manual injection, one instance per process (ADR-012).
 * The database is opened on first use; every collaborator is created once. After [wipeAllData] the instance
 * is spent: the host app creates a new one (and drops every cached state of its own).
 */
public class SharedFactory(
    public val config: SharedConfig,
) {
    public val appInfo: AppInfo = AppInfo

    private var openedDriver: SqlDriver? = null
    private var wiped = false
    private val driver: SqlDriver by lazy {
        check(config.storageReady) { "storage not ready" }
        check(!wiped) { "factory wiped" }
        config.driverFactory.create().also { openedDriver = it }
    }
    private val database: TrouveToutDb by lazy { openDatabase(driver) }
    private val files: FileStore by lazy { createFileStore(config.dataDirectory) }

    private val deviceIdentity: String by lazy { DeviceIdentity.ensure(database, config.clock, config.random) }

    /** Local installation identity, stable across launches (ADR-005). */
    public val deviceId: String
        get() {
            check(!wiped) { "factory wiped" }
            return deviceIdentity
        }

    public val eventStore: EventStore by lazy { EventStore(database, Projector(database), config.dispatchers.io) }

    private val commandContext: CommandContext by lazy {
        CommandContext(eventStore, MonotonicUlidGenerator(config.clock, config.random), config.clock, deviceId)
    }

    /** The trial and the last purchase state (lot 10). */
    public val licensing: LicensingService by lazy {
        LicensingService(database, config.licenseStore, config.clock, config.dispatchers.io)
    }
    public val itemCommands: ItemCommands by lazy { ItemCommands(commandContext) { licensing.canAddItems() } }
    public val placeCommands: PlaceCommands by lazy { PlaceCommands(commandContext) }
    public val containerCommands: ContainerCommands by lazy { ContainerCommands(commandContext) }
    public val attachmentCommands: AttachmentCommands by lazy { AttachmentCommands(commandContext, config::timeZone) }
    public val undo: UndoService by lazy { UndoService(eventStore, commandContext, deviceId) }

    /** Text capture (lot 3): sentence → proposal → events. [captureLang] is the host's UI language. */
    public val capture: CaptureService by lazy {
        CaptureService(
            database,
            itemCommands,
            placeCommands,
            containerCommands,
            config.dispatchers.io,
            config.captureLang,
            locked = { appLock.locked.value },
            attachments = attachmentCommands,
            clock = config.clock,
            timeZone = config::timeZone,
            dayFirst = config.dayFirstDates,
            canAddItems = { licensing.canAddItems() },
        )
    }

    public val itemRepository: ItemRepository by lazy { ItemRepository(database, config.dispatchers) }

    /** Reminders (E2, E6) for the item sheet and the notifications the apps schedule (lot 9). */
    public val reminders: ReminderRepository by lazy { ReminderRepository(database, config.dispatchers) }
    public val placeRepository: PlaceRepository by lazy { PlaceRepository(database, config.dispatchers) }
    public val containerRepository: ContainerRepository by lazy { ContainerRepository(database, config.dispatchers) }

    /** Navigation by place and item-sheet extras (lot 4). */
    public val browse: BrowseRepository by lazy {
        BrowseRepository(database, config.dispatchers, itemRepository, eventStore)
    }

    /** The photo side of the model (B8, C10): device memory, bundled file, the user's two settings (ADR-022). */
    public val modelSettings: ModelSettings by lazy {
        ModelSettings(
            database,
            config.deviceInfo.totalRamBytes,
            bundled = config.textModel?.imageModelId != null,
            io = config.dispatchers.io,
        ).also { settings ->
            // Another model is loaded at the next use; the search computes what the new one needs.
            settings.onModelChange = { if (searchService.isInitialized()) searchService.value.modelChanged() }
        }
    }

    /** The privacy settings of this device (H3, keyboard learning). */
    public val privacySettings: PrivacySettings by lazy { PrivacySettings(database, config.dispatchers.io) }

    /** I10: whether the first-steps card is offered on the home screen. */
    public val welcome: WelcomeGuide by lazy { WelcomeGuide(database, config.dispatchers.io) }

    /** When to ask for the device owner (H3) and whether sensitive items may be shown (H4); one per process. */
    public val appLock: AppLock by lazy { AppLock(privacySettings, config.clock) }

    private val modelRuntime =
        lazy {
            ModelRuntime(
                config.textModel,
                config.logger,
                imagesWanted = { modelSettings.state.value.imagesActive },
                onImagesFailed = { modelSettings.imagesFailed() },
                visionLoading = { modelSettings.visionLoading(it) },
            )
        }
    private val searchService =
        lazy {
            SearchService(
                database,
                modelRuntime.value,
                config.searchTuning,
                config.clock,
                config.dispatchers,
                config.logger,
                PhotoFiles(config.imageCodec) { name -> photoStore.read(name) },
            )
        }

    /** Hybrid search (lots 6 and 7): keywords, meaning and photos, with a keyword-only fallback. */
    public val search: SearchService by searchService

    private val photoIds: MonotonicUlidGenerator by lazy { MonotonicUlidGenerator(config.clock, config.random) }
    private val photoIdLock = Mutex()
    public val photoStore: PhotoStore by lazy {
        PhotoStore(
            files = files,
            codec = config.imageCodec,
            newId = { photoIdLock.withLock { photoIds.next() } },
            dispatcher = config.dispatchers.default,
        )
    }
    public val photoAttacher: PhotoAttacher by lazy { PhotoAttacher(photoStore, attachmentCommands) }
    public val bin: BinService by lazy { BinService(database, photoStore, config.clock, config.dispatchers.io) }
    public val exportService: ExportService by lazy {
        ExportService(
            files,
            database,
            eventStore,
            photoStore,
            config.clock,
            ExportIdentity({ deviceId }, config.appVersion),
            config.dispatchers.io,
        )
    }
    public val importService: ImportService by lazy {
        ImportService(files, eventStore, photoStore, config.dispatchers.io)
    }

    /** The optional encrypted backup (A9, lot 10). */
    public val backup: BackupService by lazy {
        BackupService(
            database,
            files,
            exportService,
            importService,
            config.backupCrypto,
            config.clock,
            config.dispatchers.io,
        )
    }

    /**
     * Opens the database and settles the device identity off the main thread, so the first capture does not
     * pay the schema creation. Returns false (and logs the failure class, never a path) when the store cannot
     * be opened: storage not ready, disk full, corrupt file, lost key ([storeFailure] says which the user must
     * hear about). Safe to call several times.
     */
    public suspend fun warmUp(): Boolean = withContext(config.dispatchers.io) {
        if (wiped) return@withContext false
        // The privacy state is read here, off the main thread, before any screen or lifecycle callback needs it.
        val attempt =
            runCatching {
                deviceId.also { appLock }
                // The trial runs from the first launch, not from the first object.
                licensing.status()
            }
        attempt.exceptionOrNull()?.let { failure ->
            if (failure is CancellationException) throw failure
            config.logger.log(LogLevel.ERROR, TAG, "storage unavailable: ${failure::class.simpleName}")
            storeFailure = if (failure is DatabaseKeyLostException) StoreFailure.KEY_LOST else StoreFailure.UNAVAILABLE
        }
        if (attempt.isSuccess) storeFailure = null
        attempt.isSuccess
    }

    /** Why the last [warmUp] failed; null after a success. */
    public var storeFailure: StoreFailure? = null
        private set

    /**
     * Routine maintenance the host runs once per launch after a successful [warmUp], never on a critical path:
     * photo files of the 30-day bin (A13), exports older than a day, leftovers of an interrupted import.
     * Failures are logged by class only.
     */
    public suspend fun housekeeping(): Unit = withContext(config.dispatchers.io) {
        if (wiped || !config.storageReady) return@withContext
        val attempt =
            runCatching {
                bin.purgeExpired()
                exportService.purgeOldExports()
                files.deleteDirectory(IMPORT_DIRECTORY)
                backup.purge()
            }
        attempt.exceptionOrNull()?.let { failure ->
            if (failure is CancellationException) throw failure
            config.logger.log(LogLevel.WARN, TAG, "housekeeping failed: ${failure::class.simpleName}")
        }
    }

    /**
     * H6 (data part): closes the database and deletes every file the core wrote — database (with its WAL and
     * shm), photos, exports, imports — and the database key. The host completes the wipe (preferences, caches,
     * scheduled notifications), then builds a fresh [SharedFactory]: this one refuses to reopen. Returns the
     * directories removed. Works also when the store could not be opened (key lost, storage unavailable).
     */
    public suspend fun wipeAllData(): List<String> = withContext(config.dispatchers.io) {
        if (searchService.isInitialized()) searchService.value.shutdown()
        close()
        wiped = true
        try {
            DATA_DIRECTORIES.filter { files.deleteDirectory(it) }
        } finally {
            config.databaseKeys?.delete()
        }
    }

    /**
     * Scope for the host's flow watchers (Swift bridge): callbacks arrive on the main dispatcher; cancelled by
     * [close].
     */
    public val uiScope: CoroutineScope = CoroutineScope(SupervisorJob() + config.dispatchers.main)

    /** Releases held resources (database connection, loaded models). */
    public fun close() {
        uiScope.cancel()
        if (searchService.isInitialized()) searchService.value.close()
        if (modelRuntime.isInitialized()) modelRuntime.value.closeNow()
        openedDriver?.close()
        openedDriver = null
    }

    public companion object {
        private const val TAG = "SharedFactory"

        /** Everything the core writes lives in these subdirectories of `dataDirectory`. */
        public val DATA_DIRECTORIES: List<String> =
            listOf(
                DATABASE_DIRECTORY,
                PhotoStore.DIRECTORY,
                ExportService.DIRECTORY,
                IMPORT_DIRECTORY,
                BackupService.DIRECTORY,
            )
        public const val DATABASE_DIRECTORY: String = "db"
        public const val DATABASE_FILE: String = "trouvetout.db"
        public const val IMPORT_DIRECTORY: String = "imports"
    }
}

/** Why the store could not be opened, as the hosts explain it. */
public enum class StoreFailure {
    /** The database is encrypted and its key is gone (Keystore or Keychain reset): only a total wipe helps. */
    KEY_LOST,

    /** Storage not ready, disk full, unreadable file. */
    UNAVAILABLE,
}
