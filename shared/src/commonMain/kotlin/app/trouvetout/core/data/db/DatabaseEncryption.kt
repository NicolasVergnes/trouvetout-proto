package app.trouvetout.core.data.db

/**
 * The 256-bit key of the database (H2): random, created on first use, kept by the platform's key store (Android
 * Keystore wrapping, iOS Keychain `AfterFirstUnlockThisDeviceOnly`, never synchronised), deleted by the total
 * wipe.
 */
public interface DatabaseKeyStore {
    /** A key exists (the database it encrypts can be opened). */
    public fun exists(): Boolean

    /** The key, created when there is none; throws when the store cannot give it (never the key in the message). */
    public fun obtain(): ByteArray

    /** Forgets the key (H6): the database it encrypted becomes unreadable. */
    public fun delete()
}

/** The encrypted database exists but its key is gone (store reset): it cannot be opened, only wiped. */
public class DatabaseKeyLostException : IllegalStateException("database key lost")

/** The SQLite linked into the app is not SQLCipher: the database would be written in clear. */
public class CipherMissingException : IllegalStateException("SQLCipher not linked")

/** The copy of the plain database did not match it: the plain file is kept as it was. */
public class EncryptionCheckException(
    what: String,
) : IllegalStateException("encrypted copy differs: $what")

/** One raw SQLCipher connection, without schema handling, for the migration. */
public interface RawConnection {
    /** Runs [sql] to completion on this very connection (rows, if any, are dropped). */
    public fun exec(sql: String)

    /**
     * `ATTACH DATABASE ? AS enc KEY ?` with both values bound: the key never appears in an SQL text that SQLite or
     * the platform could log with an error.
     */
    public fun attach(
        path: String,
        keyLiteral: String,
    )

    public fun long(sql: String): Long

    public fun texts(sql: String): List<String>

    public fun close()
}

/** What a platform gives the migration: raw connections and file operations on absolute paths. */
public interface EncryptionSupport {
    /** Opens [path] with SQLCipher; [keyLiteral] null opens a plain database. */
    public fun open(
        path: String,
        keyLiteral: String?,
    ): RawConnection

    /** Up to the first 16 bytes of the file; null when it does not exist. */
    public fun header(path: String): ByteArray?

    public fun delete(path: String)

    /** Atomic rename over [to] (same directory). */
    public fun replace(
        from: String,
        to: String,
    )
}

/**
 * Turns the database file into an SQLCipher one, once, before the driver opens it (H2, ADR-023):
 * - no file: nothing to do, the driver creates an encrypted database;
 * - a plain file (lots 1 to 7): copied into `<file>.enc` by `sqlcipher_export`, the copy is reopened with the key,
 *   given the schema version and compared (every table's row count), then renamed over the plain file; any failure
 *   before the rename leaves the plain file as it was, and the next launch starts again;
 * - an encrypted file without a key: [DatabaseKeyLostException], nothing is touched.
 */
public object DatabaseEncryption {
    private const val TEMP_SUFFIX = ".enc"
    private val PLAIN_HEADER = "SQLite format 3\u0000".encodeToByteArray()
    private val SIDE_FILES = listOf("-wal", "-shm", "-journal")

    /** SQLCipher's raw key form: the 32 bytes are used as the key, no key derivation (fast open). */
    public fun keyLiteral(key: ByteArray): String = "x'" + key.toHexString() + "'"

    /** Readies the file at [path] and returns the key literal to open it with. */
    public fun prepare(
        path: String,
        keys: DatabaseKeyStore,
        support: EncryptionSupport,
    ): String {
        dropTemp(path, support)
        val header = support.header(path)
        if (header != null && header.isEmpty()) {
            // An empty file holds nothing: SQLCipher would refuse it as « not a database » once keyed.
            delete(path, support)
        }
        val plain = header != null && header.contentEquals(PLAIN_HEADER)
        val encrypted = header != null && header.isNotEmpty() && !plain
        if (encrypted && !keys.exists()) throw DatabaseKeyLostException()
        // A plain database needs no old key: leftovers of a reset key store are cleared and a new key made.
        if (plain && !keys.exists()) keys.delete()
        val key = keys.obtain()
        val literal = keyLiteral(key)
        key.fill(0)
        if (plain) encrypt(path, literal, support)
        return literal
    }

    /** The connection really is SQLCipher (`PRAGMA cipher_version` answers), not the system SQLite. */
    public fun requireCipher(cipherVersion: String?) {
        if (cipherVersion.isNullOrBlank()) throw CipherMissingException()
    }

    @Suppress("ThrowsCount") // one refusal per check, each named in the exception
    private fun encrypt(
        path: String,
        literal: String,
        support: EncryptionSupport,
    ) {
        val temp = path + TEMP_SUFFIX
        val plain = support.open(path, null)
        val expected: Map<String, Long>
        val version: Long
        try {
            // Everything in the main file first: the side files are deleted before the rename, so the checkpoint
            // must have completed (first column « busy » = 0).
            if (plain.long("PRAGMA wal_checkpoint(TRUNCATE)") != 0L) throw EncryptionCheckException("checkpoint")
            version = plain.long("PRAGMA user_version")
            expected = counts(plain)
            // The two statements that need the attached copy run back to back; closing the connection detaches it.
            // Anything else is done on the copy itself: Android's pool does not promise one connection per call.
            plain.attach(temp, literal)
            plain.exec("SELECT sqlcipher_export('enc')")
        } finally {
            plain.close()
        }
        val copy = support.open(temp, literal)
        try {
            copy.exec("PRAGMA user_version = $version")
            if (copy.long("PRAGMA user_version") != version) throw EncryptionCheckException("version")
            if (counts(copy) != expected) throw EncryptionCheckException("rows")
        } finally {
            copy.close()
        }
        for (suffix in SIDE_FILES) support.delete(path + suffix)
        support.replace(temp, path)
    }

    private fun counts(connection: RawConnection): Map<String, Long> = connection
        .texts("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        .associateWith { table -> connection.long("SELECT COUNT(*) FROM \"${table.replace("\"", "\"\"")}\"") }

    private fun dropTemp(
        path: String,
        support: EncryptionSupport,
    ) = delete(path + TEMP_SUFFIX, support)

    private fun delete(
        path: String,
        support: EncryptionSupport,
    ) {
        support.delete(path)
        for (suffix in SIDE_FILES) support.delete(path + suffix)
    }
}
