package app.trouvetout.core.search

import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.db.toContainer
import app.trouvetout.core.data.db.toItem
import app.trouvetout.core.data.db.toLocationState
import app.trouvetout.core.data.db.toPlace
import app.trouvetout.core.data.repository.ItemView
import app.trouvetout.core.data.repository.LocationResolver
import app.trouvetout.core.domain.model.LocationState
import app.trouvetout.core.domain.model.LocationStatus

/** Identifies a state of the journal: any append (even an out-of-order import) changes it. */
internal data class JournalVersion(
    val count: Long,
    val headId: String?,
)

/** Every active item with its searchable text and its keyword index, for one state of the journal. */
internal class SearchSnapshot(
    val version: JournalVersion,
    val views: List<ItemView>,
    val documents: List<SearchDocument>,
) {
    val keyword: KeywordIndex = KeywordIndex(documents)
    val indexOf: Map<String, Int> = views.withIndex().associate { (i, view) -> view.item.id to i }

    companion object {
        /** Sentences kept per item for the index: the latest ones say the most about it. */
        private const val RAW_INPUTS_PER_ITEM = 2

        fun version(db: TrouveToutDb): JournalVersion =
            JournalVersion(db.eventQueries.count().executeAsOne(), db.eventQueries.head().executeAsOneOrNull()?.id)

        /** Reads the projection in one pass (blocking: called on the IO dispatcher). */
        fun load(db: TrouveToutDb): SearchSnapshot = db.transactionWithResult {
            val version = version(db)
            val items = db.itemQueries.selectActive().executeAsList().map { it.toItem() }
            val locations = db.locationStateQueries.selectAll().executeAsList().associate {
                it.item_id to it.toLocationState()
            }
            val resolver =
                LocationResolver(
                    db.placeQueries.selectAll().executeAsList().map { it.toPlace() },
                    db.containerQueries.selectActive().executeAsList().map { it.toContainer() },
                )
            val rawInputs = HashMap<String, MutableList<String>>()
            for (row in db.eventSubjectQueries.itemRawInputs().executeAsList()) {
                val text = row.raw_input
                val list = rawInputs.getOrPut(row.subject_id) { ArrayList(RAW_INPUTS_PER_ITEM) }
                if (list.size < RAW_INPUTS_PER_ITEM && text !in list) list += text
            }
            val views =
                items.map { item ->
                    val location =
                        locations[item.id]
                            ?: LocationState(item.id, null, null, LocationStatus.STORED, null, item.createdAt)
                    ItemView(item, location, resolver.resolve(location))
                }
            val documents =
                views.map { view ->
                    SearchDocument(
                        itemId = view.item.id,
                        name = view.item.name,
                        description = view.item.description,
                        tags = view.item.tags,
                        location = view.path.labels,
                        rawInputs = rawInputs[view.item.id].orEmpty(),
                    )
                }
            SearchSnapshot(version, views, documents)
        }
    }
}

/** The text the model embeds for an item: what it is, not where it is (the keyword index covers places). */
internal fun SearchDocument.embeddingText(): String = buildString {
    append(name)
    description?.takeIf { it.isNotBlank() }?.let { append(". ").append(it) }
    if (tags.isNotEmpty()) append(". ").append(tags.joinToString(", "))
}
