package app.trouvetout.android.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trouvetout.android.App
import app.trouvetout.core.capture.CaptureOutcome
import app.trouvetout.core.capture.CaptureProblem
import app.trouvetout.core.capture.CaptureProposal
import app.trouvetout.core.capture.Intent
import app.trouvetout.core.capture.ItemChoice
import app.trouvetout.core.capture.ItemTarget
import app.trouvetout.core.capture.Lang
import app.trouvetout.core.capture.LocationCandidate
import app.trouvetout.core.capture.PlaceChoice
import app.trouvetout.core.capture.PlaceResolution
import app.trouvetout.core.data.repository.ItemView
import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.usecase.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

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
        /** The answers given so far, one per asked target. */
        val answers: List<ItemChoice>,
    ) : PendingDecision
}

/** What was just recorded, shown for three seconds (B6); tapping it puts the sentence back in the field. */
data class ConfirmationCard(
    val intent: Intent,
    val itemNames: List<String>,
    val placeText: String?,
    val person: String?,
    val rawInput: String,
    /** Exactly what this capture wrote: « Défaire » reverts these and nothing else. */
    val events: List<Event>,
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
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModel(
    private val app: App,
) : ViewModel() {
    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state

    /** Follows the cores whose store opened: after a total wipe the host swaps the core and the list restarts. */
    val items: StateFlow<List<ItemView>> =
        app.readyShared
            .flatMapLatest { it.itemRepository.observeActive() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private var cardToken = 0L
    private var suggestionJob: Job? = null

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text, problem = null, searchQuery = null) }
        suggestionJob?.cancel()
        val prefix = text.substringAfterLast('@', missingDelimiterValue = "").trim()
        if (prefix.isEmpty()) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestionJob =
            viewModelScope.launch {
                val found = app.shared.capture.suggestPlaces(prefix)
                _state.update { it.copy(suggestions = found) }
            }
    }

    fun pickSuggestion(candidate: LocationCandidate) {
        suggestionJob?.cancel()
        val text = _state.value.input
        val completed = text.substringBeforeLast('@') + "@ " + candidate.name
        _state.update { it.copy(input = completed, suggestions = emptyList()) }
    }

    fun submit() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.busy) return
        run {
            val proposal = app.shared.capture.propose(text, uiLang())
            when {
                proposal.problem != null -> _state.update { it.copy(problem = proposal.problem) }
                proposal.parse.intent == Intent.QUERY -> _state.update {
                    it.copy(searchQuery = proposal.parse.query, input = "")
                }
                proposal.needsPlaceDecision -> _state.update {
                    it.copy(pending = PendingDecision.Place(proposal, proposal.place!!))
                }
                else -> continueWithItems(proposal, null, emptyList())
            }
        }
    }

    fun choosePlace(choice: PlaceChoice) {
        val pending = _state.value.pending as? PendingDecision.Place ?: return
        _state.update { it.copy(pending = null) }
        run { continueWithItems(pending.proposal, choice, emptyList()) }
    }

    /** [existingId] = act on that item; null = create a new one (STORE) or give up (movements). */
    fun chooseItem(existingId: String?) {
        val pending = _state.value.pending as? PendingDecision.Item ?: return
        _state.update { it.copy(pending = null) }
        if (existingId == null && pending.proposal.movesExistingItems) {
            _state.update { it.copy(problem = CaptureProblem.ITEM_NOT_FOUND) }
            return
        }
        run {
            continueWithItems(
                pending.proposal,
                pending.placeChoice,
                pending.answers + ItemChoice(pending.index, existingId),
            )
        }
    }

    fun dismissDecision() = _state.update { it.copy(pending = null) }

    fun dismissCard(token: Long) = _state.update { if (it.card?.token == token) it.copy(card = null) else it }

    /** Tap on the card: bring the sentence back for correction (B6) and revert what it wrote. */
    fun editLast() {
        val card = _state.value.card ?: return
        _state.update { it.copy(card = null, input = card.rawInput) }
        revert(card)
    }

    fun undo() {
        val card = _state.value.card ?: return
        _state.update { it.copy(card = null) }
        revert(card)
    }

    /** Closes the search results and the error line. */
    fun dismissMessages() = _state.update { it.copy(searchQuery = null, problem = null) }

    private fun revert(card: ConfirmationCard) {
        run {
            val result = app.shared.undo.undo(card.events, EventSource.TEXT)
            if (result is CommandResult.Rejected) _state.update { it.copy(problem = CaptureProblem.COMMAND_REJECTED) }
        }
    }

    /** Asks the next target the core flags, then commits. */
    private suspend fun continueWithItems(
        proposal: CaptureProposal,
        placeChoice: PlaceChoice?,
        answers: List<ItemChoice>,
    ) {
        val next = proposal.nextQuestion(answers.map { it.index })
        if (next != null) {
            _state.update {
                it.copy(pending = PendingDecision.Item(proposal, placeChoice, next, proposal.targets[next], answers))
            }
            return
        }
        when (val outcome = app.shared.capture.commit(proposal, placeChoice, answers)) {
            is CaptureOutcome.Recorded -> recorded(proposal, outcome)
            is CaptureOutcome.Refused -> _state.update { it.copy(problem = outcome.problem) }
            is CaptureOutcome.Search -> _state.update { it.copy(searchQuery = outcome.query, input = "") }
        }
    }

    private fun recorded(
        proposal: CaptureProposal,
        outcome: CaptureOutcome.Recorded,
    ) {
        val parse = proposal.parse
        // Nothing written (the object was already there): nothing to confirm or to undo.
        val card =
            if (outcome.events.isEmpty()) {
                null
            } else {
                ConfirmationCard(
                    parse.intent,
                    parse.items.map {
                        it.name
                    },
                    parse.place,
                    parse.person,
                    parse.input,
                    outcome.events,
                    ++cardToken,
                )
            }
        _state.update { it.copy(input = "", suggestions = emptyList(), card = card) }
    }

    /** The UI language at the time of the call (a system language change does not restart the process). */
    private fun uiLang(): Lang = if (Locale.getDefault().language == "fr") Lang.FR else Lang.EN

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
    }
}
