package app.trouvetout.core.data.export

/** CRC-32 (IEEE 802.3, as used by ZIP), pure common Kotlin. */
public class Crc32 {
    private var crc: Int = -1

    public fun update(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ) {
        var c = crc
        for (i in offset until offset + length) {
            c = TABLE[(c xor bytes[i].toInt()) and BYTE_MASK] xor (c ushr BITS_PER_BYTE)
        }
        crc = c
    }

    /** Unsigned value as a Long (0..2^32-1). */
    public val value: Long get() = (crc.inv().toLong()) and UINT_MASK

    public fun reset() {
        crc = -1
    }

    public companion object {
        private const val POLYNOMIAL = 0xEDB88320.toInt()
        private const val BYTE_MASK = 0xFF
        private const val BITS_PER_BYTE = 8
        private const val UINT_MASK = 0xFFFFFFFFL
        private val TABLE: IntArray =
            IntArray(256) { n ->
                var c = n
                repeat(BITS_PER_BYTE) { c = if (c and 1 != 0) POLYNOMIAL xor (c ushr 1) else c ushr 1 }
                c
            }

        public fun of(bytes: ByteArray): Long = Crc32().apply { update(bytes) }.value
    }
}
