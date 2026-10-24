package app.trouvetout.core.log

public enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Logging sink provided by the host app.
 *
 * Messages must never contain business data (item or place names, dictated phrases,
 * search queries, photos): log identifiers and counts only.
 */
public interface Logger {
    public fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )
}

/** Default sink: discards everything (release builds). */
public object NoopLogger : Logger {
    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        // Intentionally silent.
    }
}
