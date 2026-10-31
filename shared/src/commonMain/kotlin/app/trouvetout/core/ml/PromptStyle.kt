package app.trouvetout.core.ml

/**
 * Task prefixes of EmbeddingGemma 2. The model is trained with prompts that tell a query from a document; the
 * runtime does not add them, so they are applied here, the same way for every host (ADR-021).
 */
public enum class PromptStyle(
    private val queryPrefix: String,
    private val documentPrefix: String,
) {
    /** Hugging Face model card / sentence-transformers configuration. */
    MODEL_CARD("task: search result | query: ", "title: none | text: "),

    /** LiteRT-LM embedding guide. */
    LITERT("task: search query | text: ", "task: search result | text: "),

    /** No prefix (for comparison only). */
    NONE("", ""),
    ;

    public fun query(text: String): String = queryPrefix + text.trim()

    public fun document(text: String): String = documentPrefix + text.trim()
}
