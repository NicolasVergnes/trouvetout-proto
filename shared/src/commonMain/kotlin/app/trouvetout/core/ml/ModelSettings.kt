package app.trouvetout.core.ml

import app.trouvetout.core.data.db.MetaKeys
import app.trouvetout.core.data.db.TrouveToutDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** What the photo side of the model can do on this device (B8, C10, ADR-022). */
public data class PhotoModelState(
    /** The device has the memory for the text+vision model (ADR-003, decided once). */
    val deviceCapable: Boolean,
    /** This build carries the text+vision file. */
    val bundled: Boolean,
    /** The user asked for the text model only (less memory). */
    val forceTextOnly: Boolean,
    /** The user wants the search by photo (C10). */
    val photoSearch: Boolean,
) {
    /** Photos are embedded and names are suggested. */
    val imagesActive: Boolean get() = deviceCapable && bundled && !forceTextOnly

    /** The search screen offers « search by photo ». */
    val photoSearchAvailable: Boolean get() = imagesActive && photoSearch
}

/** The two photo settings, kept in `meta` (local, never exported). */
public class ModelSettings internal constructor(
    private val db: TrouveToutDb,
    totalRamBytes: Long,
    bundled: Boolean,
    private val io: CoroutineDispatcher,
) {
    private val stateFlow =
        MutableStateFlow(
            PhotoModelState(
                deviceCapable = ModelSelector.ensure(db, totalRamBytes) == ModelVariant.OMNIMODAL,
                bundled = bundled,
                forceTextOnly = flag(MetaKeys.FORCE_TEXT_ONLY, default = false),
                photoSearch = flag(MetaKeys.PHOTO_SEARCH, default = true),
            ),
        )

    public val state: StateFlow<PhotoModelState> = stateFlow.asStateFlow()

    /** Called after a change of the loaded model (the search frees it and computes the missing vectors). */
    internal var onModelChange: suspend () -> Unit = {}

    /** « Text model only »: less memory, no photo naming nor photo search; the photo vectors are kept. */
    public suspend fun setForceTextOnly(value: Boolean) {
        if (value == stateFlow.value.forceTextOnly) return
        withContext(io) { db.metaQueries.set(MetaKeys.FORCE_TEXT_ONLY, value.toString()) }
        stateFlow.value = stateFlow.value.copy(forceTextOnly = value)
        onModelChange()
    }

    public suspend fun setPhotoSearch(value: Boolean) {
        withContext(io) { db.metaQueries.set(MetaKeys.PHOTO_SEARCH, value.toString()) }
        stateFlow.value = stateFlow.value.copy(photoSearch = value)
    }

    private fun flag(
        key: String,
        default: Boolean,
    ): Boolean = db.metaQueries.get(key).executeAsOneOrNull()?.toBooleanStrictOrNull() ?: default
}
