package app.trouvetout.core.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** java.io implementation shared by Android (API 29+) and the JVM tools. */
internal class JavaFileStore(
    override val rootDirectory: String,
) : FileStore {
    private val root = File(rootDirectory).absoluteFile.normalize()

    override fun absolutePath(relativePath: String): String = resolve(relativePath).path

    override fun write(
        relativePath: String,
        bytes: ByteArray,
    ) {
        val target = resolve(relativePath)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(bytes)
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    override fun read(relativePath: String): ByteArray? = resolve(relativePath).takeIf { it.isFile }?.readBytes()

    override fun delete(relativePath: String): Boolean = resolve(relativePath).delete()

    override fun exists(relativePath: String): Boolean = resolve(relativePath).exists()

    override fun list(relativeDirectory: String): List<String> =
        resolve(relativeDirectory).list()?.sorted() ?: emptyList()

    private fun resolve(relativePath: String): File {
        requireSafeRelativePath(relativePath)
        return File(root, relativePath)
    }
}

public actual fun createFileStore(rootDirectory: String): FileStore = JavaFileStore(rootDirectory)
