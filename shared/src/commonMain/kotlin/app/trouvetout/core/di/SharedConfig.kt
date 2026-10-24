package app.trouvetout.core.di

import app.trouvetout.core.log.Logger
import app.trouvetout.core.log.NoopLogger
import app.trouvetout.core.platform.DeviceInfo

/**
 * Everything the host app must provide to the core.
 *
 * @param dataDirectory absolute path of the app's private directory that is excluded from system backups
 *   (`noBackupFilesDir` on Android, `Application Support` with `isExcludedFromBackup` on iOS).
 */
public class SharedConfig(
    public val dataDirectory: String,
    public val deviceInfo: DeviceInfo,
    public val logger: Logger = NoopLogger,
)
