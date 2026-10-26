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
        var itemsPurged = 0
        var filesDeleted = 0
        val expiredItems = db.itemQueries.selectDeleted().executeAsList().filter { it.deleted_at <= cutoff }
        for (item in expiredItems) {
            val photos = db.photoQueries.selectByOwner(PhotoOwnerType.ITEM.name, item.id).executeAsList()
            var deletedSomething = false
            for (photo in photos) {
                if (photoStore.delete(photo.file_name)) {
                    filesDeleted++
                    deletedSomething = true
                }
                if (photoStore.delete(photo.thumb_name)) filesDeleted++
            }
            if (deletedSomething) itemsPurged++
        }
        val detached = db.photoQueries.selectAll().executeAsList().filter {
            it.detached_at != null &&
                it.detached_at <= cutoff
        }
        for (photo in detached) {
            if (photoStore.delete(photo.file_name)) filesDeleted++
            if (photoStore.delete(photo.thumb_name)) filesDeleted++
        }
        PurgeReport(itemsPurged, filesDeleted)
    }

    public companion object {
        public val RETENTION: Duration = 30.days
    }
}
