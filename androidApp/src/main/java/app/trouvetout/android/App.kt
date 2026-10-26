package app.trouvetout.android

import android.app.Application
import app.trouvetout.core.data.db.AndroidDriverFactory
import app.trouvetout.core.di.SharedConfig
import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.log.LogcatLogger
import app.trouvetout.core.log.NoopLogger
import app.trouvetout.core.platform.AndroidDeviceInfo
import app.trouvetout.core.platform.AndroidImageCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class App : Application() {
    lateinit var shared: SharedFactory
        private set

    /** Process-wide scope for work that must not die with an Activity (warm-up, later: reindexing). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Excluded from Auto Backup: the only backup channel is the app's own encrypted one (A9).
        val dataDirectory = noBackupFilesDir
        val databaseDirectory = File(dataDirectory, "db")
        shared =
            SharedFactory(
                SharedConfig(
                    dataDirectory = dataDirectory.absolutePath,
                    deviceInfo = AndroidDeviceInfo(this),
                    driverFactory = AndroidDriverFactory(this, File(databaseDirectory, "trouvetout.db").absolutePath),
                    imageCodec = AndroidImageCodec(),
                    logger = if (BuildConfig.DEBUG) LogcatLogger else NoopLogger,
                    storageReady = databaseDirectory.isDirectory || databaseDirectory.mkdirs(),
                ),
            )
        // Opens the database off the main thread so the first capture (lot 3) is instant; every entry point
        // (activity, widget, tile, reminder receiver) benefits. warmUp() never throws.
        appScope.launch { shared.warmUp() }
    }
}
