package app.trouvetout.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

@OptIn(ExperimentalForeignApi::class)
actual fun testTempDirectory(): String {
    val path = NSTemporaryDirectory().trimEnd('/') + "/trouvetout-test-" + NSUUID().UUIDString
    NSFileManager.defaultManager.createDirectoryAtPath(
        path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return path
}
