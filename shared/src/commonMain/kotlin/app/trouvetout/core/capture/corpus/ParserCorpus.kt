package app.trouvetout.core.capture.corpus

import app.trouvetout.core.capture.Intent
import app.trouvetout.core.capture.Lang
import app.trouvetout.core.capture.ParseResult
import app.trouvetout.core.capture.Parser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One line of `corpus/parser.jsonl` (format in `docs/06-corpus-recherche.md` §1). */
@Serializable
public data class CorpusLine(
    val id: String,
    val lang: String,
    val origin: String = "synthetic",
    val input: String,
    val expect: Expectation,
    /** Known failure kept in the corpus with its reason; counted, never asserted. */
    val xfail: String? = null,
)

@Serializable
public data class Expectation(
    val intent: String,
    val items: List<String> = emptyList(),
    val place: String? = null,
    val person: String? = null,
    val query: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
)

/** Outcome of one line: intent right? split (objects, place, person, query, quantity) right? */
public data class LineResult(
    val line: CorpusLine,
    val actual: ParseResult,
    val intentOk: Boolean,
    val splitOk: Boolean,
) {
    val ok: Boolean get() = intentOk && splitOk
}

public data class LangScore(
    val lang: String,
    val total: Int,
    val intentOk: Int,
    val splitOk: Int,
    val xfail: Int,
) {
    public val intentRate: Double get() = if (total == 0) 1.0 else intentOk.toDouble() / total
    public val splitRate: Double get() = if (total == 0) 1.0 else splitOk.toDouble() / total
}

public data class CorpusReport(
    val results: List<LineResult>,
    val scores: Map<String, LangScore>,
) {
    public val failures: List<LineResult> get() = results.filter { !it.ok && it.line.xfail == null }

    public fun meets(
        intentThreshold: Double,
        splitThreshold: Double,
    ): Boolean = scores.values.all { it.intentRate >= intentThreshold && it.splitRate >= splitThreshold }
}

/** Replays the capture corpus through the parser; shared by the JVM test and `tools/eval-parser`. */
public object ParserCorpus {
    public const val INTENT_THRESHOLD: Double = 0.95
    public const val SPLIT_THRESHOLD: Double = 0.90

    private val json = Json { ignoreUnknownKeys = true }

    public fun parseLines(jsonl: String): List<CorpusLine> = jsonl.lineSequence().filter {
        it.isNotBlank()
    }.map { json.decodeFromString(CorpusLine.serializer(), it) }.toList()

    public fun evaluate(
        lines: List<CorpusLine>,
        parser: Parser = Parser(),
    ): CorpusReport {
        val results =
            lines.map { line ->
                val lang = if (line.lang.equals("en", ignoreCase = true)) Lang.EN else Lang.FR
                val actual = parser.parse(line.input, lang)
                LineResult(line, actual, intentMatches(line.expect, actual), splitMatches(line.expect, actual))
            }
        val scores =
            results.groupBy { it.line.lang }.mapValues { (lang, list) ->
                val scored = list.filter { it.line.xfail == null }
                LangScore(
                    lang = lang,
                    total = scored.size,
                    intentOk = scored.count { it.intentOk },
                    splitOk = scored.count { it.splitOk },
                    xfail = list.size - scored.size,
                )
            }
        return CorpusReport(results, scores)
    }

    private fun intentMatches(
        expect: Expectation,
        actual: ParseResult,
    ): Boolean = intentOf(expect.intent) == actual.intent

    private fun splitMatches(
        expect: Expectation,
        actual: ParseResult,
    ): Boolean {
        if (actual.intent == Intent.QUERY || expect.query != null) {
            return expect.query.sameText(actual.query)
        }
        val names = actual.items.map { it.name }
        val quantityOk = expect.quantity == null || actual.items.firstOrNull()?.quantity == expect.quantity
        val unitOk = expect.unit == null || actual.items.firstOrNull()?.unit == expect.unit
        return names.size == expect.items.size &&
            names.zip(expect.items).all { (a, e) -> e.sameText(a) } &&
            expect.place.sameText(actual.place) &&
            expect.person.sameText(actual.person) &&
            quantityOk &&
            unitOk
    }

    private fun String?.sameText(other: String?): Boolean = (this == null && other == null) ||
        (this != null && other != null && this.trim().equals(other.trim(), ignoreCase = true))

    private fun intentOf(name: String): Intent = when (name.lowercase()) {
        "store" -> Intent.STORE
        "take" -> Intent.TAKE
        "return" -> Intent.RETURN
        "moveto", "move_to", "move" -> Intent.MOVE_TO
        "lend" -> Intent.LEND
        "returnfromlend", "return_from_lend" -> Intent.RETURN_FROM_LEND
        "query" -> Intent.QUERY
        else -> Intent.UNKNOWN
    }
}
