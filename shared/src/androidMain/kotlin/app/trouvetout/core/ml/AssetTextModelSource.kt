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

/** A model file in the APK assets: path inside `assets/`, identity (name and checksum prefix), exact size. */
public class BundledModelAsset(
    public val assetPath: String,
    public val id: String,
    public val bytes: Long,
)

/**
 * EmbeddingGemma 2 bundled in the APK assets (ADR-021, ADR-022), run by LiteRT-LM on the CPU: the text file, and
 * on ≥ 6 GB phones the text+vision file, whose text weights are the same byte for byte (one text identity).
 * LiteRT-LM maps a file, not an entry inside the APK, so a load copies the file it needs once to the cache
 * directory (excluded from backups; recopied if the system purges it); only one copy is kept. Nothing is downloaded.
 *
 * @param text the text file, or null for a build without models (keyword search only).
 * @param vision the text+vision file, or null for a build without it (no photo side).
 * @param labelsAsset the label catalog (B8) computed with this text model, inside `assets/`.
 */
public class AssetTextModelSource(
    context: Context,
    private val text: BundledModelAsset?,
    private val vision: BundledModelAsset? = null,
    private val labelsAsset: String? = null,
) : TextModelSource {
    private val appContext = context.applicationContext

    override val modelId: String? = text?.id

    override val imageModelId: String? = vision?.id?.takeIf { text != null }

    /** LiteRT-LM ships native code for 64-bit ARM and x86 only; 32-bit phones keep keyword search. */
    override val isSupported: Boolean = Build.SUPPORTED_ABIS.any { it in SUPPORTED_ABIS }

    override suspend fun load(withImages: Boolean): TextEmbedder = withContext(Dispatchers.IO) {
        val asset = checkNotNull(if (withImages) vision else text) { "no model in this build" }
        val model = materialize(asset)
        // The native library logs its inputs at some levels (rule 3: no business data in logs, debug included).
        Engine.setNativeMinLogSeverity(LogSeverity.INFINITY)
        val cache = File(appContext.cacheDir, "litertlm").apply { mkdirs() }
        val config =
            if (withImages) {
                // The vision settings are refused by the text-only file: set only for the text+vision one.
                EmbeddingEngineConfig(
                    modelPath = model.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = cache.absolutePath,
                    maxInputLength = MAX_INPUT_TOKENS,
                    visionBackend = Backend.CPU(),
                    visionTokensPerImage = VISION_TOKENS,
                )
            } else {
                EmbeddingEngineConfig(
                    modelPath = model.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = cache.absolutePath,
                    maxInputLength = MAX_INPUT_TOKENS,
                )
            }
        val engine = EmbeddingEngine(config)
        try {
            engine.initialize()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            engine.close()
            throw e
        }
        if (withImages) LiteRtImageEmbedder(engine) else LiteRtTextEmbedder(engine)
    }

    override fun labelCatalog(): ByteArray? =
        labelsAsset?.let { path -> appContext.assets.open(path).use { it.readBytes() } }

    /**
     * The model as a plain file: copied from the assets once, atomically, named after the model identity (another
     * model with the same file name is copied again); copies of other models and partial copies are removed, so
     * the cache holds one model file at a time.
     */
    private fun materialize(asset: BundledModelAsset): File {
        val directory = File(appContext.cacheDir, "models")
        val target = File(directory, "${asset.id.replace(UNSAFE_CHARS, "_")}.litertlm")
        if (target.isFile && target.length() == asset.bytes) return target
        directory.listFiles()?.filter { it != target }?.forEach { it.delete() }
        directory.mkdirs()
        val partial = File(directory, target.name + ".part")
        try {
            appContext.assets.open(asset.assetPath).use { input ->
                partial.outputStream().use { input.copyTo(it, COPY_BUFFER) }
            }
            check(partial.length() == asset.bytes) { "model copy incomplete" }
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

        /** Image tokens: 70 do as well as 140 on household photos at 2.4× the speed (lot 7 spike, ADR-022). */
        const val VISION_TOKENS = 70
        const val COPY_BUFFER = 1 shl 16
        val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}

/** LiteRT-LM embedding engine behind the core's interface; one computation at a time (ModelRuntime). */
internal open class LiteRtTextEmbedder(
    protected val engine: EmbeddingEngine,
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

/** The text+vision engine: photos (JPEG, already oriented, without metadata) land in the text space (B8, C10). */
internal class LiteRtImageEmbedder(
    engine: EmbeddingEngine,
) : LiteRtTextEmbedder(engine),
    ImageEmbedder {
    override suspend fun embedImages(
        jpegs: List<ByteArray>,
        dim: Int,
    ): List<FloatArray> = withContext(Dispatchers.Default) {
        val options = EmbeddingOptions(normalize = true, outputSize = dim)
        engine.computeEmbeddingBatch(jpegs.map { listOf(InputData.Image(it)) }, options).map { it.embedding }
    }
}
