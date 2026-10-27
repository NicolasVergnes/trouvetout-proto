package app.trouvetout.core.data.photo

import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.usecase.AttachmentCommands
import app.trouvetout.core.domain.usecase.CommandResult
import app.trouvetout.core.domain.usecase.Rejection

/**
 * Picked or captured image → stored files → `PhotoAttached` event, in one call (A10, D5). When the attach is
 * refused (owner gone meanwhile) or fails, the files just written are removed: nothing stays on disk without
 * a journal entry.
 */
public class PhotoAttacher internal constructor(
    private val store: PhotoStore,
    private val attachments: AttachmentCommands,
) {
    public suspend fun attach(
        ownerType: PhotoOwnerType,
        ownerId: String,
        source: ByteArray,
        eventSource: EventSource,
    ): CommandResult {
        if (source.size > PhotoStore.MAX_SOURCE_BYTES) return CommandResult.Rejected(Rejection.IMAGE_TOO_LARGE)
        val file = store.store(source) ?: return CommandResult.Rejected(Rejection.UNSUPPORTED_IMAGE)
        val attached = runCatching { attachments.attachPhoto(ownerType, ownerId, file, eventSource) }
        if (attached.getOrNull() !is CommandResult.Done) {
            store.delete(file.fileName)
            store.delete(file.thumbName)
        }
        return attached.getOrThrow()
    }
}
