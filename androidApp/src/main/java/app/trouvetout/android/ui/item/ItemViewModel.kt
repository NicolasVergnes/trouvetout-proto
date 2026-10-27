package app.trouvetout.android.ui.item

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trouvetout.android.App
import app.trouvetout.android.ui.common.PhotoAttachment
import app.trouvetout.core.data.repository.ItemView
import app.trouvetout.core.domain.model.Container
import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.Photo
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.model.Place
import app.trouvetout.core.domain.usecase.CommandResult
import app.trouvetout.core.domain.usecase.ItemPatch
import app.trouvetout.core.domain.usecase.LocationRef
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

data class ItemUiState(
    val busy: Boolean = false,
    val problem: ItemProblem? = null,
    /** True once the item was deleted (bin) or the history toggle changed; the screen reacts. */
    val gone: Boolean = false,
    val showHistory: Boolean = false,
)

enum class ItemProblem { REJECTED, PHOTO_UNSUPPORTED, PHOTO_UNREADABLE }

/** The item sheet (A1, A5, A11, A12, D7, D11): every change is a command of the core. */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemViewModel(
    private val app: App,
    private val itemId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ItemUiState(showHistory = app.uiPrefs.showHistory))
    val state: StateFlow<ItemUiState> = _state

    val item: StateFlow<ItemView?> =
        app.readyShared
            .flatMapLatest { it.itemRepository.observeItem(itemId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    val photos: StateFlow<List<Photo>> =
        app.readyShared
            .flatMapLatest { it.browse.observePhotos(PhotoOwnerType.ITEM, itemId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val tagSuggestions: StateFlow<List<String>> =
        app.readyShared
            .flatMapLatest { it.browse.observeTags() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /** A5: read only while the optional history is shown. */
    val history: StateFlow<List<Event>> =
        _state
            .flatMapLatest { ui ->
                if (ui.showHistory) {
                    app.readyShared.flatMapLatest {
                        it.browse.observeHistory(itemId)
                    }
                } else {
                    flowOf(emptyList())
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val allPlaces: StateFlow<List<Place>> =
        app.readyShared
            .flatMapLatest { it.placeRepository.observeActive() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val allContainers: StateFlow<List<Container>> =
        app.readyShared
            .flatMapLatest { it.containerRepository.observeActive() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    fun save(
        name: String,
        description: String,
        tags: List<String>,
        quantity: Double?,
        unit: String,
    ) = run {
        val patch =
            ItemPatch(
                name = name,
                description = description.ifBlank { null },
                clearDescription = description.isBlank(),
                tags = tags,
                quantity = quantity,
                clearQuantity = quantity == null,
                unit = unit.ifBlank { null },
            )
        record(app.shared.itemCommands.update(itemId, patch, EventSource.TEXT))
    }

    /** A12: one less, never below zero. */
    fun decrement() = run {
        val current = item.value?.item?.quantity ?: return@run
        val next = (current - 1).coerceAtLeast(0.0)
        record(app.shared.itemCommands.update(itemId, ItemPatch(quantity = next), EventSource.TEXT))
    }

    fun setPinned(pinned: Boolean) = run {
        record(app.shared.itemCommands.update(itemId, ItemPatch(pinned = pinned), EventSource.TEXT))
    }

    fun setArchived(archived: Boolean) = run {
        record(app.shared.itemCommands.setArchived(itemId, archived, EventSource.TEXT))
    }

    fun moveTo(location: LocationRef) = run { record(app.shared.itemCommands.move(itemId, location, EventSource.TEXT)) }

    fun take() = run { record(app.shared.itemCommands.take(itemId, EventSource.TEXT)) }

    fun putBack() = run { record(app.shared.itemCommands.putBack(itemId, EventSource.TEXT)) }

    fun lend(to: String) = run { record(app.shared.itemCommands.lend(itemId, to, EventSource.TEXT)) }

    fun attachPhoto(attachment: PhotoAttachment) = run {
        val bytes = attachment.read() ?: return@run problem(ItemProblem.PHOTO_UNREADABLE)
        val file = app.shared.photoStore.store(bytes) ?: return@run problem(ItemProblem.PHOTO_UNSUPPORTED)
        record(app.shared.attachmentCommands.attachPhoto(PhotoOwnerType.ITEM, itemId, file, EventSource.PHOTO))
    }

    fun detachPhoto(photoId: String) = run {
        record(app.shared.attachmentCommands.detachPhoto(photoId, EventSource.PHOTO))
    }

    /** A13: to the bin; a restore stays possible for 30 days. */
    fun delete() = run {
        if (record(app.shared.itemCommands.delete(itemId, EventSource.TEXT))) _state.update { it.copy(gone = true) }
    }

    fun setShowHistory(show: Boolean) {
        app.uiPrefs.showHistory = show
        _state.update { it.copy(showHistory = show) }
    }

    fun dismissProblem() = _state.update { it.copy(problem = null) }

    private fun problem(problem: ItemProblem) = _state.update { it.copy(problem = problem) }

    private fun record(result: CommandResult): Boolean {
        if (result is CommandResult.Rejected) problem(ItemProblem.REJECTED)
        return result is CommandResult.Done
    }

    private fun run(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                problem(ItemProblem.REJECTED)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
