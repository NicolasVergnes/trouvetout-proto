package app.trouvetout.core.ml

import app.trouvetout.core.data.db.openTestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelSelectorTest {
    private val gib = 1024L * 1024 * 1024

    @Test
    fun the_variant_follows_the_memory_ADR_003() {
        assertEquals(ModelVariant.TEXT_ONLY, ModelSelector.variantFor(0), "unknown memory: the light one")
        assertEquals(ModelVariant.TEXT_ONLY, ModelSelector.variantFor(4 * gib))
        assertEquals(ModelVariant.OMNIMODAL, ModelSelector.variantFor(5_600L * gib / 1000), "a « 6 GB » phone")
        assertEquals(ModelVariant.OMNIMODAL, ModelSelector.variantFor(12 * gib))
    }

    @Test
    fun the_choice_is_kept_once_made() {
        val db = openTestDatabase()
        assertEquals(ModelVariant.TEXT_ONLY, ModelSelector.ensure(db, 3 * gib))
        assertEquals(ModelVariant.TEXT_ONLY, ModelSelector.ensure(db, 12 * gib), "same device, same choice")
    }
}
