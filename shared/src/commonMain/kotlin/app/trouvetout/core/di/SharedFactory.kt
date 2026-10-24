package app.trouvetout.core.di

import app.trouvetout.core.AppInfo

/**
 * Composition root of the core: manual injection, one instance per process (ADR-012).
 * Grows with each lot (database, repositories, use cases, parser, search).
 */
public class SharedFactory(
    public val config: SharedConfig,
) {
    public val appInfo: AppInfo = AppInfo

    /** Releases held resources (database connections, loaded models). */
    public fun close() {
        // Nothing to release yet.
    }
}
