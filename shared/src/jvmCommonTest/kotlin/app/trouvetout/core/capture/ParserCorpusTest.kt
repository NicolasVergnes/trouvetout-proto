package app.trouvetout.core.capture

import app.trouvetout.core.capture.corpus.ParserCorpus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** N3 companion for capture: replays `corpus/parser.jsonl`, FR and EN scored separately (docs/06 §1). */
class ParserCorpusTest {
    private fun corpusFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "corpus/parser.jsonl")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("corpus/parser.jsonl not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun should_meet_the_intent_and_split_thresholds_in_both_languages() {
        val lines = ParserCorpus.parseLines(corpusFile().readText())
        val report = ParserCorpus.evaluate(lines)

        val summary =
            report.scores.values.joinToString("\n") {
                "${it.lang}: ${it.total} lines, intents ${(it.intentRate * 100).toInt()} %, " +
                    "split ${(it.splitRate * 100).toInt()} %, xfail ${it.xfail}"
            }
        val failures =
            report.failures.joinToString("\n") { r ->
                val a = r.actual
                "${r.line.id} [${if (!r.intentOk) "intent" else "split"}] '${r.line.input}' -> ${a.intent} " +
                    "items=${a.items.map { it.name }} place=${a.place} person=${a.person} query=${a.query}"
            }
        println(summary)
        if (failures.isNotEmpty()) println(failures)
        assertTrue(report.meets(ParserCorpus.INTENT_THRESHOLD, ParserCorpus.SPLIT_THRESHOLD), "$summary\n$failures")
    }

    @Test
    fun should_have_enough_sentences_in_each_language() {
        val lines = ParserCorpus.parseLines(corpusFile().readText())
        val fr = lines.count { it.lang == "fr" }
        val en = lines.count { it.lang == "en" }
        assertTrue(fr >= MIN_FR, "fr: $fr < $MIN_FR")
        assertTrue(en >= MIN_EN, "en: $en < $MIN_EN")
        assertTrue(lines.map { it.id }.toSet().size == lines.size, "duplicate ids")
        assertTrue(
            lines.all { it.origin == "synthetic" || it.origin == "own-usage" },
            "no real user sentence in the public corpus (docs/06 §4)",
        )
    }

    private companion object {
        const val MIN_FR = 150
        const val MIN_EN = 60
    }
}
