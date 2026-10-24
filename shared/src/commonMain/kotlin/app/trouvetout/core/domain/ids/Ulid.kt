package app.trouvetout.core.domain.ids

import kotlin.random.Random
import kotlin.time.Clock

/**
 * ULID: 26 Crockford base32 characters, 48 bits of milliseconds since the epoch followed by 80 random bits.
 * Lexicographic order follows creation time, which is what the journal and the merge order rely on.
 * Pure common implementation (no platform dependency).
 */
public object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val LENGTH = 26
    private const val TIME_CHARS = 10
    private const val RANDOM_HALF_CHARS = 8
    private const val BITS_PER_CHAR = 5
    private const val CHAR_MASK = 0x1FL
    private const val HALF_RANDOM_BITS = 40
    private const val HALF_RANDOM_MASK = (1L shl HALF_RANDOM_BITS) - 1
    private const val MAX_TIME = (1L shl 48) - 1
    private const val MAX_FIRST_CHAR_INDEX = 7
    private val DECODE: IntArray =
        IntArray(128) { -1 }.also { table ->
            ALPHABET.forEachIndexed { index, c ->
                table[c.code] = index
                table[c.lowercaseChar().code] = index
            }
            // Crockford aliases
            for (c in "Oo") table[c.code] = 0
            for (c in "IiLl") table[c.code] = 1
        }

    /** A fresh ULID for [timeMillis] with random entropy from [random]. */
    public fun generate(
        timeMillis: Long,
        random: Random = Random.Default,
    ): String = encode(timeMillis, random.nextLong() and HALF_RANDOM_MASK, random.nextLong() and HALF_RANDOM_MASK)

    /** Milliseconds since the epoch encoded in [ulid]. */
    public fun timestampOf(ulid: String): Long {
        require(isValid(ulid)) { "Not a ULID" }
        var time = 0L
        for (i in 0 until TIME_CHARS) {
            time = (time shl BITS_PER_CHAR) or DECODE[ulid[i].code].toLong()
        }
        return time
    }

    public fun isValid(value: String): Boolean = value.length == LENGTH &&
        value.all { it.code < DECODE.size && DECODE[it.code] >= 0 } &&
        DECODE[value[0].code] <= MAX_FIRST_CHAR_INDEX

    internal fun encode(
        timeMillis: Long,
        randomHigh: Long,
        randomLow: Long,
    ): String {
        require(timeMillis in 0..MAX_TIME) { "Time out of ULID range" }
        val out = CharArray(LENGTH)
        var position = 0
        for (i in TIME_CHARS - 1 downTo 0) {
            out[position++] = ALPHABET[((timeMillis ushr (i * BITS_PER_CHAR)) and CHAR_MASK).toInt()]
        }
        for (i in RANDOM_HALF_CHARS - 1 downTo 0) {
            out[position++] = ALPHABET[((randomHigh ushr (i * BITS_PER_CHAR)) and CHAR_MASK).toInt()]
        }
        for (i in RANDOM_HALF_CHARS - 1 downTo 0) {
            out[position++] = ALPHABET[((randomLow ushr (i * BITS_PER_CHAR)) and CHAR_MASK).toInt()]
        }
        return out.concatToString()
    }

    internal const val RANDOM_MASK: Long = HALF_RANDOM_MASK
}

/**
 * Generates strictly increasing ULIDs even within the same millisecond, by incrementing the random part
 * (and spilling into the next millisecond in the astronomically unlikely overflow case).
 * Not thread-safe: one generator per writer, used from the single writer coroutine of the event store.
 */
public class MonotonicUlidGenerator(
    private val clock: Clock,
    private val random: Random = Random.Default,
) {
    private var lastTime = -1L
    private var high = 0L
    private var low = 0L

    public fun next(): String {
        var now = clock.now().toEpochMilliseconds()
        if (now <= lastTime) {
            now = lastTime
            low = (low + 1) and Ulid.RANDOM_MASK
            if (low == 0L) {
                high = (high + 1) and Ulid.RANDOM_MASK
                if (high == 0L) now = lastTime + 1
            }
        } else {
            high = random.nextLong() and Ulid.RANDOM_MASK
            low = random.nextLong() and Ulid.RANDOM_MASK
        }
        lastTime = now
        return Ulid.encode(now, high, low)
    }
}
