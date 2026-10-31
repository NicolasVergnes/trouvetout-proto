package app.trouvetout.tools

import app.trouvetout.core.ml.ImageEmbedder
import app.trouvetout.core.ml.Label
import app.trouvetout.core.ml.TextEmbedder
import app.trouvetout.core.ml.TextModelSource
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.sqrt

/** One entry of `models/manifest.json`, with the identity the apps derive from it (name and checksum prefix). */
internal class ManifestModel(
    val id: String,
    val variant: String,
    val file: String,
    val sha256: String,
) {
    /** Same form as the apps' `TEXT_MODEL_ID` (Android BuildConfig, iOS Info.plist). */
    val identity: String get() = "$id@${sha256.take(IDENTITY_SHA_CHARS)}"

    companion object {
        private const val IDENTITY_SHA_CHARS = 12

        fun read(manifest: File): List<ManifestModel> =
            Json.parseToJsonElement(manifest.readText()).jsonObject.getValue("models").jsonArray.map {
                val o = it.jsonObject

                fun field(name: String) = o.getValue(name).jsonPrimitive.content
                ManifestModel(field("id"), field("variant"), field("file"), field("sha256"))
            }
    }
}

/** The two bundled files on the JVM, each engine loaded once and kept for the whole run. */
internal class JvmEngines(
    private val textFile: File,
    private val visionFile: File?,
) : AutoCloseable {
    private var text: EmbeddingEngine? = null
    private var vision: EmbeddingEngine? = null

    fun text(): EmbeddingEngine = text ?: open(textFile, images = false).also { text = it }

    fun vision(): EmbeddingEngine = vision ?: open(checkNotNull(visionFile), images = true).also { vision = it }

    private fun open(
        file: File,
        images: Boolean,
    ): EmbeddingEngine {
        val cache = Files.createTempDirectory("litertlm").toString()
        // The vision settings are refused by the text-only file: set only for the text+vision one.
        val config =
            if (images) {
                EmbeddingEngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = cache,
                    maxInputLength = MAX_INPUT_TOKENS,
                    visionBackend = Backend.CPU(),
                    visionTokensPerImage = VISION_TOKENS,
                )
            } else {
                EmbeddingEngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = cache,
                    maxInputLength = MAX_INPUT_TOKENS,
                )
            }
        return EmbeddingEngine(config).also { it.initialize() }
    }

    override fun close() {
        text?.close()
        vision?.close()
        text = null
        vision = null
    }

    companion object {
        /** Same value as the apps (AssetTextModelSource, LiteRtTextModel.swift). */
        const val VISION_TOKENS = 70
    }
}

internal fun EmbeddingEngine.texts(
    texts: List<String>,
    dim: Int,
): List<FloatArray> = computeEmbeddingBatch(
    texts.map { listOf(InputData.Text(it)) },
    EmbeddingOptions(normalize = true, outputSize = dim),
).map { it.embedding }

internal fun EmbeddingEngine.images(
    jpegs: List<ByteArray>,
    dim: Int,
): List<FloatArray> = computeEmbeddingBatch(
    jpegs.map { listOf(InputData.Image(it)) },
    EmbeddingOptions(normalize = true, outputSize = dim),
).map { it.embedding }

/** The core's model interface over [JvmEngines], as the apps provide it (text file, text+vision file, catalog). */
internal class JvmModelSource(
    private val engines: JvmEngines,
    override val modelId: String,
    override val imageModelId: String?,
    private val catalog: ByteArray?,
) : TextModelSource {
    override val isSupported: Boolean = true

    override fun labelCatalog(): ByteArray? = catalog

    override suspend fun load(withImages: Boolean): TextEmbedder =
        if (withImages) Vision(engines.vision()) else Text(engines.text())

    private open class Text(
        val engine: EmbeddingEngine,
    ) : TextEmbedder {
        override suspend fun embed(
            texts: List<String>,
            dim: Int,
        ): List<FloatArray> = withContext(Dispatchers.Default) { engine.texts(texts, dim) }

        /** The engines stay loaded for the whole run; [JvmEngines.close] frees them. */
        override fun close() = Unit
    }

    private class Vision(
        engine: EmbeddingEngine,
    ) : Text(engine),
        ImageEmbedder {
        override suspend fun embedImages(
            jpegs: List<ByteArray>,
            dim: Int,
        ): List<FloatArray> = withContext(Dispatchers.Default) { engine.images(jpegs, dim) }
    }
}

/** `models/labels_fr_en.tsv`: header `key fr en`, one tab-separated label per line. */
internal fun readLabels(tsv: File): List<Label> = tsv.readLines(Charsets.UTF_8)
    .drop(1)
    .filter { it.isNotBlank() }
    .map { line ->
        val (key, fr, en) = line.split('\t')
        Label(key, fr, en)
    }

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun dot(
    a: FloatArray,
    b: FloatArray,
): Float {
    var sum = 0f
    for (i in a.indices) sum += a[i] * b[i]
    return sum
}

/** L2-normalised mean of [vectors] (a label written several ways becomes one vector). */
internal fun meanVector(vectors: List<FloatArray>): FloatArray {
    val sum = FloatArray(vectors.first().size)
    vectors.forEach { v -> v.indices.forEach { sum[it] += v[it] } }
    val norm = sqrt(sum.fold(0f) { acc, x -> acc + x * x })
    return FloatArray(sum.size) { sum[it] / norm }
}

/**
 * How a label becomes the text(s) whose mean vector represents it (B8). The lot 7 spike showed the bare label
 * close to « a photo of », and the classification prefix worse; [LabelTemplates.DEFAULT] is chosen by `evalPhotos`.
 */
internal object LabelTemplates {
    val ALL: Map<String, (Label) -> List<String>> =
        mapOf(
            "fr+en" to { l -> listOf(l.fr, l.en) },
            "photo" to { l -> listOf("une photo de ${l.fr}", "a photo of ${l.en}") },
            "fr/en" to { l -> listOf("${l.fr} / ${l.en}") },
            "en" to { l -> listOf(l.en) },
            "photo-en" to { l -> listOf("a photo of ${l.en}") },
            "doc" to { l -> listOf("title: none | text: ${l.fr} / ${l.en}") },
        )

    const val DEFAULT = "doc"
}

private const val MAX_INPUT_TOKENS = 512
