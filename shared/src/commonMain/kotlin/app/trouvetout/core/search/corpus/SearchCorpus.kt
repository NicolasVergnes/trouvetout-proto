package app.trouvetout.core.search.corpus

import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.usecase.CommandResult
import app.trouvetout.core.domain.usecase.ItemDraft
import app.trouvetout.core.domain.usecase.LocationRef
import app.trouvetout.core.search.SearchService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TimeSource

/** `corpus/fiches-reference.json`: the reference household the questions are asked about (docs/06 §2). */
@Serializable
public data class ReferenceHousehold(
    val formatVersion: Int = 1,
    val places: List<ReferencePlace>,
    val containers: List<ReferenceContainer> = emptyList(),
    val items: List<ReferenceItem>,
)

@Serializable
public data class ReferencePlace(
    val id: String,
    val name: String,
    val parent: String? = null,
)

@Serializable
public data class ReferenceContainer(
    val id: String,
    val label: String,
    val place: String,
)

@Serializable
public data class ReferenceItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val place: String? = null,
    val container: String? = null,
    val rawInput: String? = null,
    val status: String = "STORED",
    val lentTo: String? = null,
    val archived: Boolean = false,
    val sensitive: Boolean = false,
    val daysAgo: Int = 0,
)

/** One line of `corpus/recherche.jsonl`; `expect` null means « nothing should answer » (kind `absent`). */
@Serializable
public data class SearchQuestion(
    val id: String,
    val lang: String,
    val origin: String = "synthetic",
    val query: String,
    val expect: String? = null,
    val kind: String,
    val split: String = "dev",
    /** Other items that are just as right (identical duplicates). */
    val accept: List<String> = emptyList(),
    /** Known failure kept with its reason; counted, never asserted. */
    val xfail: String? = null,
) {
    val isAbsent: Boolean get() = expect == null
}

/** Outcome of one question: rank of the first right item (1-based), or null when absent from the top 10. */
public data class QuestionResult(
    val question: SearchQuestion,
    val rank: Int?,
    val top: List<String>,
    val latency: Duration,
) {
    val top1: Boolean get() = if (question.isAbsent) top.isEmpty() else rank == 1
    val top3: Boolean get() = if (question.isAbsent) top.isEmpty() else rank != null && rank <= 3
}

/** Rates over a group of questions. */
public data class Score(
    val total: Int,
    val top1: Int,
    val top3: Int,
) {
    public val top1Rate: Double get() = if (total == 0) 1.0 else top1.toDouble() / total
    public val top3Rate: Double get() = if (total == 0) 1.0 else top3.toDouble() / total
}

public data class SearchReport(
    val results: List<QuestionResult>,
) {
    private val answerable get() = results.filter { !it.question.isAbsent }

    public fun score(filter: (SearchQuestion) -> Boolean = { true }): Score {
        val group = answerable.filter { filter(it.question) }
        return Score(group.size, group.count { it.top1 }, group.count { it.top3 })
    }

    /** « Nothing found » answered when nothing should answer, and only then. */
    public val absent: Score
        get() {
            val group = results.filter { it.question.isAbsent }
            return Score(group.size, group.count { it.top1 }, group.count { it.top1 })
        }

    public fun latencyPercentile(percent: Int): Duration {
        val sorted = results.map { it.latency }.sorted()
        if (sorted.isEmpty()) return Duration.ZERO
        return sorted[((sorted.size - 1) * percent / HUNDRED).coerceIn(0, sorted.lastIndex)]
    }

    public val failures: List<QuestionResult> get() = results.filter { !it.top1 && it.question.xfail == null }

    private companion object {
        const val HUNDRED = 100
    }
}

/** A clock the corpus loader moves to date each item as the reference file says (`daysAgo`). */
public class ManualClock(
    public var now: Instant,
) : Clock {
    override fun now(): Instant = now
}

/** Builds the reference household through the journal and replays the questions (N3); JVM test and tool. */
public object SearchCorpus {
    public const val TOP1_THRESHOLD: Double = 0.80
    public const val TOP3_THRESHOLD: Double = 0.95
    public val LATENCY_BUDGET: Duration = Duration.parse("1s")

    /** Results kept per question: enough to find the right item's rank. */
    private const val DEPTH = 10

    private val json = Json { ignoreUnknownKeys = true }

    public fun parseHousehold(text: String): ReferenceHousehold =
        json.decodeFromString(ReferenceHousehold.serializer(), text)

    public fun parseQuestions(jsonl: String): List<SearchQuestion> = jsonl.lineSequence()
        .filter { it.isNotBlank() }
        .map { json.decodeFromString(SearchQuestion.serializer(), it) }
        .toList()

    /**
     * Writes [household] into [factory] (whose clock is [clock]): places and containers first, then the items
     * from the oldest to the newest. Returns reference id → item id.
     */
    public suspend fun load(
        factory: SharedFactory,
        clock: ManualClock,
        household: ReferenceHousehold,
    ): Map<String, String> {
        val today = clock.now
        clock.now = today - SETUP_AGE
        val placeIds = HashMap<String, String>()
        val pending = household.places.toMutableList()
        while (pending.isNotEmpty()) {
            val ready = pending.filter { it.parent == null || it.parent in placeIds }
            check(ready.isNotEmpty()) { "place cycle or unknown parent in the reference household" }
            for (place in ready) {
                val parent = place.parent?.let { placeIds.getValue(it) }
                val kind = if (parent == null) PlaceKind.ROOT else PlaceKind.ROOM
                val done = factory.placeCommands.create(place.name, parent, kind, source = EventSource.IMPORT).done()
                placeIds[place.id] = (done.events.single().payload as EventPayload.PlaceCreated).placeId
            }
            pending -= ready.toSet()
        }
        val containerIds =
            household.containers.associate { c ->
                val done = factory.containerCommands.create(
                    c.label,
                    placeIds.getValue(c.place),
                    source = EventSource.IMPORT,
                ).done()
                c.id to (done.events.single().payload as EventPayload.ContainerCreated).containerId
            }
        val itemIds = HashMap<String, String>()
        for (item in household.items.sortedByDescending { it.daysAgo }) {
            clock.now = today - item.daysAgo.days
            val location =
                item.container?.let { LocationRef.InContainer(containerIds.getValue(it)) }
                    ?: item.place?.let { LocationRef.InPlace(placeIds.getValue(it)) }
            val draft = ItemDraft(item.name, item.description, item.tags, sensitive = item.sensitive)
            val done = factory.itemCommands.create(draft, location, EventSource.TEXT, item.rawInput).done()
            val id = (done.events.first().payload as EventPayload.ItemCreated).itemId
            itemIds[item.id] = id
            clock.now += 1.minutes
            when (item.status) {
                "TAKEN" -> factory.itemCommands.take(id, EventSource.TEXT).done()
                "LENT" -> factory.itemCommands.lend(id, item.lentTo ?: "?", EventSource.TEXT).done()
            }
            if (item.archived) factory.itemCommands.setArchived(id, true, EventSource.TEXT).done()
        }
        clock.now = today
        return itemIds
    }

    /** Asks every question; a right answer is the expected item (or an accepted twin) at the given rank. */
    public suspend fun evaluate(
        search: SearchService,
        questions: List<SearchQuestion>,
        itemIds: Map<String, String>,
    ): SearchReport {
        val referenceOf = itemIds.entries.associate { (ref, id) -> id to ref }
        val results =
            questions.map { question ->
                val mark = TimeSource.Monotonic.markNow()
                val hits = search.search(question.query, limit = DEPTH).hits
                val latency = mark.elapsedNow()
                val top = hits.map { referenceOf[it.view.item.id] ?: it.view.item.id }
                val right = setOfNotNull(question.expect) + question.accept
                val rank = top.indexOfFirst { it in right }.takeIf { it >= 0 }?.plus(1)
                QuestionResult(question, rank, top, latency)
            }
        return SearchReport(results)
    }

    private val SETUP_AGE = 1_000.days

    private fun CommandResult.done(): CommandResult.Done =
        this as? CommandResult.Done ?: error("reference household rejected: ${(this as CommandResult.Rejected).reason}")
}
