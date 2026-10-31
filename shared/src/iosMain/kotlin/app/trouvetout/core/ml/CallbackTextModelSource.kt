package app.trouvetout.core.ml

import app.trouvetout.core.platform.toByteArray
import app.trouvetout.core.platform.toNSData
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Adapter for the Swift implementation of [TextModelSource] (`iosApp/Platform/LiteRtTextModel.swift`): Swift
 * cannot implement a Kotlin `suspend` function, so it answers through a completion handler instead.
 */
public abstract class CallbackTextModelSource : TextModelSource {
    /**
     * Calls [completion] once, with the loaded model or with a failure description (never business data). With
     * [withImages] the text+vision file is loaded and the model is a [CallbackImageEmbedder].
     */
    public abstract fun loadModel(
        withImages: Boolean,
        completion: (CallbackTextEmbedder?, String?) -> Unit,
    )

    /** The bundled label catalog (B8) as read from the app bundle, or null. */
    public abstract fun labelCatalogData(): NSData?

    final override fun labelCatalog(): ByteArray? = labelCatalogData()?.toByteArray()

    final override suspend fun load(withImages: Boolean): TextEmbedder = suspendCancellableCoroutine { continuation ->
        loadModel(withImages) { embedder, failure ->
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
    ): List<FloatArray> = awaitVectors { completion -> embedTexts(texts, dim, completion) }
}

/** The text+vision model loaded by Swift (B8, C10): photos land in the text space. */
public abstract class CallbackImageEmbedder :
    CallbackTextEmbedder(),
    ImageEmbedder {
    /** Calls [completion] once, with one vector per JPEG in order, or with a failure description. */
    public abstract fun embedJpegs(
        jpegs: List<NSData>,
        dim: Int,
        completion: (List<NSData>?, String?) -> Unit,
    )

    final override suspend fun embedImages(
        jpegs: List<ByteArray>,
        dim: Int,
    ): List<FloatArray> {
        val data = jpegs.map { it.toNSData() }
        return awaitVectors { completion -> embedJpegs(data, dim, completion) }
    }
}

private suspend fun awaitVectors(call: ((List<NSData>?, String?) -> Unit) -> Unit): List<FloatArray> {
    val vectors =
        suspendCancellableCoroutine { continuation ->
            call { vectors, failure ->
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

private const val BYTE_MASK = 0xFF
