package app.trouvetout.core.data

import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.db.toContainer
import app.trouvetout.core.data.db.toItem
import app.trouvetout.core.data.db.toLocationState
import app.trouvetout.core.data.db.toPhoto
import app.trouvetout.core.data.db.toPlace
import app.trouvetout.core.data.db.toReminder
import app.trouvetout.core.data.projection.Projector
import app.trouvetout.core.domain.model.Container
import app.trouvetout.core.domain.model.Item
import app.trouvetout.core.domain.model.LocationState
import app.trouvetout.core.domain.model.Photo
import app.trouvetout.core.domain.model.Place
import app.trouvetout.core.domain.model.Reminder
import kotlinx.coroutines.Dispatchers

/** Every projected table as sorted domain lists: two databases with equal snapshots are equal. */
data class ProjectionSnapshot(
    val items: List<Item>,
    val locations: List<LocationState>,
    val places: List<Place>,
    val containers: List<Container>,
    val photos: List<Photo>,
    val reminders: List<Reminder>,
)

fun TrouveToutDb.snapshot(): ProjectionSnapshot = ProjectionSnapshot(
    items = itemQueries.selectAll().executeAsList().map { it.toItem() }.sortedBy { it.id },
    locations = locationStateQueries.selectAll().executeAsList().map {
        it.toLocationState()
    }.sortedBy { it.itemId },
    places = placeQueries.selectAll().executeAsList().map { it.toPlace() }.sortedBy { it.id },
    containers = containerQueries.selectActive().executeAsList().map { it.toContainer() }.sortedBy { it.id },
    photos = photoQueries.selectAll().executeAsList().map { it.toPhoto() }.sortedBy { it.id },
    reminders = reminderQueries.selectPending().executeAsList().map { it.toReminder() }.sortedBy { it.id },
)

/** An event store over an in-memory database, on an unconfined dispatcher for tests. */
fun TrouveToutDb.testEventStore(): EventStore = EventStore(this, Projector(this), Dispatchers.Unconfined)
