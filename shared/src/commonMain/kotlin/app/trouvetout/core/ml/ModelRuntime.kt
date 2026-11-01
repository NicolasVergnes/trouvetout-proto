package app.trouvetout.core.ml

import app.trouvetout.core.log.LogLevel
import app.trouvetout.core.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/** Why the search answers with keywords only (the host says it to the user). */
public enum class KeywordOnlyReason {
    /** This build carries no model. */
    NO_MODEL,

    /** The device cannot run the model (processor without the native library). */
    UNSUPPORTED_DEVICE,

    /** The model failed to load (memory, damaged file); retried at the next launch. */
    LOAD_FAILED,
}

/**
 * Owns the loaded model: lazy load on first use, one user at a time, release on demand (app in background,
 * memory warning). Nothing loads it ahead of need, and a failure leaves keyword search working (rule 7).
 */
internal class ModelRuntime(
    private val source: TextModelSource?,
    private val logger: Logger,
    /** Whether the text+vision file is wanted now (memory, settings); read at every use. */
    private val imagesWanted: () -> Boolean = { false },
    /** The text+vision file failed to load: photos are off until the next launch (the hosts say so). */
    private val onImagesFailed: () -> Unit = {},
    /** Around a load of the text+vision file: a mark that outlives a kill by the system. */
    private val visionLoading: suspend (running: Boolean) -> Unit = {},
) {
    private val mutex = Mutex()
    private var embedder: TextEmbedder? = null
    private var loadedWithImages = false
    private var failed = false

    /** The text+vision file failed to load: photos stay off until the next launch, text keeps working. */
    private var imagesFailed = false

    @Volatile
    private var closing = false

    val modelId: String? = source?.modelId

    /** The bundled label catalog (B8), read from the host; null when it has none. */
    fun labelCatalog(): ByteArray? = source?.labelCatalog()

    /** Identifies the image encoder; null when this build has none. */
    val imageModelId: String? = source?.imageModelId

    /** The photo side can be used now: the file is there, the device may load it and the user wants it. */
    val imagesActive: Boolean
        get() = imageModelId != null && !imagesFailed && unavailableReason == null && imagesWanted()

    /** Null while the model can be used (loaded or not yet). */
    val unavailableReason: KeywordOnlyReason?
        get() = when {
            source?.modelId == null -> KeywordOnlyReason.NO_MODEL
            !source.isSupported -> KeywordOnlyReason.UNSUPPORTED_DEVICE
            failed -> KeywordOnlyReason.LOAD_FAILED
            else -> null
        }

    /** Runs [block] with the model, loading it if needed; null when the model cannot be used. */
    suspend fun <T> withModel(block: suspend (TextEmbedder) -> T): T? = mutex.withLock {
        if (closing) return@withLock null
        val model = current() ?: return@withLock null
        try {
            block(model)
        } finally {
            // closeNow() arrived during the computation: the engine is freed now that nothing uses it.
            if (closing) closeEmbedder()
        }
    }

    /** Runs [block] with the image side of the model; null when photos are not active (or the model failed). */
    suspend fun <T> withImageModel(block: suspend (ImageEmbedder) -> T): T? {
        if (!imagesActive) return null
        return withModel { model -> (model as? ImageEmbedder)?.let { block(it) } }
    }

    /** The loaded model if it matches the wanted variant; otherwise the right one is loaded (one engine at a time). */
    private suspend fun current(): TextEmbedder? {
        val withImages = imagesActive
        embedder?.let { if (loadedWithImages == withImages) return it }
        closeEmbedder()
        return load(withImages)
    }

    private suspend fun load(withImages: Boolean): TextEmbedder? {
        if (unavailableReason != null) return null
        if (withImages) visionLoading(true)
        val attempt =
            try {
                runCatching { checkNotNull(source).load(withImages) }
            } finally {
                if (withImages) withContext(NonCancellable) { visionLoading(false) }
            }
        attempt.exceptionOrNull()?.let { failure ->
            if (failure is CancellationException) throw failure
            logger.log(LogLevel.WARN, TAG, "model load failed (images=$withImages): ${failure::class.simpleName}")
            // The bigger file failing (memory) must not take text search with it (rule 7).
            if (withImages) {
                imagesFailed = true
                onImagesFailed()
                return load(false)
            }
            failed = true
        }
        embedder = attempt.getOrNull()
        loadedWithImages = withImages
        return embedder
    }

    /** Frees the model memory; the next use loads it again. Waits for a running computation to finish. */
    suspend fun release() = mutex.withLock {
        embedder?.close()
        embedder = null
    }

    val isLoaded: Boolean get() = embedder != null

    /**
     * Shutdown path ([app.trouvetout.core.di.SharedFactory.close]), from a non-suspending caller: frees the engine now
     * when idle, otherwise as soon as the running computation ends; no computation starts afterwards.
     */
    fun closeNow() {
        closing = true
        if (mutex.tryLock()) {
            try {
                closeEmbedder()
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun closeEmbedder() {
        embedder?.close()
        embedder = null
    }

    private companion object {
        const val TAG = "ModelRuntime"
    }
}
