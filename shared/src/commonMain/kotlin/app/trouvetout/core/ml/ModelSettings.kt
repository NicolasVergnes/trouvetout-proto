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
    /** The text+vision file failed to load in this process (memory): photos are off until the next launch. */
    val loadFailed: Boolean = false,
    /**
     * The system closed the app while the text+vision file was loading (not enough memory, no exception to catch):
     * naming from photos was turned off; turning it on again tries once more.
     */
    val stoppedWhileLoading: Boolean = false,
) {
    /** Photos are embedded and names are suggested. */
    val imagesActive: Boolean get() = deviceCapable && bundled && !forceTextOnly && !loadFailed

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
            ).let(::afterAKilledLoad),
        )

    public val state: StateFlow<PhotoModelState> = stateFlow.asStateFlow()

    /** Called after a change of the loaded model (the search frees it and computes the missing vectors). */
    internal var onModelChange: suspend () -> Unit = {}

    /**
     * A load of the text+vision file that never ended (the system killed the app, which iOS and Android do without an
     * exception when memory runs out) must not crash every launch: naming from photos is turned off, and said.
     */
    private fun afterAKilledLoad(state: PhotoModelState): PhotoModelState {
        if (db.metaQueries.get(MetaKeys.VISION_LOADING).executeAsOneOrNull() == null) return state
        db.transaction {
            db.metaQueries.remove(MetaKeys.VISION_LOADING)
            db.metaQueries.set(MetaKeys.FORCE_TEXT_ONLY, true.toString())
        }
        return state.copy(forceTextOnly = true, stoppedWhileLoading = true)
    }

    /** « Text model only »: less memory, no photo naming nor photo search; the photo vectors are kept. */
    public suspend fun setForceTextOnly(value: Boolean) {
        if (value == stateFlow.value.forceTextOnly) return
        withContext(io) { db.metaQueries.set(MetaKeys.FORCE_TEXT_ONLY, value.toString()) }
        stateFlow.value = stateFlow.value.copy(forceTextOnly = value, stoppedWhileLoading = false)
        onModelChange()
    }

    /** Marks the text+vision load as running, then as over (whatever the outcome). */
    internal suspend fun visionLoading(running: Boolean) {
        withContext(io) {
            if (running) {
                db.metaQueries.set(MetaKeys.VISION_LOADING, true.toString())
            } else {
                db.metaQueries.remove(MetaKeys.VISION_LOADING)
            }
        }
    }

    internal fun imagesFailed() {
        stateFlow.value = stateFlow.value.copy(loadFailed = true)
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
