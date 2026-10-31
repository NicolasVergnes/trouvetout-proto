package app.trouvetout.core.search

import app.trouvetout.core.data.repository.ItemView
import app.trouvetout.core.domain.model.LocationStatus
import app.trouvetout.core.ml.KeywordOnlyReason
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** State of an item as the filters see it (C11): its location status, or archived (D11). */
public enum class ItemStateFilter { STORED, TAKEN, LENT, ARCHIVED }

/** Filters of the search (C11); every field narrows, none is required. */
public data class SearchFilters(
    /** The place and everything below it. */
    val placeId: String? = null,
    val containerId: String? = null,
    val tag: String? = null,
    val state: ItemStateFilter? = null,
    /** Items put where they are since this instant at least. */
    val movedSince: Instant? = null,
) {
    public val isEmpty: Boolean
        get() = placeId == null && containerId == null && tag == null && state == null && movedSince == null

    public companion object {
        /** The instant [days] days ago, for [movedSince] (hosts without a date type of the core at hand). */
        public fun daysAgo(days: Int): Instant = Clock.System.now() - days.days
    }
}

/** How the result was found, for the tests and the evaluation (never shown as a score). */
public enum class MatchKind { KEYWORD, SEMANTIC, BOTH, FILTER }

public data class SearchHit(
    val view: ItemView,
    val match: MatchKind,
)

/**
 * The one-line answer (C4) as data: the host writes the sentence (« Cave, étagère du fond, carton 7, rangé le
 * 8 janvier ») with its own date formatter and plural rules, and links each place of the breadcrumb (C6).
 */
public data class AnswerLine(
    val itemId: String,
    val itemName: String,
    /** Root-first places, each a link (C6). */
    val places: List<AnswerPlace>,
    val containerId: String?,
    val containerLabel: String?,
    val status: LocationStatus,
    val archived: Boolean,
    val lentTo: String?,
    /** When the item reached its current state. */
    val since: Instant,
    /** Thumbnail file of the item's first photo, for `PhotoStore.absolutePath`. */
    val photoThumbName: String? = null,
)

public data class AnswerPlace(
    val id: String,
    val name: String,
)

/** What the search engine can do right now (shown as a discreet banner by the hosts). */
public sealed interface SearchMode {
    /** Keywords and meaning (C3). */
    public data object Hybrid : SearchMode

    /** The model is computing the vectors of [done] / [total] items; meanwhile results mix both. */
    public data class Indexing(
        val done: Int,
        val total: Int,
    ) : SearchMode

    /** Keywords and synonyms only (C1); the reason is explained to the user. */
    public data class KeywordOnly(
        val reason: KeywordOnlyReason,
    ) : SearchMode
}

public data class SearchResult(
    val query: String,
    val filters: SearchFilters,
    /** Best first; empty when nothing answers (« rien trouvé »). */
    val hits: List<SearchHit>,
    /** The first hit as a one-line answer; null when there is no hit or when only filters were given. */
    val answer: AnswerLine?,
)

/** A completion of what the user is typing (C12); never a sensitive item. */
public data class Suggestion(
    val text: String,
    val kind: SuggestionKind,
)

public enum class SuggestionKind { ITEM, TAG, PLACE }
