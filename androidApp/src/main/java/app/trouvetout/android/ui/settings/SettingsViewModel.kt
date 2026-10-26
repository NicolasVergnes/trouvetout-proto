package app.trouvetout.android.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trouvetout.android.App
import app.trouvetout.core.data.export.ImportRejection
import app.trouvetout.core.data.export.ImportReport
import app.trouvetout.core.data.export.ImportResult
import app.trouvetout.core.di.SharedFactory
import app.trouvetout.core.domain.ids.Ulid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Outcome of the last action, rendered by the screen. */
sealed interface SettingsMessage {
    data class Exported(
        val events: Long,
        val photos: Long,
    ) : SettingsMessage

    data class Imported(
        val report: ImportReport,
    ) : SettingsMessage

    data class ImportRefused(
        val reason: ImportRejection,
    ) : SettingsMessage

    data object Wiped : SettingsMessage

    data object Failed : SettingsMessage
}

data class SettingsUiState(
    val busy: Boolean = false,
    val message: SettingsMessage? = null,
    /** Absolute path of a fresh export, consumed by the share sheet. */
    val exportToShare: String? = null,
)

/** Settings actions of lot 2: export (A7), import (A8), total wipe (H6). No business rule here. */
class SettingsViewModel(
    private val app: App,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    private val shared: SharedFactory get() = app.shared

    fun export() {
        run {
            val result = shared.exportService.export()
            _state.update {
                it.copy(
                    message = SettingsMessage.Exported(result.counts.events, result.counts.photos),
                    exportToShare = result.absolutePath,
                )
            }
        }
    }

    fun exportShared() = _state.update { it.copy(exportToShare = null) }

    /** Copies the picked document into the private import directory (the core never reads foreign URIs). */
    fun import(
        uri: Uri,
        resolver: ContentResolver,
    ) {
        run {
            val relativePath = "${SharedFactory.IMPORT_DIRECTORY}/${Ulid.generate(System.currentTimeMillis())}.zip"
            val target = File(shared.config.dataDirectory, relativePath)
            withContext(Dispatchers.IO) {
                target.parentFile?.mkdirs()
                resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: throw IOException("unreadable document")
            }
            try {
                when (val result = shared.importService.import(relativePath)) {
                    is ImportResult.Done -> _state.update { it.copy(message = SettingsMessage.Imported(result.report)) }
                    is ImportResult.Rejected ->
                        _state.update { it.copy(message = SettingsMessage.ImportRefused(result.reason)) }
                }
            } finally {
                withContext(Dispatchers.IO) { target.delete() }
            }
        }
    }

    /** H6: the core wipes its data directory; the host then drops its own leftovers (share copies in the cache). */
    fun wipeAll() {
        run {
            shared.wipeAllData()
            withContext(Dispatchers.IO) { app.cacheDir.deleteRecursively() }
            app.recreateShared()
            _state.update { it.copy(message = SettingsMessage.Wiped) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (_: IOException) {
                _state.update { it.copy(message = SettingsMessage.Failed) }
            } catch (_: IllegalStateException) {
                _state.update { it.copy(message = SettingsMessage.Failed) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
