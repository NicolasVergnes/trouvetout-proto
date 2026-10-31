package app.trouvetout.android.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trouvetout.android.BuildConfig
import app.trouvetout.core.ml.AssetTextModelSource
import app.trouvetout.core.ml.PromptStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The model bundled in the APK (ADR-021) loads with LiteRT-LM on Android 10 and its vectors carry meaning (U3):
 * a charger is closer to a cable than to a corkscrew.
 */
@RunWith(AndroidJUnit4::class)
class TextModelTest {
    @Test
    fun the_bundled_model_embeds_on_the_device() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source =
            AssetTextModelSource(
                context,
                BuildConfig.TEXT_MODEL_ASSET,
                BuildConfig.TEXT_MODEL_ID,
                BuildConfig.TEXT_MODEL_BYTES,
            )
        assertTrue("x86_64 or arm64 test device", source.isSupported)
        val model = source.load()
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

            fun dot(
                a: FloatArray,
                b: FloatArray,
            ) = a.indices.sumOf { (a[it] * b[it]).toDouble() }
            assertEquals(1.0, dot(vectors[1], vectors[1]), TOLERANCE)
            assertTrue("U3: a charger is a cable", dot(vectors[0], vectors[1]) > dot(vectors[0], vectors[2]))
        } finally {
            model.close()
        }
    }

    private companion object {
        const val DIMENSION = 256
        const val TOLERANCE = 0.01
    }
}
