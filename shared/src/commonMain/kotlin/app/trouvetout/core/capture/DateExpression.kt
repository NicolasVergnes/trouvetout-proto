package app.trouvetout.core.capture

/** The day a reminder phrase names, before it is placed in a calendar (E2). */
public sealed interface DayRef {
    /** « le 12 mars », "March 12": [year] null when the sentence gives none (the next such day is meant). */
    public data class OnDate(
        val day: Int,
        val month: Int,
        val year: Int?,
    ) : DayRef

    /** « aujourd'hui » (0), « demain » (1), « dans 2 semaines » (14). */
    public data class InDays(
        val days: Int,
    ) : DayRef

    /** « dans 3 mois », « le mois prochain »: the same day of the month, clamped to the month's end. */
    public data class InMonths(
        val months: Int,
    ) : DayRef

    /** « lundi », "next Monday": the next such weekday strictly after today; [isoDay] 1 = Monday … 7 = Sunday. */
    public data class NextWeekday(
        val isoDay: Int,
    ) : DayRef
}

/**
 * A date expression read from a sentence, still unresolved: [DateResolver] places it in a calendar with a clock and
 * a time zone. [day] null means the sentence only gave a time (« à 18h »): today, or tomorrow once that time is past.
 * [hour] null means no time was said (9:00 is used). [dayBefore] is « la veille du … » / "the day before …".
 */
public data class DateSpec(
    val day: DayRef?,
    val hour: Int? = null,
    val minute: Int = 0,
    val dayBefore: Boolean = false,
)

/** Where a date expression sits in the cleaned tokens of a sentence: [start] inclusive, [end] exclusive. */
internal data class DateSpan(
    val start: Int,
    val end: Int,
    val spec: DateSpec,
)

/**
 * Rule-based reader of the date and time expressions of a reminder (FR, EN), on cleaned tokens (folded keys, number
 * words already spelled as digits by `Rules.clean`). Pure: no clock, no calendar; an impossible day (« 31 avril »)
 * is not a date.
 */
internal class DateExpression(
    private val lang: Lang,
    /** « 12/03 » is the 12th of March unless the host's locale writes the month first (en-US). */
    private val dayFirst: Boolean = true,
) {
    private val words = if (lang == Lang.FR) FR else EN

    /** The first date expression of [toks], with the glue words in front of it (« le », « à », "on", "at"). */
    fun find(toks: List<Tok>): DateSpan? {
        for (i in toks.indices) {
            val parsed = parseAt(toks, i) ?: continue
            return DateSpan(i, parsed.first, parsed.second)
        }
        return null
    }

    /** End index and spec of the expression starting at [from], glue included; null when none starts there. */
    private fun parseAt(
        toks: List<Tok>,
        from: Int,
    ): Pair<Int, DateSpec>? {
        var j = from
        var day: DayRef? = null
        var time: Pair<Int, Int>? = null
        var dayBefore = false
        var parts = 0
        while (parts < MAX_PARTS) {
            val component = componentAt(toks, j, day == null && !dayBefore, day == null, time == null) ?: break
            j = component.end
            component.day?.let { day = it }
            component.time?.let { time = it }
            if (component.dayBefore) dayBefore = true
            parts++
        }
        if (parts == 0 || (dayBefore && day == null)) return null
        return j to DateSpec(day, time?.first, time?.second ?: 0, dayBefore)
    }

    private class Component(
        val end: Int,
        val day: DayRef? = null,
        val time: Pair<Int, Int>? = null,
        val dayBefore: Boolean = false,
    )

    /** One part (day, time, « la veille de ») at [at], possibly after up to two glue words. */
    private fun componentAt(
        toks: List<Tok>,
        at: Int,
        allowBefore: Boolean,
        allowDay: Boolean,
        allowTime: Boolean,
        glued: Int = 0,
    ): Component? {
        if (at >= toks.size) return null
        val part = partAt(toks, at, allowBefore, allowDay, allowTime, glued > 0)
        val glue = if (part == null && glued < MAX_GLUE) words.glue.matchAt(toks, at) else 0
        return when {
            part != null -> part
            glue == 0 -> null
            else -> componentAt(toks, at + glue, allowBefore, allowDay, allowTime, glued + 1)
        }
    }

    private fun partAt(
        toks: List<Tok>,
        i: Int,
        allowBefore: Boolean,
        allowDay: Boolean,
        allowTime: Boolean,
        afterGlue: Boolean,
    ): Component? {
        val before = if (allowBefore) words.dayBefore.matchAt(toks, i) else 0
        if (before > 0) return Component(i + before, dayBefore = true)
        val day = if (allowDay) dayAt(toks, i) else null
        return day ?: if (allowTime) timeAt(toks, i, afterGlue) else null
    }

    // ---- days

    private fun dayAt(
        toks: List<Tok>,
        i: Int,
    ): Component? {
        words.combined.entries.firstOrNull { (phrase, _) -> Tokens.matchAt(toks, i, phrase) > 0 }?.let { (phrase, v) ->
            return Component(i + phrase.size, DayRef.InDays(v.first), v.second to 0)
        }
        words.relativeDays.entries.firstOrNull { (phrase, _) -> Tokens.matchAt(toks, i, phrase) > 0 }?.let { (p, v) ->
            return Component(i + p.size, v)
        }
        return inDuration(toks, i) ?: weekdayAt(toks, i) ?: absoluteAt(toks, i)
    }

    /** « dans 3 jours », "in a week". */
    private fun inDuration(
        toks: List<Tok>,
        i: Int,
    ): Component? {
        if (toks[i].key != words.durationLink) return null
        val count = toks.getOrNull(i + 1)?.let { countOf(it) } ?: return null
        val unit = toks.getOrNull(i + 2)?.key ?: return null
        val ref =
            when (unit) {
                in words.dayUnits -> DayRef.InDays(count)
                in words.weekUnits -> DayRef.InDays(count * DAYS_PER_WEEK)
                in words.monthUnits -> DayRef.InMonths(count)
                in words.yearUnits -> DayRef.InMonths(count * MONTHS_PER_YEAR)
                else -> return null
            }
        return Component(i + DURATION_TOKENS, ref)
    }

    private fun countOf(tok: Tok): Int? = when {
        tok.key in words.one -> 1
        tok.isNumber -> tok.key.toIntOrNull()?.takeIf { it in 1..MAX_COUNT }
        else -> null
    }

    private fun weekdayAt(
        toks: List<Tok>,
        i: Int,
    ): Component? {
        val next = words.nextBefore.isNotEmpty() && toks[i].key in words.nextBefore
        val at = if (next) i + 1 else i
        val iso = toks.getOrNull(at)?.key?.let { words.weekdays[it] } ?: return null
        val trailing = toks.getOrNull(at + 1)?.key in words.nextAfter
        return Component(at + 1 + if (trailing) 1 else 0, DayRef.NextWeekday(iso))
    }

    /** « 12 mars 2027 », « 1er avril », "March 12th", "the 12th of March", « 12/03 ». */
    private fun absoluteAt(
        toks: List<Tok>,
        i: Int,
    ): Component? = numericAt(toks, i) ?: dayMonthAt(toks, i) ?: monthDayAt(toks, i)

    private fun dayMonthAt(
        toks: List<Tok>,
        i: Int,
    ): Component? {
        val day = dayNumber(toks[i]) ?: return null
        var j = i + 1
        if (toks.getOrNull(j)?.key == words.ofLink) j++
        val month = toks.getOrNull(j)?.key?.let { words.months[it] } ?: return null
        return dated(toks, day, month, j + 1)
    }

    private fun monthDayAt(
        toks: List<Tok>,
        i: Int,
    ): Component? {
        if (lang != Lang.EN) return null
        val month = words.months[toks[i].key] ?: return null
        var j = i + 1
        if (toks.getOrNull(j)?.key == "the") j++
        val day = toks.getOrNull(j)?.let { dayNumber(it) } ?: return null
        var end = j + 1
        if (toks.getOrNull(end)?.key == ",") end++
        return dated(toks, day, month, end)
    }

    /** Adds the year that may follow, and checks that the day exists (29 February needs a leap year, or none). */
    private fun dated(
        toks: List<Tok>,
        day: Int,
        month: Int,
        after: Int,
    ): Component? {
        val year = toks.getOrNull(after)?.key?.takeIf { YEAR.matches(it) }?.toInt()
        val end = if (year != null) after + 1 else after
        if (!exists(day, month, year)) return null
        return Component(end, DayRef.OnDate(day, month, year))
    }

    private fun numericAt(
        toks: List<Tok>,
        i: Int,
    ): Component? {
        val match = NUMERIC.matchEntire(toks[i].key) ?: return null
        val (a, b, y) = match.destructured
        val first = a.toInt()
        val second = b.toInt()
        val day = if (dayFirst) first else second
        val month = if (dayFirst) second else first
        val year = y.takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < CENTURY) it + YEAR_2000 else it }
        if (!exists(day, month, year)) return null
        return Component(i + 1, DayRef.OnDate(day, month, year))
    }

    private fun dayNumber(tok: Tok): Int? {
        val key = tok.key
        val digits =
            when {
                key in words.firstOfMonth -> "1"
                else -> ORDINAL.matchEntire(key)?.groupValues?.get(1)
            } ?: return null
        return digits.toIntOrNull()?.takeIf { it in 1..MAX_DAY }
    }

    // ---- times

    private fun timeAt(
        toks: List<Tok>,
        i: Int,
        afterGlue: Boolean,
    ): Component? = namedTimeAt(toks, i) ?: clockTokenAt(toks, i) ?: spokenHourAt(toks, i, afterGlue)

    /** « midi », "noon", « dans la matinée ». */
    private fun namedTimeAt(
        toks: List<Tok>,
        i: Int,
    ): Component? {
        words.timeWords[toks[i].key]?.let { return Component(i + 1, time = it to 0) }
        val phrase = words.timePhrases.entries.firstOrNull { (p, _) -> Tokens.matchAt(toks, i, p) > 0 } ?: return null
        return Component(i + phrase.key.size, time = phrase.value to 0)
    }

    /** One token: « 18h30 », « 21:00 », "6pm". */
    private fun clockTokenAt(
        toks: List<Tok>,
        i: Int,
    ): Component? {
        val (hour, minute, suffix) = CLOCK.matchEntire(toks[i].key)?.destructured ?: return null
        return clock(toks, i + 1, hour.toInt(), minute.ifEmpty { "0" }.toInt(), suffix)
    }

    /** Several tokens: « 18 heures 30 », "6 pm", or a bare number right after « à » / "at". */
    private fun spokenHourAt(
        toks: List<Tok>,
        i: Int,
        afterGlue: Boolean,
    ): Component? {
        if (!toks[i].isNumber) return null
        val hour = toks[i].key.toIntOrNull() ?: return null
        val next = toks.getOrNull(i + 1)?.key
        return when {
            next in words.hourUnits -> {
                val minutes = toks.getOrNull(i + 2)?.takeIf { it.isNumber }?.key?.toIntOrNull()
                val end = if (minutes != null && minutes < MINUTES) i + HOUR_AND_MINUTE_TOKENS else i + 2
                clock(toks, end, hour, minutes?.takeIf { it < MINUTES } ?: 0, "")
            }
            next in MERIDIEM -> clock(toks, i + 2, hour, 0, next.orEmpty())
            // A bare number is an hour only right after « à » / "at" (« 12 vis » is a quantity).
            afterGlue && toks.getOrNull(i - 1)?.key in words.atLinks -> clock(toks, i + 1, hour, 0, "")
            else -> null
        }
    }

    /** Validates the hour, applies "am"/"pm" or « du soir », and consumes a trailing « du matin » / « du soir ». */
    private fun clock(
        toks: List<Tok>,
        end: Int,
        hour: Int,
        minute: Int,
        suffix: String,
    ): Component? {
        if (minute !in 0 until MINUTES || hour !in 0..MAX_HOUR) return null
        val period = words.periods.entries.firstOrNull { (p, _) -> Tokens.matchAt(toks, end, p) > 0 }
        val pm = suffix.startsWith("p") || period?.value == true
        val am = suffix.startsWith("a") || period?.value == false
        if ((am || pm) && hour !in 1..HALF_DAY) return null
        val h =
            when {
                pm && hour < HALF_DAY -> hour + HALF_DAY
                am && hour == HALF_DAY -> 0
                else -> hour
            }
        return Component(end + (period?.key?.size ?: 0), time = h to minute)
    }

    /** Folded word lists of one language. */
    @Suppress("LongParameterList") // a word-list record, built twice (FR, EN) with named arguments
    private class Words(
        val glue: Phrases,
        val dayBefore: Phrases,
        /** A day and a time in one phrase (« ce soir », "tonight"). */
        val combined: Map<List<String>, Pair<Int, Int>>,
        val relativeDays: Map<List<String>, DayRef>,
        val durationLink: String,
        val one: Set<String>,
        val dayUnits: Set<String>,
        val weekUnits: Set<String>,
        val monthUnits: Set<String>,
        val yearUnits: Set<String>,
        val weekdays: Map<String, Int>,
        val nextBefore: Set<String>,
        val nextAfter: Set<String>,
        val months: Map<String, Int>,
        val ofLink: String,
        val firstOfMonth: Set<String>,
        val timeWords: Map<String, Int>,
        val timePhrases: Map<List<String>, Int>,
        val hourUnits: Set<String>,
        val atLinks: Set<String>,
        /** « du soir » = afternoon (true), « du matin » = morning (false). */
        val periods: Map<List<String>, Boolean>,
    )

    private companion object {
        const val MAX_PARTS = 3
        const val MAX_GLUE = 2

        /** « dans 3 jours »: link, count, unit. */
        const val DURATION_TOKENS = 3

        /** « 18 heures 30 »: hour, unit, minutes. */
        const val HOUR_AND_MINUTE_TOKENS = 3
        const val MAX_COUNT = 999
        const val MAX_DAY = 31
        const val MAX_HOUR = 23
        const val HALF_DAY = 12
        const val MINUTES = 60
        const val DAYS_PER_WEEK = 7
        const val MONTHS_PER_YEAR = 12
        const val CENTURY = 100
        const val YEAR_2000 = 2000
        const val MORNING = 9
        const val NOON = 12
        const val AFTERNOON = 14
        const val EVENING = 19
        val YEAR = Regex("""20\d\d""")
        val NUMERIC = Regex("""(\d{1,2})/(\d{1,2})(?:/(\d{2}|\d{4}))?""")
        val ORDINAL = Regex("""(\d{1,2})(?:er|ere|e|st|nd|rd|th)?""")

        /** « 9h », « 18h30 », « 21:00 », "6pm", "6:30pm". */
        val CLOCK = Regex("""(\d{1,2})(?:h|:|(?=am|pm))(\d{2})?(am|pm)?""")
        val MERIDIEM = setOf("am", "pm", "a.m", "p.m")

        private val DAYS_IN_MONTH = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

        fun exists(
            day: Int,
            month: Int,
            year: Int?,
        ): Boolean {
            if (month !in 1..MONTHS_PER_YEAR || day < 1 || day > DAYS_IN_MONTH[month - 1]) return false
            val leapDay = month == 2 && day == DAYS_IN_MONTH[1]
            return !leapDay || year == null || isLeap(year)
        }

        @Suppress("MagicNumber") // the Gregorian leap-year rule
        fun isLeap(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

        fun <V> phraseMap(vararg entries: Pair<String, V>): Map<List<String>, V> = entries
            .map { (text, value) -> Tokens.tokenize(text).map(Tok::key) to value }
            .sortedByDescending { it.first.size }
            .toMap(LinkedHashMap())

        val FR_MONTHS =
            mapOf(
                "janvier" to 1,
                "janv" to 1,
                "fevrier" to 2,
                "fevr" to 2,
                "fev" to 2,
                "mars" to 3,
                "avril" to 4,
                "avr" to 4,
                "mai" to 5,
                "juin" to 6,
                "juillet" to 7,
                "juil" to 7,
                "aout" to 8,
                "septembre" to 9,
                "sept" to 9,
                "octobre" to 10,
                "oct" to 10,
                "novembre" to 11,
                "nov" to 11,
                "decembre" to 12,
                "dec" to 12,
            )
        val EN_MONTHS =
            mapOf(
                "january" to 1,
                "jan" to 1,
                "february" to 2,
                "feb" to 2,
                "march" to 3,
                "mar" to 3,
                "april" to 4,
                "apr" to 4,
                "may" to 5,
                "june" to 6,
                "jun" to 6,
                "july" to 7,
                "jul" to 7,
                "august" to 8,
                "aug" to 8,
                "september" to 9,
                "sept" to 9,
                "sep" to 9,
                "october" to 10,
                "oct" to 10,
                "november" to 11,
                "nov" to 11,
                "december" to 12,
                "dec" to 12,
            )

        val FR =
            Words(
                glue = Phrases(
                    listOf("le", "pour le", "pour", "a", "vers", "au", "d'ici", "dans la", "en", "des", ","),
                ),
                dayBefore = Phrases(
                    listOf("la veille du", "la veille de la", "la veille de l'", "la veille de", "veille du"),
                ),
                combined = phraseMap(
                    "ce soir" to (0 to EVENING),
                    "ce matin" to (0 to MORNING),
                    "ce midi" to (0 to NOON),
                ),
                relativeDays =
                    phraseMap(
                        "aujourd'hui" to DayRef.InDays(0),
                        "demain" to DayRef.InDays(1),
                        "apres-demain" to DayRef.InDays(2),
                        "apres demain" to DayRef.InDays(2),
                        "la semaine prochaine" to DayRef.InDays(DAYS_PER_WEEK),
                        "semaine prochaine" to DayRef.InDays(DAYS_PER_WEEK),
                        "le mois prochain" to DayRef.InMonths(1),
                        "mois prochain" to DayRef.InMonths(1),
                        "l'annee prochaine" to DayRef.InMonths(MONTHS_PER_YEAR),
                        "l'an prochain" to DayRef.InMonths(MONTHS_PER_YEAR),
                    ),
                durationLink = "dans",
                one = setOf("un", "une"),
                dayUnits = setOf("jour", "jours"),
                weekUnits = setOf("semaine", "semaines"),
                monthUnits = setOf("mois"),
                yearUnits = setOf("an", "ans", "annee", "annees"),
                weekdays =
                    mapOf(
                        "lundi" to 1,
                        "mardi" to 2,
                        "mercredi" to 3,
                        "jeudi" to 4,
                        "vendredi" to 5,
                        "samedi" to 6,
                        "dimanche" to 7,
                    ),
                nextBefore = emptySet(),
                nextAfter = setOf("prochain", "prochaine"),
                months = FR_MONTHS,
                ofLink = "",
                firstOfMonth = setOf("1er", "1ere", "premier"),
                timeWords = mapOf("midi" to NOON, "matin" to MORNING, "soir" to EVENING, "apres-midi" to AFTERNOON),
                timePhrases = phraseMap(
                    "dans la matinee" to MORNING,
                    "dans la soiree" to EVENING,
                    "en soiree" to EVENING,
                ),
                hourUnits = setOf("h", "heure", "heures"),
                atLinks = setOf("a", "vers"),
                periods = phraseMap("du soir" to true, "de l'apres-midi" to true, "du matin" to false),
            )

        val EN =
            Words(
                glue = Phrases(listOf("on", "at", "the", "for", "by", "around", "in the", "this", ",")),
                dayBefore = Phrases(listOf("the day before", "day before", "the eve of", "the night before")),
                combined = phraseMap(
                    "tonight" to (0 to EVENING),
                    "this evening" to (0 to EVENING),
                    "this morning" to (0 to MORNING),
                ),
                relativeDays =
                    phraseMap(
                        "today" to DayRef.InDays(0),
                        "tomorrow" to DayRef.InDays(1),
                        "day after tomorrow" to DayRef.InDays(2),
                        "next week" to DayRef.InDays(DAYS_PER_WEEK),
                        "next month" to DayRef.InMonths(1),
                        "next year" to DayRef.InMonths(MONTHS_PER_YEAR),
                    ),
                durationLink = "in",
                one = setOf("a", "an", "one"),
                dayUnits = setOf("day", "days"),
                weekUnits = setOf("week", "weeks"),
                monthUnits = setOf("month", "months"),
                yearUnits = setOf("year", "years"),
                weekdays =
                    mapOf(
                        "monday" to 1,
                        "tuesday" to 2,
                        "wednesday" to 3,
                        "thursday" to 4,
                        "friday" to 5,
                        "saturday" to 6,
                        "sunday" to 7,
                    ),
                nextBefore = setOf("next"),
                nextAfter = emptySet(),
                months = EN_MONTHS,
                ofLink = "of",
                firstOfMonth = emptySet(),
                timeWords = mapOf(
                    "noon" to NOON,
                    "midday" to NOON,
                    "morning" to MORNING,
                    "evening" to EVENING,
                    "afternoon" to AFTERNOON,
                ),
                timePhrases = emptyMap(),
                hourUnits = setOf("o'clock"),
                atLinks = setOf("at", "around", "by"),
                periods = phraseMap("in the evening" to true, "in the afternoon" to true, "in the morning" to false),
            )
    }
}
