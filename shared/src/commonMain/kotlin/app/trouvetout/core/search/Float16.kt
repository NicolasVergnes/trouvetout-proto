package app.trouvetout.core.search

/** IEEE 754 half-precision storage of the vectors (half the size of float32, ample for a cosine). */
@Suppress("MagicNumber") // the bit layout of IEEE 754 binary16 and binary32
internal object Float16 {
    fun encode(vector: FloatArray): ByteArray {
        val out = ByteArray(vector.size * 2)
        vector.forEachIndexed { i, value ->
            val half = toHalf(value)
            out[2 * i] = (half and 0xFF).toByte()
            out[2 * i + 1] = (half shr 8 and 0xFF).toByte()
        }
        return out
    }

    fun decode(bytes: ByteArray): FloatArray = FloatArray(bytes.size / 2) { i ->
        val half = (bytes[2 * i].toInt() and 0xFF) or ((bytes[2 * i + 1].toInt() and 0xFF) shl 8)
        toFloat(half)
    }

    /** Round-to-nearest-even conversion; NaN and infinities never come out of a normalised embedding. */
    fun toHalf(value: Float): Int {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val exponent = ((bits ushr 23) and 0xFF) - 127 + 15
        var mantissa = bits and 0x7FFFFF
        return when {
            exponent <= 0 -> {
                if (exponent < -10) {
                    sign
                } else {
                    mantissa = mantissa or 0x800000
                    val shift = 14 - exponent
                    val rounded = (mantissa + (1 shl (shift - 1)) - 1 + ((mantissa shr shift) and 1)) shr shift
                    sign or rounded
                }
            }
            exponent >= 0x1F -> sign or 0x7C00
            else -> {
                val rounded = mantissa + 0xFFF + ((mantissa shr 13) and 1)
                if (rounded and 0x800000 != 0) {
                    if (exponent + 1 >= 0x1F) sign or 0x7C00 else sign or ((exponent + 1) shl 10)
                } else {
                    sign or (exponent shl 10) or (rounded shr 13)
                }
            }
        }
    }

    fun toFloat(half: Int): Float {
        val sign = (half and 0x8000) shl 16
        val exponent = (half ushr 10) and 0x1F
        val mantissa = half and 0x3FF
        val bits =
            when {
                exponent == 0 && mantissa == 0 -> sign
                exponent == 0 -> {
                    // Subnormal: m·2⁻²⁴; normalise by k shifts → 1.f·2^(-14-k).
                    var shifts = 0
                    var m = mantissa
                    while (m and 0x400 == 0) {
                        m = m shl 1
                        shifts++
                    }
                    sign or ((127 - 14 - shifts) shl 23) or ((m and 0x3FF) shl 13)
                }
                exponent == 0x1F -> sign or 0x7F800000 or (mantissa shl 13)
                else -> sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
            }
        return Float.fromBits(bits)
    }
}
