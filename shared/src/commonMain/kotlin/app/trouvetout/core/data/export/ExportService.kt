package app.trouvetout.core.data.export

import app.trouvetout.core.data.EventStore
import app.trouvetout.core.data.db.SCHEMA_VERSION
import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.db.toContainer
import app.trouvetout.core.data.db.toItem
import app.trouvetout.core.data.db.toLocationState
import app.trouvetout.core.data.db.toPhoto
import app.trouvetout.core.data.db.toPlace
import app.trouvetout.core.data.photo.PhotoStore
import app.trouvetout.core.data.repository.LocationResolver
import app.trouvetout.core.domain.model.Container
import app.trouvetout.core.domain.model.EventJson
import app.trouvetout.core.domain.model.Item
import app.trouvetout.core.domain.model.LocationState
import app.trouvetout.core.domain.model.Photo
import app.trouvetout.core.domain.model.Place
import app.trouvetout.core.platform.FileStore
import app.trouvetout.core.platform.use
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Where an export landed, for the host app's share sheet. */
public data class ExportResult(
    val relativePath: String,
    val absolutePath: String,
    val counts: ExportCounts,
)

/** What the manifest says about the producer. */
public class ExportIdentity(
    public val deviceId: () -> String,
    public val appVersion: String,
)

/**
 * A7: writes `trouvetout-export-<utc timestamp>.zip` under `exports/` in the private data directory —
 * `manifest.json`, `events.jsonl` (the journal, source of truth), `snapshot.json` (the projection, for humans)
 * and `photos/`. Nothing leaves the device: the host app hands the file to the system share sheet, and only
 * on the user's action. The archive is streamed; one entry at a time is in memory.
 */
public class ExportService internal constructor(
    private val files: FileStore,
    private val db: TrouveToutDb,
    private val eventStore: EventStore,
    private val photoStore: PhotoStore,
    private val clock: Clock,
    private val identity: ExportIdentity,
    private val dispatcher: CoroutineDispatcher,
) {
    public suspend fun export(): ExportResult = withContext(dispatcher) {
        val now = clock.now().toEpochMilliseconds()
        val relativePath = "$DIRECTORY/$PREFIX${utcTimestamp(now)}.zip"
        val photoFiles = photoStore.listFiles().filter { PhotoStore.isValidFileName(it) }
        val counts =
            ExportCounts(
                events = db.eventQueries.count().executeAsOne(),
                items = db.itemQueries.countActive().executeAsOne(),
                places = db.placeQueries.countActive().executeAsOne(),
                containers = db.containerQueries.countActive().executeAsOne(),
                photos = photoFiles.count { !it.endsWith(THUMB_SUFFIX) }.toLong(),
            )
        val manifest =
            Manifest(
                appVersion = identity.appVersion,
                schemaVersion = SCHEMA_VERSION,
                exportedAt = now,
                deviceId = identity.deviceId(),
                counts = counts,
            )
        files.sink(relativePath).use { sink ->
            val zip = ZipWriter(sink)
            zip.add(ExportEntries.MANIFEST, json(Manifest.serializer(), manifest))
            zip.add(ExportEntries.EVENTS, eventsJsonl())
            zip.add(ExportEntries.SNAPSHOT, json(Snapshot.serializer(), snapshot()))
            for (name in photoFiles) {
                val bytes = photoStore.read(name) ?: continue
                zip.add("${ExportEntries.PHOTOS_DIR}/$name", bytes, store = true)
            }
            zip.finish()
        }
        ExportResult(relativePath, files.absolutePath(relativePath), counts)
    }

    /** Deletes export files older than [maxAgeMillis] (the share sheet copies them; they need not stay). */
    public suspend fun purgeOldExports(maxAgeMillis: Long = DEFAULT_RETENTION_MILLIS): Int = withContext(dispatcher) {
        val cutoff = utcTimestamp(clock.now().toEpochMilliseconds() - maxAgeMillis)
        files
            .list(DIRECTORY)
            .filter { it.startsWith(PREFIX) && it.removePrefix(PREFIX).removeSuffix(".zip") < cutoff }
            .count { files.delete("$DIRECTORY/$it") }
    }

    private fun <T> json(
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
    ): ByteArray = EventJson.format.encodeToString(serializer, value).encodeToByteArray()

    private suspend fun eventsJsonl(): ByteArray {
        val out = ByteWriter()
        val newline = "\n".encodeToByteArray()
        eventStore.forEachInOrder { event ->
            out.bytes(json(EventRecord.serializer(), EventRecord.of(event)))
            out.bytes(newline)
        }
        return out.toByteArray()
    }

    private fun snapshot(): Snapshot {
        val places = db.placeQueries.selectAll().executeAsList().map { it.toPlace() }
        val containers = db.containerQueries.selectActive().executeAsList().map { it.toContainer() }
        val resolver = LocationResolver(places, containers)
        return Snapshot(
            items = db.itemQueries.selectAll().executeAsList().map { it.toItem().toSnapshot() },
            places = places.map { it.toSnapshot() },
            containers = containers.map { it.toSnapshot() },
            locations = db.locationStateQueries.selectAll().executeAsList().map {
                it.toLocationState().toSnapshot(resolver)
            },
            photos = db.photoQueries.selectAll().executeAsList().map { it.toPhoto().toSnapshot() },
        )
    }

    private fun Item.toSnapshot() = SnapshotItem(
        id = id,
        name = name,
        description = description,
        tags = tags,
        quantity = quantity,
        unit = unit,
        sensitive = sensitive,
        pinned = pinned,
        archived = archived,
        createdAt = createdAt.toEpochMilliseconds(),
        deletedAt = deletedAt?.toEpochMilliseconds(),
    )

    private fun Place.toSnapshot() = SnapshotPlace(
        id = id,
        name = name,
        parentId = parentId,
        kind = kind.name,
        aliases = aliases,
        createdAt = createdAt.toEpochMilliseconds(),
        deletedAt = deletedAt?.toEpochMilliseconds(),
        mergedInto = mergedInto,
    )

    private fun Container.toSnapshot() = SnapshotContainer(
        id = id,
        label = label,
        number = number,
        placeId = placeId,
        createdAt = createdAt.toEpochMilliseconds(),
    )

    private fun LocationState.toSnapshot(resolver: LocationResolver) = SnapshotLocation(
        itemId = itemId,
        placeId = placeId,
        containerId = containerId,
        state = status.name,
        lentTo = lentTo,
        since = since.toEpochMilliseconds(),
        path = resolver.resolve(this).labels,
    )

    private fun Photo.toSnapshot() = SnapshotPhoto(
        id = id,
        ownerType = ownerType.name,
        ownerId = ownerId,
        fileName = fileName,
        thumbName = thumbName,
        width = width,
        height = height,
        sha256 = sha256,
        createdAt = createdAt.toEpochMilliseconds(),
        detachedAt = detachedAt?.toEpochMilliseconds(),
    )

    public companion object {
        public const val DIRECTORY: String = "exports"
        private const val PREFIX = "trouvetout-export-"
        private const val THUMB_SUFFIX = "_t.jpg"
        private const val DEFAULT_RETENTION_MILLIS = 24L * 60 * 60 * 1000
    }
}
