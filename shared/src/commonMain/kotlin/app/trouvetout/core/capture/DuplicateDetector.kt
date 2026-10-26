package app.trouvetout.core.capture

/** An existing item whose name is close to the one being captured (A14). */
public data class DuplicateMatch(
    val itemId: String,
    val name: String,
    val score: Double,
)

/**
 * Finds active items whose names look like the new one, so the confirmation card can offer « déplacer
 * l'existant » instead of creating a twin. Lexical only in lot 3; the optional [extraScorer] takes embeddings
 * in lot 6.
 */
public class DuplicateDetector(
    private val items: List<Pair<String, String>>,
    private val extraScorer: ((String, String) -> Double)? = null,
) {
    public fun find(
        name: String,
        limit: Int = MAX_MATCHES,
    ): List<DuplicateMatch> = items
        .map { (id, existing) ->
            val lexical = Text.similarity(name, existing)
            val semantic = extraScorer?.invoke(name, existing) ?: 0.0
            DuplicateMatch(id, existing, maxOf(lexical, semantic))
        }
        .filter { it.score >= THRESHOLD }
        .sortedByDescending { it.score }
        .take(limit)

    public companion object {
        public const val THRESHOLD: Double = 0.85
        public const val MAX_MATCHES: Int = 3
    }
}
