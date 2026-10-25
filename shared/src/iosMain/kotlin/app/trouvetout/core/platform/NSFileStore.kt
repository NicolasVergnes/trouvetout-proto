package app.trouvetout.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/** Foundation implementation; writes are atomic (`writeToFile(atomically:)`). */
@OptIn(ExperimentalForeignApi::class)
internal class NSFileStore(
    override val rootDirectory: String,
) : FileStore {
    private val fileManager = NSFileManager.defaultManager

    override fun absolutePath(relativePath: String): String {
        requireSafeRelativePath(relativePath)
        return "${rootDirectory.trimEnd('/')}/$relativePath"
    }

    override fun write(
        relativePath: String,
        bytes: ByteArray,
    ) {
        val path = absolutePath(relativePath)
        val parent = path.substringBeforeLast('/')
        fileManager.createDirectoryAtPath(parent, withIntermediateDirectories = true, attributes = null, error = null)
        check(bytes.toNSData().writeToFile(path, atomically = true)) { "Write failed" }
    }

    override fun read(relativePath: String): ByteArray? =
        NSData.dataWithContentsOfFile(absolutePath(relativePath))?.toByteArray()

    override fun delete(relativePath: String): Boolean =
        fileManager.removeItemAtPath(absolutePath(relativePath), error = null)

    override fun exists(relativePath: String): Boolean = fileManager.fileExistsAtPath(absolutePath(relativePath))

    override fun list(relativeDirectory: String): List<String> = fileManager
        .contentsOfDirectoryAtPath(absolutePath(relativeDirectory), error = null)
        ?.map { it.toString() }
        ?.sorted()
        ?: emptyList()
}

public actual fun createFileStore(rootDirectory: String): FileStore = NSFileStore(rootDirectory)
