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

    public fun query(text: String): String = queryPrefix + clip(text)

    public fun document(text: String): String = documentPrefix + clip(text)

    public companion object {
        /**
         * Characters kept from a text: about a hundred tokens, far below the 256-token input the hosts configure
         * (the runtime rejects a longer input instead of truncating it). Cut at a word boundary.
         */
        public const val MAX_TEXT_CHARS: Int = 400

        internal fun clip(text: String): String {
            val trimmed = text.trim()
            if (trimmed.length <= MAX_TEXT_CHARS) return trimmed
            val cut = trimmed.lastIndexOf(' ', MAX_TEXT_CHARS).takeIf { it > MAX_TEXT_CHARS / 2 } ?: MAX_TEXT_CHARS
            return trimmed.substring(0, cut)
        }
    }
}
