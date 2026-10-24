package app.trouvetout.core.platform

/** SHA-256 digest of [bytes] computed with the platform's system implementation (no custom crypto). */
public expect fun sha256(bytes: ByteArray): ByteArray

private const val BYTE_MASK = 0xff
private const val HEX_RADIX = 16

/** Lowercase hexadecimal rendering, used for stored digests and model pack verification. */
public fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0') }
