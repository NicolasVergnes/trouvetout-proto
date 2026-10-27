package app.trouvetout.core.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.trouvetout.core.data.EventStore
import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.db.toContainer
import app.trouvetout.core.data.db.toPhoto
import app.trouvetout.core.data.db.toPlace
import app.trouvetout.core.domain.model.Container
import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.EventSubject
import app.trouvetout.core.domain.model.Photo
import app.trouvetout.core.domain.model.PhotoOwnerType
import app.trouvetout.core.domain.model.Place
import app.trouvetout.core.platform.CoroutineDispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/** What a place screen shows (C7): the place itself (null at the root), its path, and what it holds. */
public data class PlaceContent(
    val place: Place?,
    /** Root first, down to [place] (C6 breadcrumb); empty at the root. */
    val path: List<Place>,
    val children: List<Place>,
    val containers: List<Container>,
    /** Items stored directly in the place (not in one of its containers), pinned first. */
    val items: List<ItemView>,
    val photos: List<Photo>,
)

/** What a container screen shows: the container, where it is, and its items. */
public data class ContainerContent(
    val container: Container,
    val path: List<Place>,
    val items: List<ItemView>,
    val photos: List<Photo>,
)

/** Read side of the navigation by place (C6, C7) and of the item sheet extras (A5, A11, D5). */
public class BrowseRepository internal constructor(
    private val db: TrouveToutDb,
    private val dispatchers: CoroutineDispatchers,
    private val items: ItemRepository,
    private val events: EventStore,
) {
    /** Content of [placeId], or of the root level when null. Emits again after each append. */
    public fun observePlace(placeId: String?): Flow<PlaceContent> {
        val structure =
            combine(placeFlow(placeId), childrenFlow(placeId), containersIn(placeId)) { place, children, containers ->
                Triple(place, children, containers)
            }
        val contents =
            combine(items.observeActive(), photosFlow(PhotoOwnerType.PLACE, placeId), resolvers(db, dispatchers)) {
                all,
                photos,
                resolver,
                ->
                Triple(all, photos, resolver)
            }
        return combine(structure, contents) { (place, children, containers), (all, photos, resolver) ->
            PlaceContent(
                place = place,
                path = placeId?.let(resolver::pathOf).orEmpty(),
                children = children,
                containers = containers,
                items = all.filter { it.location.placeId == placeId && it.location.containerId == null }.pinnedFirst(),
                photos = photos,
            )
        }
    }

    /** Content of one container; stops emitting (never emits null) once the container is gone. */
    public fun observeContainer(containerId: String): Flow<ContainerContent> = combine(
        db.containerQueries.selectById(containerId).asFlow().mapToOneOrNull(dispatchers.io),
        items.observeActive(),
        photosFlow(PhotoOwnerType.CONTAINER, containerId),
        resolvers(db, dispatchers),
    ) { row, allItems, photos, resolver ->
        row?.toContainer()?.let { container ->
            ContainerContent(
                container = container,
                path = resolver.pathOf(container.placeId),
                items = allItems.filter { it.location.containerId == containerId }.pinnedFirst(),
                photos = photos,
            )
        }
    }.filterNotNull()

    /** The item of a sheet; stops emitting (never emits null) once the item is deleted. */
    public fun observeItem(itemId: String): Flow<ItemView> = items.observeItem(itemId).filterNotNull()

    /** Attached photos of any owner, oldest first. */
    public fun observePhotos(
        ownerType: PhotoOwnerType,
        ownerId: String,
    ): Flow<List<Photo>> = photosFlow(ownerType, ownerId)

    /** Every tag in use, most used first (A11 suggestions). */
    public fun observeTags(): Flow<List<String>> = items.observeActive().map { list ->
        list
            .filter { !it.item.archived }
            .flatMap { it.item.tags }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    /** The item's journal, oldest first (A5); re-read whenever the item changes. */
    public fun observeHistory(itemId: String): Flow<List<Event>> =
        items.observeItem(itemId).map { events.historyOf(EventSubject(EventSubject.SubjectType.ITEM, itemId)) }

    private fun placeFlow(placeId: String?): Flow<Place?> = if (placeId == null) {
        db.placeQueries.selectChildren(null).asFlow().map { null }
    } else {
        db.placeQueries.selectById(placeId).asFlow().mapToOneOrNull(dispatchers.io).map { it?.toPlace() }
    }

    private fun childrenFlow(placeId: String?): Flow<List<Place>> =
        db.placeQueries.selectChildren(placeId).asFlow().mapToList(dispatchers.io).map { rows ->
            rows.map { it.toPlace() }
        }

    private fun containersIn(placeId: String?): Flow<List<Container>> = if (placeId == null) {
        db.containerQueries.selectActive().asFlow().map { emptyList() }
    } else {
        db.containerQueries.selectByPlace(placeId).asFlow().mapToList(dispatchers.io).map { rows ->
            rows.map { it.toContainer() }
        }
    }

    private fun photosFlow(
        ownerType: PhotoOwnerType,
        ownerId: String?,
    ): Flow<List<Photo>> = if (ownerId == null) {
        db.photoQueries.selectAll().asFlow().map { emptyList() }
    } else {
        db.photoQueries
            .selectByOwner(ownerType.name, ownerId)
            .asFlow()
            .mapToList(dispatchers.io)
            .map { rows -> rows.map { it.toPhoto() } }
    }

    /** Pinned first (D7), archived last (D11), names in between. */
    private fun List<ItemView>.pinnedFirst(): List<ItemView> = sortedWith(
        compareBy<ItemView> { it.item.archived }.thenByDescending { it.item.pinned }.thenBy { it.item.name },
    )
}
