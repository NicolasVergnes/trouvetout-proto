package app.trouvetout.tools

import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.usecase.CommandResult
import app.trouvetout.core.domain.usecase.ItemDraft
import app.trouvetout.core.domain.usecase.LocationRef
import app.trouvetout.core.ml.Label
import app.trouvetout.core.ml.PromptStyle
import app.trouvetout.core.platform.JvmImageCodec
import app.trouvetout.core.search.SearchTuning
import java.util.Locale

/** C10 on the CC set: each object has one photo, the other photo of the same kind is the question. */
internal class PhotoSearchResult(
    val total: Int,
    val top1: Int,
    val top3: Int,
) {
    val rate1: Double get() = top1.toDouble() / total
    val rate3: Double get() = top3.toDouble() / total
}

/**
 * Builds a household of one object per label that has two photos (named in French, stored at home, first photo
 * attached), then searches with the second photo through `SearchService.searchByPhoto`.
 */
internal suspend fun photoSearch(
    factory: SharedFactory,
    photos: List<EvalPhoto>,
    labels: Map<String, Label>,
): PhotoSearchResult {
    val pairs = photos.groupBy { it.key }.values.filter { it.size >= 2 && it[0].key in labels }
    val house = created(factory.placeCommands.create("Maison", kind = PlaceKind.ROOT, source = EventSource.TEXT))
    val itemOf = HashMap<String, String>()
    for (pair in pairs) {
        val key = pair[0].key
        val result =
            factory.itemCommands.create(
                ItemDraft(name = labels.getValue(key).fr),
                LocationRef.InPlace(house),
                EventSource.TEXT,
            )
        val itemId = created(result)
        itemOf[key] = itemId
        factory.photoAttacher.attach(PhotoOwnerType.ITEM, itemId, pair[0].bytes, EventSource.PHOTO)
    }
    factory.search.prepareAndWait()
    var top1 = 0
    var top3 = 0
    for (pair in pairs) {
        val expected = itemOf.getValue(pair[0].key)
        val ids = factory.search.searchByPhoto(pair[1].bytes).hits.map { it.view.item.id }
        if (ids.firstOrNull() == expected) top1++
        if (expected in ids.take(TOP)) top3++
    }
    return PhotoSearchResult(pairs.size, top1, top3)
}

/** The id the first event of a successful command created (place or item). */
private fun created(result: CommandResult): String {
    val event = (result as CommandResult.Done).events.first()
    return when (val payload = event.payload) {
        is EventPayload.PlaceCreated -> payload.placeId
        is EventPayload.ItemCreated -> payload.itemId
        else -> error("unexpected event")
    }
}

/**
 * Raw cosines, to set absolute floors: image to image for two photos of the same label against the best other
 * label, and image to label text ([--calibrate]).
 */
internal fun calibrate(
    engines: JvmEngines,
    photos: List<EvalPhoto>,
    labels: Map<String, Label>,
) {
    val dim = SearchTuning().dimension
    val codec = JvmImageCodec()
    val usable = photos.filter { it.key in labels }
    val vectors =
        usable.chunked(CALIBRATION_BATCH).flatMap { batch ->
            engines.vision().images(batch.map { checkNotNull(codec.transcodeJpeg(it.bytes, SIDE, QUALITY)).bytes }, dim)
        }
    val same = ArrayList<Float>()
    val bestOther = ArrayList<Float>()
    usable.forEachIndexed { i, photo ->
        var other = -1f
        usable.forEachIndexed { j, candidate ->
            if (i != j) {
                val cosine = dot(vectors[i], vectors[j])
                if (candidate.key == photo.key) same += cosine else other = maxOf(other, cosine)
            }
        }
        bestOther += other
    }
    println("image↔image same label: ${summary(same)}")
    println("image↔image best other label: ${summary(bestOther)}")
    // Image to the text of an item named after its label, as the search embeds it.
    val texts = labels.values.associate { it.key to PromptStyle.MODEL_CARD.document("${it.fr}. Rangé : Maison") }
    val keys = usable.map { it.key }.distinct()
    val textVectors = keys.zip(engines.vision().texts(keys.map { texts.getValue(it) }, dim)).toMap()
    val own = ArrayList<Float>()
    val otherText = ArrayList<Float>()
    usable.forEachIndexed { i, photo ->
        own += dot(vectors[i], textVectors.getValue(photo.key))
        otherText += keys.filter { it != photo.key }.maxOf { dot(vectors[i], textVectors.getValue(it)) }
    }
    println("image↔item text own label: ${summary(own)}")
    println("image↔item text best other label: ${summary(otherText)}")
}

private fun summary(values: List<Float>): String {
    val sorted = values.sorted()

    fun at(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
    return "n=%d p10=%.3f p50=%.3f p90=%.3f".format(Locale.ROOT, sorted.size, at(P10), at(P50), at(P90))
}

private const val TOP = 3
private const val CALIBRATION_BATCH = 4
private const val SIDE = 768
private const val QUALITY = 85
private const val P10 = 0.1
private const val P50 = 0.5
private const val P90 = 0.9
