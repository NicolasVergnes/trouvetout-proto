package app.trouvetout.tools

import app.trouvetout.core.capture.Lang
import app.trouvetout.core.data.db.JvmDriverFactory
import app.trouvetout.core.di.SharedConfig
import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.ml.PromptStyle
import app.trouvetout.core.platform.DeviceInfo
import app.trouvetout.core.platform.JvmImageCodec
import app.trouvetout.core.search.SearchTuning
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.system.exitProcess
import kotlin.time.measureTime

/**
 * B8 evaluation (lot 7, ADR-022): names proposed for photographed objects, through the core's own path
 * (`SearchService.suggestNames`: platform transcoding to 768 px, the text+vision model, the bundled label catalog).
 *
 * - CI: the CC BY 2.0 Open Images set of `corpus/photos-cc.jsonl` (downloaded, checksum verified, cached, never
 *   committed; credits in `corpus/PHOTOS-CREDITS.md`) against the floors of [PHOTO_FLOORS].
 * - Locally: `--local <dir>` replays the user's own photos (lot 7 criterion: right label in the top 3 for ≥ 70 % of
 *   100 household photos). `<dir>/labels.csv` holds `file,key[,other|accepted|keys]`; the folder never enters the
 *   repository.
 * - `--templates` compares the ways of writing a label (`LabelTemplates`) on the same photos, before
 *   `genLabels` writes the catalog.
 *
 * Also checks that the text weights of both bundled files give the same vectors (one text identity).
 * Arguments: corpus directory, reports directory, `models/` directory, bundled model directory, image cache
 * directory, then options.
 */
fun main(args: Array<String>) {
    val corpus = File(args[0])
    val modelsDir = File(args[2])
    val options = args.drop(FIRST_OPTION)
    val local = options.windowed(2).firstOrNull { it[0] == "--local" }?.get(1)?.let(::File)
    val manifest = ManifestModel.read(File(modelsDir, "manifest.json"))
    val text = manifest.single { it.variant == "TEXT_ONLY" }
    val vision = manifest.single { it.variant == "OMNIMODAL" }
    val cache = File(args[ARG_CACHE]).apply { mkdirs() }
    val photos = if (local != null) localPhotos(local) else ccPhotos(File(corpus, "photos-cc.jsonl"), cache)
    println("${photos.size} photos (${if (local != null) "local" else "CC BY 2.0"})")
    val bundled = File(args[ARG_BUNDLED])
    val failed =
        JvmEngines(File(bundled, text.file), File(bundled, vision.file)).use { engines ->
            val identity = textIdentity(engines, corpus)
            println("text identity 270M vs 440M: min cosine %.6f".format(identity))
            val labels = readLabels(File(modelsDir, LABELS_TSV)).associateBy { it.key }
            explore(engines, options, File(modelsDir, LABELS_TSV), photos, labels)
            val catalog = File(modelsDir, LABELS_CATALOG).readBytes()
            val source = JvmModelSource(engines, text.identity, vision.identity, catalog)
            val naming = runBlocking { suggestAll(newFactory(source), photos) }
            val perPhoto = ", %.0f ms per photo".format(naming.msPerPhoto)
            println(rateLine("B8", naming.top1, naming.top3, naming.total) + perPhoto)
            naming.misses.forEach { println("  miss ${it.first}: ${it.second}") }
            // C10 needs two photos per label: the CC set only.
            val search = if (local == null) runBlocking { photoSearch(newFactory(source), photos, labels) } else null
            search?.let { println(rateLine("C10", it.top1, it.top3, it.total)) }
            val reports = File(args[1]).apply { mkdirs() }
            val report = File(reports, if (local != null) "photos-local.json" else "photos.json")
            report.writeText(naming.json(identity, search))
            underFloors(naming, search, identity, if (local != null) LOCAL_CRITERION else PHOTO_FLOORS)
        }
    exitProcess(if (failed) 1 else 0)
}

/** Optional studies before the measure: label templates (`--templates`, `--template-set a,b`), `--calibrate`. */
private fun explore(
    engines: JvmEngines,
    options: List<String>,
    tsv: File,
    photos: List<EvalPhoto>,
    labels: Map<String, app.trouvetout.core.ml.Label>,
) {
    val templateSet = options.windowed(2).firstOrNull { it[0] == "--template-set" }?.get(1)?.split(',')
    if ("--templates" in options || templateSet != null) compareTemplates(engines, tsv, photos, templateSet?.toSet())
    if ("--calibrate" in options) calibrate(engines, photos, labels)
}

private fun rateLine(
    name: String,
    top1: Int,
    top3: Int,
    total: Int,
): String = "%s top-1 %d/%d (%.1f %%), top-3 %d/%d (%.1f %%)".format(
    name,
    top1,
    total,
    top1 * PERCENT / total,
    top3,
    total,
    top3 * PERCENT / total,
)

/** True (and printed) when a measure is under its floor. */
private fun underFloors(
    naming: PhotoResult,
    search: PhotoSearchResult?,
    identity: Float,
    floors: Pair<Double, Double>,
): Boolean {
    val searchUnder = search != null && (search.rate1 < C10_FLOORS.first || search.rate3 < C10_FLOORS.second)
    val under = naming.rate1 < floors.first || naming.rate3 < floors.second || identity < IDENTITY_FLOOR || searchUnder
    if (under) println("UNDER FLOOR: B8 ≥ $floors, C10 ≥ $C10_FLOORS, identity ≥ $IDENTITY_FLOOR")
    return under
}

/**
 * CI floors on the CC set (top-1, top-3): lot 7 closing measure 135/205, 168/205, minus a photo; they never go
 * down.
 */
internal val PHOTO_FLOORS = 0.65 to 0.81

/** CI floors of the search by photo (C10) on the CC set (top-1, top-3): lot 7 closing measure 71/102, 84/102. */
internal val C10_FLOORS = 0.68 to 0.81

/** Lot 7 criterion on the user's household photos: top-3 ≥ 70 %. */
private val LOCAL_CRITERION = 0.0 to 0.70
private const val IDENTITY_FLOOR = 0.999f
private const val ARG_BUNDLED = 3
private const val ARG_CACHE = 4
private const val FIRST_OPTION = 5
private const val PERCENT = 100.0

internal class EvalPhoto(
    val id: String,
    val bytes: ByteArray,
    /** The expected label key. */
    val key: String,
    val accepted: Set<String>,
)

private class PhotoResult(
    val total: Int,
    val top1: Int,
    val top3: Int,
    val msPerPhoto: Double,
    val misses: List<Pair<String, String>>,
) {
    val rate1: Double get() = top1.toDouble() / total
    val rate3: Double get() = top3.toDouble() / total

    fun json(
        identity: Float,
        search: PhotoSearchResult?,
    ): String =
        """{"total":$total,"top1":$top1,"top3":$top3,"msPerPhoto":${"%.1f".format(Locale.ROOT, msPerPhoto)},""" +
            """"textIdentity":$identity""" +
            search?.let { ""","photoSearch":{"total":${it.total},"top1":${it.top1},"top3":${it.top3}}""" }.orEmpty() +
            "}"
}

/** An empty in-memory core, as a ≥ 6 GB phone with the default settings. */
private fun newFactory(source: JvmModelSource): SharedFactory = SharedFactory(
    SharedConfig(
        dataDirectory = Files.createTempDirectory("eval-photos").toString(),
        deviceInfo = EightGigabytes,
        driverFactory = JvmDriverFactory(null),
        imageCodec = JvmImageCodec(),
        captureLang = Lang.FR,
    ).apply { textModel = source },
)

/** Every photo through the core's naming path (B8). */
private suspend fun suggestAll(
    factory: SharedFactory,
    photos: List<EvalPhoto>,
): PhotoResult {
    var top1 = 0
    var top3 = 0
    val misses = ArrayList<Pair<String, String>>()
    factory.search.suggestNames(photos.first().bytes, Lang.EN) // loads the model outside the timing
    val time =
        measureTime {
            for (photo in photos) {
                val keys = factory.search.suggestNames(photo.bytes, Lang.EN).map { it.key }
                if (keys.firstOrNull() in photo.accepted) top1++
                if (keys.any { it in photo.accepted }) top3++ else misses += photo.id to keys.joinToString()
            }
        }
    factory.close()
    return PhotoResult(photos.size, top1, top3, time.inWholeMilliseconds.toDouble() / photos.size, misses)
}

/** Top-1 / top-3 of each label template on the same image vectors (direct cosine, no catalog file). */
private fun compareTemplates(
    engines: JvmEngines,
    tsv: File,
    photos: List<EvalPhoto>,
    only: Set<String>?,
) {
    val dim = SearchTuning().dimension
    val codec = JvmImageCodec()
    val images =
        photos.chunked(IMAGE_BATCH).flatMap { batch ->
            engines.vision().images(
                batch.map {
                    checkNotNull(codec.transcodeJpeg(it.bytes, IMAGE_SIDE, QUALITY)).bytes
                },
                dim,
            )
        }
    val labels = readLabels(tsv)
    for ((name, toTexts) in LabelTemplates.ALL.filterKeys { only == null || it in only }) {
        val vectors =
            labels.chunked(TEXT_BATCH).flatMap { batch ->
                val texts = batch.map(toTexts)
                val flat = engines.text().texts(texts.flatten(), dim)
                var offset = 0
                texts.map { variants ->
                    meanVector(flat.subList(offset, offset + variants.size)).also {
                        offset +=
                            variants.size
                    }
                }
            }
        var top1 = 0
        var top3 = 0
        photos.forEachIndexed { i, photo ->
            val ranked = labels.indices.sortedByDescending {
                dot(images[i], vectors[it])
            }.take(TOP).map { labels[it].key }
            if (ranked.first() in photo.accepted) top1++
            if (ranked.any { it in photo.accepted }) top3++
        }
        println("template %-9s top-1 %3d  top-3 %3d  / %d".format(name, top1, top3, photos.size))
    }
}

/** Lowest cosine between the 270M and 440M text vectors over corpus questions (identical weights expected). */
private fun textIdentity(
    engines: JvmEngines,
    corpus: File,
): Float {
    val questions =
        File(corpus, "recherche.jsonl").readLines().filter { it.isNotBlank() }.take(IDENTITY_SAMPLE).map {
            PromptStyle.MODEL_CARD.query(Json.parseToJsonElement(it).jsonObject.getValue("query").jsonPrimitive.content)
        }
    val dim = SearchTuning().dimension
    val a = engines.text().texts(questions, dim)
    val b = engines.vision().texts(questions, dim)
    return a.indices.minOf { dot(a[it], b[it]) }
}

/** The CC set: each image downloaded once into [cache], refused when its checksum differs. */
private fun ccPhotos(
    jsonl: File,
    cache: File,
): List<EvalPhoto> = jsonl.readLines().filter { it.isNotBlank() }.map { line ->
    val o = Json.parseToJsonElement(line).jsonObject

    fun field(name: String) = o.getValue(name).jsonPrimitive.content
    val id = field("id")
    val sha = field("sha256")
    val file = File(cache, "$id.jpg")
    if (!file.isFile || sha256Hex(file.readBytes()) != sha) {
        val partial = File(cache, "$id.part")
        URI(field("url")).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        check(sha256Hex(partial.readBytes()) == sha) { "checksum mismatch for $id" }
        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    val accepted = setOf(field("expect")) + o.getValue("accept").jsonArray.map { it.jsonPrimitive.content }
    EvalPhoto(id, file.readBytes(), field("expect"), accepted)
}

/** The user's folder: `labels.csv` lines `file,key[,a|b|c]`; a `#` starts a comment. */
private fun localPhotos(dir: File): List<EvalPhoto> = File(dir, "labels.csv").readLines()
    .map { it.substringBefore('#').trim() }
    .filter { it.isNotEmpty() && !it.startsWith("file,") }
    .map { line ->
        val cells = line.split(',').map { it.trim() }
        val accepted = setOf(cells[1]) + cells.getOrNull(2)?.split('|')?.map { it.trim() }.orEmpty()
        EvalPhoto(cells[0], File(dir, cells[0]).readBytes(), cells[1], accepted)
    }

private object EightGigabytes : DeviceInfo {
    override val totalRamBytes: Long = 8L * 1024 * 1024 * 1024
    override val osName: String = "eval"
    override val osVersion: String = ""
}

private const val IMAGE_SIDE = 768
private const val QUALITY = 85
private const val IMAGE_BATCH = 4
private const val TEXT_BATCH = 32
private const val TOP = 3
private const val IDENTITY_SAMPLE = 40
