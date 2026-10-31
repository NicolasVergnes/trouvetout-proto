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

    /** Guards [indexing]: one indexing job at a time. */
    private val indexLock = Mutex()
    private var indexing: Job? = null

    /** The journal state whose vectors are all computed; a search on a newer state restarts the indexing. */
    private var indexedVersion: JournalVersion? = null

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
        indexLock.withLock { indexing }?.join()
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
        // The journal changed since the vectors were computed (a capture, a return from the background): catch up.
        if (snap.version != indexedVersion) startIndexing(snap)
        val allowed = allowedItems(snap, filters)
        // A pasted page must not freeze the screen: a question is a few words.
        val text = query.trim().take(MAX_QUERY_CHARS)
        if (text.isEmpty()) return SearchResult(query, filters, listFiltered(snap, allowed, filters, limit), null)

        val keyword = if (tuning.semanticOnly) emptyList() else keywordRanking(snap, text, allowed)
        // C1: an item that has every word of the question is a sure keyword answer; its share grows accordingly.
        val sure = keyword.firstOrNull()?.let { it.coverage >= 1.0 } == true
        val effective = if (sure) tuning.copy(keywordShare = tuning.fullMatchKeywordShare) else tuning
        val nearest = if (tuning.keywordOnly) emptyList() else nearest(text, allowed)
        if (keyword.isEmpty() && (nearest.firstOrNull()?.second ?: 0f) < tuning.semanticAnswerFloor) {
            return SearchResult(query, filters, emptyList(), null)
        }
        val hits = rank(snap, keyword.map { it.id to it.score }, nearest, filters, limit, effective)
        return SearchResult(query, filters, hits, hits.firstOrNull()?.view?.let { answerOf(it) })
    }

    /** Fusion, then recency, frequency and archive signals; equal scores keep a stable order. */
    private suspend fun rank(
        snap: SearchSnapshot,
        keyword: List<Pair<String, Double>>,
        nearest: List<Pair<String, Float>>,
        filters: SearchFilters,
        limit: Int,
        tuning: SearchTuning,
    ): List<SearchHit> {
        val semanticHits = Ranking.semanticCandidates(nearest, tuning)
        val semanticMean = nearest.map { it.second.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0
        val fused = Ranking.combine(keyword, semanticHits, tuning, semanticMean)
        val opened = openedCounts()
        val now = clock.now()
        val keywordSet = keyword.mapTo(HashSet()) { it.first }
        val semanticSet = semanticHits.mapTo(HashSet()) { it.first }
        val archivedRequested = filters.state == ItemStateFilter.ARCHIVED
        return fused.entries
            .map { (id, score) ->
                val view = snap.views[snap.indexOf.getValue(id)]
                view to Ranking.adjust(score, view, opened[id] ?: 0, now, tuning, archivedRequested)
            }.sortedWith(
                // Equal scores: the most recently moved first, then a stable order.
                compareByDescending<Pair<ItemView, Double>> { it.second }
                    .thenByDescending { it.first.location.since }
                    .thenBy { it.first.item.id },
            ).take(limit)
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

    /** Keyword matches among the allowed items, best first. */
    private fun keywordRanking(
        snap: SearchSnapshot,
        text: String,
        allowed: Set<String>,
    ): List<KeywordHit> = snap.keyword
        .match(text)
        .filterKeys { snap.views[it].item.id in allowed }
        .entries
        .sortedByDescending { it.value.score }
        .map { KeywordHit(snap.views[it.key].item.id, it.value.score, it.value.coverage) }

    private class KeywordHit(
        val id: String,
        val score: Double,
        val coverage: Double,
    )

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
        indexLock.withLock {
            indexing?.cancelAndJoin()
            indexing = null
        }
        runtime.release()
        modeState.value = idleMode()
    }

    /** Before the store closes ([app.trouvetout.core.di.SharedFactory.wipeAllData]): no write may still be running. */
    internal suspend fun shutdown() {
        indexLock.withLock { indexing?.cancelAndJoin() }
        scope.cancel()
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

    private suspend fun startIndexing(snap: SearchSnapshot) {
        if (tuning.keywordOnly) return
        runtime.unavailableReason?.let {
            modeState.value = SearchMode.KeywordOnly(it)
            return
        }
        indexLock.withLock {
            if (indexing?.isActive == true) return
            indexing =
                scope.launch {
                    runCatchingLogged("indexing") {
                        var current = snap
                        while (index(current)) {
                            indexedVersion = current.version
                            val latest = fresh()
                            if (latest.version == current.version) break
                            current = latest
                        }
                    }
                    modeState.value = idleMode()
                }
        }
    }

    /**
     * Brings the stored and in-memory vectors in line with [snap]; resumable, batch by batch. False when the model
     * could not be used (the search stays on keywords).
     */
    private suspend fun index(snap: SearchSnapshot): Boolean {
        val modelId = runtime.modelId ?: return false
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
            val vectors = embedBatch(batch.map { texts.getValue(it) })
            if (vectors == null) {
                modeState.value = idleMode()
                return false
            }
            store(key, batch.zip(vectors), hashes)
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
        return true
    }

    /**
     * Vectors of [texts] in order, null for a text the model rejects (too long, odd characters) or that yields an
     * unusable vector (NaN, a known float16 pitfall): a failing batch is retried text by text so that one text never
     * blocks the others. Null altogether when the model cannot be used.
     */
    private suspend fun embedBatch(texts: List<String>): List<FloatArray?>? {
        fun usable(vector: FloatArray) = vector.size == tuning.dimension && vector.all { it.isFinite() }
        val whole = attempt { runtime.withModel { it.embed(texts, tuning.dimension) } }
        if (whole != null) return whole.getOrNull()?.map { v -> v.takeIf(::usable) }
        return texts.map { text ->
            val single = attempt { runtime.withModel { it.embed(listOf(text), tuning.dimension) } } ?: return@map null
            val vectors = single.getOrNull() ?: return null
            vectors.singleOrNull()?.takeIf(::usable)
        }
    }

    /** Success (null value: model unusable), or null when the computation threw (logged by class only). */
    private suspend fun <T> attempt(block: suspend () -> T?): Result<T?>? = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        logger.log(LogLevel.WARN, TAG, "embedding failed: ${e::class.simpleName}")
        null
    }

    /** Writes the computed vectors; an item without one loses its old vector too (it must not answer for old text). */
    private suspend fun store(
        key: String,
        results: List<Pair<String, FloatArray?>>,
        hashes: Map<String, String>,
    ) {
        val computed = results.mapNotNull { (id, vector) -> vector?.let { id to it } }
        val failed = results.filter { it.second == null }.map { it.first }
        withContext(dispatchers.io) {
            db.transaction {
                computed.forEach { (id, vector) ->
                    val blob = Float16.encode(vector)
                    db.embeddingQueries.upsert(
                        OWNER_ITEM,
                        id,
                        key,
                        tuning.dimension.toLong(),
                        blob,
                        hashes.getValue(id),
                    )
                }
                failed.forEach { db.embeddingQueries.deleteOne(OWNER_ITEM, it, key) }
            }
        }
        lock.withLock {
            computed.forEach { (id, vector) -> semantic.put(id, vector) }
            failed.forEach { semantic.remove(it) }
        }
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
                val vector = Float16.decode(row.vector)
                // A damaged row (wrong size, NaN) is skipped: the item is embedded again by this indexing pass.
                val valid = vector.size == tuning.dimension && vector.all { it.isFinite() }
                if (valid && storedHash[row.owner_id] == hashes[row.owner_id]) semantic.put(row.owner_id, vector)
            }
        }
    }

    private suspend fun nearest(
        text: String,
        allowed: Set<String>,
    ): List<Pair<String, Float>> {
        if (runtime.unavailableReason != null || lock.withLock { semantic.size } == 0) return emptyList()
        // Rule 7: whatever the model does (native error, memory, a bad vector), the search answers with keywords.
        val query =
            try {
                runtime.withModel { model ->
                    model.embed(listOf(tuning.promptStyle.query(text)), tuning.dimension).single()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.log(LogLevel.WARN, TAG, "query embedding failed: ${e::class.simpleName}")
                null
            }
        if (query == null || query.size != tuning.dimension || !query.all { it.isFinite() }) return emptyList()
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
        const val MAX_QUERY_CHARS = 200

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
