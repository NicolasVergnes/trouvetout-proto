package app.trouvetout.core.data.backup

import app.trouvetout.core.platform.sha256

/**
 * AES-256-GCM and secure random bytes from the platform's system libraries (no custom crypto): `javax.crypto` on
 * Android, CryptoKit on iOS. Sealed data is the ciphertext followed by the 16-byte tag.
 */
public interface BackupCrypto {
    public fun randomBytes(count: Int): ByteArray

    public fun seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plain: ByteArray,
    ): ByteArray

    /** Null when the key, the nonce, the additional data or the content do not match. */
    public fun open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        sealed: ByteArray,
    ): ByteArray?
}

/** HKDF over HMAC-SHA-256 (RFC 5869, RFC 2104), written on top of the system's SHA-256. */
internal object Hkdf {
    private const val BLOCK_BYTES = 64
    private const val INNER_PAD = 0x36
    private const val OUTER_PAD = 0x5c
    private const val DIGEST_BYTES = 32

    fun hmacSha256(
        key: ByteArray,
        message: ByteArray,
    ): ByteArray {
        val shortKey = if (key.size > BLOCK_BYTES) sha256(key) else key
        val block = shortKey.copyOf(BLOCK_BYTES)
        val inner = ByteArray(BLOCK_BYTES) { (block[it].toInt() xor INNER_PAD).toByte() }
        val outer = ByteArray(BLOCK_BYTES) { (block[it].toInt() xor OUTER_PAD).toByte() }
        return sha256(outer + sha256(inner + message))
    }

    fun derive(
        keyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length <= DIGEST_BYTES * Byte.MAX_VALUE) { "output too long" }
        val pseudoRandomKey = hmacSha256(salt, keyMaterial)
        var previous = ByteArray(0)
        var output = ByteArray(0)
        var counter = 1
        while (output.size < length) {
            previous = hmacSha256(pseudoRandomKey, previous + info + counter.toByte())
            output += previous
            counter++
        }
        return output.copyOf(length)
    }
}
