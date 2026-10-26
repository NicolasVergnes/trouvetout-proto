package app.trouvetout.android.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trouvetout.android.App
import app.trouvetout.core.capture.CaptureOutcome
import app.trouvetout.core.capture.CaptureProblem
import app.trouvetout.core.capture.CaptureProposal
import app.trouvetout.core.capture.Intent
import app.trouvetout.core.capture.ItemTarget
import app.trouvetout.core.capture.LocationCandidate
import app.trouvetout.core.capture.PlaceChoice
import app.trouvetout.core.capture.PlaceResolution
import app.trouvetout.core.data.repository.ItemView
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.usecase.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A question the card must ask before anything is written (B7 place, A14 duplicate, movement target). */
sealed interface PendingDecision {
    val proposal: CaptureProposal

    data class Place(
        override val proposal: CaptureProposal,
        val resolution: PlaceResolution,
    ) : PendingDecision

    data class Item(
        override val proposal: CaptureProposal,
        val placeChoice: PlaceChoice?,
        val index: Int,
        val target: ItemTarget,
        val chosen: List<String?>,
    ) : PendingDecision
}

/** What was just recorded, shown for three seconds (B6); tapping it puts the sentence back in the field. */
data class ConfirmationCard(
    val intent: Intent,
    val itemNames: List<String>,
    val placeText: String?,
    val person: String?,
    val rawInput: String,
    val token: Long,
)

data class CaptureUiState(
    val input: String = "",
    val suggestions: List<LocationCandidate> = emptyList(),
    val pending: PendingDecision? = null,
    val card: ConfirmationCard? = null,
    val searchQuery: String? = null,
    val busy: Boolean = false,
    val problem: CaptureProblem? = null,
)

/** Text capture screen (B4, B6, B7): the core parses and decides; this class only sequences the questions. */
class CaptureViewModel(
    private val app: App,
) : ViewModel() {
    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state

    val items: StateFlow<List<ItemView>> =
        app.shared.itemRepository
            .observeActive()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private var cardToken = 0L

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text, problem = null, searchQuery = null) }
        val prefix = text.substringAfterLast('@', missingDelimiterValue = "").trim()
        if (prefix.length < MIN_PREFIX) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        viewModelScope.launch {
            val found = runCatching { app.shared.capture.suggestPlaces(prefix) }.getOrDefault(emptyList())
            if (_state.value.input == text) _state.update { it.copy(suggestions = found) }
        }
    }

    fun pickSuggestion(candidate: LocationCandidate) {
        val text = _state.value.input
        val completed = text.substringBeforeLast('@') + "@ " + candidate.name
        _state.update { it.copy(input = completed, suggestions = emptyList()) }
    }

    fun submit() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.busy) return
        run {
            val proposal = app.shared.capture.propose(text)
            when {
                proposal.parse.intent == Intent.QUERY -> _state.update {
                    it.copy(searchQuery = proposal.parse.query, input = "")
                }
                proposal.needsPlaceDecision -> _state.update {
                    it.copy(pending = PendingDecision.Place(proposal, proposal.place!!))
                }
                else -> continueWithItems(proposal, null, emptyList(), 0)
            }
        }
    }

    fun choosePlace(choice: PlaceChoice) {
        val pending = _state.value.pending as? PendingDecision.Place ?: return
        _state.update { it.copy(pending = null) }
        run { continueWithItems(pending.proposal, choice, emptyList(), 0) }
    }

    /** [existingId] = act on that item; null = create a new one (STORE) or give up (movements). */
    fun chooseItem(existingId: String?) {
        val pending = _state.value.pending as? PendingDecision.Item ?: return
        _state.update { it.copy(pending = null) }
        if (existingId == null && pending.proposal.movesExistingItems) {
            _state.update { it.copy(problem = CaptureProblem.ITEM_NOT_FOUND) }
            return
        }
        run { continueWithItems(pending.proposal, pending.placeChoice, pending.chosen + existingId, pending.index + 1) }
    }

    fun dismissDecision() = _state.update { it.copy(pending = null) }

    fun dismissCard(token: Long) = _state.update { if (it.card?.token == token) it.copy(card = null) else it }

    /** Tap on the card: bring the sentence back for correction (B6). */
    fun editLast() {
        val card = _state.value.card ?: return
        _state.update { it.copy(card = null, input = card.rawInput) }
        undoQuietly()
    }

    fun undo() {
        _state.update { it.copy(card = null) }
        undoQuietly()
    }

    /** Closes the search results and the error line. */
    fun dismissMessages() = _state.update { it.copy(searchQuery = null, problem = null) }

    private fun undoQuietly() {
        run {
            val result = app.shared.undo.undoLast(EventSource.TEXT)
            if (result is CommandResult.Rejected) _state.update { it.copy(problem = CaptureProblem.COMMAND_REJECTED) }
        }
    }

    /** Asks about the targets one by one from [from], then commits. */
    private suspend fun continueWithItems(
        proposal: CaptureProposal,
        placeChoice: PlaceChoice?,
        chosen: List<String?>,
        from: Int,
    ) {
        for (index in from until proposal.targets.size) {
            val target = proposal.targets[index]
            val mustAsk =
                if (proposal.movesExistingItems) target.confident == null else target.matches.isNotEmpty()
            if (mustAsk) {
                _state.update { it.copy(pending = PendingDecision.Item(proposal, placeChoice, index, target, chosen)) }
                return
            }
        }
        when (val outcome = app.shared.capture.commit(proposal, placeChoice, chosen)) {
            is CaptureOutcome.Recorded -> {
                val parse = proposal.parse
                val card =
                    ConfirmationCard(
                        parse.intent,
                        parse.items.map {
                            it.name
                        },
                        parse.place,
                        parse.person,
                        parse.input,
                        ++cardToken,
                    )
                _state.update { it.copy(input = "", suggestions = emptyList(), card = card) }
            }
            is CaptureOutcome.Refused -> _state.update { it.copy(problem = outcome.problem) }
            is CaptureOutcome.Search -> _state.update { it.copy(searchQuery = outcome.query, input = "") }
        }
    }

    private fun run(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(problem = CaptureProblem.COMMAND_REJECTED) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MIN_PREFIX = 1
    }
}
