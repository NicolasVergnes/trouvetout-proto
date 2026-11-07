package app.trouvetout.core.licensing

import app.trouvetout.core.data.db.MetaKeys
import app.trouvetout.core.data.db.TrouveToutDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** What the screens say about the licence (U11). */
public data class LicenseStatus(
    val purchased: Boolean,
    val trialEndMillis: Long,
    val canAddItems: Boolean,
)

/**
 * The trial and the last purchase state (J1, J4, J5, ADR-025). The apps ask the stores and report here; the core
 * decides. The trial starts at the oldest of the stored date and the first event of the journal, so that importing
 * an export after a reinstall does not start it again. What the journal says is read each time and never stored: an
 * export imported by mistake stops counting once it is erased, and erasing leaves the date of the first launch.
 */
public class LicensingService internal constructor(
    private val db: TrouveToutDb,
    /** Null where nothing is ever blocked: tools and tests that build the core without a host. */
    private val store: LicenseStore?,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
) {
    @Throws(Exception::class) // to Swift: an error it can handle, not the end of the process
    public suspend fun status(): LicenseStatus = withContext(io) {
        val now = clock.now().toEpochMilliseconds()
        val start = settledStart(now)
        val purchased = db.metaQueries.get(MetaKeys.PURCHASED).executeAsOneOrNull().toBoolean()
        LicenseStatus(purchased, TrialPolicy.endOf(start), TrialPolicy.canAddItems(purchased, now, start))
    }

    internal suspend fun canAddItems(): Boolean = store == null || status().canAddItems

    /**
     * What the store answered: a purchase or a running subscription, or neither (a subscription ended, a refund). When
     * it could not answer the apps do not call this, and the last answer stands.
     */
    @Throws(Exception::class)
    public suspend fun recordPurchased(purchased: Boolean) {
        withContext(io) { db.metaQueries.set(MetaKeys.PURCHASED, purchased.toString()) }
    }

    /** iOS: the date Apple signed for the first download of the app, which a reinstall does not change. */
    @Throws(Exception::class)
    public suspend fun recordStoreDate(millis: Long) {
        withContext(io) {
            if (millis < EARLIEST_PLAUSIBLE_MILLIS || millis > clock.now().toEpochMilliseconds()) return@withContext
            val stored = store?.trialStartMillis()
            if (stored == null || millis < stored) store?.setTrialStartMillis(millis)
        }
    }

    private fun settledStart(now: Long): Long {
        // A date older than the app is a phone that was at the wrong time: it must not end the trial for good.
        val firstLaunch =
            store?.trialStartMillis()?.takeIf { it >= EARLIEST_PLAUSIBLE_MILLIS }
                ?: now.also { store?.setTrialStartMillis(it) }
        val oldest = db.eventQueries.oldestAtSince(EARLIEST_PLAUSIBLE_MILLIS).executeAsOne().oldest
        // A journal imported from the future does not push the first launch forward.
        return listOfNotNull(firstLaunch, oldest).min()
    }

    private companion object {
        /**
         * 1 January 2026, before the app existed. An older date is a wrong clock or a damaged export (the import
         * accepts from 1970): it must not end someone's trial.
         */
        const val EARLIEST_PLAUSIBLE_MILLIS = 1_767_225_600_000L
    }
}
