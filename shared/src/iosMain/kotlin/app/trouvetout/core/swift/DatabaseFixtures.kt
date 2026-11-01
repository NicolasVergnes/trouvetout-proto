package app.trouvetout.core.swift

import app.trouvetout.core.data.db.IosEncryptionSupport

/**
 * Test support for the XCTest of the encryption (H2): a plain database as lots 1 to 7 left it, and a raw look at
 * a file through the SQLCipher the app links. Nothing here touches the app's own database.
 */
public object DatabaseFixtures {
    /** Writes a plain SQLite file with [rows] rows in a `probe` table (user version 0). */
    public fun writePlain(
        path: String,
        rows: Int,
    ) {
        val connection = IosEncryptionSupport.open(path, null)
        try {
            connection.exec("CREATE TABLE probe (n INTEGER NOT NULL)")
            connection.exec(
                "WITH RECURSIVE c(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM c WHERE n < $rows) " +
                    "INSERT INTO probe SELECT n FROM c",
            )
        } finally {
            connection.close()
        }
    }

    /** Rows of `probe` read with [keyLiteral] (null: as a plain file); -1 when the file cannot be read that way. */
    public fun probeCount(
        path: String,
        keyLiteral: String?,
    ): Long = runCatching {
        val connection = IosEncryptionSupport.open(path, keyLiteral)
        try {
            connection.long("SELECT COUNT(*) FROM probe")
        } finally {
            connection.close()
        }
    }.getOrDefault(-1)

    /** The file starts with the plain SQLite header. */
    public fun isPlain(path: String): Boolean =
        IosEncryptionSupport.header(path)?.contentEquals("SQLite format 3\u0000".encodeToByteArray()) == true
}
