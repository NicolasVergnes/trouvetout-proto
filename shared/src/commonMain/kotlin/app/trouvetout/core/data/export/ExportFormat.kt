package app.trouvetout.core.data.export

import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.EventJson
import app.trouvetout.core.domain.model.EventSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

/** Format version written in `manifest.json`; only grows when a reader must change (N9). */
public const val EXPORT_FORMAT_VERSION: Int = 1

/** Entry names inside the archive (see docs/format-export.md). */
public object ExportEntries {
    public const val MANIFEST: String = "manifest.json"
    public const val EVENTS: String = "events.jsonl"
    public const val SNAPSHOT: String = "snapshot.json"
    public const val PHOTOS_DIR: String = "photos"
}

@Serializable
public data class ExportCounts(
    val events: Long,
    val items: Long,
    val places: Long,
    val containers: Long,
    val photos: Long,
)

@Serializable
public data class Manifest(
    val formatVersion: Int = EXPORT_FORMAT_VERSION,
    val appVersion: String,
    val schemaVersion: Long,
    /** Milliseconds since the epoch, UTC. */
    val exportedAt: Long,
    val deviceId: String,
    val counts: ExportCounts,
)

/** One line of `events.jsonl`; the payload is kept as a JSON object so unknown types survive untouched. */
@Serializable
public data class EventRecord(
    val id: String,
    val at: Long,
    val deviceId: String,
    val actor: String = Event.DEFAULT_ACTOR,
    val type: String,
    val payload: JsonObject,
    val source: String,
    val rawInput: String? = null,
) {
    public companion object {
        public fun of(event: Event): EventRecord = EventRecord(
            id = event.id,
            at = event.at.toEpochMilliseconds(),
            deviceId = event.deviceId,
            actor = event.actor,
            type = event.type,
            payload = EventJson.format.parseToJsonElement(EventJson.encodePayload(event.payload)).jsonObject,
            source = event.source.name,
            rawInput = event.rawInput,
        )
    }

    /** Domain event; an unreadable payload becomes `EventPayload.Unknown` (never throws). */
    public fun toEvent(): Event = Event(
        id = id,
        at = Instant.fromEpochMilliseconds(at),
        deviceId = deviceId,
        actor = actor,
        payload = EventJson.decodePayload(payload.toString()),
        source = EventSource.parse(source),
        rawInput = rawInput,
    )
}

// --- snapshot.json: the projection, for reading without the app (never imported) ---

@Serializable
public data class SnapshotItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val quantity: Double? = null,
    val unit: String? = null,
    val sensitive: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
public data class SnapshotPlace(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val kind: String,
    val aliases: List<String> = emptyList(),
    val createdAt: Long,
    val deletedAt: Long? = null,
    val mergedInto: String? = null,
)

@Serializable
public data class SnapshotContainer(
    val id: String,
    val label: String,
    val number: Long? = null,
    val placeId: String,
    val createdAt: Long,
)

@Serializable
public data class SnapshotLocation(
    val itemId: String,
    val placeId: String? = null,
    val containerId: String? = null,
    val state: String,
    val lentTo: String? = null,
    val since: Long,
    /** Human-readable path, root place first, container last. */
    val path: List<String> = emptyList(),
)

@Serializable
public data class SnapshotPhoto(
    val id: String,
    val ownerType: String,
    val ownerId: String,
    val fileName: String,
    val thumbName: String,
    val width: Int,
    val height: Int,
    val sha256: String,
    val createdAt: Long,
    val detachedAt: Long? = null,
)

@Serializable
public data class Snapshot(
    val items: List<SnapshotItem>,
    val places: List<SnapshotPlace>,
    val containers: List<SnapshotContainer>,
    val locations: List<SnapshotLocation>,
    val photos: List<SnapshotPhoto>,
)

/** `YYYYMMDD-HHMMSS` in UTC from epoch milliseconds, without a calendar library (proleptic Gregorian). */
public fun utcTimestamp(epochMillis: Long): String {
    val totalSeconds = Math.floorDiv(epochMillis, MILLIS_PER_SECOND)
    val days = Math.floorDiv(totalSeconds, SECONDS_PER_DAY)
    val secondsOfDay = Math.floorMod(totalSeconds, SECONDS_PER_DAY)
    val (year, month, day) = civilFromDays(days)
    val hours = secondsOfDay / SECONDS_PER_HOUR
    val minutes = secondsOfDay % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = secondsOfDay % SECONDS_PER_MINUTE
    return "${year.pad(YEAR_DIGITS)}${month.pad(FIELD_DIGITS)}${day.pad(FIELD_DIGITS)}-" +
        "${hours.pad(FIELD_DIGITS)}${minutes.pad(FIELD_DIGITS)}${seconds.pad(FIELD_DIGITS)}"
}

private fun Long.pad(width: Int) = toString().padStart(width, '0')

private const val YEAR_DIGITS = 4
private const val FIELD_DIGITS = 2

/** Howard Hinnant's days-to-civil algorithm. */
@Suppress("MagicNumber")
private fun civilFromDays(daysSinceEpoch: Long): Triple<Long, Long, Long> {
    val z = daysSinceEpoch + 719_468
    val era = Math.floorDiv(z, 146_097)
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val mp = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yearOfEra + era * 400 + (if (month <= 2) 1 else 0)
    return Triple(year, month, day)
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L

/** Common-Kotlin floor division helpers (`java.lang.Math` is not available in commonMain). */
private object Math {
    fun floorDiv(
        a: Long,
        b: Long,
    ): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
    }

    fun floorMod(
        a: Long,
        b: Long,
    ): Long = a - floorDiv(a, b) * b
}
