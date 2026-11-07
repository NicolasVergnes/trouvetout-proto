package app.trouvetout.core.data.backup

import app.trouvetout.core.platform.sha256

/**
 * The recovery key of the backups (A9, ADR-025), which the user writes down: 128 random bits and one check byte in
 * Crockford's base 32, by groups of four. Random bits leave no weak password to guess offline; the check byte tells a
 * typing mistake from a wrong key. It reads back whatever the case and the separators, and O, I and L are forgiven.
 */
internal object RecoveryKey {
    const val KEY_BYTES = 16
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val BITS_PER_CHAR = 5
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF
    private const val CHAR_MASK = 0x1F
    private const val GROUP = 4
    private const val PAYLOAD_BYTES = KEY_BYTES + 1
    private const val TEXT_CHARS = 28

    fun encode(key: ByteArray): String {
        require(key.size == KEY_BYTES) { "bad key size" }
        val payload = key + checkByte(key)
        val text = StringBuilder(TEXT_CHARS)
        var buffer = 0
        var bits = 0
        for (byte in payload) {
            buffer = (buffer shl BITS_PER_BYTE) or (byte.toInt() and BYTE_MASK)
            bits += BITS_PER_BYTE
            while (bits >= BITS_PER_CHAR) {
                bits -= BITS_PER_CHAR
                text.append(ALPHABET[(buffer shr bits) and CHAR_MASK])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) text.append(ALPHABET[(buffer shl (BITS_PER_CHAR - bits)) and CHAR_MASK])
        return text.toString().chunked(GROUP).joinToString("-")
    }

    /** Whether [typed] is the last group of [key], read with the same forgiveness as a whole key. */
    fun confirmsEnd(
        key: String,
        typed: String,
    ): Boolean = normalized(typed) == normalized(key).takeLast(GROUP)

    private fun normalized(text: String): String = text
        .uppercase()
        .filterNot { it == '-' || it.isWhitespace() }
        .replace('O', '0')
        .replace('I', '1')
        .replace('L', '1')

    /** The key, or null when the text is not one: wrong length, a character out of the alphabet, a typing mistake. */
    fun decode(text: String): ByteArray? {
        val clean = normalized(text)
        if (clean.length != TEXT_CHARS) return null
        val payload = ByteArray(PAYLOAD_BYTES)
        var buffer = 0
        var bits = 0
        var index = 0
        for (char in clean) {
            val value = ALPHABET.indexOf(char)
            if (value < 0) return null
            buffer = (buffer shl BITS_PER_CHAR) or value
            bits += BITS_PER_CHAR
            if (bits >= BITS_PER_BYTE) {
                bits -= BITS_PER_BYTE
                if (index < PAYLOAD_BYTES) payload[index++] = (buffer shr bits).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        val key = payload.copyOf(KEY_BYTES)
        return key.takeIf { payload[KEY_BYTES] == checkByte(it) }
    }

    private fun checkByte(key: ByteArray): Byte = sha256(key)[0]
}
