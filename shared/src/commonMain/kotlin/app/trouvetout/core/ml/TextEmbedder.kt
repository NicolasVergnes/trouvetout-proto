package app.trouvetout.core.ml

/**
 * A loaded text embedding model (C2). Implemented by the hosts on LiteRT-LM (Android, iOS) and by the
 * evaluation tool on the JVM; never on a critical path without a keyword fallback (rule 7).
 */
public interface TextEmbedder {
    /** One L2-normalised vector of [dim] floats per text, in order. Heavy: called off the main thread. */
    public suspend fun embed(
        texts: List<String>,
        dim: Int,
    ): List<FloatArray>

    /** Frees the model memory; the embedder is not used afterwards. */
    public fun close()
}

/**
 * The image side of a model loaded with its vision encoder (B8, C10, ADR-022): photos land in the same space as
 * texts. A [TextEmbedder] loaded with images also implements this interface.
 */
public interface ImageEmbedder {
    /** One L2-normalised vector of [dim] floats per image (JPEG bytes, already oriented, without metadata). */
    public suspend fun embedImages(
        jpegs: List<ByteArray>,
        dim: Int,
    ): List<FloatArray>
}

/** Where the host's model comes from: bundled with the app (ADR-021, ADR-022), loaded lazily, released on demand. */
public interface TextModelSource {
    /**
     * Identifies the text model (name and checksum prefix): vectors computed by another model are recomputed. The
     * text and text+vision files share their text weights byte for byte, hence one identity for both (ADR-022).
     * Null when this build carries no model (keyword search only).
     */
    public val modelId: String?

    /** False when this device cannot run the model (no native library for its processor). */
    public val isSupported: Boolean

    /** Identifies the image encoder of the text+vision file; null when this build does not carry it. */
    public val imageModelId: String?

    /**
     * Loads the model, typically in one or two seconds; throws when it cannot. With [withImages] the text+vision file
     * is loaded and the result also implements [ImageEmbedder].
     */
    public suspend fun load(withImages: Boolean): TextEmbedder

    /** The bundled label catalog with its precomputed vectors (B8, [LabelCatalog] format), or null. */
    public fun labelCatalog(): ByteArray?
}

/** Variant of EmbeddingGemma 2 for the device (ADR-003): text only, or text and images (≥ 6 GB of memory). */
public enum class ModelVariant { TEXT_ONLY, OMNIMODAL }
