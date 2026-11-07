package app.trouvetout.core.licensing

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.time.Instant

/** J1, J4: six months of full use, then only new items wait for a purchase. Everything else stays free. */
public object TrialPolicy {
    public const val TRIAL_MONTHS: Int = 6

    /** Counted in UTC: the end does not move with the phone's time zone. */
    public fun endOf(startMillis: Long): Long = Instant
        .fromEpochMilliseconds(startMillis)
        .plus(TRIAL_MONTHS, DateTimeUnit.MONTH, TimeZone.UTC)
        .toEpochMilliseconds()

    public fun canAddItems(
        purchased: Boolean,
        nowMillis: Long,
        startMillis: Long,
    ): Boolean = purchased || nowMillis < endOf(startMillis)
}
