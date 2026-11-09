package app.trouvetout.core.onboarding

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import app.trouvetout.core.data.db.MetaKeys
import app.trouvetout.core.data.db.TrouveToutDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    /** Dismissed in this process (written by [dismiss]); the stored flag is read on [io], never on the caller's thread. */
    private val dismissedNow = MutableStateFlow(false)
    private val dismissedBefore =
        flow {
            emit(withContext(io) { db.metaQueries.get(MetaKeys.WELCOME_DISMISSED).executeAsOneOrNull() == "true" })
        }

    /** True while the card is offered: an empty journal (no action yet, deleted ones included) and no dismissal. */
    @OptIn(ExperimentalCoroutinesApi::class)
    public val visible: Flow<Boolean> =
        combine(dismissedBefore, dismissedNow) { before, now -> before || now }
            .flatMapLatest { dismissed ->
                // Once dismissed, the journal is no longer watched.
                if (dismissed) flowOf(false) else db.eventQueries.hasAny().asFlow().mapToOne(io).map { any -> !any }
            }.distinctUntilChanged()

    @Throws(Exception::class) // to Swift: an error it can handle
    public suspend fun dismiss() {
        withContext(io) { db.metaQueries.set(MetaKeys.WELCOME_DISMISSED, true.toString()) }
        dismissedNow.value = true
    }
}
