package app.trouvetout.core.platform

import platform.Foundation.NSProcessInfo

public class IosDeviceInfo : DeviceInfo {
    override val totalRamBytes: Long = NSProcessInfo.processInfo.physicalMemory.toLong()
    override val osName: String = "iOS"
    override val osVersion: String = NSProcessInfo.processInfo.operatingSystemVersionString
}
