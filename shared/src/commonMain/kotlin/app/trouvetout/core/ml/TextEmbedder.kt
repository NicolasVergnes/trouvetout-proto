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

/** Where the host's model comes from: bundled with the app (ADR-021), loaded lazily, released on demand. */
public interface TextModelSource {
    /**
     * Identifies the model files (name and checksum prefix): vectors computed by another model are recomputed.
     * Null when this build carries no model (keyword search only).
     */
    public val modelId: String?

    /** False when this device cannot run the model (no native library for its processor). */
    public val isSupported: Boolean

    /** Loads the model, typically in one or two seconds; throws when it cannot. */
    public suspend fun load(): TextEmbedder
}

/** Variant of EmbeddingGemma 2 chosen for the device (ADR-003); lot 6 ships the text-only one. */
public enum class ModelVariant { TEXT_ONLY, OMNIMODAL }
