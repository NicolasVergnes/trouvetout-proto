package app.trouvetout.core.platform

import java.io.File
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
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}$TEMP_SUFFIX")
        try {
            temporary.writeBytes(bytes)
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            temporary.delete()
        }
    }

    override fun read(relativePath: String): ByteArray? = resolve(relativePath).takeIf { it.isFile }?.readBytes()

    override fun delete(relativePath: String): Boolean = resolve(relativePath).delete()

    override fun exists(relativePath: String): Boolean = resolve(relativePath).exists()

    override fun list(relativeDirectory: String): List<String> =
        resolve(relativeDirectory).list()?.filter(::isListableName)?.sorted() ?: emptyList()

    /** Validates the relative path, then checks the resolved file really sits under the root (defence in depth). */
    private fun resolve(relativePath: String): File {
        requireSafeRelativePath(relativePath)
        val file = File(root, relativePath)
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Path escapes the store" }
        return file
    }
}

public actual fun createFileStore(rootDirectory: String): FileStore = JavaFileStore(rootDirectory)
