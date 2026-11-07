package app.trouvetout.android.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trouvetout.android.App
import app.trouvetout.core.data.backup.BackupRestoreResult
import app.trouvetout.core.data.backup.BackupService
import app.trouvetout.core.data.backup.BackupState
import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.domain.ids.Ulid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface BackupMessage {
    data object Written : BackupMessage

    data class Restored(
        val result: BackupRestoreResult,
    ) : BackupMessage

    data object Failed : BackupMessage
}

data class BackupUiState(
    val backup: BackupState? = null,
    val busy: Boolean = false,
    val message: BackupMessage? = null,
    /** Turning on: the key to show once, and the file chosen, until the user confirms. */
    val newKey: String? = null,
    val newLocation: Uri? = null,
    /** Restoring: the file chosen, until the user gives its key. */
    val restoreFrom: Uri? = null,
)

/** What the section does; the section itself is pure. */
data class BackupCallbacks(
    val onTurnOn: () -> Unit,
    val onConfirmKey: () -> Unit,
    val onCancelKey: () -> Unit,
    val onBackUpNow: () -> Unit,
    val onTurnOff: () -> Unit,
    val onRestore: () -> Unit,
    val onRestoreWithKey: (String) -> Unit,
    val onCancelRestore: () -> Unit,
    val onDismissMessage: () -> Unit,
)

/** A9: the steps of the settings section; the rules are in the core's `BackupService`. */
class BackupViewModel(
    private val app: App,
) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state

    init {
        viewModelScope.launch { read() }
    }

    /** The picker created the file: the key is drawn, nothing is kept until it is confirmed. */
    fun fileChosen(uri: Uri) {
        viewModelScope.launch {
            val shared = app.readyShared.first()
            _state.update { it.copy(newKey = shared.backup.newRecoveryKey(), newLocation = uri) }
        }
    }

    fun cancelNewKey() = _state.update { it.copy(newKey = null, newLocation = null) }

    /** The user retyped the end of the key: backups start, the first one now. */
    fun confirmNewKey() {
        val current = _state.value
        val key = current.newKey ?: return
        val uri = current.newLocation ?: return
        _state.update { it.copy(newKey = null, newLocation = null) }
        run {
            val shared = shared()
            withContext(Dispatchers.IO) { app.backupRunner.keepAccess(uri) }
            check(shared.backup.enable(key, uri.toString())) { "bad key" }
            write(shared)
        }
    }

    fun backUpNow() {
        run { write(shared()) }
    }

    /** Backups stop; the file stays where it is, and the app forgets the key and its access to the file. */
    fun disable() {
        run {
            val shared = shared()
            val location = shared.backup.state().location
            shared.backup.disable()
            location?.let { withContext(Dispatchers.IO) { app.backupRunner.releaseAccess(it) } }
        }
    }

    fun restoreChosen(uri: Uri) = _state.update { it.copy(restoreFrom = uri) }

    fun cancelRestore() = _state.update { it.copy(restoreFrom = null) }

    /** Copies the file (bounded) into the private import directory: the core never reads foreign URIs. */
    fun restore(recoveryKey: String) {
        val uri = _state.value.restoreFrom ?: return
        _state.update { it.copy(restoreFrom = null) }
        run {
            val shared = shared()
            val relativePath = "${SharedFactory.IMPORT_DIRECTORY}/${Ulid.generate(System.currentTimeMillis())}.ttbk"
            val target = File(shared.config.dataDirectory, relativePath)
            withContext(Dispatchers.IO) {
                target.parentFile?.mkdirs()
                app.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> copyBounded(input, output, BackupService.MAX_BACKUP_BYTES) }
                } ?: throw IOException("unreadable document")
            }
            val result = shared.backup.restore(relativePath, recoveryKey)
            _state.update { it.copy(message = BackupMessage.Restored(result)) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private suspend fun write(shared: SharedFactory) {
        app.backupRunner.runNow(shared)
        if (shared.backup.state().problem == null) _state.update { it.copy(message = BackupMessage.Written) }
    }

    private suspend fun shared(): SharedFactory = app.readyShared.first()

    private suspend fun read() {
        val backup = shared().backup.state()
        _state.update { it.copy(backup = backup) }
    }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(message = BackupMessage.Failed) }
            } finally {
                runCatching { read() }
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
