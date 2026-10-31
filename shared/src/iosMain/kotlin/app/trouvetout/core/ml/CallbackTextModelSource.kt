package app.trouvetout.core.ml

import app.trouvetout.core.platform.toByteArray
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Adapter for the Swift implementation of [TextModelSource] (`iosApp/Platform/LiteRtTextModel.swift`): Swift
 * cannot implement a Kotlin `suspend` function, so it answers through a completion handler instead.
 */
public abstract class CallbackTextModelSource : TextModelSource {
    /** Calls [completion] once, with the loaded model or with a failure description (never business data). */
    public abstract fun loadModel(completion: (CallbackTextEmbedder?, String?) -> Unit)

    final override suspend fun load(): TextEmbedder = suspendCancellableCoroutine { continuation ->
        loadModel { embedder, failure ->
            if (embedder != null) {
                continuation.resume(embedder)
            } else {
                continuation.resumeWithException(IllegalStateException(failure ?: "model not loaded"))
            }
        }
    }
}

/** A model loaded by Swift: vectors come back as float32 data (native byte order, little-endian on iOS). */
public abstract class CallbackTextEmbedder : TextEmbedder {
    /** Calls [completion] once, with one vector per text in order, or with a failure description. */
    public abstract fun embedTexts(
        texts: List<String>,
        dim: Int,
        completion: (List<NSData>?, String?) -> Unit,
    )

    final override suspend fun embed(
        texts: List<String>,
        dim: Int,
    ): List<FloatArray> {
        val vectors =
            suspendCancellableCoroutine { continuation ->
                embedTexts(texts, dim) { vectors, failure ->
                    if (vectors != null) {
                        continuation.resume(vectors)
                    } else {
                        continuation.resumeWithException(IllegalStateException(failure ?: "embedding failed"))
                    }
                }
            }
        return vectors.map { it.toFloats() }
    }

    /** Little-endian float32, as Swift's `[Float]` lays them out on every iOS device. */
    private fun NSData.toFloats(): FloatArray {
        val bytes = toByteArray()
        return FloatArray(bytes.size / Float.SIZE_BYTES) { i ->
            var bits = 0
            for (b in 0 until Float.SIZE_BYTES) {
                bits = bits or ((bytes[i * Float.SIZE_BYTES + b].toInt() and BYTE_MASK) shl (b * Byte.SIZE_BITS))
            }
            Float.fromBits(bits)
        }
    }

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}
