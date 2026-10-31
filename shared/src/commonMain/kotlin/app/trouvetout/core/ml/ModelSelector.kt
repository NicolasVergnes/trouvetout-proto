package app.trouvetout.core.ml

import app.trouvetout.core.data.db.MetaKeys
import app.trouvetout.core.data.db.TrouveToutDb

/**
 * Chooses the model variant from the device memory once, then keeps the choice in `meta.model_variant`
 * (architecture §5.2). A « 6 GB » phone reports a little less than 6 GiB of total memory, hence the margin.
 */
public object ModelSelector {
    public const val OMNIMODAL_MIN_RAM_BYTES: Long = 5_500L * 1024 * 1024 * 1024 / 1000

    /** Pure rule; an unknown amount of memory (0) keeps the light variant. */
    public fun variantFor(totalRamBytes: Long): ModelVariant =
        if (totalRamBytes >= OMNIMODAL_MIN_RAM_BYTES) ModelVariant.OMNIMODAL else ModelVariant.TEXT_ONLY

    /** The stored variant, or the one the memory allows, stored on first call. */
    internal fun ensure(
        db: TrouveToutDb,
        totalRamBytes: Long,
    ): ModelVariant {
        val stored = db.metaQueries.get(MetaKeys.MODEL_VARIANT).executeAsOneOrNull()
        ModelVariant.entries.firstOrNull { it.name == stored }?.let { return it }
        return variantFor(totalRamBytes).also { db.metaQueries.set(MetaKeys.MODEL_VARIANT, it.name) }
    }
}
