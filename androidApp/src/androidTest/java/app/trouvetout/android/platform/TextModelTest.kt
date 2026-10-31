package app.trouvetout.android.platform

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trouvetout.android.BuildConfig
import app.trouvetout.android.bundledModels
import app.trouvetout.core.ml.ImageEmbedder
import app.trouvetout.core.ml.LabelCatalog
import app.trouvetout.core.ml.ModelSelector
import app.trouvetout.core.ml.PromptStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * The models bundled in the APK (ADR-021, ADR-022) load with LiteRT-LM on the device: the text file on Android 10
 * and its vectors carry meaning (U3); the text+vision file, on a device with the memory for it, puts a photo in
 * the same space; the label catalog matches the text model.
 */
@RunWith(AndroidJUnit4::class)
class TextModelTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun the_bundled_model_embeds_on_the_device() = runBlocking {
        val source = bundledModels(context)
        assertTrue("x86_64 or arm64 test device", source.isSupported)
        val model = source.load(withImages = false)
        try {
            val vectors =
                model.embed(
                    listOf(
                        PromptStyle.MODEL_CARD.query("le chargeur du Kindle"),
                        PromptStyle.MODEL_CARD.document("Câble USB-C blanc"),
                        PromptStyle.MODEL_CARD.document("Tire-bouchon"),
                    ),
                    DIMENSION,
                )
            assertEquals(DIMENSION, vectors[0].size)
            assertEquals(1.0, dot(vectors[1], vectors[1]), TOLERANCE)
            assertTrue("U3: a charger is a cable", dot(vectors[0], vectors[1]) > dot(vectors[0], vectors[2]))
        } finally {
            model.close()
        }
    }

    @Test
    fun the_label_catalog_belongs_to_the_text_model_B8() {
        val bytes = checkNotNull(bundledModels(context).labelCatalog()) { "label catalog in the assets" }
        val catalog = checkNotNull(LabelCatalog.decode(bytes)) { "readable catalog" }
        assertEquals(BuildConfig.TEXT_MODEL_ID, catalog.header.modelId)
        assertEquals(DIMENSION, catalog.header.dim)
        assertTrue(catalog.labels.size > MIN_LABELS)
    }

    @Test
    fun the_vision_file_puts_a_photo_in_the_text_space_B8() = runBlocking {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        // The text+vision file is only loaded on ≥ 6 GB phones (ADR-003); the API 29 CI device has less.
        assumeTrue("≥ 6 GB device", memory.totalMem >= ModelSelector.OMNIMODAL_MIN_RAM_BYTES)
        val model = bundledModels(context).load(withImages = true)
        try {
            val images = model as ImageEmbedder
            val photo = drawing()
            val (a, b) = images.embedImages(listOf(photo, photo), DIMENSION)
            assertEquals(DIMENSION, a.size)
            assertEquals(1.0, dot(a, a), TOLERANCE)
            assertEquals("deterministic", 1.0, dot(a, b), TOLERANCE)
            val text = model.embed(listOf(PromptStyle.MODEL_CARD.query("chargeur")), DIMENSION).single()
            assertEquals(DIMENSION, text.size)
        } finally {
            model.close()
        }
    }

    /** A generated test picture (no real photo in the repository): a yellow disc on blue. */
    private fun drawing(): ByteArray {
        val bitmap = Bitmap.createBitmap(PICTURE_SIDE, PICTURE_SIDE, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.BLUE)
            drawCircle(PICTURE_SIDE / 2f, PICTURE_SIDE / 2f, PICTURE_SIDE / 3f, Paint().apply { color = Color.YELLOW })
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    private fun dot(
        a: FloatArray,
        b: FloatArray,
    ) = a.indices.sumOf { (a[it] * b[it]).toDouble() }

    private companion object {
        const val DIMENSION = 256
        const val TOLERANCE = 0.01
        const val MIN_LABELS = 1000
        const val PICTURE_SIDE = 320
        const val JPEG_QUALITY = 90
    }
}
