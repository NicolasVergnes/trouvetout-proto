import groovy.json.JsonSlurper
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// Model files bundled with the apps (ADR-021, ADR-022): downloaded at build time from a pinned revision, verified
// against the SHA-256 published in manifest.json, never committed (CLAUDE.md rule 8).

/** Downloads the model files unless verified copies are already there; refuses a file whose checksum differs. */
@DisableCachingByDefault(because = "large downloads, verified by checksum; the build cache would only duplicate them")
abstract class FetchModelsTask : DefaultTask() {
    /** One entry per file: `url`, `sha256` and `file` (path inside [outputDirectory]) from manifest.json. */
    @get:Input
    abstract val models: ListProperty<Map<String, String>>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun fetch() {
        models.get().forEach { fetchOne(it.getValue("url"), it.getValue("sha256"), it.getValue("file")) }
    }

    private fun fetchOne(
        url: String,
        sha256: String,
        relativePath: String,
    ) {
        val target = outputDirectory.file(relativePath).get().asFile
        if (target.isFile && digest(target) == sha256) return
        target.parentFile.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        val connection =
            URI(url).toURL().openConnection().apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
        try {
            connection.getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            val actual = digest(partial)
            if (actual != sha256) {
                throw GradleException("Checksum mismatch for $relativePath: got $actual, expected $sha256")
            }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            partial.delete()
        }
    }

    private companion object {
        /** A stalled download fails the build instead of hanging it (no partial file is ever used). */
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

@Suppress("UNCHECKED_CAST")
val manifestModels =
    (JsonSlurper().parse(file("manifest.json")) as Map<String, Any>)["models"] as List<Map<String, Any>>

val fetchModels by tasks.registering(FetchModelsTask::class) {
    group = "models"
    description = "Downloads the embedding models (text, text+vision) and verifies their SHA-256 (ADR-021, ADR-022)."
    models.set(manifestModels.map { m -> listOf("url", "sha256", "file").associateWith { m.getValue(it) as String } })
    outputDirectory.set(layout.buildDirectory.dir("bundled"))
}

/** The verified model directory, consumed by the Android app (assets) and the evaluation tools. */
val bundledModels by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(bundledModels.name, fetchModels.flatMap { it.outputDirectory }) {
        builtBy(fetchModels)
    }
}
