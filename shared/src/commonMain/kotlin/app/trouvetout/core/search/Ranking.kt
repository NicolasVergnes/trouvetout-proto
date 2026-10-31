package app.trouvetout.core.search

import app.trouvetout.core.data.repository.ItemView
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.time.Instant

/** Pure ranking steps of the hybrid search (C3), separated from the I/O for the tests. */
internal object Ranking {
    /**
     * Reciprocal rank fusion of the keyword and semantic lists (ids with their score, best first): an item found
     * by both lists outranks an item found by one, and neither list's raw scores need calibrating against the
     * other. Equal scores share a rank, so a tie is left to the recency and frequency signals.
     */
    fun fuse(
        keyword: List<Pair<String, Double>>,
        semantic: List<Pair<String, Double>>,
        tuning: SearchTuning,
    ): Map<String, Double> {
        val out = HashMap<String, Double>()
        for ((id, rank) in ranks(keyword)) out[id] = (out[id] ?: 0.0) + tuning.keywordWeight / (tuning.rrfK + rank)
        for ((id, rank) in ranks(semantic)) out[id] = (out[id] ?: 0.0) + tuning.semanticWeight / (tuning.rrfK + rank)
        return out
    }

    /** 1-based competition ranks of a list sorted best first (« 1, 1, 3 »). */
    fun ranks(sorted: List<Pair<String, Double>>): List<Pair<String, Int>> {
        var rank = 0
        var previous: Double? = null
        return sorted.mapIndexed { i, (id, score) ->
            if (score != previous) rank = i + 1
            previous = score
            id to rank
        }
    }

    /** Semantic candidates worth fusing: above the floor and close enough to the best one. */
    fun semanticCandidates(
        nearest: List<Pair<String, Float>>,
        tuning: SearchTuning,
    ): List<Pair<String, Double>> {
        val best = nearest.firstOrNull()?.second ?: return emptyList()
        return nearest
            .filter { it.second >= tuning.semanticFloor && it.second >= best - tuning.semanticMargin }
            .map { it.first to it.second.toDouble() }
    }

    /** Multiplies a fused score by the recency, frequency and archive signals. */
    fun adjust(
        score: Double,
        view: ItemView,
        openedCount: Long,
        now: Instant,
        tuning: SearchTuning,
        archivedRequested: Boolean,
    ): Double {
        val days = (now - view.location.since).inWholeHours.coerceAtLeast(0) / HOURS_PER_DAY
        val recency = tuning.recencyBonus * exp(-days / tuning.recencyDays)
        val frequency = tuning.frequencyBonus * min(1.0, ln(1.0 + openedCount) / ln(FREQUENCY_SATURATION))
        val archived = if (view.item.archived && !archivedRequested) tuning.archivedFactor else 1.0
        return score * (1 + recency + frequency) * archived
    }

    private const val HOURS_PER_DAY = 24.0

    /** Ten openings give the full frequency bonus. */
    private const val FREQUENCY_SATURATION = 11.0
}
