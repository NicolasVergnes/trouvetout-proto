package app.trouvetout.core.data.repository

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.db.toContainer
import app.trouvetout.core.data.db.toItem
import app.trouvetout.core.data.db.toLocationState
import app.trouvetout.core.data.db.toPlace
import app.trouvetout.core.domain.model.Container
import app.trouvetout.core.domain.model.Item
import app.trouvetout.core.domain.model.LocationState
import app.trouvetout.core.domain.model.LocationStatus
import app.trouvetout.core.domain.model.Place
import app.trouvetout.core.platform.CoroutineDispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import app.trouvetout.core.data.db.Container as ContainerRow
import app.trouvetout.core.data.db.Item as ItemRow
import app.trouvetout.core.data.db.Place as PlaceRow

/** An item with its current state and resolved location, as screens need it. */
public data class ItemView(
    val item: Item,
    val location: LocationState,
    val path: LocationPath,
)

/** Read side over the projected tables; everything is a `Flow` that re-emits after each append. */
public class ItemRepository internal constructor(
    private val db: TrouveToutDb,
    private val dispatchers: CoroutineDispatchers,
) {
    public fun observeActive(): Flow<List<ItemView>> = views(items(db.itemQueries.selectActive()))

    /** Items in the bin (A13), most recently deleted first. */
    public fun observeDeleted(): Flow<List<ItemView>> = views(
        db.itemQueries.selectDeleted().asFlow().mapToList(dispatchers.io).map { rows ->
            rows.map { it.toItem() }
        },
    )

    public fun observeItem(itemId: String): Flow<ItemView?> =
        views(items(db.itemQueries.selectById(itemId))).map { it.firstOrNull() }

    private fun items(query: Query<ItemRow>): Flow<List<Item>> =
        query.asFlow().mapToList(dispatchers.io).map { rows -> rows.map { it.toItem() } }

    private fun views(items: Flow<List<Item>>): Flow<List<ItemView>> = combine(
        items,
        db.locationStateQueries.selectAll().asFlow().mapToList(dispatchers.io),
        resolvers(db, dispatchers),
    ) { items, locationRows, resolver ->
        val locations = locationRows.associate { it.item_id to it.toLocationState() }
        items.map { item ->
            val location =
                locations[item.id]
                    ?: LocationState(item.id, null, null, LocationStatus.STORED, null, item.createdAt)
            ItemView(item, location, resolver.resolve(location))
        }
    }
}

public class PlaceRepository internal constructor(
    private val db: TrouveToutDb,
    private val dispatchers: CoroutineDispatchers,
) {
    public fun observeActive(): Flow<List<Place>> = places(db.placeQueries.selectActive())

    /** Direct children; `null` lists the root places (D1). */
    public fun observeChildren(parentId: String?): Flow<List<Place>> = places(db.placeQueries.selectChildren(parentId))

    public fun observeResolver(): Flow<LocationResolver> = resolvers(db, dispatchers)

    private fun places(query: Query<PlaceRow>): Flow<List<Place>> =
        query.asFlow().mapToList(dispatchers.io).map { rows -> rows.map { it.toPlace() } }
}

public class ContainerRepository internal constructor(
    private val db: TrouveToutDb,
    private val dispatchers: CoroutineDispatchers,
) {
    public fun observeActive(): Flow<List<Container>> = containers(db.containerQueries.selectActive())

    public fun observeInPlace(placeId: String): Flow<List<Container>> =
        containers(db.containerQueries.selectByPlace(placeId))

    private fun containers(query: Query<ContainerRow>): Flow<List<Container>> =
        query.asFlow().mapToList(dispatchers.io).map { rows -> rows.map { it.toContainer() } }
}

private fun resolvers(
    db: TrouveToutDb,
    dispatchers: CoroutineDispatchers,
): Flow<LocationResolver> = combine(
    db.placeQueries.selectAll().asFlow().mapToList(dispatchers.io),
    db.containerQueries.selectActive().asFlow().mapToList(dispatchers.io),
) { places, containers -> LocationResolver(places.map { it.toPlace() }, containers.map { it.toContainer() }) }
