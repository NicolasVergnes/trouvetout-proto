package app.trouvetout.core.search

import app.trouvetout.core.capture.Text
import kotlin.math.ln

/** The searchable text of one item, field by field (C1). */
internal class SearchDocument(
    val itemId: String,
    val name: String,
    val description: String?,
    val tags: List<String>,
    /** Place names from the root down to the container label. */
    val location: List<String>,
    /** The latest sentences typed or dictated about the item. */
    val rawInputs: List<String>,
)

/**
 * In-memory inverted index with BM25 over weighted fields (ADR-021: SQLite FTS5 is missing from Android's
 * system SQLite, and FTS does not tolerate typos anyway). Tolerant by construction: accents and case folded,
 * plurals stemmed, prefix on the word being typed, edit distance on unknown words, synonyms (C1, C3).
 * Immutable: rebuilt when the journal changes, in a few milliseconds per thousand items.
 */
internal class KeywordIndex(
    documents: List<SearchDocument>,
) {
    val size: Int = documents.size
    private val postings = HashMap<String, MutableList<Posting>>()
    private val lengths = FloatArray(documents.size)
    private val averageLength: Float

    /** Indexed words by length, for the edit-distance lookup. */
    private val byLength: Map<Int, List<String>>

    init {
        documents.forEachIndexed { doc, d ->
            val weights = HashMap<String, Float>()

            fun add(
                text: String,
                weight: Float,
            ) {
                for (term in SearchText.terms(text)) weights[term] = (weights[term] ?: 0f) + weight
            }
            add(d.name, NAME_WEIGHT)
            d.tags.forEach { add(it, TAG_WEIGHT) }
            d.location.forEach { add(it, LOCATION_WEIGHT) }
            d.description?.let { add(it, DESCRIPTION_WEIGHT) }
            d.rawInputs.forEach { add(it, RAW_INPUT_WEIGHT) }
            for ((term, weight) in weights) postings.getOrPut(term) { ArrayList() } += Posting(doc, weight)
            lengths[doc] = weights.values.sum()
        }
        averageLength = if (documents.isEmpty()) 1f else lengths.average().toFloat().coerceAtLeast(1f)
        byLength = (postings.keys + Synonyms.vocabulary).groupBy { it.length }
    }

    /**
     * Score of every matching document, by document index. Each query word counts once, through its best
     * expansion (itself, a synonym, a completion, a correction). [prefixLast] completes the last word, which the
     * user may still be typing.
     */
    fun search(
        query: String,
        prefixLast: Boolean = true,
    ): Map<Int, Double> {
        val terms = SearchText.terms(query).ifEmpty { SearchText.words(query).map(SearchText::stem) }.distinct()
        val scores = HashMap<Int, Double>()
        val matched = HashMap<Int, Int>()
        terms.forEachIndexed { i, term ->
            val best = HashMap<Int, Double>()
            for ((candidate, weight) in expand(term, prefixLast && i == terms.lastIndex)) {
                val list = postings[candidate] ?: continue
                val idf = idf(list.size)
                for (p in list) {
                    val score = weight * idf * bm25(p.weight, lengths[p.doc])
                    if (score > (best[p.doc] ?: 0.0)) best[p.doc] = score
                }
            }
            for ((doc, score) in best) {
                scores[doc] = (scores[doc] ?: 0.0) + score
                matched[doc] = (matched[doc] ?: 0) + 1
            }
        }
        // Covering every word of the question matters more than repeating one of them.
        return scores.mapValues { (doc, score) ->
            score * (COVERAGE_FLOOR + (1 - COVERAGE_FLOOR) * (matched[doc] ?: 0) / terms.size)
        }
    }

    /** The term itself, its corrections and completions, and the synonyms of all of them, with their weights. */
    internal fun expand(
        term: String,
        prefix: Boolean,
    ): Map<String, Double> {
        val out = HashMap<String, Double>()

        fun offer(
            word: String,
            weight: Double,
        ) {
            if (weight > (out[word] ?: 0.0)) out[word] = weight
        }
        offer(term, 1.0)
        if (term !in postings && term !in Synonyms.vocabulary) {
            for ((word, distance) in corrections(term)) offer(word, if (distance == 1) TYPO_1 else TYPO_2)
        }
        if (prefix) completions(term).forEach { (word, weight) -> offer(word, weight) }
        for ((word, weight) in out.toList()) {
            for (synonym in Synonyms.of(word)) offer(synonym, weight * SYNONYM_WEIGHT)
        }
        return out
    }

    /** Indexed words that [term] begins, weighted by how much of them is typed. */
    private fun completions(term: String): List<Pair<String, Double>> {
        if (term.length < MIN_PREFIX || term.any(Char::isDigit)) return emptyList()
        return postings.keys
            .filter { it.length > term.length && it.startsWith(term) }
            .map { it to PREFIX_WEIGHT * term.length / it.length + PREFIX_BASE }
    }

    /** Known words within the tolerated edit distance (1 from 4 letters, 2 from 8); numbers must match. */
    private fun corrections(term: String): List<Pair<String, Int>> {
        if (term.length < MIN_TYPO_LENGTH || term.any(Char::isDigit)) return emptyList()
        val maxDistance = if (term.length >= LONG_WORD) 2 else 1
        val out = ArrayList<Pair<String, Int>>()
        for (length in term.length - maxDistance..term.length + maxDistance) {
            for (word in byLength[length].orEmpty()) {
                if (word.any(Char::isDigit)) continue
                val distance = Text.levenshtein(term, word)
                if (distance <= maxDistance) out += word to distance
            }
        }
        return out
    }

    private fun idf(documentFrequency: Int): Double =
        ln(1 + (size - documentFrequency + HALF) / (documentFrequency + HALF))

    private fun bm25(
        termWeight: Float,
        length: Float,
    ): Double = termWeight * (K1 + 1) / (termWeight + K1 * (1 - B + B * length / averageLength))

    private class Posting(
        val doc: Int,
        val weight: Float,
    )

    companion object {
        const val NAME_WEIGHT = 3f
        const val TAG_WEIGHT = 2f
        const val LOCATION_WEIGHT = 1.5f
        const val DESCRIPTION_WEIGHT = 1f
        const val RAW_INPUT_WEIGHT = 0.5f
        private const val HALF = 0.5
        private const val K1 = 1.2
        private const val B = 0.75
        private const val SYNONYM_WEIGHT = 0.7
        private const val TYPO_1 = 0.75
        private const val TYPO_2 = 0.6
        private const val PREFIX_WEIGHT = 0.4
        private const val PREFIX_BASE = 0.4
        private const val MIN_PREFIX = 3
        private const val MIN_TYPO_LENGTH = 4
        private const val LONG_WORD = 8
        private const val COVERAGE_FLOOR = 0.5
    }
}
