package app.trouvetout.core.data.projection

import app.trouvetout.core.data.db.JsonLists
import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.LocationStatus

/**
 * Applies journal events to the projected tables (`item`, `place`, `container`, `location_state`, `photo`,
 * `reminder`). This is the only writer of those tables. Applying the whole journal in `(at, deviceId, id)`
 * order from an empty projection must give the same tables as applying events as they arrived: see the
 * replay tests. Unknown references are tolerated (the journal is the truth, the projection never fails).
 */
internal class Projector(
    private val db: TrouveToutDb,
) {
    /** Empties every projected table; the caller then replays the journal. */
    fun reset() {
        db.locationStateQueries.deleteAll()
        db.itemQueries.deleteAll()
        db.containerQueries.deleteAll()
        db.placeQueries.deleteAll()
        db.photoQueries.deleteAll()
        db.reminderQueries.deleteAll()
    }

    fun apply(event: Event) {
        val at = event.at.toEpochMilliseconds()
        when (val p = event.payload) {
            is EventPayload.ItemCreated,
            is EventPayload.ItemUpdated,
            is EventPayload.ItemStored,
            is EventPayload.ItemMovedTo,
            is EventPayload.ItemTaken,
            is EventPayload.ItemReturned,
            is EventPayload.ItemLent,
            is EventPayload.ItemArchived,
            is EventPayload.ItemDeleted,
            is EventPayload.ItemRestored,
            -> applyItem(p, at)
            is EventPayload.PlaceCreated,
            is EventPayload.PlaceRenamed,
            is EventPayload.PlaceMerged,
            is EventPayload.PlaceDeleted,
            -> applyPlace(p, at)
            is EventPayload.ContainerCreated,
            is EventPayload.ContainerMoved,
            is EventPayload.ContainerDeleted,
            -> applyContainer(p, at)
            is EventPayload.PhotoAttached,
            is EventPayload.PhotoDetached,
            is EventPayload.ReminderSet,
            is EventPayload.ReminderDone,
            -> applyAttachment(p, at)
        }
    }

    private fun applyItem(
        p: EventPayload,
        at: Long,
    ) {
        when (p) {
            is EventPayload.ItemCreated -> createItem(p, at)
            is EventPayload.ItemUpdated -> updateItem(p)
            is EventPayload.ItemStored -> locate(p.itemId, p.placeId, p.containerId, at)
            is EventPayload.ItemMovedTo -> locate(p.itemId, p.placeId, p.containerId, at)
            is EventPayload.ItemTaken -> setStatus(p.itemId, LocationStatus.TAKEN, at)
            is EventPayload.ItemReturned -> setStatus(p.itemId, LocationStatus.STORED, at)
            is EventPayload.ItemLent -> setStatus(p.itemId, LocationStatus.LENT, at, lentTo = p.to)
            is EventPayload.ItemArchived -> {
                db.itemQueries.setArchived(p.archived, p.itemId)
                setStatus(p.itemId, if (p.archived) LocationStatus.ARCHIVED else LocationStatus.STORED, at)
            }
            is EventPayload.ItemDeleted -> db.itemQueries.setDeletedAt(at, p.itemId)
            is EventPayload.ItemRestored -> db.itemQueries.setDeletedAt(null, p.itemId)
            else -> Unit
        }
    }

    private fun applyPlace(
        p: EventPayload,
        at: Long,
    ) {
        when (p) {
            is EventPayload.PlaceCreated ->
                db.placeQueries.insert(p.placeId, p.name, p.parentId, p.kind.name, JsonLists.encode(p.aliases), at)
            is EventPayload.PlaceRenamed -> db.placeQueries.rename(p.name, JsonLists.encode(p.aliases), p.placeId)
            is EventPayload.PlaceMerged -> mergePlaces(p, at)
            is EventPayload.PlaceDeleted -> db.placeQueries.setDeletedAt(at, p.placeId)
            else -> Unit
        }
    }

    private fun applyContainer(
        p: EventPayload,
        at: Long,
    ) {
        when (p) {
            is EventPayload.ContainerCreated -> db.containerQueries.insert(
                p.containerId,
                p.label,
                p.number,
                p.placeId,
                at,
            )
            is EventPayload.ContainerMoved -> db.containerQueries.setPlace(p.placeId, p.containerId)
            is EventPayload.ContainerDeleted -> db.containerQueries.setDeletedAt(at, p.containerId)
            else -> Unit
        }
    }

    private fun applyAttachment(
        p: EventPayload,
        at: Long,
    ) {
        when (p) {
            is EventPayload.PhotoAttached ->
                db.photoQueries.insert(
                    p.photoId,
                    p.ownerType.name,
                    p.ownerId,
                    p.fileName,
                    p.thumbName,
                    p.width.toLong(),
                    p.height.toLong(),
                    p.sha256,
                    at,
                )
            is EventPayload.PhotoDetached -> db.photoQueries.setDetachedAt(at, p.photoId)
            is EventPayload.ReminderSet -> db.reminderQueries.insert(
                p.reminderId,
                p.itemId,
                p.fireAtEpochMillis,
                p.kind.name,
            )
            is EventPayload.ReminderDone -> db.reminderQueries.setDone(true, p.reminderId)
            else -> Unit
        }
    }

    private fun createItem(
        p: EventPayload.ItemCreated,
        at: Long,
    ) {
        db.itemQueries.insert(
            p.itemId,
            p.name,
            p.description,
            JsonLists.encode(p.tags),
            p.quantity,
            p.unit,
            p.sensitive,
            false,
            at,
        )
        if (db.locationStateQueries.selectByItem(p.itemId).executeAsOneOrNull() == null) {
            db.locationStateQueries.upsert(p.itemId, null, null, LocationStatus.STORED.name, null, at)
        }
    }

    private fun updateItem(p: EventPayload.ItemUpdated) {
        val row = db.itemQueries.selectById(p.itemId).executeAsOneOrNull() ?: return
        db.itemQueries.update(
            name = p.name ?: row.name,
            description = if (p.clearDescription) null else p.description ?: row.description,
            tags = p.tags?.let(JsonLists::encode) ?: row.tags,
            quantity = if (p.clearQuantity) null else p.quantity ?: row.quantity,
            unit = if (p.clearQuantity) null else p.unit ?: row.unit,
            sensitive = p.sensitive ?: row.sensitive,
            pinned = p.pinned ?: row.pinned,
            id = p.itemId,
        )
    }

    /** A stored or moved item is at exactly one of a place or a container; a loan or a take is over. */
    private fun locate(
        itemId: String,
        placeId: String?,
        containerId: String?,
        at: Long,
    ) {
        val (place, container) = if (containerId != null) null to containerId else placeId to null
        db.locationStateQueries.upsert(itemId, place, container, LocationStatus.STORED.name, null, at)
    }

    private fun setStatus(
        itemId: String,
        status: LocationStatus,
        at: Long,
        lentTo: String? = null,
    ) {
        val current = db.locationStateQueries.selectByItem(itemId).executeAsOneOrNull()
        db.locationStateQueries.upsert(itemId, current?.place_id, current?.container_id, status.name, lentTo, at)
    }

    /** D6: everything under the source place now belongs to the target; the source keeps its history. */
    private fun mergePlaces(
        p: EventPayload.PlaceMerged,
        at: Long,
    ) {
        if (p.placeId == p.intoPlaceId) return
        val source = db.placeQueries.selectById(p.placeId).executeAsOneOrNull() ?: return
        val target = db.placeQueries.selectById(p.intoPlaceId).executeAsOneOrNull() ?: return
        db.placeQueries.reparentChildren(p.intoPlaceId, p.placeId)
        db.containerQueries.movePlace(p.intoPlaceId, p.placeId)
        db.locationStateQueries.movePlace(p.intoPlaceId, p.placeId)
        val mergedAliases = (
            JsonLists.decode(
                target.aliases,
            ) + source.name + JsonLists.decode(source.aliases)
        ).distinct()
        db.placeQueries.rename(target.name, JsonLists.encode(mergedAliases), p.intoPlaceId)
        db.placeQueries.setMergedInto(p.intoPlaceId, at, p.placeId)
    }
}
