package app.trouvetout.core.capture

/** A known location the dictated place text can be matched against: a place or a movable container. */
public data class LocationCandidate(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val parentId: String? = null,
    val isContainer: Boolean = false,
)

/** One scored match. */
public data class LocationMatch(
    val candidate: LocationCandidate,
    val score: Double,
)

/**
 * What the resolver makes of a place text (B7).
 * - [Resolved]: one candidate scores at or above [PlaceResolver.RESOLVED]; use it silently.
 * - [Ambiguous]: the best candidates score between [PlaceResolver.SUGGEST] and [PlaceResolver.RESOLVED]; the UI
 *   asks « rattacher à … ? » and remembers the alias.
 * - [Unknown]: nothing close enough; the UI proposes to create [Unknown.names] (root first) under
 *   [Unknown.under] when a prefix of the dictated path was recognised (« carton 7 de l'étagère de la cave »
 *   with « cave » known → create « étagère » then « carton 7 » under it).
 */
public sealed interface PlaceResolution {
    public data class Resolved(
        val match: LocationMatch,
    ) : PlaceResolution

    public data class Ambiguous(
        val suggestions: List<LocationMatch>,
    ) : PlaceResolution

    public data class Unknown(
        val names: List<String>,
        val under: LocationMatch?,
    ) : PlaceResolution
}

/**
 * Pure similarity-based resolution of a dictated place against the known places and containers: normalised
 * Levenshtein on names and aliases, token Jaccard, an optional extra scorer (embeddings from lot 6), plus a
 * right-to-left walk of multi-level texts (« tiroir du haut de la commode » → « commode » then « tiroir du haut »).
 */
public class PlaceResolver(
    private val candidates: List<LocationCandidate>,
    private val lang: Lang = Lang.FR,
    /** Extra similarity in [0, 1] (semantic), combined by max with the lexical scores. */
    private val extraScorer: ((String, String) -> Double)? = null,
) {
    private val byId = candidates.associateBy { it.id }
    private val levelLinks = Phrases(if (lang == Lang.FR) FR_LEVEL_LINKS else EN_LEVEL_LINKS)

    public fun resolve(placeText: String): PlaceResolution {
        val text = placeName(placeText, lang)
        val whole = rank(text)
        val top = whole.takeWhile { it.score >= RESOLVED }
        return when {
            // Two locations scoring the same (« tiroir » in two rooms): ask, never pick one (B7).
            top.size >= 2 && top[0].score - top[1].score < TIE -> PlaceResolution.Ambiguous(top.take(MAX_SUGGESTIONS))
            top.isNotEmpty() -> PlaceResolution.Resolved(top[0])
            else -> walk(text) ?: suggestOrCreate(text, whole)
        }
    }

    private fun suggestOrCreate(
        text: String,
        ranked: List<LocationMatch>,
    ): PlaceResolution {
        val close = ranked.takeWhile { it.score >= SUGGEST }.take(MAX_SUGGESTIONS)
        return if (close.isNotEmpty()) PlaceResolution.Ambiguous(close) else PlaceResolution.Unknown(listOf(text), null)
    }

    /** Candidates sorted by score for one level of text, children of [parentId] first when given. */
    public fun rank(
        text: String,
        parentId: String? = null,
    ): List<LocationMatch> = candidates
        .map {
            LocationMatch(
                it,
                score(text, it) * if (parentId == null || it.parentId == parentId) 1.0 else OUT_OF_SCOPE,
            )
        }
        .filter { it.score > 0.0 }
        .sortedByDescending { it.score }

    /** Autocomplete: names and aliases starting with (or containing) the typed prefix, best first. */
    public fun suggest(
        prefix: String,
        limit: Int = MAX_SUGGESTIONS,
    ): List<LocationCandidate> {
        val key = Text.fold(prefix.trim())
        if (key.isEmpty()) return emptyList()
        return candidates
            .map { c -> c to (listOf(c.name) + c.aliases).maxOf { prefixScore(key, Text.fold(it)) } }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun prefixScore(
        key: String,
        name: String,
    ): Double = when {
        name.startsWith(key) -> 1.0
        name.split(' ', '\'').any { it.startsWith(key) } -> WORD_PREFIX
        name.contains(key) -> CONTAINS
        else -> 0.0
    }

    private fun score(
        text: String,
        candidate: LocationCandidate,
    ): Double {
        val names = listOf(candidate.name) + candidate.aliases
        var best = names.maxOf { maxOf(Text.similarity(text, it), Text.tokenJaccard(text, it)) }
        val path = pathOf(candidate.id)
        if (path.size > 1) best = maxOf(best, Text.similarity(text, path.joinToString(" ")))
        extraScorer?.let { scorer -> best = maxOf(best, names.maxOf { scorer(text, it) }) }
        return best
    }

    private fun pathOf(id: String): List<String> {
        val names = ArrayDeque<String>()
        var current: LocationCandidate? = byId[id]
        val seen = HashSet<String>()
        while (current != null && seen.add(current.id)) {
            names.addFirst(current.name)
            current = current.parentId?.let { byId[it] }
        }
        return names.toList()
    }

    /**
     * Right-to-left walk of a multi-level text: the longest suffix after a level link that names a known location
     * becomes the parent, and the walk continues on the prefix under it. « carton 7 de l'étagère du fond de la
     * cave » → cave → étagère du fond → carton 7 (Resolved); « tiroir du haut de la commode » with only
     * « commode » known → Unknown([tiroir du haut], under = commode). Null when no level is known.
     */
    private fun walk(text: String): PlaceResolution? {
        var toks = Tokens.tokenize(text)
        var parent: LocationMatch? = null
        while (true) {
            val cut = (1 until toks.size).firstNotNullOfOrNull { p -> knownSuffix(toks, p, parent) } ?: break
            parent = cut.second
            toks = toks.subList(0, cut.first)
        }
        if (parent == null) return null
        val remaining = Tokens.render(toks).trim()
        if (remaining.isEmpty()) return PlaceResolution.Resolved(parent)
        val child = childOf(remaining, parent)
        return if (child !=
            null
        ) {
            PlaceResolution.Resolved(child)
        } else {
            PlaceResolution.Unknown(listOf(remaining), parent)
        }
    }

    private fun knownSuffix(
        toks: List<Tok>,
        at: Int,
        parent: LocationMatch?,
    ): Pair<Int, LocationMatch>? {
        val n = levelLinks.matchAt(toks, at)
        if (n == 0 || at + n >= toks.size) return null
        val match = childOf(Tokens.render(toks.drop(at + n)), parent) ?: return null
        return at to match
    }

    /** Best known location for [text] that is a child of [parent] (or anything when [parent] is null). */
    private fun childOf(
        text: String,
        parent: LocationMatch?,
    ): LocationMatch? = rank(text, parent?.candidate?.id).firstOrNull {
        it.score >= RESOLVED && (parent == null || it.candidate.parentId == parent.candidate.id)
    }

    public companion object {
        public const val RESOLVED: Double = 0.85
        private const val TIE = 1e-9

        /**
         * The name a dictated place gets when created or remembered: a kept position preposition and the article
         * after it are dropped (« sous le canapé » → « canapé »), the rest is unchanged.
         */
        public fun placeName(
            text: String,
            lang: Lang,
        ): String {
            val rules = Rules.of(lang)
            val toks = Tokens.tokenize(text)
            val n = rules.positionPrepositions.matchAt(toks, 0)
            if (n == 0 || n >= toks.size) return text.trim()
            var rest = toks.drop(n)
            while (rest.isNotEmpty() && rest[0].key in rules.lex.articles) rest = rest.drop(1)
            return Tokens.render(rest).ifEmpty { text.trim() }
        }

        public const val SUGGEST: Double = 0.6
        public const val MAX_SUGGESTIONS: Int = 5
        private const val OUT_OF_SCOPE = 0.9
        private const val WORD_PREFIX = 0.8
        private const val CONTAINS = 0.5
        private val FR_LEVEL_LINKS =
            listOf(
                "de la",
                "de l'",
                "du",
                "des",
                "de",
                "dans la",
                "dans le",
                "dans l'",
                "dans",
                "sur la",
                "sur le",
                "sur l'",
                "sur",
            )
        private val EN_LEVEL_LINKS = listOf("of the", "in the", "on the", "of", "in", "on")
    }
}
