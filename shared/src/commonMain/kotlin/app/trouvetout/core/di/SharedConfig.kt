package app.trouvetout.core.di

import app.trouvetout.core.data.db.DriverFactory
import app.trouvetout.core.log.Logger
import app.trouvetout.core.log.NoopLogger
import app.trouvetout.core.platform.CoroutineDispatchers
import app.trouvetout.core.platform.DeviceInfo
import app.trouvetout.core.platform.ImageCodec
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Everything the host app must provide to the core.
 *
 * @param dataDirectory absolute path of the app's private directory that is excluded from system backups
 *   (`noBackupFilesDir` on Android, `Application Support` with `isExcludedFromBackup` on iOS).
 * @param driverFactory opens the SQLite database inside that directory.
 * @param imageCodec platform image transcoding for the photo store.
 * @param storageReady false when the host could not prepare or verify that directory: the core then refuses
 *   to open the store (nothing is written to a backed-up or purgeable location) and `warmUp()` reports it.
 */
public class SharedConfig(
    public val dataDirectory: String,
    public val deviceInfo: DeviceInfo,
    public val driverFactory: DriverFactory,
    public val imageCodec: ImageCodec,
    public val logger: Logger = NoopLogger,
    public val storageReady: Boolean = true,
) {
    /** Overridable for tests; the host apps keep the system defaults. */
    public var clock: Clock = Clock.System
    public var dispatchers: CoroutineDispatchers = CoroutineDispatchers()
    public var random: Random = Random.Default
}
