package app.trouvetout.core.data.export

import app.trouvetout.core.data.EventStore
import app.trouvetout.core.data.photo.PhotoStore
import app.trouvetout.core.domain.ids.Ulid
import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.EventJson
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.platform.FileStore
import app.trouvetout.core.platform.use
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/** Why an archive was refused as a whole (per-entry problems are counted in [ImportReport] instead). */
public enum class ImportRejection {
    FILE_NOT_FOUND,
    NOT_AN_ARCHIVE,
    MANIFEST_MISSING,
    MANIFEST_INVALID,
    FORMAT_TOO_NEW,
    EVENTS_MISSING,
    EVENTS_TOO_LARGE,
    TOO_MANY_EVENTS,
}

public data class ImportReport(
    val eventsRead: Int,
    val eventsAppended: Int,
    /** Already in the journal (merge). */
    val eventsSkipped: Int,
    /** Kept in the journal but not understood by this version (newer type). */
    val unknownPayloads: Int,
    /** Lines that were not a valid event record and were dropped. */
    val invalidLines: Int,
    val photosImported: Int,
    val photosSkipped: Int,
    /** Photo entries that were not decodable images or carried an invalid name. */
    val photosRejected: Int,
    /** Archive entries this version does not know (ignored). */
    val ignoredEntries: Int,
)

public sealed interface ImportResult {
    public data class Done(
        val report: ImportReport,
    ) : ImportResult

    public data class Rejected(
        val reason: ImportRejection,
    ) : ImportResult
}

/**
 * A8: imports an export archive that the host app copied under the private data directory (document picker).
 * Validation is strict and bounded (entry count, per-entry sizes, entry names, photo names, re-encoding of every
 * photo); the journal merges by event id and the projection is rebuilt once at the end. The source file is a
 * foreign input: nothing from it is copied to disk untouched, and nothing from it is logged.
 */
public class ImportService internal constructor(
    private val files: FileStore,
    private val eventStore: EventStore,
    private val photoStore: PhotoStore,
    private val dispatcher: CoroutineDispatcher,
) {
    public suspend fun import(relativePath: String): ImportResult = withContext(dispatcher) {
        val source =
            files.source(relativePath) ?: return@withContext ImportResult.Rejected(ImportRejection.FILE_NOT_FOUND)
        source.use { src ->
            val reader =
                try {
                    ZipReader(src, MAX_ENTRIES)
                } catch (_: IllegalArgumentException) {
                    return@withContext ImportResult.Rejected(ImportRejection.NOT_AN_ARCHIVE)
                }
            val byName = reader.records.associateBy { it.name }
            val manifestRecord = byName[ExportEntries.MANIFEST]
                ?: return@withContext ImportResult.Rejected(ImportRejection.MANIFEST_MISSING)
            val manifest = readManifest(reader, manifestRecord)
                ?: return@withContext ImportResult.Rejected(ImportRejection.MANIFEST_INVALID)
            if (manifest.formatVersion > EXPORT_FORMAT_VERSION) {
                return@withContext ImportResult.Rejected(ImportRejection.FORMAT_TOO_NEW)
            }
            val eventsRecord = byName[ExportEntries.EVENTS]
                ?: return@withContext ImportResult.Rejected(ImportRejection.EVENTS_MISSING)
            if (eventsRecord.size >
                MAX_EVENTS_BYTES
            ) {
                return@withContext ImportResult.Rejected(ImportRejection.EVENTS_TOO_LARGE)
            }

            val events = importEvents(reader.read(eventsRecord))
                ?: return@withContext ImportResult.Rejected(ImportRejection.TOO_MANY_EVENTS)
            val photos = importPhotos(reader)
            val known = setOf(ExportEntries.MANIFEST, ExportEntries.EVENTS, ExportEntries.SNAPSHOT)
            val ignored = reader.records.count {
                it.name !in known &&
                    !it.name.startsWith("${ExportEntries.PHOTOS_DIR}/")
            }
            ImportResult.Done(
                ImportReport(
                    eventsRead = events.read,
                    eventsAppended = events.appended,
                    eventsSkipped = events.skipped,
                    unknownPayloads = events.unknown,
                    invalidLines = events.invalid,
                    photosImported = photos.imported,
                    photosSkipped = photos.skipped,
                    photosRejected = photos.rejected,
                    ignoredEntries = ignored,
                ),
            )
        }
    }

    private fun readManifest(
        reader: ZipReader,
        record: ZipRecord,
    ): Manifest? {
        if (record.size > MAX_MANIFEST_BYTES) return null
        return try {
            EventJson.format.decodeFromString(Manifest.serializer(), reader.read(record).decodeToString())
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private class EventsOutcome(
        val read: Int,
        val appended: Int,
        val skipped: Int,
        val unknown: Int,
        val invalid: Int,
    )

    /** Null when the archive declares more events than the policy allows. */
    private suspend fun importEvents(jsonl: ByteArray): EventsOutcome? {
        val lines = jsonl.decodeToString().lineSequence().filter { it.isNotBlank() }
        var read = 0
        var appended = 0
        var skipped = 0
        var unknown = 0
        var invalid = 0
        val batch = ArrayList<Event>(BATCH)

        suspend fun flush() {
            if (batch.isEmpty()) return
            val result = eventStore.appendWithoutProjection(batch)
            appended += result.appended
            skipped += result.skipped
            batch.clear()
        }
        for (line in lines) {
            read++
            if (read > MAX_EVENTS) return null
            val event = parseLine(line)
            if (event == null) {
                invalid++
                continue
            }
            if (event.payload is EventPayload.Unknown) unknown++
            batch += event
            if (batch.size == BATCH) flush()
        }
        flush()
        if (appended > 0) eventStore.rebuildProjection()
        return EventsOutcome(read, appended, skipped, unknown, invalid)
    }

    private fun parseLine(line: String): Event? {
        if (line.length > MAX_LINE_CHARS) return null
        val record =
            try {
                EventJson.format.decodeFromString(EventRecord.serializer(), line)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        val valid =
            Ulid.isValid(record.id) &&
                record.deviceId.isNotBlank() &&
                record.deviceId.length <= MAX_ID_CHARS &&
                record.actor.length <= MAX_ID_CHARS &&
                record.type.isNotBlank() &&
                record.at in MIN_AT..MAX_AT
        return if (valid) record.toEvent() else null
    }

    private class PhotosOutcome(
        val imported: Int,
        val skipped: Int,
        val rejected: Int,
    )

    /** Full-size entries only; thumbnails are regenerated from the re-encoded photo, never copied. */
    private suspend fun importPhotos(reader: ZipReader): PhotosOutcome {
        var imported = 0
        var skipped = 0
        var rejected = 0
        val prefix = "${ExportEntries.PHOTOS_DIR}/"
        for (record in reader.records) {
            val name = record.name.removePrefix(prefix)
            if (record.name.startsWith(prefix) && !name.endsWith(THUMB_SUFFIX)) {
                when (importPhoto(reader, record, name)) {
                    PhotoOutcome.IMPORTED -> imported++
                    PhotoOutcome.SKIPPED -> skipped++
                    PhotoOutcome.REJECTED -> rejected++
                }
            }
        }
        return PhotosOutcome(imported, skipped, rejected)
    }

    private enum class PhotoOutcome { IMPORTED, SKIPPED, REJECTED }

    private suspend fun importPhoto(
        reader: ZipReader,
        record: ZipRecord,
        name: String,
    ): PhotoOutcome {
        if (!PhotoStore.isValidFileName(name) || record.size > MAX_PHOTO_BYTES) return PhotoOutcome.REJECTED
        if (photoStore.exists(name)) return PhotoOutcome.SKIPPED
        val bytes = runCatching { reader.read(record) }.getOrElse { failure ->
            if (failure !is IllegalArgumentException) throw failure
            return PhotoOutcome.REJECTED
        }
        return if (photoStore.importPhoto(name, bytes) != null) PhotoOutcome.IMPORTED else PhotoOutcome.REJECTED
    }

    private companion object {
        const val MAX_ENTRIES = 200_000
        const val MAX_MANIFEST_BYTES = 1024 * 1024
        const val MAX_EVENTS_BYTES = 512 * 1024 * 1024
        const val MAX_EVENTS = 2_000_000
        const val MAX_LINE_CHARS = 64 * 1024
        const val MAX_ID_CHARS = 64
        const val MAX_PHOTO_BYTES = 48 * 1024 * 1024
        const val BATCH = 1_000
        const val THUMB_SUFFIX = "_t.jpg"
        const val MIN_AT = 0L
        const val MAX_AT = 32_503_680_000_000L // year 3000
    }
}
