package app.trouvetout.core.domain.ids

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class UlidTest {
    private class FixedClock(
        var millis: Long,
    ) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
    }

    @Test
    fun should_produce_26_crockford_characters() {
        val ulid = Ulid.generate(1_700_000_000_000L, Random(1))
        assertEquals(26, ulid.length)
        assertTrue(ulid.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" }, ulid)
        assertTrue(Ulid.isValid(ulid))
    }

    @Test
    fun should_round_trip_the_timestamp() {
        val time = 1_700_000_000_123L
        assertEquals(time, Ulid.timestampOf(Ulid.generate(time, Random(7))))
        assertEquals(0L, Ulid.timestampOf(Ulid.encode(0L, 0L, 0L)))
    }

    @Test
    fun should_sort_by_time_lexicographically() {
        val earlier = Ulid.generate(1_000L, Random(3))
        val later = Ulid.generate(1_001L, Random(3))
        assertTrue(earlier < later)
    }

    @Test
    fun should_reject_malformed_values() {
        assertFalse(Ulid.isValid(""))
        assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FA"))
        assertFalse(Ulid.isValid("81ARZ3NDEKTSV4RRFFQ69G5FAV")) // first char > 7 overflows 48 bits
        assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAU")) // U is not in the alphabet
    }

    @Test
    fun should_be_strictly_increasing_within_the_same_millisecond() {
        val generator = MonotonicUlidGenerator(FixedClock(5_000L), Random(11))
        val ids = List(1_000) { generator.next() }
        assertEquals(ids, ids.sorted())
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { Ulid.timestampOf(it) == 5_000L })
    }

    @Test
    fun should_follow_the_clock_when_it_moves_forward() {
        val clock = FixedClock(5_000L)
        val generator = MonotonicUlidGenerator(clock, Random(11))
        val first = generator.next()
        clock.millis = 6_000L
        val second = generator.next()
        assertTrue(first < second)
        assertEquals(6_000L, Ulid.timestampOf(second))
    }

    @Test
    fun should_stay_monotonic_when_the_clock_goes_backwards() {
        val clock = FixedClock(5_000L)
        val generator = MonotonicUlidGenerator(clock, Random(11))
        val first = generator.next()
        clock.millis = 4_000L
        val second = generator.next()
        assertTrue(first < second)
    }

    @Test
    fun should_not_collide_over_many_generations() {
        val ids = HashSet<String>()
        repeat(20_000) { ids += Ulid.generate(1_700_000_000_000L + it / 10, Random.Default) }
        assertEquals(20_000, ids.size)
    }
}
