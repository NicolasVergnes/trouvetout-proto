package app.trouvetout.core.search

import app.trouvetout.core.data.TickingClock
import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.db.openTestDatabase
import app.trouvetout.core.data.testEventStore
import app.trouvetout.core.domain.ids.MonotonicUlidGenerator
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.usecase.AttachmentCommands
import app.trouvetout.core.domain.usecase.CommandContext
import app.trouvetout.core.domain.usecase.CommandResult
import app.trouvetout.core.domain.usecase.ContainerCommands
import app.trouvetout.core.domain.usecase.ItemCommands
import app.trouvetout.core.domain.usecase.ItemDraft
import app.trouvetout.core.domain.usecase.LocationRef
import app.trouvetout.core.domain.usecase.PhotoFile
import app.trouvetout.core.domain.usecase.PlaceCommands
import app.trouvetout.core.log.NoopLogger
import app.trouvetout.core.ml.ImageEmbedder
import app.trouvetout.core.ml.Label
import app.trouvetout.core.ml.LabelCatalog
import app.trouvetout.core.ml.LabelCatalogHeader
import app.trouvetout.core.ml.ModelRuntime
import app.trouvetout.core.ml.TextEmbedder
import app.trouvetout.core.ml.TextModelSource
import app.trouvetout.core.platform.CoroutineDispatchers
import app.trouvetout.core.platform.EncodedImage
import app.trouvetout.core.platform.ImageCodec
import app.trouvetout.core.platform.sha256
import kotlinx.coroutines.Dispatchers
import kotlin.math.sqrt
import kotlin.random.Random

/** A small household written through the journal, and a search service over it. */
internal class SearchWorld(
    val model: FakeModelSource? = null,
    tuning: SearchTuning = SearchTuning(),
    val db: TrouveToutDb = openTestDatabase(),
    /** Whether the photo side is active (memory and settings, decided by ModelSettings in the app). */
    imagesWanted: Boolean = model?.imageModelId != null,
) {
    val clock = TickingClock()
    private val ctx = CommandContext(db.testEventStore(), MonotonicUlidGenerator(clock, Random(3)), clock, "device-S")
    val items = ItemCommands(ctx)
    private val places = PlaceCommands(ctx)
    private val containers = ContainerCommands(ctx)
    private val unconfined =
        CoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
    private val attachments = AttachmentCommands(ctx)

    /** Photo files by name: the fake « image » is the words it shows. */
    val photoFiles = HashMap<String, ByteArray>()

    /** Reads of stored photo files by the search. */
    var photoReads = 0
    val runtime = ModelRuntime(model, NoopLogger, imagesWanted = { imagesWanted })
    val search =
        SearchService(
            db,
            runtime,
            tuning,
            clock,
            unconfined,
            NoopLogger,
            PhotoFiles(PassthroughCodec) {
                photoReads++
                photoFiles[it]
            },
        )

    /** Attaches a « photo » showing [words] to [itemId]. */
    suspend fun photo(
        itemId: String,
        words: String,
    ) {
        val name = "photo-${photoFiles.size}.jpg"
        photoFiles[name] = words.encodeToByteArray()
        val file = PhotoFile(name, "t-$name", 100, 100, sha256(words.encodeToByteArray()).toHexString())
        attachments.attachPhoto(PhotoOwnerType.ITEM, itemId, file, EventSource.PHOTO) as CommandResult.Done
    }

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
) : TextEmbedder,
    ImageEmbedder {
    var images = 0
    var calls = 0
    var texts = 0
    var closed = false

    /** The next call throws, like a native error of the runtime. */
    var failNext = false

    override suspend fun embed(
        texts: List<String>,
        dim: Int,
    ): List<FloatArray> {
        if (failNext) {
            failNext = false
            error("native failure")
        }
        calls++
        this.texts += texts.size
        return texts.map { vector(it, dim) }
    }

    /**
     * A fake « image » is the words it shows, embedded like a text. Only the platform codec's output may reach the
     * model (its native decoder never sees a file of unknown origin, ADR-022): anything else fails the test.
     */
    override suspend fun embedImages(
        jpegs: List<ByteArray>,
        dim: Int,
    ): List<FloatArray> {
        if (failNext) {
            failNext = false
            error("native failure")
        }
        images += jpegs.size
        return jpegs.map {
            val text = it.decodeToString()
            check(text.startsWith(PassthroughCodec.MARK)) { "raw bytes reached the model" }
            vector(text.removePrefix(PassthroughCodec.MARK), dim)
        }
    }

    fun vector(
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
    override val imageModelId: String? = null,
    /** The text+vision file fails to load (not enough memory), the text file loads. */
    private val failWithImages: Boolean = false,
    /** Labels (B8) whose vectors are computed with the fake embedder, as `tools` does with the real model. */
    private val labels: List<Label> = emptyList(),
) : TextModelSource {
    val embedder = FakeEmbedder(concepts)
    var loads = 0
    var loadsWithImages = 0

    override suspend fun load(withImages: Boolean): TextEmbedder {
        loads++
        if (withImages) loadsWithImages++
        check(!fail) { "cannot load" }
        check(!(withImages && failWithImages)) { "cannot load the vision file" }
        return embedder
    }

    override fun labelCatalog(): ByteArray? {
        if (labels.isEmpty()) return null
        val dim = SearchTuning().dimension
        val vectors = FloatArray(labels.size * dim)
        labels.forEachIndexed { i, label -> embedder.vector("${label.fr} ${label.en}", dim).copyInto(vectors, i * dim) }
        val header = LabelCatalogHeader(checkNotNull(modelId), dim, "{fr} {en}", "test", labels.size)
        return LabelCatalog(header, labels, vectors).encode()
    }
}

/**
 * Gives the « image » back marked as re-encoded: the fake images are words, not pixels. « unreadable » is not an
 * image.
 */
object PassthroughCodec : ImageCodec {
    const val MARK = "jpeg:"

    override fun transcodeJpeg(
        source: ByteArray,
        maxSide: Int,
        quality: Int,
    ): EncodedImage? = if (source.decodeToString() == "unreadable") {
        null
    } else {
        EncodedImage((MARK + source.decodeToString()).encodeToByteArray(), maxSide, maxSide)
    }
}
