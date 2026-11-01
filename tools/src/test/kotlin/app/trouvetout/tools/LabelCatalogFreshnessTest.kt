package app.trouvetout.tools

import app.trouvetout.core.ml.LabelCatalog
import app.trouvetout.core.search.SearchTuning
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The bundled label catalog (B8) matches its sources: the TSV it was computed from, the text model the apps run and
 * the vector size of the search. Run `./gradlew :tools:genLabels` after changing either.
 */
class LabelCatalogFreshnessTest {
    private val models = File(checkNotNull(System.getProperty("trouvetout.root")), "models")

    @Test
    fun the_catalog_was_computed_from_the_current_labels_and_text_model() {
        val catalog = assertNotNull(LabelCatalog.decode(File(models, LABELS_CATALOG).readBytes()), "readable catalog")
        val tsv = File(models, LABELS_TSV)
        val text = ManifestModel.read(File(models, "manifest.json")).single { it.variant == "TEXT_ONLY" }
        assertEquals(sha256Hex(tsv.readBytes()), catalog.header.sourceSha256, "labels_fr_en.tsv changed: run genLabels")
        assertEquals(text.identity, catalog.header.modelId, "text model changed: run genLabels")
        assertEquals(SearchTuning().dimension, catalog.header.dim)
        assertEquals(readLabels(tsv), catalog.labels)
    }
}
