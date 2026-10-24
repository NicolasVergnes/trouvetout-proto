package app.trouvetout.core.platform

import android.app.ActivityManager
import android.content.Context
import android.os.Build

public class AndroidDeviceInfo(
    context: Context,
) : DeviceInfo {
    private val appContext = context.applicationContext

    override val totalRamBytes: Long
        get() {
            val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            return info.totalMem
        }

    override val osName: String = "Android"
    override val osVersion: String = Build.VERSION.RELEASE.orEmpty()
}
