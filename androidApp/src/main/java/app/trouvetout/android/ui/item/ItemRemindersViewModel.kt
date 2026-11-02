package app.trouvetout.android.ui.item

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trouvetout.android.App
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.Reminder
import app.trouvetout.core.domain.usecase.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Instant

/** State of the reminders section: busy while a command runs, failed when the core refused it. */
data class RemindersUiState(
    val busy: Boolean = false,
    val failed: Boolean = false,
)

/** The reminders of one item sheet (E2, E6): every change is a command of the core. */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemRemindersViewModel(
    private val app: App,
    private val itemId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(RemindersUiState())
    val state: StateFlow<RemindersUiState> = _state

    /** Pending reminders of the item, past ones included (shown as past). */
    val reminders: StateFlow<List<Reminder>> =
        app.readyShared
            .flatMapLatest { it.reminders.observeForItem(itemId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /** E2: a reminder at [fireAtMillis] (the screen only offers moments to come). */
    fun add(fireAtMillis: Long) = run {
        val at = Instant.fromEpochMilliseconds(fireAtMillis)
        app.shared.attachmentCommands.setReminder(itemId, at, source = EventSource.TEXT)
    }

    fun remove(reminderId: String) = run {
        app.shared.attachmentCommands.completeReminder(reminderId, EventSource.TEXT)
    }

    /** E6: « vérifier tous les 6 mois » on or off. */
    fun setChecking(on: Boolean) = run { app.shared.attachmentCommands.setChecking(itemId, on, EventSource.TEXT) }

    /** E6: checked today; the next check is six months away. */
    fun confirmChecked() = run { app.shared.attachmentCommands.confirmChecked(itemId, EventSource.TEXT) }

    private fun run(command: suspend () -> CommandResult) {
        _state.value = RemindersUiState(busy = true)
        viewModelScope.launch {
            val failed =
                try {
                    command() !is CommandResult.Done
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalStateException) {
                    true
                }
            _state.value = RemindersUiState(busy = false, failed = failed)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
