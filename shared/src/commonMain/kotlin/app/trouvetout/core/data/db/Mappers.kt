package app.trouvetout.core.data.db

import app.trouvetout.core.domain.model.Container
import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.EventJson
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.Item
import app.trouvetout.core.domain.model.LocationState
import app.trouvetout.core.domain.model.LocationStatus
import app.trouvetout.core.domain.model.Photo
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.model.Place
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.model.Reminder
import app.trouvetout.core.domain.model.ReminderKind
import kotlin.time.Instant
import app.trouvetout.core.data.db.Container as ContainerRow
import app.trouvetout.core.data.db.Event as EventRow
import app.trouvetout.core.data.db.Item as ItemRow
import app.trouvetout.core.data.db.Location_state as LocationRow
import app.trouvetout.core.data.db.Photo as PhotoRow
import app.trouvetout.core.data.db.Place as PlaceRow
import app.trouvetout.core.data.db.Reminder as ReminderRow

// Row ↔ domain conversions. Rows are the generated SQLDelight classes; the domain never sees them.

internal fun Long.toInstant(): Instant = Instant.fromEpochMilliseconds(this)

internal fun Long?.toInstantOrNull(): Instant? = this?.let { Instant.fromEpochMilliseconds(it) }

internal fun EventRow.toEvent(): Event = Event(
    id = id,
    at = at.toInstant(),
    deviceId = device_id,
    actor = actor,
    payload = EventJson.decodePayload(payload),
    source = EventSource.valueOf(source),
    rawInput = raw_input,
)

internal fun PlaceRow.toPlace(): Place = Place(
    id = id,
    name = name,
    parentId = parent_id,
    kind = PlaceKind.entries.firstOrNull { it.name == kind } ?: PlaceKind.SPOT,
    aliases = JsonLists.decode(aliases),
    createdAt = created_at.toInstant(),
    deletedAt = deleted_at.toInstantOrNull(),
    mergedInto = merged_into,
)

internal fun ContainerRow.toContainer(): Container = Container(
    id = id,
    label = label,
    number = number,
    placeId = place_id,
    createdAt = created_at.toInstant(),
    deletedAt = deleted_at.toInstantOrNull(),
)

internal fun ItemRow.toItem(): Item = Item(
    id = id,
    name = name,
    description = description,
    tags = JsonLists.decode(tags),
    quantity = quantity,
    unit = unit,
    sensitive = sensitive,
    pinned = pinned,
    archived = archived,
    createdAt = created_at.toInstant(),
    deletedAt = deleted_at.toInstantOrNull(),
)

internal fun LocationRow.toLocationState(): LocationState = LocationState(
    itemId = item_id,
    placeId = place_id,
    containerId = container_id,
    status = LocationStatus.entries.firstOrNull { it.name == state } ?: LocationStatus.STORED,
    lentTo = lent_to,
    since = since.toInstant(),
)

internal fun PhotoRow.toPhoto(): Photo = Photo(
    id = id,
    ownerType = PhotoOwnerType.valueOf(owner_type),
    ownerId = owner_id,
    fileName = file_name,
    thumbName = thumb_name,
    width = width.toInt(),
    height = height.toInt(),
    sha256 = sha256,
    createdAt = created_at.toInstant(),
    detachedAt = detached_at.toInstantOrNull(),
)

internal fun ReminderRow.toReminder(): Reminder = Reminder(
    id = id,
    itemId = item_id,
    fireAt = fire_at.toInstant(),
    kind = ReminderKind.entries.firstOrNull { it.name == kind } ?: ReminderKind.DATE,
    done = done,
)

/** Rows of `selectDeleted` carry a non-null `deleted_at`, hence their own generated type. */
internal fun SelectDeleted.toItem(): Item = Item(
    id = id,
    name = name,
    description = description,
    tags = JsonLists.decode(tags),
    quantity = quantity,
    unit = unit,
    sensitive = sensitive,
    pinned = pinned,
    archived = archived,
    createdAt = created_at.toInstant(),
    deletedAt = deleted_at.toInstant(),
)
