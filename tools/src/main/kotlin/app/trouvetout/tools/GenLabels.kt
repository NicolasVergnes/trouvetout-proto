package app.trouvetout.tools

import app.trouvetout.core.ml.LabelCatalog
import app.trouvetout.core.ml.LabelCatalogHeader
import app.trouvetout.core.search.SearchTuning
import java.io.File

/**
 * Computes the label catalog the apps bundle (B8, ADR-022): every label of `models/labels_fr_en.tsv` embedded with
 * the text model through the chosen template, written to `models/labels_fr_en.catalog`. The text weights are the
 * same in both bundled files, so the catalog serves the text+vision model. `LabelCatalogFreshnessTest` fails when the
 * TSV or the model changes without a new run.
 *
 * Arguments: `models/` directory, bundled model directory (`models/build/bundled`), then `--template <name>`.
 */
fun main(args: Array<String>) {
    val modelsDir = File(args[0])
    val bundled = File(args[1])
    val template = args.toList().windowed(2).firstOrNull { it[0] == "--template" }?.get(1) ?: LabelTemplates.DEFAULT
    val toTexts = LabelTemplates.ALL[template] ?: error("unknown template $template; one of ${LabelTemplates.ALL.keys}")
    val text = ManifestModel.read(File(modelsDir, "manifest.json")).single { it.variant == "TEXT_ONLY" }
    val tsv = File(modelsDir, LABELS_TSV)
    val labels = readLabels(tsv)
    val dim = SearchTuning().dimension
    val vectors = FloatArray(labels.size * dim)
    JvmEngines(File(bundled, text.file), null).use { engines ->
        labels.chunked(BATCH).forEachIndexed { batchIndex, batch ->
            val texts = batch.map(toTexts)
            val flat = engines.text().texts(texts.flatten(), dim)
            var offset = 0
            texts.forEachIndexed { i, variants ->
                val mean = meanVector(flat.subList(offset, offset + variants.size))
                offset += variants.size
                mean.copyInto(vectors, (batchIndex * BATCH + i) * dim)
            }
        }
    }
    val header = LabelCatalogHeader(text.identity, dim, template, sha256Hex(tsv.readBytes()), labels.size)
    val out = File(modelsDir, LABELS_CATALOG)
    out.writeBytes(LabelCatalog(header, labels, vectors).encode())
    println("${labels.size} labels, template $template, ${out.length()} bytes → ${out.name}")
}

internal const val LABELS_TSV = "labels_fr_en.tsv"
internal const val LABELS_CATALOG = "labels_fr_en.catalog"
private const val BATCH = 32
