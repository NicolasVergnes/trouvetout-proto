package app.trouvetout.tools

import app.trouvetout.core.capture.corpus.CorpusReport
import app.trouvetout.core.capture.corpus.ParserCorpus
import java.io.File
import kotlin.system.exitProcess

/**
 * Replays `corpus/parser.jsonl` through the capture parser, prints the scores per language, writes
 * `build/reports/eval/parser.json` and fails under the thresholds (>= 95 % intents, >= 90 % split).
 */
fun main(args: Array<String>) {
    val corpus = File(args.getOrElse(0) { "corpus/parser.jsonl" })
    val reportsDir = File(args.getOrElse(1) { "build/reports/eval" }).apply { mkdirs() }
    require(corpus.isFile) { "corpus not found: ${corpus.path}" }

    val report = ParserCorpus.evaluate(ParserCorpus.parseLines(corpus.readText()))
    for (score in report.scores.values.sortedBy { it.lang }) {
        println(
            "eval-parser ${score.lang}: ${score.total} sentences, intents ${pct(score.intentRate)}, " +
                "split ${pct(score.splitRate)}, xfail ${score.xfail}",
        )
    }
    for (failure in report.failures) {
        val a = failure.actual
        println(
            "  FAIL ${failure.line.id} [${if (!failure.intentOk) "intent" else "split"}] ${failure.line.input} -> " +
                "${a.intent} items=${a.items.map { it.name }} place=${a.place} person=${a.person} query=${a.query}",
        )
    }
    File(reportsDir, "parser.json").writeText(toJson(report))

    val ok = report.meets(ParserCorpus.INTENT_THRESHOLD, ParserCorpus.SPLIT_THRESHOLD)
    println(if (ok) "eval-parser: OK" else "eval-parser: thresholds not met")
    if (!ok) exitProcess(1)
}

private fun pct(rate: Double): String = "${"%.1f".format(rate * 100)} %"

private fun toJson(report: CorpusReport): String {
    val scores =
        report.scores.values.joinToString(",") {
            """{"lang":"${it.lang}","total":${it.total},"intentOk":${it.intentOk},"splitOk":${it.splitOk},"xfail":${it.xfail}}"""
        }
    val failures = report.failures.joinToString(",") {
        """{"id":"${it.line.id}","intentOk":${it.intentOk},"splitOk":${it.splitOk}}"""
    }
    return """{"scores":[$scores],"failures":[$failures]}"""
}
