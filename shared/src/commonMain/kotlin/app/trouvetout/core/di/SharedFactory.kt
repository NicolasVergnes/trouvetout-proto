package app.trouvetout.core.di

import app.cash.sqldelight.db.SqlDriver
import app.trouvetout.core.AppInfo
import app.trouvetout.core.data.DeviceIdentity
import app.trouvetout.core.data.EventStore
import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.db.openDatabase
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
import app.trouvetout.core.log.LogLevel
import app.trouvetout.core.platform.createFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Composition root of the core: manual injection, one instance per process (ADR-012).
 * The database is opened on first use; every collaborator is created once.
 */
public class SharedFactory(
    public val config: SharedConfig,
) {
    public val appInfo: AppInfo = AppInfo

    private var openedDriver: SqlDriver? = null
    private val driver: SqlDriver by lazy {
        check(config.storageReady) { "storage not ready" }
        config.driverFactory.create().also { openedDriver = it }
    }
    private val database: TrouveToutDb by lazy { openDatabase(driver) }

    /** Local installation identity, stable across launches (ADR-005). */
    public val deviceId: String by lazy { DeviceIdentity.ensure(database, config.clock, config.random) }

    public val eventStore: EventStore by lazy { EventStore(database, Projector(database), config.dispatchers.io) }

    private val commandContext: CommandContext by lazy {
        CommandContext(eventStore, MonotonicUlidGenerator(config.clock, config.random), config.clock, deviceId)
    }
    public val itemCommands: ItemCommands by lazy { ItemCommands(commandContext) }
    public val placeCommands: PlaceCommands by lazy { PlaceCommands(commandContext) }
    public val containerCommands: ContainerCommands by lazy { ContainerCommands(commandContext) }
    public val attachmentCommands: AttachmentCommands by lazy { AttachmentCommands(commandContext) }

    public val itemRepository: ItemRepository by lazy { ItemRepository(database, config.dispatchers) }
    public val placeRepository: PlaceRepository by lazy { PlaceRepository(database, config.dispatchers) }
    public val containerRepository: ContainerRepository by lazy { ContainerRepository(database, config.dispatchers) }

    private val photoIds: MonotonicUlidGenerator by lazy { MonotonicUlidGenerator(config.clock, config.random) }
    private val photoIdLock = Mutex()
    public val photoStore: PhotoStore by lazy {
        PhotoStore(
            files = createFileStore(config.dataDirectory),
            codec = config.imageCodec,
            newId = { photoIdLock.withLock { photoIds.next() } },
            dispatcher = config.dispatchers.default,
        )
    }

    /**
     * Opens the database and settles the device identity off the main thread, so the first capture does not
     * pay the schema creation. Returns false (and logs the failure class, never a path) when the store cannot
     * be opened: storage not ready, disk full, corrupt file. Safe to call several times.
     */
    public suspend fun warmUp(): Boolean = withContext(config.dispatchers.io) {
        val attempt = runCatching { deviceId }
        attempt.exceptionOrNull()?.let { failure ->
            if (failure is CancellationException) throw failure
            config.logger.log(LogLevel.ERROR, TAG, "storage unavailable: ${failure::class.simpleName}")
        }
        attempt.isSuccess
    }

    /** Releases held resources (database connection, loaded models). */
    public fun close() {
        openedDriver?.close()
        openedDriver = null
    }

    private companion object {
        const val TAG = "SharedFactory"
    }
}
