package app.trouvetout.core.data

import app.trouvetout.core.domain.ids.MonotonicUlidGenerator
import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/** A clock that advances by one second at each tick so events are strictly ordered. */
class TickingClock(
    startMillis: Long = 1_700_000_000_000L,
    private val stepMillis: Long = 1_000L,
) : Clock {
    var millis: Long = startMillis

    override fun now(): Instant = Instant.fromEpochMilliseconds(millis)

    fun tick(): Instant {
        millis += stepMillis
        return Instant.fromEpochMilliseconds(millis)
    }
}

/** Builds well-formed events for one device. */
class EventFixtures(
    val deviceId: String = "device-A",
    val clock: TickingClock = TickingClock(),
    seed: Int = 1,
) {
    private val ids = MonotonicUlidGenerator(clock, Random(seed))

    fun event(
        payload: EventPayload,
        source: EventSource = EventSource.TEXT,
        rawInput: String? = null,
    ): Event = Event(
        id = ids.next(),
        at = clock.tick(),
        deviceId = deviceId,
        payload = payload,
        source = source,
        rawInput = rawInput,
    )
}
