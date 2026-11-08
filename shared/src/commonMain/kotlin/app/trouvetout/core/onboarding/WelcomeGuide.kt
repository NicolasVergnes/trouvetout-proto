package app.trouvetout.core.onboarding

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import app.trouvetout.core.data.db.MetaKeys
import app.trouvetout.core.data.db.TrouveToutDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * I10, first steps without a blocking tutorial: the hosts show a card above the empty home list while nothing has
 * ever been recorded on this device and the user has not dismissed it. Never modal; the flag lives in `meta`
 * (local, never exported, erased by the total wipe, after which the card comes back with the empty journal).
 */
public class WelcomeGuide internal constructor(
    private val db: TrouveToutDb,
    private val io: CoroutineDispatcher,
) {
    private val dismissed =
        MutableStateFlow(
            db.metaQueries.get(MetaKeys.WELCOME_DISMISSED).executeAsOneOrNull()?.toBooleanStrictOrNull() ?: false,
        )

    /** True while the card is offered: an empty journal (no action yet, deleted ones included) and no dismissal. */
    public val visible: Flow<Boolean> =
        combine(dismissed, db.eventQueries.count().asFlow().mapToOne(io)) { gone, events -> !gone && events == 0L }
            .distinctUntilChanged()

    @Throws(Exception::class) // to Swift: an error it can handle
    public suspend fun dismiss() {
        withContext(io) { db.metaQueries.set(MetaKeys.WELCOME_DISMISSED, true.toString()) }
        dismissed.value = true
    }
}
