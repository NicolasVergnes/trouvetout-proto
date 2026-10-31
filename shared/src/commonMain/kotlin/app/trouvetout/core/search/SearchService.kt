package app.trouvetout.core.search

import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.repository.ItemView
import app.trouvetout.core.domain.model.LocationStatus
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.log.LogLevel
import app.trouvetout.core.log.Logger
import app.trouvetout.core.ml.KeywordOnlyReason
import app.trouvetout.core.ml.ModelRuntime
import app.trouvetout.core.platform.CoroutineDispatchers
import app.trouvetout.core.platform.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Hybrid search (C1–C4, C11, C12): keywords with tolerance and synonyms, meaning through the embedded model,
 * fused, adjusted by recency and frequency, answered in one line. Works without the model (keyword only).
 *
 * Nothing runs in the background (N6): the host calls [prepare] when search may be used (screen opened, app
 * in the foreground) and [release] when the app leaves the foreground or memory runs low.
 */
public class SearchService internal constructor(
    private val db: TrouveToutDb,
    private val runtime: ModelRuntime,
    private val tuning: SearchTuning,
    private val clock: Clock,
    private val dispatchers: CoroutineDispatchers,
    private val logger: Logger,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    /** Guards [snapshot] and [semantic]. */
    private val lock = Mutex()
    private var snapshot: SearchSnapshot? = null
    private val semantic = SemanticIndex(tuning.dimension)
    private var indexing: Job? = null

    private val modeState = MutableStateFlow(idleMode())

    /** What the engine can do right now; the hosts show a banner when it is not [SearchMode.Hybrid]. */
    public val mode: StateFlow<SearchMode> = modeState.asStateFlow()

    /** Refreshes the index if the journal changed and computes the missing vectors, in the foreground only. */
    public fun prepare() {
        scope.launch { runCatchingLogged("prepare") { startIndexing(fresh()) } }
    }

    /** [prepare], then waits until every vector is computed (evaluation tool, tests). */
    public suspend fun prepareAndWait() {
        startIndexing(fresh())
        indexing?.join()
    }

    /**
     * Searches the active items. A blank [query] with [filters] lists the filtered items (pinned first, most
     * recently moved first); a blank query without filters returns nothing.
     */
    public suspend fun search(
        query: String,
        filters: SearchFilters = SearchFilters(),
        limit: Int = DEFAULT_LIMIT,
    ): SearchResult {
        val snap = fresh()
        val allowed = allowedItems(snap, filters)
        val text = query.trim()
        if (text.isEmpty()) return SearchResult(query, filters, listFiltered(snap, allowed, filters, limit), null)

        val keyword = if (tuning.semanticOnly) emptyList() else keywordRanking(snap, text, allowed)
        val nearest = if (tuning.keywordOnly) emptyList() else nearest(text, allowed)
        if (keyword.isEmpty() && (nearest.firstOrNull()?.second ?: 0f) < tuning.semanticAnswerFloor) {
            return SearchResult(query, filters, emptyList(), null)
        }
        val semanticHits = Ranking.semanticCandidates(nearest, tuning)
        val semanticMean = nearest.map { it.second.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0
        val fused = Ranking.combine(keyword, semanticHits, tuning, semanticMean)
        val opened = openedCounts()
        val now = clock.now()
        val keywordSet = keyword.mapTo(HashSet()) { it.first }
        val semanticSet = semanticHits.mapTo(HashSet()) { it.first }
        val archivedRequested = filters.state == ItemStateFilter.ARCHIVED
        val hits =
            fused.entries
                .map { (id, score) ->
                    val view = snap.views[snap.indexOf.getValue(id)]
                    view to Ranking.adjust(score, view, opened[id] ?: 0, now, tuning, archivedRequested)
                }.sortedByDescending { it.second }
                .take(limit)
                .map { (view, _) ->
                    val id = view.item.id
                    val kind =
                        when {
                            id in keywordSet && id in semanticSet -> MatchKind.BOTH
                            id in keywordSet -> MatchKind.KEYWORD
                            else -> MatchKind.SEMANTIC
                        }
                    SearchHit(view, kind)
                }
        return SearchResult(query, filters, hits, hits.firstOrNull()?.view?.let { answerOf(it) })
    }

    /** Filters without a query: pinned first, then the most recently moved. */
    private fun listFiltered(
        snap: SearchSnapshot,
        allowed: Set<String>,
        filters: SearchFilters,
        limit: Int,
    ): List<SearchHit> {
        if (filters.isEmpty) return emptyList()
        return snap.views
            .filter { it.item.id in allowed }
            .sortedWith(compareByDescending<ItemView> { it.item.pinned }.thenByDescending { it.location.since })
            .take(limit)
            .map { SearchHit(it, MatchKind.FILTER) }
    }

    /** Keyword matches among the allowed items, best first, with their score. */
    private fun keywordRanking(
        snap: SearchSnapshot,
        text: String,
        allowed: Set<String>,
    ): List<Pair<String, Double>> = snap.keyword
        .search(text)
        .filterKeys { snap.views[it].item.id in allowed }
        .entries
        .sortedByDescending { it.value }
        .map { snap.views[it.key].item.id to it.value }

    /** Completions of [prefix] among item names, tags and places (C12), never from a sensitive item. */
    public suspend fun suggest(
        prefix: String,
        limit: Int = DEFAULT_SUGGESTIONS,
    ): List<Suggestion> {
        val words = SearchText.words(prefix)
        if (words.isEmpty()) return emptyList()
        val snap = fresh()
        val opened = openedCounts()
        val visible = snap.views.filter { !it.item.sensitive }
        val candidates = LinkedHashMap<String, Pair<Suggestion, Long>>()

        fun offer(
            text: String,
            kind: SuggestionKind,
            weight: Long,
        ) {
            val key = text.trim().lowercase()
            if (key.isEmpty() || !matchesPrefix(text, words)) return
            val previous = candidates[key]
            if (previous == null || previous.second < weight) candidates[key] = Suggestion(text.trim(), kind) to weight
        }
        visible.forEach { offer(it.item.name, SuggestionKind.ITEM, ITEM_WEIGHT + (opened[it.item.id] ?: 0)) }
        visible.flatMap { it.item.tags }.groupingBy { it }.eachCount().forEach { (tag, n) ->
            offer(tag, SuggestionKind.TAG, n.toLong())
        }
        visible.flatMap { it.path.places }.distinctBy { it.id }.forEach { offer(it.name, SuggestionKind.PLACE, 0) }
        return candidates.values.sortedWith(
            compareByDescending<Pair<Suggestion, Long>> {
                it.second
            }.thenBy { it.first.text.length },
        )
            .take(limit)
            .map { it.first }
    }

    /** Counts an item opened from the results (frequency signal, C3). */
    public suspend fun recordOpened(itemId: String): Unit = withContext(dispatchers.io) {
        db.searchUsageQueries.recordOpened(itemId, clock.now().toEpochMilliseconds())
    }

    /** Stops computing vectors and frees the model (app in the background, memory warning). */
    public suspend fun release() {
        indexing?.cancelAndJoin()
        indexing = null
        runtime.release()
        modeState.value = idleMode()
    }

    internal fun close() {
        scope.cancel()
    }

    // --- Index maintenance -------------------------------------------------------------------------------

    private suspend fun fresh(): SearchSnapshot {
        val version = withContext(dispatchers.io) { SearchSnapshot.version(db) }
        lock.withLock { snapshot?.takeIf { it.version == version }?.let { return it } }
        val loaded = withContext(dispatchers.io) { SearchSnapshot.load(db) }
        lock.withLock { snapshot = loaded }
        return loaded
    }

    private fun startIndexing(snap: SearchSnapshot) {
        if (tuning.keywordOnly) return
        runtime.unavailableReason?.let {
            modeState.value = SearchMode.KeywordOnly(it)
            return
        }
        if (indexing?.isActive == true) return
        indexing =
            scope.launch {
                runCatchingLogged("indexing") {
                    var current = snap
                    while (true) {
                        index(current)
                        val latest = fresh()
                        if (latest.version == current.version) break
                        current = latest
                    }
                }
                modeState.value = idleMode()
            }
    }

    /** Brings the stored and in-memory vectors in line with [snap]; resumable, batch by batch. */
    private suspend fun index(snap: SearchSnapshot) {
        val modelId = runtime.modelId ?: return
        val key = tuning.modelKey(modelId)
        val texts = snap.documents.associate { it.itemId to it.embeddingText(tuning) }
        val hashes = texts.mapValues { (_, text) -> hashOf(text) }
        val stored = withContext(dispatchers.io) { db.embeddingQueries.hashes(OWNER_ITEM, key).executeAsList() }
        val storedHash = stored.associate { it.owner_id to it.text_hash }
        loadStoredVectors(key, hashes, storedHash)
        val inMemory = lock.withLock { texts.keys.filterTo(HashSet()) { it in semantic } }
        val missing = texts.keys.filter { storedHash[it] != hashes[it] || it !in inMemory }
        var done = 0
        if (missing.isNotEmpty()) modeState.value = SearchMode.Indexing(0, missing.size)
        for (batch in missing.chunked(BATCH)) {
            val vectors =
                runtime.withModel { model ->
                    model.embed(batch.map { texts.getValue(it) }, tuning.dimension)
                }
            if (vectors == null) {
                modeState.value = idleMode()
                return
            }
            // A vector with NaN (a known float16 pitfall of the model) is never stored: keywords still find the item.
            val computed = batch.zip(vectors).filter { (_, vector) ->
                vector.size == tuning.dimension &&
                    vector.all { it.isFinite() }
            }
            withContext(dispatchers.io) {
                db.transaction {
                    computed.forEach { (id, vector) ->
                        db.embeddingQueries.upsert(
                            OWNER_ITEM,
                            id,
                            key,
                            tuning.dimension.toLong(),
                            Float16.encode(vector),
                            hashes.getValue(id),
                        )
                    }
                }
            }
            lock.withLock { computed.forEach { (id, vector) -> semantic.put(id, vector) } }
            done += batch.size
            modeState.value = SearchMode.Indexing(done, missing.size)
        }
        val orphans = storedHash.keys - texts.keys
        withContext(dispatchers.io) {
            db.transaction {
                orphans.forEach { db.embeddingQueries.deleteOne(OWNER_ITEM, it, key) }
                db.embeddingQueries.deleteOtherModels(key)
            }
        }
        lock.withLock { semantic.retain(texts.keys) }
    }

    /** First use after launch: vectors already computed for the current text are read, not recomputed. */
    private suspend fun loadStoredVectors(
        key: String,
        hashes: Map<String, String>,
        storedHash: Map<String, String>,
    ) {
        val loaded = lock.withLock { semantic.size }
        if (loaded > 0) return
        val rows =
            withContext(dispatchers.io) {
                db.embeddingQueries.vectors(OWNER_ITEM, key, tuning.dimension.toLong()).executeAsList()
            }
        lock.withLock {
            for (row in rows) {
                if (storedHash[row.owner_id] ==
                    hashes[row.owner_id]
                ) {
                    semantic.put(row.owner_id, Float16.decode(row.vector))
                }
            }
        }
    }

    private suspend fun nearest(
        text: String,
        allowed: Set<String>,
    ): List<Pair<String, Float>> {
        if (runtime.unavailableReason != null || lock.withLock { semantic.size } == 0) return emptyList()
        val query =
            runtime.withModel { model ->
                model.embed(listOf(tuning.promptStyle.query(text)), tuning.dimension).single()
            }
                ?: return emptyList()
        return lock.withLock { semantic.nearest(query, tuning.semanticCandidates) { it in allowed } }
    }

    private fun allowedItems(
        snap: SearchSnapshot,
        filters: SearchFilters,
    ): Set<String> = snap.views
        .filter { view ->
            (filters.placeId == null || view.path.places.any { it.id == filters.placeId }) &&
                (filters.containerId == null || view.path.container?.id == filters.containerId) &&
                (filters.tag == null || view.item.tags.any { it.equals(filters.tag, ignoreCase = true) }) &&
                (filters.state == null || stateOf(view) == filters.state) &&
                (filters.movedSince == null || view.location.since >= filters.movedSince)
        }.mapTo(HashSet()) { it.item.id }

    private fun stateOf(view: ItemView): ItemStateFilter = when {
        view.item.archived -> ItemStateFilter.ARCHIVED
        view.location.status == LocationStatus.TAKEN -> ItemStateFilter.TAKEN
        view.location.status == LocationStatus.LENT -> ItemStateFilter.LENT
        else -> ItemStateFilter.STORED
    }

    private suspend fun openedCounts(): Map<String, Long> = withContext(dispatchers.io) {
        db.searchUsageQueries.selectAll().executeAsList().associate { it.item_id to it.opened_count }
    }

    private fun idleMode(): SearchMode = if (tuning.keywordOnly) {
        SearchMode.KeywordOnly(KeywordOnlyReason.NO_MODEL)
    } else {
        runtime.unavailableReason?.let { SearchMode.KeywordOnly(it) } ?: SearchMode.Hybrid
    }

    private suspend fun runCatchingLogged(
        what: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Never the query or an item: the class of the failure only.
            logger.log(LogLevel.WARN, TAG, "$what failed: ${e::class.simpleName}")
        }
    }

    private companion object {
        const val TAG = "SearchService"
        const val OWNER_ITEM = "item"
        const val DEFAULT_LIMIT = 20
        const val DEFAULT_SUGGESTIONS = 6
        const val ITEM_WEIGHT = 1_000L
        const val BATCH = 8

        fun hashOf(text: String): String = sha256(text.encodeToByteArray()).toHexString().take(HASH_CHARS)

        const val HASH_CHARS = 16

        /** Every typed word starts a word of [text] (« pas » + « for » → « passeport for travel »). */
        fun matchesPrefix(
            text: String,
            typed: List<String>,
        ): Boolean {
            val words = SearchText.words(text)
            return typed.all { prefix -> words.any { it.startsWith(prefix) } }
        }
    }

    /** One-line answer of an item (C4), with its first photo. */
    private suspend fun answerOf(view: ItemView): AnswerLine = AnswerLine(
        itemId = view.item.id,
        itemName = view.item.name,
        places = view.path.places.map { AnswerPlace(it.id, it.name) },
        containerId = view.path.container?.id,
        containerLabel = view.path.container?.label,
        status = view.location.status,
        archived = view.item.archived,
        lentTo = view.location.lentTo,
        since = view.location.since,
        photoThumbName =
            withContext(dispatchers.io) {
                db.photoQueries.selectByOwner(
                    PhotoOwnerType.ITEM.name,
                    view.item.id,
                ).executeAsList().firstOrNull()?.thumb_name
            },
    )
}
