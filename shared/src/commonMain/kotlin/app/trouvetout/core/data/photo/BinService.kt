package app.trouvetout.core.data.photo

import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.domain.model.PhotoOwnerType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

public data class PurgeReport(
    val itemsPurged: Int,
    val photoFilesDeleted: Int,
)

/**
 * A13: the bin keeps deleted items (and their photos) for [RETENTION]; after that the photo *files* are removed.
 * Events and rows stay — the journal is never rewritten — so a restore after the retention brings the record
 * back without its pictures. Detached photos follow the same rule.
 */
public class BinService internal constructor(
    private val db: TrouveToutDb,
    private val photoStore: PhotoStore,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
) {
    public suspend fun purgeExpired(retention: Duration = RETENTION): PurgeReport = withContext(dispatcher) {
        val cutoff = clock.now().toEpochMilliseconds() - retention.inWholeMilliseconds
        val expiredItemIds = db.itemQueries.selectDeleted().executeAsList().filter {
            it.deleted_at <= cutoff
        }.map { it.id }.toSet()
        val photos = db.photoQueries.selectAll().executeAsList()
        val (expiredByItem, others) = photos.partition {
            it.owner_type == PhotoOwnerType.ITEM.name &&
                it.owner_id in expiredItemIds
        }
        val expiredDetached = others.filter { it.detached_at != null && it.detached_at <= cutoff }
        // A file still referenced by a live row (possible after an import) is never removed.
        val stillReferenced = (others - expiredDetached.toSet()).flatMap { listOf(it.file_name, it.thumb_name) }.toSet()
        var itemsPurged = 0
        var filesDeleted = 0
        for (itemPhotos in expiredByItem.groupBy { it.owner_id }.values) {
            var deletedSomething = false
            for (photo in itemPhotos) {
                if (deleteUnlessReferenced(photo.file_name, stillReferenced)) {
                    filesDeleted++
                    deletedSomething = true
                }
                if (deleteUnlessReferenced(photo.thumb_name, stillReferenced)) filesDeleted++
            }
            if (deletedSomething) itemsPurged++
        }
        for (photo in expiredDetached) {
            if (deleteUnlessReferenced(photo.file_name, stillReferenced)) filesDeleted++
            if (deleteUnlessReferenced(photo.thumb_name, stillReferenced)) filesDeleted++
        }
        PurgeReport(itemsPurged, filesDeleted)
    }

    /** A row with a name the store never produced (imported journal) is skipped, never a reason to stop the purge. */
    private suspend fun deleteUnlessReferenced(
        fileName: String,
        stillReferenced: Set<String>,
    ): Boolean = fileName !in stillReferenced &&
        PhotoStore.isValidFileName(fileName) &&
        photoStore.delete(fileName)

    public companion object {
        public val RETENTION: Duration = 30.days
    }
}
