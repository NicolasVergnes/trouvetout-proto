package app.trouvetout.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo

public class IosDeviceInfo : DeviceInfo {
    override val totalRamBytes: Long = NSProcessInfo.processInfo.physicalMemory.toLong()
    override val osName: String = "iOS"

    @OptIn(ExperimentalForeignApi::class)
    override val osVersion: String =
        NSProcessInfo.processInfo.operatingSystemVersion.useContents { "$majorVersion.$minorVersion.$patchVersion" }
}
