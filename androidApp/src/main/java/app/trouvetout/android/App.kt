package app.trouvetout.android

import android.app.Application
import app.trouvetout.core.data.db.AndroidDriverFactory
import app.trouvetout.core.di.SharedConfig
import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.log.LogcatLogger
import app.trouvetout.core.log.NoopLogger
import app.trouvetout.core.platform.AndroidDeviceInfo
import app.trouvetout.core.platform.AndroidImageCodec
import java.io.File

class App : Application() {
    lateinit var shared: SharedFactory
        private set

    override fun onCreate() {
        super.onCreate()
        // Excluded from Auto Backup: the only backup channel is the app's own encrypted one (A9).
        val dataDirectory = noBackupFilesDir
        shared =
            SharedFactory(
                SharedConfig(
                    dataDirectory = dataDirectory.absolutePath,
                    deviceInfo = AndroidDeviceInfo(this),
                    driverFactory = AndroidDriverFactory(this, File(dataDirectory, "db/trouvetout.db").absolutePath),
                    imageCodec = AndroidImageCodec(),
                    logger = if (BuildConfig.DEBUG) LogcatLogger else NoopLogger,
                ),
            )
    }
}
