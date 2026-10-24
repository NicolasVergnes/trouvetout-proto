package app.trouvetout.android

import android.app.Application
import app.trouvetout.core.di.SharedConfig
import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.log.LogcatLogger
import app.trouvetout.core.log.NoopLogger
import app.trouvetout.core.platform.AndroidDeviceInfo

class App : Application() {
    lateinit var shared: SharedFactory
        private set

    override fun onCreate() {
        super.onCreate()
        shared = SharedFactory(
            SharedConfig(
                // Excluded from Auto Backup: the only backup channel is the app's own encrypted one (A9).
                dataDirectory = noBackupFilesDir.absolutePath,
                deviceInfo = AndroidDeviceInfo(this),
                logger = if (BuildConfig.DEBUG) LogcatLogger else NoopLogger,
            ),
        )
    }
}
