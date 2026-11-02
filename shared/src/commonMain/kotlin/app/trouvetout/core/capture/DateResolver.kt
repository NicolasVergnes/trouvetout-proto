package app.trouvetout.core.capture

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Places a [DateSpec] in the calendar of [timeZone] as seen at [now] (E2). A day without a time is at 9:00; a day
 * without a year is the next one to come; a time alone is today, or tomorrow once it is past. The result can still be
 * in the past (« ce matin » at noon, « le 3 mars 2020 »): the capture refuses it then.
 */
internal object DateResolver {
    const val DEFAULT_HOUR: Int = 9
    private const val YEARS_AHEAD = 8

    fun resolve(
        spec: DateSpec,
        now: Instant,
        timeZone: TimeZone,
    ): Instant {
        val today = now.toLocalDateTime(timeZone).date
        val time = LocalTime(spec.hour ?: DEFAULT_HOUR, spec.minute)
        fun at(date: LocalDate): Instant {
            val day = if (spec.dayBefore) date.minus(1, DateTimeUnit.DAY) else date
            return LocalDateTime(day, time).toInstant(timeZone)
        }
        return when (val day = spec.day) {
            null -> at(today).let { if (it > now) it else at(today.plus(1, DateTimeUnit.DAY)) }
            is DayRef.InDays -> at(today.plus(day.days, DateTimeUnit.DAY))
            is DayRef.InMonths -> at(today.plus(day.months, DateTimeUnit.MONTH))
            is DayRef.NextWeekday -> {
                val ahead = (day.isoDay - today.dayOfWeek.isoDayNumber + DAYS_PER_WEEK) % DAYS_PER_WEEK
                at(today.plus(if (ahead == 0) DAYS_PER_WEEK else ahead, DateTimeUnit.DAY))
            }
            is DayRef.OnDate -> onDate(day, today, now, ::at)
        }
    }

    private fun onDate(
        day: DayRef.OnDate,
        today: LocalDate,
        now: Instant,
        at: (LocalDate) -> Instant,
    ): Instant {
        day.year?.let { return at(LocalDate(it, day.month, day.day)) }
        // The next such day: this year unless it is past, else the following years (29 February waits for a leap year).
        for (year in today.year..today.year + YEARS_AHEAD) {
            val date = runCatching { LocalDate(year, day.month, day.day) }.getOrNull() ?: continue
            val instant = at(date)
            if (instant > now) return instant
        }
        return at(LocalDate(today.year, day.month, minOf(day.day, LAST_SAFE_DAY)))
    }

    private const val DAYS_PER_WEEK = 7
    private const val LAST_SAFE_DAY = 28
}
