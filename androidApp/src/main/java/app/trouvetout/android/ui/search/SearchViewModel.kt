package app.trouvetout.android.ui.search

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trouvetout.android.App
import app.trouvetout.android.R
import app.trouvetout.android.ui.common.PhotoAttachment
import app.trouvetout.android.ui.common.PhotoBytes
import app.trouvetout.android.ui.common.previewOf
import app.trouvetout.core.domain.model.Container
import app.trouvetout.core.domain.model.Place
import app.trouvetout.core.search.ItemStateFilter
import app.trouvetout.core.search.SearchFilters
import app.trouvetout.core.search.SearchMode
import app.trouvetout.core.search.SearchResult
import app.trouvetout.core.search.Suggestion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** Period offered by the « moved since » filter (C11). */
@Suppress("MagicNumber") // days of each period
enum class SincePeriod(
    val days: Int,
) {
    WEEK(7),
    MONTH(30),
    YEAR(365),
}

data class SearchUiState(
    val query: String = "",
    val placeId: String? = null,
    val containerId: String? = null,
    val tag: String? = null,
    val state: ItemStateFilter? = null,
    val since: SincePeriod? = null,
    /** Null until the first search of the current query and filters has answered. */
    val result: SearchResult? = null,
    val suggestions: List<Suggestion> = emptyList(),
    /** C10: the photo searched for instead of the text, previewed only (never stored). */
    val photo: ImageBitmap? = null,
    val photoProblem: Int? = null,
) {
    val hasFilters: Boolean get() = placeId != null ||
        containerId != null ||
        tag != null ||
        state != null ||
        since != null
}

/**
 * Search screen (C1–C4, C11, C12): every keystroke searches again after a short pause; the core does the
 * ranking, this class only sequences the calls and keeps the filters.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val app: App,
    initialQuery: String,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState(query = initialQuery))
    val state: StateFlow<SearchUiState> = _state

    /** Keywords only, indexing or hybrid; the screen explains anything but hybrid. */
    val mode: StateFlow<SearchMode> =
        app.readyShared
            .flatMapLatest { it.search.mode }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SearchMode.Hybrid)

    val places: StateFlow<List<Place>> =
        app.readyShared
            .flatMapLatest { it.placeRepository.observeActive() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val containers: StateFlow<List<Container>> =
        app.readyShared
            .flatMapLatest { it.containerRepository.observeActive() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val tags: StateFlow<List<String>> =
        app.readyShared
            .flatMapLatest { it.browse.observeTags() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /** C10 is offered on ≥ 6 GB phones with the photo model and the setting on (ADR-022). */
    val photoSearch: StateFlow<Boolean> =
        app.readyShared
            .flatMapLatest { core -> core.modelSettings.state.map { it.photoSearchAvailable } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    private var searchJob: Job? = null

    /** The bytes of [SearchUiState.photo], kept in memory while the photo is the question. */
    private var photoBytes: ByteArray? = null

    init {
        viewModelScope.launch { app.readyShared.first().search.prepare() }
        refresh(debounce = false)
    }

    fun onQueryChange(text: String) {
        if (text.isNotEmpty()) photoBytes = null
        _state.update { it.copy(query = text, photo = if (text.isNotEmpty()) null else it.photo, photoProblem = null) }
        refresh(debounce = true)
    }

    /** C10: the photo becomes the question; typing or clearing it goes back to text. */
    fun searchByPhoto(attachment: PhotoAttachment) {
        viewModelScope.launch {
            val bytes =
                when (val read = attachment.read()) {
                    is PhotoBytes.Ok -> read.bytes
                    PhotoBytes.TooLarge -> return@launch _state.update {
                        it.copy(photoProblem = R.string.photo_too_large)
                    }
                    PhotoBytes.Unreadable -> return@launch _state.update {
                        it.copy(photoProblem = R.string.photo_unreadable)
                    }
                }
            val preview =
                previewOf(bytes) ?: return@launch _state.update { it.copy(photoProblem = R.string.photo_unsupported) }
            photoBytes = bytes
            _state.update {
                it.copy(query = "", photo = preview, photoProblem = null, suggestions = emptyList(), result = null)
            }
            refresh(debounce = false)
        }
    }

    fun clearPhoto() {
        photoBytes = null
        _state.update { it.copy(photo = null, result = null) }
        refresh(debounce = false)
    }

    fun pickSuggestion(suggestion: Suggestion) {
        _state.update { it.copy(query = suggestion.text, suggestions = emptyList()) }
        refresh(debounce = false)
    }

    fun setPlace(
        placeId: String?,
        containerId: String?,
    ) = filter { it.copy(placeId = placeId, containerId = containerId) }

    fun setTag(tag: String?) = filter { it.copy(tag = tag) }

    fun setState(state: ItemStateFilter?) = filter { it.copy(state = state) }

    fun setSince(since: SincePeriod?) = filter { it.copy(since = since) }

    fun clearFilters() = filter { it.copy(placeId = null, containerId = null, tag = null, state = null, since = null) }

    /** The item the user opens counts for the frequency signal (C3). */
    fun opened(itemId: String) {
        viewModelScope.launch { app.readyShared.first().search.recordOpened(itemId) }
    }

    /** Back on the screen: the journal may have changed (an item moved from its sheet); vectors may lag behind. */
    fun onResume() {
        viewModelScope.launch { app.readyShared.first().search.prepare() }
        refresh(debounce = false)
    }

    private fun filter(change: (SearchUiState) -> SearchUiState) {
        _state.update(change)
        refresh(debounce = false)
    }

    private fun refresh(debounce: Boolean) {
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                if (debounce) delay(DEBOUNCE_MILLIS)
                val core = app.readyShared.first()
                val current = _state.value
                val filters =
                    SearchFilters(
                        placeId = current.placeId,
                        containerId = current.containerId,
                        tag = current.tag,
                        state = current.state,
                        movedSince = current.since?.let { Clock.System.now() - it.days.days },
                    )
                val photo = photoBytes
                val suggestions = if (current.query.isBlank()) emptyList() else core.search.suggest(current.query)
                val result =
                    if (photo !=
                        null
                    ) {
                        core.search.searchByPhoto(photo, filters)
                    } else {
                        core.search.search(current.query, filters)
                    }
                _state.update {
                    if (it.query == current.query) it.copy(result = result, suggestions = suggestions) else it
                }
            }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val DEBOUNCE_MILLIS = 250L
    }
}
