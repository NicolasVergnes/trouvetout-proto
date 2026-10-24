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
 * natural merge (A8). Events that arrive out of order (another device's history) trigger a full replay so
 * the projection always equals `replay(all events sorted by at, deviceId, id)`.
 */
public class EventStore internal constructor(
    private val db: TrouveToutDb,
    private val projector: Projector,
    private val dispatcher: CoroutineDispatcher,
) {
    public suspend fun append(events: List<Event>): AppendResult = withContext(dispatcher) {
        db.transactionWithResult {
            val headAt = db.eventQueries.lastAt().executeAsOneOrNull()?.last_at ?: Long.MIN_VALUE
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
                if (event.at.toEpochMilliseconds() < headAt) outOfOrder = true
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

    public suspend fun all(): List<Event> = withContext(dispatcher) {
        db.eventQueries.allOrdered().executeAsList().map { it.toEvent() }
    }

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
        db.eventQueries.allOrdered().executeAsList().forEach { projector.apply(it.toEvent()) }
    }

    private companion object {
        val journalOrder: Comparator<Event> = compareBy<Event>({ it.at }, { it.deviceId }, { it.id })
    }
}
