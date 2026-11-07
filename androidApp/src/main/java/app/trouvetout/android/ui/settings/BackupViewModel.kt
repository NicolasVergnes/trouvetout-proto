package app.trouvetout.android.ui.settings

import android.net.Uri
import android.provider.DocumentsContract
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
    val onChooseOtherFile: () -> Unit,
    val onRestore: () -> Unit,
    val onRestoreWithKey: (String) -> Unit,
    val onCancelRestore: () -> Unit,
    val onDismissMessage: () -> Unit,
    /** Whether the text typed is the end of the key: the core's rule, the same on iOS. */
    val confirmsKeyEnd: (String, String) -> Boolean,
)

/** A9: the steps of the settings section; the rules are in the core's `BackupService`. */
class BackupViewModel(
    private val app: App,
) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state

    init {
        // A backup that ran by itself while this screen is open.
        viewModelScope.launch { app.backupChanges.collect { runCatching { read() } } }
    }

    /** The picker created the file: the key is drawn, nothing is kept until it is confirmed. */
    fun fileChosen(uri: Uri) {
        viewModelScope.launch {
            val shared = app.readyShared.first()
            _state.update { it.copy(newKey = shared.backup.newRecoveryKey(), newLocation = uri) }
        }
    }

    /** No backup was made: the empty file the picker created goes too. */
    fun cancelNewKey() {
        val uri = _state.value.newLocation
        _state.update { it.copy(newKey = null, newLocation = null) }
        if (uri != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
            }
        }
    }

    /** The user retyped the end of the key: backups start, the first one now. */
    fun confirmNewKey() {
        val current = _state.value
        if (current.busy) return
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

    /** The file was lost: another one, with the key the user already wrote down. */
    fun otherFileChosen(uri: Uri) {
        run {
            val shared = shared()
            withContext(Dispatchers.IO) {
                app.backupRunner.releaseAccess()
                app.backupRunner.keepAccess(uri)
            }
            shared.backup.relocate(uri.toString())
            write(shared)
        }
    }

    fun backUpNow() {
        run { write(shared()) }
    }

    /** Backups stop; the file stays where it is, and the app forgets the key and its access to the file. */
    fun disable() {
        run {
            shared().backup.disable()
            withContext(Dispatchers.IO) { app.backupRunner.releaseAccess() }
        }
    }

    fun confirmsKeyEnd(
        key: String,
        typed: String,
    ): Boolean = app.current?.backup?.confirmsKeyEnd(key, typed) == true

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
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output ->
                            copyBounded(input, output, BackupService.MAX_BACKUP_BYTES)
                        }
                    } ?: throw IOException("unreadable document")
                } catch (failure: IOException) {
                    target.delete()
                    throw failure
                }
            }
            val result = shared.backup.restore(relativePath, recoveryKey)
            _state.update { it.copy(message = BackupMessage.Restored(result)) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private suspend fun write(shared: SharedFactory) {
        val before = shared.backup.state().lastSuccessMillis
        app.backUpNow()
        val after = shared.backup.state()
        // Only for what really happened: leaving the app cancels it, a failure is said in the section.
        if (after.problem == null && after.lastSuccessMillis != before) {
            _state.update { it.copy(message = BackupMessage.Written) }
        }
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
