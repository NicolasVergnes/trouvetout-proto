package app.trouvetout.core.search

import app.trouvetout.core.ml.PromptStyle

/**
 * Ranking knobs (C3), set once from the evaluation corpus (`tools/eval-search`, N3) and documented in ADR-021.
 * The hosts keep the defaults; the evaluation tool varies them to compare modes.
 */
public data class SearchTuning(
    val promptStyle: PromptStyle = PromptStyle.LITERT,
    /** Matryoshka size of the stored vectors. */
    val dimension: Int = 256,
    /** Reciprocal rank fusion constant. */
    val rrfK: Int = 60,
    val keywordWeight: Double = 1.0,
    val semanticWeight: Double = 1.0,
    /** Semantic candidates kept for the fusion. */
    val semanticCandidates: Int = 50,
    /** A semantic candidate must reach this cosine… */
    val semanticFloor: Float = 0.6f,
    /** …and stay within this margin of the best one. */
    val semanticMargin: Float = 0.12f,
    /** Without any keyword match, the best cosine must reach this to answer at all (« nothing found »). */
    val semanticAnswerFloor: Float = 0.7f,
    /** Bonus for an item moved recently (`exp(-days/90)`), as a fraction of its score. */
    val recencyBonus: Double = 0.1,
    val recencyDays: Double = 90.0,
    /** Bonus for an item often opened from the search, capped, as a fraction of its score. */
    val frequencyBonus: Double = 0.1,
    /** Archived items still answer, behind the others (D11). */
    val archivedFactor: Double = 0.8,
    /** Search with the keyword list only (evaluation of the no-model mode). */
    val keywordOnly: Boolean = false,
    /** Search with the semantic list only (evaluation). */
    val semanticOnly: Boolean = false,
) {
    /** Identifies the stored vectors: another model, size or prompt means recomputing them. */
    internal fun modelKey(modelId: String): String = "$modelId/$dimension/${promptStyle.name}"
}
