package app.trouvetout.android.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trouvetout.android.App
import app.trouvetout.android.ui.common.PhotoAttachment
import app.trouvetout.android.ui.common.PhotoBytes
import app.trouvetout.core.capture.Lang
import app.trouvetout.core.data.repository.ContainerContent
import app.trouvetout.core.data.repository.PlaceContent
import app.trouvetout.core.domain.model.Container
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.model.Place
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.places.PlaceTemplate
import app.trouvetout.core.domain.places.PlaceTemplates
import app.trouvetout.core.domain.usecase.CommandResult
import app.trouvetout.core.domain.usecase.Rejection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** What a browse screen shows: the root level, one place, or one container. */
sealed interface BrowseTarget {
    data object Root : BrowseTarget

    data class Place(
        val id: String,
    ) : BrowseTarget

    data class Container(
        val id: String,
    ) : BrowseTarget
}

/** Transient feedback of the screen; the lists themselves come from the core flows. */
data class BrowseUiState(
    val busy: Boolean = false,
    val problem: BrowseProblem? = null,
    /** Set when the shown place or container was removed (merged, deleted): the screen goes back. */
    val gone: Boolean = false,
)

enum class BrowseProblem { REJECTED, PHOTO_UNSUPPORTED, PHOTO_UNREADABLE, PHOTO_TOO_LARGE }

/** Navigation by place (C7), containers (A3, D4), templates (D2), rename/merge (D6), place photos (D5). */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModel(
    private val app: App,
    val target: BrowseTarget,
) : ViewModel() {
    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state

    val place: StateFlow<PlaceContent?> =
        app.readyShared
            .flatMapLatest { shared ->
                when (target) {
                    BrowseTarget.Root -> shared.browse.observePlace(null)
                    is BrowseTarget.Place -> shared.browse.observePlace(target.id)
                    is BrowseTarget.Container -> flowOf(null)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    val container: StateFlow<ContainerContent?> =
        app.readyShared
            .flatMapLatest { shared ->
                when (target) {
                    is BrowseTarget.Container -> shared.browse.observeContainer(target.id)
                    else -> flowOf(null)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** Every active place, for the merge and move pickers. */
    val allPlaces: StateFlow<List<Place>> =
        app.readyShared
            .flatMapLatest { it.placeRepository.observeActive() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val allContainers: StateFlow<List<Container>> =
        app.readyShared
            .flatMapLatest { it.containerRepository.observeActive() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val templates: List<PlaceTemplate> = PlaceTemplates.forLang(uiLang())

    fun addPlace(
        name: String,
        kind: PlaceKind,
    ) = run {
        val parentId = (target as? BrowseTarget.Place)?.id
        record(app.shared.placeCommands.create(name, parentId, kind, emptyList(), EventSource.TEXT))
    }

    fun applyTemplate(template: PlaceTemplate) = run {
        record(app.shared.placeCommands.createFromTemplate(template, EventSource.TEXT))
    }

    /** The number (« Carton 7 » → 7) is derived by the core, not here. */
    fun addContainer(label: String) = run {
        val placeId = (target as? BrowseTarget.Place)?.id ?: return@run
        record(app.shared.containerCommands.create(label, placeId, source = EventSource.TEXT))
    }

    fun rename(
        name: String,
        aliases: List<String>,
    ) = run {
        val placeId = (target as? BrowseTarget.Place)?.id ?: return@run
        record(app.shared.placeCommands.rename(placeId, name, aliases, EventSource.TEXT))
    }

    /** D6: the shown place disappears into [intoPlaceId]; its content follows, its events stay. */
    fun mergeInto(intoPlaceId: String) = run {
        val placeId = (target as? BrowseTarget.Place)?.id ?: return@run
        if (record(app.shared.placeCommands.merge(placeId, intoPlaceId, EventSource.TEXT))) {
            _state.update {
                it.copy(gone = true)
            }
        }
    }

    /** D4: moves the shown container, and everything in it, in one event. */
    fun moveContainer(toPlaceId: String) = run {
        val containerId = (target as? BrowseTarget.Container)?.id ?: return@run
        record(app.shared.containerCommands.move(containerId, toPlaceId, EventSource.TEXT))
    }

    fun deleteContainer() = run {
        val containerId = (target as? BrowseTarget.Container)?.id ?: return@run
        if (record(app.shared.containerCommands.delete(containerId, EventSource.TEXT))) {
            _state.update {
                it.copy(gone = true)
            }
        }
    }

    /** D5: a photo of the place or container; the bytes are re-encoded by the core, never stored as received. */
    fun attachPhoto(attachment: PhotoAttachment) = run {
        val (ownerType, ownerId) =
            when (target) {
                is BrowseTarget.Place -> PhotoOwnerType.PLACE to target.id
                is BrowseTarget.Container -> PhotoOwnerType.CONTAINER to target.id
                BrowseTarget.Root -> return@run
            }
        val bytes =
            when (val read = attachment.read()) {
                is PhotoBytes.Ok -> read.bytes
                PhotoBytes.TooLarge -> return@run problem(BrowseProblem.PHOTO_TOO_LARGE)
                PhotoBytes.Unreadable -> return@run problem(BrowseProblem.PHOTO_UNREADABLE)
            }
        recordPhoto(app.shared.photoAttacher.attach(ownerType, ownerId, bytes, EventSource.PHOTO))
    }

    private fun recordPhoto(result: CommandResult) {
        when ((result as? CommandResult.Rejected)?.reason) {
            Rejection.UNSUPPORTED_IMAGE -> problem(BrowseProblem.PHOTO_UNSUPPORTED)
            Rejection.IMAGE_TOO_LARGE -> problem(BrowseProblem.PHOTO_TOO_LARGE)
            else -> record(result)
        }
    }

    fun detachPhoto(photoId: String) = run {
        record(app.shared.attachmentCommands.detachPhoto(photoId, EventSource.PHOTO))
    }

    fun dismissProblem() = _state.update { it.copy(problem = null) }

    private fun problem(problem: BrowseProblem) = _state.update { it.copy(problem = problem) }

    private fun record(result: CommandResult): Boolean {
        if (result is CommandResult.Rejected) problem(BrowseProblem.REJECTED)
        return result is CommandResult.Done
    }

    private fun uiLang(): Lang = if (Locale.getDefault().language == "fr") Lang.FR else Lang.EN

    /** Several actions may overlap (a photo while a rename runs): busy stays true until the last one ends. */
    private var running = 0

    private fun run(block: suspend () -> Unit) {
        running += 1
        _state.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                problem(BrowseProblem.REJECTED)
            } finally {
                running -= 1
                _state.update { it.copy(busy = running > 0) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
