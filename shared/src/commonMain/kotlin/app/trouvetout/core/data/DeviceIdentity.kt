package app.trouvetout.core.data

import app.trouvetout.core.data.db.MetaKeys
import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.domain.ids.Ulid
import kotlin.random.Random
import kotlin.time.Clock

/** Local identity of this installation, generated once and stored in `meta` (ADR-005: ready for multi-device merge). */
internal object DeviceIdentity {
    fun ensure(
        db: TrouveToutDb,
        clock: Clock,
        random: Random,
    ): String = db.metaQueries.get(MetaKeys.DEVICE_ID).executeAsOneOrNull()
        ?: Ulid.generate(clock.now().toEpochMilliseconds(), random).also {
            db.metaQueries.set(MetaKeys.DEVICE_ID, it)
        }
}
