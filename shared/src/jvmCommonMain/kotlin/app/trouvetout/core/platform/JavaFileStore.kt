package app.trouvetout.core.platform

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** java.io implementation shared by Android (API 29+) and the JVM tools. */
internal class JavaFileStore(
    override val rootDirectory: String,
) : FileStore {
    private val root = File(rootDirectory).absoluteFile.normalize()

    override fun absolutePath(relativePath: String): String = resolve(relativePath).path

    /** Atomic: the bytes go to a uniquely named hidden temporary file, then replace the target in one move. */
    override fun write(
        relativePath: String,
        bytes: ByteArray,
    ) {
        val target = resolve(relativePath)
        val temporary = temporaryFor(target)
        try {
            temporary.writeBytes(bytes)
            moveInto(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    override fun read(relativePath: String): ByteArray? = resolve(relativePath).takeIf { it.isFile }?.readBytes()

    override fun delete(relativePath: String): Boolean = resolve(relativePath).delete()

    override fun exists(relativePath: String): Boolean = resolve(relativePath).exists()

    override fun list(relativeDirectory: String): List<String> =
        resolve(relativeDirectory).list()?.filter(::isListableName)?.sorted() ?: emptyList()

    override fun sink(relativePath: String): ByteSink {
        val target = resolve(relativePath)
        val temporary = temporaryFor(target)
        return FileSink(temporary, target)
    }

    override fun source(relativePath: String): RandomAccessSource? =
        resolve(relativePath).takeIf { it.isFile }?.let { FileSource(RandomAccessFile(it, "r")) }

    override fun deleteDirectory(relativeDirectory: String): Boolean {
        val directory = resolve(relativeDirectory)
        return directory.exists() && directory.deleteRecursively()
    }

    private fun temporaryFor(target: File): File {
        target.parentFile?.mkdirs()
        return File(target.parentFile, ".${target.name}.${UUID.randomUUID()}$TEMP_SUFFIX")
    }

    private fun moveInto(
        temporary: File,
        target: File,
    ) {
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    /** Validates the relative path, then checks the resolved file really sits under the root (defence in depth). */
    private fun resolve(relativePath: String): File {
        requireSafeRelativePath(relativePath)
        val file = File(root, relativePath)
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Path escapes the store" }
        return file
    }

    private inner class FileSink(
        private val temporary: File,
        private val target: File,
    ) : ByteSink {
        private val stream = FileOutputStream(temporary)
        override var position: Long = 0
            private set
        private var closed = false

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            stream.write(bytes, offset, length)
            position += length
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                stream.fd.sync()
                stream.close()
                moveInto(temporary, target)
            } finally {
                temporary.delete()
            }
        }
    }

    private class FileSource(
        private val file: RandomAccessFile,
    ) : RandomAccessSource {
        override val size: Long = file.length()

        override fun read(
            offset: Long,
            length: Int,
        ): ByteArray {
            require(offset >= 0 && length >= 0 && offset + length <= size) { "Read out of range" }
            val out = ByteArray(length)
            file.seek(offset)
            file.readFully(out)
            return out
        }

        override fun close() = file.close()
    }
}

public actual fun createFileStore(rootDirectory: String): FileStore = JavaFileStore(rootDirectory)
