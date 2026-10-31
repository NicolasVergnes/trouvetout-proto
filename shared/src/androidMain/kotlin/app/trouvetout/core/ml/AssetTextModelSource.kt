package app.trouvetout.core.ml

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.LogSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EmbeddingGemma 2 bundled in the APK assets (ADR-021), run by LiteRT-LM on the CPU. LiteRT-LM maps a file, not
 * an entry inside the APK, so the first load copies the model once to the cache directory (excluded from
 * backups; recopied if the system purges it). Nothing is downloaded.
 *
 * @param assetPath path inside `assets/`, or null for a build without the model (keyword search only).
 * @param modelId identifies the model files (name and checksum prefix), from the build configuration.
 * @param expectedBytes size of the model file; a partial copy is never used.
 */
public class AssetTextModelSource(
    context: Context,
    private val assetPath: String?,
    modelId: String,
    private val expectedBytes: Long,
) : TextModelSource {
    private val appContext = context.applicationContext

    override val modelId: String? = modelId.takeIf { assetPath != null }

    /** LiteRT-LM ships native code for 64-bit ARM and x86 only; 32-bit phones keep keyword search. */
    override val isSupported: Boolean = Build.SUPPORTED_ABIS.any { it in SUPPORTED_ABIS }

    override suspend fun load(): TextEmbedder = withContext(Dispatchers.IO) {
        val model = materialize(checkNotNull(assetPath) { "no model in this build" })
        // The native library logs its inputs at some levels (rule 3: no business data in logs, debug included).
        Engine.setNativeMinLogSeverity(LogSeverity.INFINITY)
        val cache = File(appContext.cacheDir, "litertlm").apply { mkdirs() }
        val engine =
            EmbeddingEngine(
                EmbeddingEngineConfig(
                    modelPath = model.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = cache.absolutePath,
                    maxInputLength = MAX_INPUT_TOKENS,
                ),
            )
        engine.initialize()
        LiteRtTextEmbedder(engine)
    }

    /**
     * The model as a plain file: copied from the assets once, atomically, named after the model identity (another
     * model with the same file name is copied again); copies of other models and partial copies are removed.
     */
    private fun materialize(path: String): File {
        val directory = File(appContext.cacheDir, "models")
        val target = File(directory, "${checkNotNull(modelId).replace(UNSAFE_CHARS, "_")}.litertlm")
        if (target.isFile && target.length() == expectedBytes) return target
        directory.listFiles()?.filter { it != target }?.forEach { it.delete() }
        directory.mkdirs()
        val partial = File(directory, target.name + ".part")
        try {
            appContext.assets.open(path).use { input -> partial.outputStream().use { input.copyTo(it, COPY_BUFFER) } }
            check(partial.length() == expectedBytes) { "model copy incomplete" }
            check(partial.renameTo(target)) { "model copy not renamed" }
        } finally {
            partial.delete()
        }
        return target
    }

    private companion object {
        val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")

        /** The core clips texts well below this (LiteRT-LM rejects, never truncates, a longer input). */
        const val MAX_INPUT_TOKENS = 512
        const val COPY_BUFFER = 1 shl 16
        val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}

/** LiteRT-LM embedding engine behind the core's interface; one computation at a time (ModelRuntime). */
internal class LiteRtTextEmbedder(
    private val engine: EmbeddingEngine,
) : TextEmbedder {
    override suspend fun embed(
        texts: List<String>,
        dim: Int,
    ): List<FloatArray> = withContext(Dispatchers.Default) {
        val options = EmbeddingOptions(normalize = true, outputSize = dim)
        engine.computeEmbeddingBatch(texts.map { listOf(InputData.Text(it)) }, options).map { it.embedding }
    }

    override fun close() {
        engine.close()
    }
}
