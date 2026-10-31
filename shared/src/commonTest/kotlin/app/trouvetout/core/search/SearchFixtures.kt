package app.trouvetout.core.search

import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.testEventStore
import app.trouvetout.core.domain.ids.MonotonicUlidGenerator
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.usecase.CommandContext
import app.trouvetout.core.domain.usecase.CommandResult
import app.trouvetout.core.domain.usecase.ContainerCommands
import app.trouvetout.core.domain.usecase.ItemCommands
import app.trouvetout.core.domain.usecase.ItemDraft
import app.trouvetout.core.domain.usecase.LocationRef
import app.trouvetout.core.domain.usecase.PlaceCommands
import app.trouvetout.core.log.NoopLogger
import app.trouvetout.core.ml.ModelRuntime
import app.trouvetout.core.ml.TextEmbedder
import app.trouvetout.core.ml.TextModelSource
import app.trouvetout.core.platform.CoroutineDispatchers
import kotlinx.coroutines.Dispatchers
import kotlin.math.sqrt
import kotlin.random.Random

/** A small household written through the journal, and a search service over it. */
internal class SearchWorld(
    val model: FakeModelSource? = null,
    tuning: SearchTuning = SearchTuning(),
    val db: TrouveToutDb = openTestDatabase(),
) {
    val clock = TickingClock()
    private val ctx = CommandContext(db.testEventStore(), MonotonicUlidGenerator(clock, Random(3)), clock, "device-S")
    val items = ItemCommands(ctx)
    private val places = PlaceCommands(ctx)
    private val containers = ContainerCommands(ctx)
    private val unconfined =
        CoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
    val runtime = ModelRuntime(model, NoopLogger)
    val search = SearchService(db, runtime, tuning, clock, unconfined, NoopLogger)

    suspend fun place(
        name: String,
        parentId: String? = null,
    ): String {
        val done = places.create(
            name,
            parentId,
            if (parentId ==
                null
            ) {
                PlaceKind.ROOT
            } else {
                PlaceKind.ROOM
            },
            source = EventSource.TEXT,
        )
        return ((done as CommandResult.Done).events.single().payload as EventPayload.PlaceCreated).placeId
    }

    suspend fun container(
        label: String,
        placeId: String,
    ): String {
        val done = containers.create(label, placeId, source = EventSource.TEXT) as CommandResult.Done
        return (done.events.single().payload as EventPayload.ContainerCreated).containerId
    }

    suspend fun item(
        name: String,
        at: LocationRef? = null,
        description: String? = null,
        tags: List<String> = emptyList(),
        sensitive: Boolean = false,
        rawInput: String? = null,
    ): String {
        val done =
            items.create(ItemDraft(name, description, tags, sensitive = sensitive), at, EventSource.TEXT, rawInput)
                as CommandResult.Done
        return (done.events.first().payload as EventPayload.ItemCreated).itemId
    }
}

/**
 * Deterministic stand-in for the model: a bag of words hashed into the vector, where [concepts] maps a word to
 * the concept it shares with others (« kindle » and « câble » both → « charge ») to simulate meaning.
 */
class FakeEmbedder(
    private val concepts: Map<String, String>,
) : TextEmbedder {
    var calls = 0
    var texts = 0
    var closed = false

    override suspend fun embed(
        texts: List<String>,
        dim: Int,
    ): List<FloatArray> {
        calls++
        this.texts += texts.size
        return texts.map { vector(it, dim) }
    }

    private fun vector(
        prompted: String,
        dim: Int,
    ): FloatArray {
        val text = prompted.substringAfterLast("text: ").substringAfterLast("query: ")
        val out = FloatArray(dim)
        for (term in SearchText.terms(text)) {
            val key = concepts[term] ?: term
            out[(key.hashCode() and Int.MAX_VALUE) % dim] += 1f
        }
        val norm = sqrt(out.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) for (i in out.indices) out[i] /= norm
        return out
    }

    override fun close() {
        closed = true
    }
}

class FakeModelSource(
    concepts: Map<String, String> = emptyMap(),
    override val modelId: String? = "fake-model",
    override val isSupported: Boolean = true,
    private val fail: Boolean = false,
) : TextModelSource {
    val embedder = FakeEmbedder(concepts)
    var loads = 0

    override suspend fun load(): TextEmbedder {
        loads++
        check(!fail) { "cannot load" }
        return embedder
    }
}
