package app.trouvetout.core.platform

import java.lang.management.ManagementFactory

/** Desktop JVM facts, used by the evaluation tools and JVM tests. */
public class JvmDeviceInfo : DeviceInfo {
    override val totalRamBytes: Long
        get() {
            val bean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            return bean?.totalMemorySize ?: 0L
        }

    override val osName: String = System.getProperty("os.name") ?: "JVM"
    override val osVersion: String = System.getProperty("os.version") ?: ""
}
