package app.trouvetout.core.di

import app.cash.sqldelight.db.SqlDriver
import app.trouvetout.core.AppInfo
import app.trouvetout.core.capture.CaptureService
import app.trouvetout.core.data.DeviceIdentity
import app.trouvetout.core.data.EventStore
import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.db.openDatabase
import app.trouvetout.core.data.export.ExportIdentity
import app.trouvetout.core.data.export.ExportService
import app.trouvetout.core.data.export.ImportService
import app.trouvetout.core.data.photo.BinService
import app.trouvetout.core.data.photo.PhotoStore
import app.trouvetout.core.data.projection.Projector
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
import app.trouvetout.core.log.LogLevel
import app.trouvetout.core.platform.FileStore
import app.trouvetout.core.platform.createFileStore
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
    public val itemCommands: ItemCommands by lazy { ItemCommands(commandContext) }
    public val placeCommands: PlaceCommands by lazy { PlaceCommands(commandContext) }
    public val containerCommands: ContainerCommands by lazy { ContainerCommands(commandContext) }
    public val attachmentCommands: AttachmentCommands by lazy { AttachmentCommands(commandContext) }
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
        )
    }

    public val itemRepository: ItemRepository by lazy { ItemRepository(database, config.dispatchers) }
    public val placeRepository: PlaceRepository by lazy { PlaceRepository(database, config.dispatchers) }
    public val containerRepository: ContainerRepository by lazy { ContainerRepository(database, config.dispatchers) }

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

    /**
     * Opens the database and settles the device identity off the main thread, so the first capture does not
     * pay the schema creation. Returns false (and logs the failure class, never a path) when the store cannot
     * be opened: storage not ready, disk full, corrupt file. Safe to call several times.
     */
    public suspend fun warmUp(): Boolean = withContext(config.dispatchers.io) {
        if (wiped) return@withContext false
        val attempt = runCatching { deviceId }
        attempt.exceptionOrNull()?.let { failure ->
            if (failure is CancellationException) throw failure
            config.logger.log(LogLevel.ERROR, TAG, "storage unavailable: ${failure::class.simpleName}")
        }
        attempt.isSuccess
    }

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
            }
        attempt.exceptionOrNull()?.let { failure ->
            if (failure is CancellationException) throw failure
            config.logger.log(LogLevel.WARN, TAG, "housekeeping failed: ${failure::class.simpleName}")
        }
    }

    /**
     * H6 (data part): closes the database and deletes every file the core wrote — database (with its WAL and
     * shm), photos, exports, imports. The host completes the wipe (keys, preferences, scheduled notifications),
     * then builds a fresh [SharedFactory]: this one refuses to reopen. Returns the directories removed.
     */
    public suspend fun wipeAllData(): List<String> = withContext(config.dispatchers.io) {
        close()
        wiped = true
        DATA_DIRECTORIES.filter { files.deleteDirectory(it) }
    }

    /** Scope for the host's flow watchers (Swift bridge): callbacks arrive on the main dispatcher; cancelled by [close]. */
    public val uiScope: CoroutineScope = CoroutineScope(SupervisorJob() + config.dispatchers.main)

    /** Releases held resources (database connection, loaded models). */
    public fun close() {
        uiScope.cancel()
        openedDriver?.close()
        openedDriver = null
    }

    public companion object {
        private const val TAG = "SharedFactory"

        /** Everything the core writes lives in these subdirectories of `dataDirectory`. */
        public val DATA_DIRECTORIES: List<String> =
            listOf(DATABASE_DIRECTORY, PhotoStore.DIRECTORY, ExportService.DIRECTORY, IMPORT_DIRECTORY)
        public const val DATABASE_DIRECTORY: String = "db"
        public const val DATABASE_FILE: String = "trouvetout.db"
        public const val IMPORT_DIRECTORY: String = "imports"
    }
}
