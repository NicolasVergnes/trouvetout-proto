package app.trouvetout.core.data

import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.db.toEvent
import app.trouvetout.core.data.projection.Projector
import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.EventJson
import app.trouvetout.core.domain.model.EventSubject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Outcome of [EventStore.append]. */
public data class AppendResult(
    val appended: Int,
    /** Events whose id was already in the journal (import merge, retries). */
    val skipped: Int,
    /** True when an event older than the journal head arrived and the projection was rebuilt from scratch. */
    val rebuilt: Boolean,
)

/**
 * The journal (A4). `append` is the only way data changes: it inserts the events, indexes their subjects and
 * updates the projection in one transaction. Events already known by id are ignored, which makes imports a
 * natural merge (A8). An event that sorts before the current head in the canonical `(at, deviceId, id)`
 * order (another device's history) triggers a full replay, so the projection always equals
 * `replay(all events sorted by at, deviceId, id)`. Importers append in large batches: one rebuild per batch.
 */
public class EventStore internal constructor(
    private val db: TrouveToutDb,
    private val projector: Projector,
    private val dispatcher: CoroutineDispatcher,
) {
    public suspend fun append(events: List<Event>): AppendResult = withContext(dispatcher) {
        db.transactionWithResult {
            val head = db.eventQueries.head().executeAsOneOrNull()?.let { JournalKey(it.at, it.device_id, it.id) }
            val fresh = ArrayList<Event>(events.size)
            var skipped = 0
            var outOfOrder = false
            for (event in events) {
                if (db.eventQueries.exists(event.id).executeAsOne() > 0L) {
                    skipped++
                    continue
                }
                insert(event)
                fresh += event
                if (head != null && JournalKey.of(event) < head) outOfOrder = true
            }
            if (outOfOrder) {
                replayAll()
            } else {
                fresh.sortedWith(journalOrder).forEach(projector::apply)
            }
            AppendResult(appended = fresh.size, skipped = skipped, rebuilt = outOfOrder)
        }
    }

    /** Rebuilds every projected table from the journal (import, repair, tests). */
    public suspend fun rebuildProjection() {
        withContext(dispatcher) { db.transaction { replayAll() } }
    }

    /** The whole journal in canonical order; prefer [forEachInOrder] for large journals (export). */
    public suspend fun all(): List<Event> =
        withContext(dispatcher) { db.eventQueries.allOrdered().executeAsList().map { it.toEvent() } }

    /** Streams the journal in canonical order, one page at a time, without loading it whole. */
    public suspend fun forEachInOrder(
        pageSize: Int = REPLAY_PAGE,
        action: (Event) -> Unit,
    ): Unit = withContext(dispatcher) { pages(pageSize, action) }

    public suspend fun count(): Long = withContext(dispatcher) { db.eventQueries.count().executeAsOne() }

    /** History of one record (A5), oldest first. */
    public suspend fun historyOf(subject: EventSubject): List<Event> = withContext(dispatcher) {
        db.eventSubjectQueries.eventsFor(subject.type.name, subject.id).executeAsList().map { it.toEvent() }
    }

    internal suspend fun <T> read(block: (TrouveToutDb) -> T): T = withContext(dispatcher) { block(db) }

    private fun insert(event: Event) {
        db.eventQueries.insert(
            event.id,
            event.at.toEpochMilliseconds(),
            event.deviceId,
            event.actor,
            event.type,
            EventJson.encodePayload(event.payload),
            event.source.name,
            event.rawInput,
        )
        for (subject in event.payload.subjects) {
            db.eventSubjectQueries.insert(event.id, subject.type.name, subject.id)
        }
    }

    private fun replayAll() {
        projector.reset()
        pages(REPLAY_PAGE, projector::apply)
    }

    private fun pages(
        pageSize: Int,
        action: (Event) -> Unit,
    ) {
        var offset = 0L
        while (true) {
            val page = db.eventQueries.pageOrdered(pageSize.toLong(), offset).executeAsList()
            page.forEach { action(it.toEvent()) }
            if (page.size < pageSize) break
            offset += page.size
        }
    }

    /** Canonical journal position of an event. */
    private data class JournalKey(
        val at: Long,
        val deviceId: String,
        val id: String,
    ) : Comparable<JournalKey> {
        override fun compareTo(other: JournalKey): Int =
            compareValuesBy(this, other, { it.at }, { it.deviceId }, { it.id })

        companion object {
            fun of(event: Event) = JournalKey(event.at.toEpochMilliseconds(), event.deviceId, event.id)
        }
    }

    private companion object {
        const val REPLAY_PAGE = 2_000
        val journalOrder: Comparator<Event> = compareBy<Event>({ it.at }, { it.deviceId }, { it.id })
    }
}
