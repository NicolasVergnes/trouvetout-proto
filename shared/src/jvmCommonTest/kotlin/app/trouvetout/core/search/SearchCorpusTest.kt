package app.trouvetout.core.search

import app.trouvetout.core.capture.Lang
import app.trouvetout.core.data.db.inMemoryDriverFactory
import app.trouvetout.core.di.SharedConfig
import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.platform.DeviceInfo
import app.trouvetout.core.platform.EncodedImage
import app.trouvetout.core.platform.ImageCodec
import app.trouvetout.core.search.corpus.ManualClock
import app.trouvetout.core.search.corpus.SearchCorpus
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

/**
 * Keyword-only replay of `corpus/recherche.jsonl` (the search without the model: 32-bit phones, a build without
 * it). A non-regression floor in `allTests`; the N3 thresholds with the model are asserted by `tools/eval-search`.
 */
class SearchCorpusTest {
    private fun corpusDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "corpus/recherche.jsonl")
            if (candidate.isFile) return candidate.parentFile
            dir = dir.parentFile
        }
        fail("corpus/recherche.jsonl not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun keyword_search_keeps_its_floor_on_the_corpus() = runBlocking {
        val corpus = corpusDir()
        val household = SearchCorpus.parseHousehold(File(corpus, "fiches-reference.json").readText())
        val questions = SearchCorpus.parseQuestions(File(corpus, "recherche.jsonl").readText())
        val clock = ManualClock(Instant.parse("2026-10-31T12:00:00Z"))
        val factory =
            SharedFactory(
                SharedConfig(
                    dataDirectory = Files.createTempDirectory("search-corpus").toString(),
                    deviceInfo = NoDevice,
                    driverFactory = inMemoryDriverFactory(),
                    imageCodec = NoImages,
                    captureLang = Lang.FR,
                ).apply { this.clock = clock },
            )
        val ids = SearchCorpus.load(factory, clock, household)
        val report = SearchCorpus.evaluate(factory.search, questions, ids)
        factory.close()
        val fr = report.score { it.lang == "fr" }
        val en = report.score { it.lang == "en" }
        val summary = "keyword only: fr ${fr.top1}/${fr.total} top-1, ${fr.top3} top-3; en ${en.top1}/${en.total}, ${en.top3}"
        println(summary)
        // Measured at the end of lot 6 (fr 62.7 % / 73.3 %, en 76.7 % / 81.7 %); a floor, never lowered.
        assertTrue(fr.top1Rate >= FR_TOP1 && fr.top3Rate >= FR_TOP3, summary)
        assertTrue(en.top1Rate >= EN_TOP1 && en.top3Rate >= EN_TOP3, summary)
    }

    private object NoDevice : DeviceInfo {
        override val totalRamBytes: Long = 0
        override val osName: String = "test"
        override val osVersion: String = "0"
    }

    private object NoImages : ImageCodec {
        override fun transcodeJpeg(
            source: ByteArray,
            maxSide: Int,
            quality: Int,
        ): EncodedImage? = null
    }

    private companion object {
        const val FR_TOP1 = 0.62
        const val FR_TOP3 = 0.73
        const val EN_TOP1 = 0.76
        const val EN_TOP3 = 0.81
    }
}
