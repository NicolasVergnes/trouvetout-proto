package app.trouvetout.core.data.backup

import app.trouvetout.core.platform.ByteSink
import app.trouvetout.core.platform.RandomAccessSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive

/**
 * The encrypted backup file (A9, ADR-025): an export ZIP cut into blocks of 1 MiB, each sealed by AES-256-GCM.
 *
 * `"TTBK"`, version (1 byte), block size (4 bytes, big endian), salt (32 bytes), then the sealed blocks. The key of the
 * file is HKDF-SHA-256 of the recovery key with that salt; a block's nonce is its number (the last byte says whether it
 * is the last block), and the header is its additional data. Altering, reordering, truncating or extending the file
 * fails to open (the STREAM construction). Blocks are small enough for the memory of a 2 GiB phone.
 */
internal class BackupFormat(
    private val crypto: BackupCrypto,
) {
    /** Thrown when the file is not a backup of a version this app reads. */
    class NotABackup : IllegalArgumentException("not a backup")

    /** Thrown when the key is not the file's, or the file was damaged. */
    class CannotOpen : IllegalArgumentException("cannot open")

    suspend fun encrypt(
        scope: CoroutineScope,
        recoveryKey: ByteArray,
        source: RandomAccessSource,
        sink: ByteSink,
    ) {
        val salt = crypto.randomBytes(SALT_BYTES)
        val header = MAGIC + VERSION + intBytes(BLOCK_BYTES) + salt
        val fileKey = fileKey(recoveryKey, salt)
        sink.write(header)
        var offset = 0L
        var number = 0L
        do {
            scope.ensureActive()
            val length = minOf(BLOCK_BYTES.toLong(), source.size - offset).toInt()
            val last = offset + length >= source.size
            val plain = if (length == 0) ByteArray(0) else source.read(offset, length)
            sink.write(crypto.seal(fileKey, nonce(number, last), header, plain))
            offset += length
            number++
        } while (!last)
    }

    suspend fun decrypt(
        scope: CoroutineScope,
        recoveryKey: ByteArray,
        source: RandomAccessSource,
        sink: ByteSink,
    ) {
        val header = readHeader(source)
        val fileKey = fileKey(recoveryKey, header.copyOfRange(HEADER_BYTES - SALT_BYTES, HEADER_BYTES))
        var offset = HEADER_BYTES.toLong()
        var number = 0L
        do {
            scope.ensureActive()
            val length = minOf((BLOCK_BYTES + TAG_BYTES).toLong(), source.size - offset).toInt()
            if (length < TAG_BYTES) throw CannotOpen()
            val last = offset + length >= source.size
            val plain =
                crypto.open(fileKey, nonce(number, last), header, source.read(offset, length)) ?: throw CannotOpen()
            sink.write(plain)
            offset += length
            number++
        } while (!last)
    }

    private fun readHeader(source: RandomAccessSource): ByteArray {
        if (source.size < HEADER_BYTES + TAG_BYTES) throw NotABackup()
        val header = source.read(0, HEADER_BYTES)
        val intact =
            header.copyOf(MAGIC.size).contentEquals(MAGIC) &&
                header[MAGIC.size] == VERSION &&
                header.copyOfRange(MAGIC.size + 1, MAGIC.size + 1 + INT_BYTES).contentEquals(intBytes(BLOCK_BYTES))
        if (!intact) throw NotABackup()
        return header
    }

    private fun fileKey(
        recoveryKey: ByteArray,
        salt: ByteArray,
    ): ByteArray = Hkdf.derive(recoveryKey, salt, INFO, KEY_BYTES)

    /** 12 bytes: 3 zero bytes, the block number (8 bytes, big endian), 1 when it is the last block. */
    private fun nonce(
        number: Long,
        last: Boolean,
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        for (index in 0 until Long.SIZE_BYTES) {
            nonce[NONCE_BYTES - 2 - index] = (number shr (index * Byte.SIZE_BITS)).toByte()
        }
        nonce[NONCE_BYTES - 1] = if (last) 1 else 0
        return nonce
    }

    private fun intBytes(value: Int) =
        ByteArray(INT_BYTES) { (value shr ((INT_BYTES - 1 - it) * Byte.SIZE_BITS)).toByte() }

    companion object {
        private val MAGIC = "TTBK".encodeToByteArray()
        private const val VERSION: Byte = 1
        private const val INT_BYTES = 4
        private const val SALT_BYTES = 32
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private const val HEADER_BYTES = 4 + 1 + INT_BYTES + SALT_BYTES
        private val INFO = "trouvetout-backup-v1".encodeToByteArray()

        /** 1 MiB; the same for every file of this version. */
        const val BLOCK_BYTES: Int = 1 shl 20

        /** The largest backup of an archive of [archiveBytes]. */
        fun maxSize(archiveBytes: Long): Long =
            HEADER_BYTES + archiveBytes + (archiveBytes / BLOCK_BYTES + 1) * TAG_BYTES
    }
}
