package app.trouvetout.tools

import app.trouvetout.core.capture.Lang
import app.trouvetout.core.data.db.JvmDriverFactory
import app.trouvetout.core.di.SharedConfig
import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.ml.PromptStyle
import app.trouvetout.core.ml.TextEmbedder
import app.trouvetout.core.ml.TextModelSource
import app.trouvetout.core.platform.EncodedImage
import app.trouvetout.core.platform.ImageCodec
import app.trouvetout.core.platform.JvmDeviceInfo
import app.trouvetout.core.search.SearchTuning
import app.trouvetout.core.search.corpus.ManualClock
import app.trouvetout.core.search.corpus.ReferenceHousehold
import app.trouvetout.core.search.corpus.ReferenceItem
import app.trouvetout.core.search.corpus.Score
import app.trouvetout.core.search.corpus.SearchCorpus
import app.trouvetout.core.search.corpus.SearchQuestion
import app.trouvetout.core.search.corpus.SearchReport
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Instant

/**
 * N3 / N2 evaluation (docs/06 §2): builds the reference household through the journal, asks every question of
 * `corpus/recherche.jsonl` in three modes (keywords only, meaning only, hybrid) with the real EmbeddingGemma 2
 * file the apps bundle, then measures the hybrid latency over 5 000 items. Writes `search.json` and fails under
 * 80 % top-1 / 95 % top-3 (French and English, hybrid) or above 1 s at the 95th percentile.
 *
 * Arguments: corpus directory, reports directory, model file, then options:
 * `--mode all|keyword|semantic|hybrid`, `--prompt LITERT|MODEL_CARD|NONE`, `--dim 128|256|512|768`, `--no-latency`.
 */
fun main(args: Array<String>) {
    val corpus = File(args[0])
    val reports = File(args[1]).apply { mkdirs() }
    val options = Options.parse(args.drop(FIRST_OPTION))
    val household = SearchCorpus.parseHousehold(File(corpus, "fiches-reference.json").readText())
    val questions = SearchCorpus.parseQuestions(File(corpus, "recherche.jsonl").readText())
    val model = CachingModel(File(args[2]))

    val base = SearchTuning(promptStyle = options.prompt, dimension = options.dim)
    val reportsByMode = runBlocking { runModes(model, base, options.mode, household, questions) }
    val hybrid = reportsByMode["hybrid"]
    hybrid?.failures?.forEach { failure ->
        val q = failure.question
        println(
            "  FAIL ${q.id} [${q.kind}/${q.lang}/${q.split}] « ${q.query} » → ${q.expect} got ${failure.top.take(TOP)}",
        )
    }
    val latency = if (options.latency &&
        hybrid != null
    ) {
        runBlocking { latency(model, base, household, questions) }
    } else {
        null
    }
    latency?.let { println("eval-search latency over ${it.items} items: p50 ${it.p50Ms} ms, p95 ${it.p95Ms} ms") }
    File(reports, "search.json").writeText(toJson(reportsByMode, latency, options))
    model.close()

    val failures = thresholdFailures(hybrid, latency)
    println(
        if (failures.isEmpty()) "eval-search: OK" else "eval-search: thresholds not met: ${failures.joinToString()}",
    )
    if (failures.isNotEmpty() && options.mode in setOf("all", "hybrid")) exitProcess(1)
}

/** Keywords only, meaning only and hybrid, each on a fresh core, the same questions. */
private suspend fun runModes(
    model: CachingModel,
    base: SearchTuning,
    only: String,
    household: ReferenceHousehold,
    questions: List<SearchQuestion>,
): Map<String, SearchReport> {
    val modes =
        mapOf(
            "keyword" to base.copy(keywordOnly = true),
            "semantic" to base.copy(semanticOnly = true),
            "hybrid" to base,
        ).filterKeys { only == "all" || it == only }
    val out = LinkedHashMap<String, SearchReport>()
    for ((name, tuning) in modes) {
        val run = EvalRun(model.takeIf { !tuning.keywordOnly }, tuning)
        val ids = run.load(household)
        run.factory.search.prepareAndWait()
        val report = SearchCorpus.evaluate(run.factory.search, questions, ids)
        out[name] = report
        print(name, report)
        run.factory.close()
    }
    return out
}

/** N3 on the hybrid mode, French and English; N2 at the 95th percentile. */
private fun thresholdFailures(
    hybrid: SearchReport?,
    latency: Latency?,
): List<String> = buildList {
    hybrid?.let { report ->
        for (lang in listOf("fr", "en")) {
            val score = report.score { it.lang == lang }
            if (score.top1Rate < SearchCorpus.TOP1_THRESHOLD) add("$lang top-1 ${pct(score.top1Rate)}")
            if (score.top3Rate < SearchCorpus.TOP3_THRESHOLD) add("$lang top-3 ${pct(score.top3Rate)}")
        }
    }
    if (latency != null &&
        latency.p95Ms > SearchCorpus.LATENCY_BUDGET.inWholeMilliseconds
    ) {
        add("latency p95 ${latency.p95Ms} ms")
    }
}

private data class Options(
    val mode: String,
    val prompt: PromptStyle,
    val dim: Int,
    val latency: Boolean,
) {
    companion object {
        fun parse(args: List<String>): Options {
            fun value(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
            return Options(
                mode = value("--mode") ?: "all",
                prompt = value("--prompt")?.let(PromptStyle::valueOf) ?: SearchTuning().promptStyle,
                dim = value("--dim")?.toInt() ?: SearchTuning().dimension,
                latency = "--no-latency" !in args,
            )
        }
    }
}

/** One in-memory core dated like the reference household. */
private class EvalRun(
    model: TextModelSource?,
    tuning: SearchTuning,
) {
    val clock = ManualClock(NOW)
    val factory =
        SharedFactory(
            SharedConfig(
                dataDirectory = Files.createTempDirectory("eval-search").toString(),
                deviceInfo = JvmDeviceInfo(),
                driverFactory = JvmDriverFactory(null),
                imageCodec = NoImages,
                captureLang = Lang.FR,
            ).apply {
                clock = this@EvalRun.clock
                textModel = model
                searchTuning = tuning
            },
        )

    suspend fun load(household: ReferenceHousehold): Map<String, String> = SearchCorpus.load(factory, clock, household)

    companion object {
        val NOW: Instant = Instant.parse("2026-10-31T12:00:00Z")
    }
}

private object NoImages : ImageCodec {
    override fun transcodeJpeg(
        source: ByteArray,
        maxSide: Int,
        quality: Int,
    ): EncodedImage? = null
}

/**
 * The real model, shared by every run, with each text embedded once (the three modes and the latency run ask the
 * same questions). Filler items of the latency run get a pseudo-random vector instead of a model call.
 */
private class CachingModel(
    private val file: File,
) : TextModelSource {
    private var engine: EmbeddingEngine? = null
    private val cache = HashMap<Pair<String, Int>, FloatArray>()

    override val modelId: String = "eval-" + file.nameWithoutExtension
    override val isSupported: Boolean = true

    override suspend fun load(): TextEmbedder {
        if (engine == null) {
            engine =
                EmbeddingEngine(
                    EmbeddingEngineConfig(
                        modelPath = file.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = Files.createTempDirectory("litertlm").toString(),
                        maxInputLength = MAX_INPUT_TOKENS,
                    ),
                ).also { it.initialize() }
        }
        return Embedder()
    }

    inner class Embedder : TextEmbedder {
        override suspend fun embed(
            texts: List<String>,
            dim: Int,
        ): List<FloatArray> = withContext(Dispatchers.Default) {
            val missing = texts.filter { (it to dim) !in cache && FILLER_MARK !in it }.distinct()
            if (missing.isNotEmpty()) {
                val options = EmbeddingOptions(normalize = true, outputSize = dim)
                val computed = checkNotNull(engine).computeEmbeddingBatch(
                    missing.map {
                        listOf(InputData.Text(it))
                    },
                    options,
                )
                missing.zip(computed).forEach { (text, response) -> cache[text to dim] = response.embedding }
            }
            texts.map { text -> if (FILLER_MARK in text) filler(text, dim) else cache.getValue(text to dim) }
        }

        /** The engine stays loaded for the next run; [CachingModel.close] frees it. */
        override fun close() = Unit
    }

    fun close() {
        engine?.close()
        engine = null
    }

    fun forget(predicate: (String) -> Boolean) {
        cache.keys.removeAll { predicate(it.first) }
    }

    private fun filler(
        text: String,
        dim: Int,
    ): FloatArray {
        val random = Random(text.hashCode())
        val v = FloatArray(dim) { random.nextFloat() - HALF }
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(dim) { v[it] / norm }
    }
}

private class Latency(
    val items: Int,
    val p50Ms: Long,
    val p95Ms: Long,
)

/** N2: the hybrid search over the reference household plus filler items up to 5 000. */
private suspend fun latency(
    model: CachingModel,
    tuning: SearchTuning,
    household: ReferenceHousehold,
    questions: List<SearchQuestion>,
): Latency {
    // Every question is embedded again: the latency includes the model, as on a phone.
    model.forget { it.startsWith(tuning.promptStyle.query("")) }
    val run = EvalRun(model, tuning)
    val fillers = (household.items.size until LATENCY_ITEMS).map { fillerItem(it, household) }
    run.load(household.copy(items = household.items + fillers))
    run.factory.search.prepareAndWait()
    val report = SearchCorpus.evaluate(run.factory.search, questions, emptyMap())
    run.factory.close()
    return Latency(
        LATENCY_ITEMS,
        report.latencyPercentile(P50).inWholeMilliseconds,
        report.latencyPercentile(P95).inWholeMilliseconds,
    )
}

private val FILLER_NAMES =
    listOf(
        "Boîte",
        "Sac",
        "Câble",
        "Livre",
        "Pot",
        "Outil",
        "Classeur",
        "Jouet",
        "Flacon",
        "Cahier",
        "Bocal",
        "Trousse",
    )
private val FILLER_DETAILS =
    listOf(
        "bleu",
        "rouge",
        "ancien",
        "petit",
        "grand",
        "en métal",
        "en bois",
        "rayé",
        "de rechange",
        "vide",
        "plein",
        "neuf",
    )

/** A plausible item with a deterministic name, spread over the reference places. */
private fun fillerItem(
    n: Int,
    household: ReferenceHousehold,
): ReferenceItem = ReferenceItem(
    id = "filler_$n",
    name = "${FILLER_NAMES[n % FILLER_NAMES.size]} ${FILLER_DETAILS[(n / FILLER_NAMES.size) % FILLER_DETAILS.size]} $n",
    description = "$FILLER_MARK $n",
    place = household.places[n % household.places.size].id,
    daysAgo = n % MAX_FILLER_AGE,
)

private fun print(
    mode: String,
    report: SearchReport,
) {
    for (lang in listOf("fr", "en")) {
        val all = report.score { it.lang == lang }
        val dev = report.score { it.lang == lang && it.split == "dev" }
        val holdout = report.score { it.lang == lang && it.split == "holdout" }
        println(
            "eval-search $mode $lang: ${all.total} questions, top-1 ${pct(all.top1Rate)}, top-3 ${pct(all.top3Rate)} " +
                "(dev ${pct(
                    dev.top1Rate,
                )}/${pct(dev.top3Rate)}, holdout ${pct(holdout.top1Rate)}/${pct(holdout.top3Rate)})",
        )
    }
    val kinds = report.results.map { it.question.kind }.filter { it != "absent" }.distinct().sorted()
    println("  by kind: " + kinds.joinToString { kind -> "$kind ${pct(report.score { it.kind == kind }.top1Rate)}" })
    println("  nothing found when nothing matches: ${report.absent.top1}/${report.absent.total}")
}

private fun scoreJson(score: Score): String = """{"total":${score.total},"top1":${score.top1},"top3":${score.top3}}"""

private fun toJson(
    reports: Map<String, SearchReport>,
    latency: Latency?,
    options: Options,
): String {
    val modes =
        reports.entries.joinToString(",") { (mode, report) ->
            val langs = listOf("fr", "en").joinToString(",") { lang ->
                "\"$lang\":${scoreJson(report.score { it.lang == lang })}"
            }
            val failures = report.failures.joinToString(",") { "\"${it.question.id}\"" }
            """"$mode":{$langs,"absent":${scoreJson(report.absent)},"failures":[$failures]}"""
        }
    val latencyJson = latency?.let { """{"items":${it.items},"p50Ms":${it.p50Ms},"p95Ms":${it.p95Ms}}""" } ?: "null"
    return """{"prompt":"${options.prompt}","dim":${options.dim},"modes":{$modes},"latency":$latencyJson}"""
}

private fun pct(rate: Double): String = "${"%.1f".format(rate * PERCENT)} %"

private const val PERCENT = 100
private const val FIRST_OPTION = 3
private const val TOP = 3
private const val HALF = 0.5f
private const val P50 = 50
private const val P95 = 95
private const val LATENCY_ITEMS = 5_000
private const val MAX_FILLER_AGE = 700
private const val MAX_INPUT_TOKENS = 256
private const val FILLER_MARK = "zzfiller"
