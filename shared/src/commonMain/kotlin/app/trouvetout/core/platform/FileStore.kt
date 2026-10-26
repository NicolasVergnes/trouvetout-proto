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

    /** Streams a large file (export archive); the target appears only when the sink is closed. */
    public fun sink(relativePath: String): ByteSink

    /** Random access to an existing file (import archive); null when absent. */
    public fun source(relativePath: String): RandomAccessSource?

    /** Removes a directory and everything below it; true when something was removed. */
    public fun deleteDirectory(relativeDirectory: String): Boolean
}

public expect fun createFileStore(rootDirectory: String): FileStore

/**
 * Shared validation: relative, slash-separated, no `.`/`..` segment, no control character (a NUL would be
 * truncated by C string APIs and turn `..\u0000` into `..`).
 */
internal fun requireSafeRelativePath(relativePath: String) {
    require(relativePath.isNotBlank()) { "Empty path" }
    require(relativePath.none { it.code < MIN_PRINTABLE || it.code == DEL }) { "Control character in path" }
    require(!relativePath.startsWith("/") && !relativePath.contains('\\') && !relativePath.contains(':')) {
        "Not a relative path"
    }
    require(relativePath.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Path escapes the store" }
}

/** Names that [FileStore.list] reports: hidden files and temporary files of an interrupted write are skipped. */
internal fun isListableName(name: String): Boolean = !name.startsWith(".") && !name.endsWith(TEMP_SUFFIX)

internal const val TEMP_SUFFIX: String = ".tmp"
private const val MIN_PRINTABLE = 0x20
private const val DEL = 0x7F
