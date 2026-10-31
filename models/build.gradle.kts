import groovy.json.JsonSlurper
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// Model files bundled with the apps (ADR-021): downloaded at build time from a pinned revision, verified
// against the SHA-256 published in manifest.json, never committed (CLAUDE.md rule 8).

/** Downloads one model file unless a verified copy is already there; refuses a file whose checksum differs. */
@DisableCachingByDefault(because = "a large download, verified by checksum; the build cache would only duplicate it")
abstract class FetchModelTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    /** Path inside [outputDirectory], e.g. `models/embeddinggemma-2-text-270m.litertlm`. */
    @get:Input
    abstract val relativePath: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun fetch() {
        val target = outputDirectory.file(relativePath).get().asFile
        if (target.isFile && digest(target) == sha256.get()) return
        target.parentFile.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        URI(url.get()).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        val actual = digest(partial)
        if (actual != sha256.get()) {
            partial.delete()
            throw GradleException("Checksum mismatch for ${relativePath.get()}: got $actual, expected ${sha256.get()}")
        }
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
val textModel =
    (JsonSlurper().parse(file("manifest.json")) as Map<String, Any>)["models"]
        .let { it as List<Map<String, Any>> }
        .single { it["variant"] == "TEXT_ONLY" }

val fetchTextModel by tasks.registering(FetchModelTask::class) {
    group = "models"
    description = "Downloads the text embedding model and verifies its SHA-256 (ADR-021)."
    url.set(textModel["url"] as String)
    sha256.set(textModel["sha256"] as String)
    relativePath.set(textModel["file"] as String)
    outputDirectory.set(layout.buildDirectory.dir("bundled"))
}

/** The verified model directory, consumed by the Android app (assets) and the evaluation tool. */
val bundledModels by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(bundledModels.name, fetchTextModel.flatMap { it.outputDirectory }) {
        builtBy(fetchTextModel)
    }
}
