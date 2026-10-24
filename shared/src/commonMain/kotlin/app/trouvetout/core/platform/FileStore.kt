package app.trouvetout.core.platform

/**
 * Byte-level access to files under one root directory: the app's private, non-backed-up data directory.
 * Relative paths use `/`; `..` segments are refused so an import can never write outside the store.
 */
public interface FileStore {
    public val rootDirectory: String

    public fun absolutePath(relativePath: String): String

    /** Writes atomically (temporary file then rename) and creates the parent directories. */
    public fun write(
        relativePath: String,
        bytes: ByteArray,
    )

    /** Null when the file does not exist. */
    public fun read(relativePath: String): ByteArray?

    /** True when a file was removed. */
    public fun delete(relativePath: String): Boolean

    public fun exists(relativePath: String): Boolean

    /** File names (not paths) directly under [relativeDirectory], sorted; empty when the directory is absent. */
    public fun list(relativeDirectory: String): List<String>
}

public expect fun createFileStore(rootDirectory: String): FileStore

/** Shared validation: relative, slash-separated, no parent segment. */
internal fun requireSafeRelativePath(relativePath: String) {
    require(relativePath.isNotBlank()) { "Empty path" }
    require(!relativePath.startsWith("/") && !relativePath.contains('\\') && !relativePath.contains(':')) {
        "Not a relative path"
    }
    require(relativePath.split('/').none { it == ".." || it.isEmpty() }) { "Path escapes the store" }
}
