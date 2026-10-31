package app.trouvetout.core.ml

import app.trouvetout.core.log.LogLevel
import app.trouvetout.core.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
) {
    private val mutex = Mutex()
    private var embedder: TextEmbedder? = null
    private var failed = false

    val modelId: String? = source?.modelId

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
        val model = embedder ?: load() ?: return@withLock null
        block(model)
    }

    private suspend fun load(): TextEmbedder? {
        if (unavailableReason != null) return null
        val attempt = runCatching { checkNotNull(source).load() }
        attempt.exceptionOrNull()?.let { failure ->
            if (failure is CancellationException) throw failure
            failed = true
            logger.log(LogLevel.WARN, TAG, "model load failed: ${failure::class.simpleName}")
        }
        embedder = attempt.getOrNull()
        return embedder
    }

    /** Frees the model memory; the next use loads it again. Waits for a running computation to finish. */
    suspend fun release() = mutex.withLock {
        embedder?.close()
        embedder = null
    }

    val isLoaded: Boolean get() = embedder != null

    /** Shutdown path ([app.trouvetout.core.di.SharedFactory.close]): no computation may be running. */
    fun closeNow() {
        embedder?.close()
        embedder = null
    }

    private companion object {
        const val TAG = "ModelRuntime"
    }
}
