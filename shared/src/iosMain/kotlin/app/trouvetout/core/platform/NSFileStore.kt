package app.trouvetout.core.platform

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSUUID
import platform.Foundation.closeFile
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.writeData
import platform.Foundation.writeToFile
import platform.posix.rename

/**
 * Foundation implementation; writes are atomic and carry the `completeUntilFirstUserAuthentication` protection
 * class explicitly (the database must stay readable for local notifications before the first unlock).
 */
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
        ensureParent(path)
        val options = NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
        check(bytes.toNSData().writeToFile(path, options = options, error = null)) { "Write failed" }
    }

    override fun read(relativePath: String): ByteArray? =
        NSData.dataWithContentsOfFile(absolutePath(relativePath))?.toByteArray()

    override fun delete(relativePath: String): Boolean =
        fileManager.removeItemAtPath(absolutePath(relativePath), error = null)

    override fun exists(relativePath: String): Boolean = fileManager.fileExistsAtPath(absolutePath(relativePath))

    override fun list(relativeDirectory: String): List<String> = fileManager
        .contentsOfDirectoryAtPath(absolutePath(relativeDirectory), error = null)
        ?.map { it.toString() }
        ?.filter(::isListableName)
        ?.sorted()
        ?: emptyList()

    override fun sink(relativePath: String): ByteSink {
        val target = absolutePath(relativePath)
        ensureParent(target)
        val name = target.substringAfterLast('/')
        val temporary = "${target.substringBeforeLast('/')}/.$name.${NSUUID().UUIDString}$TEMP_SUFFIX"
        val attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)
        check(fileManager.createFileAtPath(temporary, contents = null, attributes = attributes)) { "Create failed" }
        val handle = NSFileHandle.fileHandleForWritingAtPath(temporary) ?: error("Open failed")
        return HandleSink(handle, temporary, target)
    }

    override fun source(relativePath: String): RandomAccessSource? {
        val path = absolutePath(relativePath)
        if (!fileManager.fileExistsAtPath(path)) return null
        val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
        return HandleSource(handle)
    }

    override fun deleteDirectory(relativeDirectory: String): Boolean {
        val path = absolutePath(relativeDirectory)
        return fileManager.fileExistsAtPath(path) && fileManager.removeItemAtPath(path, error = null)
    }

    private fun ensureParent(path: String) {
        val parent = path.substringBeforeLast('/')
        fileManager.createDirectoryAtPath(parent, withIntermediateDirectories = true, attributes = null, error = null)
    }

    private inner class HandleSink(
        private val handle: NSFileHandle,
        private val temporary: String,
        private val target: String,
    ) : ByteSink {
        override var position: Long = 0
            private set
        private var closed = false

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            val slice = if (offset == 0 && length == bytes.size) bytes else bytes.copyOfRange(offset, offset + length)
            check(withError { handle.writeData(slice.toNSData(), it) }) { "Write failed" }
            position += length
        }

        override fun close() {
            if (closed) return
            closed = true
            val synced = withError { handle.synchronizeAndReturnError(it) }
            val closedOk = withError { handle.closeAndReturnError(it) }
            if (!synced || !closedOk) {
                fileManager.removeItemAtPath(temporary, error = null)
                error("Flush failed")
            }
            // rename(2) replaces the target atomically on APFS, like ATOMIC_MOVE on the JVM.
            val moved = rename(temporary, target) == 0
            if (!moved) fileManager.removeItemAtPath(temporary, error = null)
            check(moved) { "Rename failed" }
        }

        override fun abort() {
            if (closed) return
            closed = true
            handle.closeFile()
            fileManager.removeItemAtPath(temporary, error = null)
        }
    }

    private class HandleSource(
        private val handle: NSFileHandle,
    ) : RandomAccessSource {
        override val size: Long = handle.seekToEndOfFile().toLong()

        override fun read(
            offset: Long,
            length: Int,
        ): ByteArray {
            require(offset >= 0 && length >= 0 && offset + length <= size) { "Read out of range" }
            check(withError { handle.seekToOffset(offset.toULong(), it) }) { "Seek failed" }
            val data =
                memScoped {
                    val failure = alloc<ObjCObjectVar<NSError?>>()
                    handle.readDataUpToLength(length.toULong(), failure.ptr)
                } ?: error("Read failed")
            val bytes = data.toByteArray()
            require(bytes.size == length) { "Short read" }
            return bytes
        }

        override fun close() = handle.closeFile()
    }
}

public actual fun createFileStore(rootDirectory: String): FileStore = NSFileStore(rootDirectory)

/** Runs a Foundation call that reports failure through an `NSError` out-parameter; the error object is dropped. */
@OptIn(ExperimentalForeignApi::class)
private inline fun withError(block: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean): Boolean = memScoped {
    val failure = alloc<ObjCObjectVar<NSError?>>()
    block(failure.ptr)
}
