package app.trouvetout.core.capture

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** E2: date expressions of reminder sentences, read then placed in the calendar (Monday 2 Nov 2026, 10:00 Paris). */
class DateExpressionTest {
    private val paris = TimeZone.of("Europe/Paris")
    private val now = LocalDateTime(2026, 11, 2, 10, 0).toInstant(paris)

    private fun span(
        text: String,
        lang: Lang = Lang.FR,
        dayFirst: Boolean = true,
    ): DateSpan? = DateExpression(lang, dayFirst).find(Rules.of(lang).clean(Tokens.tokenize(text)))

    private fun at(
        text: String,
        lang: Lang = Lang.FR,
        dayFirst: Boolean = true,
        reference: Instant = now,
    ): LocalDateTime? = span(text, lang, dayFirst)?.let {
        DateResolver.resolve(it.spec, reference, paris).toLocalDateTime(paris)
    }

    private fun local(
        month: Int,
        day: Int,
        hour: Int = 9,
        minute: Int = 0,
        year: Int = 2026,
    ) = LocalDateTime(year, month, day, hour, minute)

    @Test
    fun should_read_relative_days_in_french() {
        assertEquals(local(11, 3), at("demain"))
        assertEquals(local(11, 4), at("après-demain"))
        assertEquals(local(11, 4), at("apres demain"))
        assertEquals(local(11, 3, 10, 0), at("demain à 10h"))
        assertEquals(local(11, 3, 19), at("demain soir"))
        assertEquals(local(11, 3, 9), at("demain matin"))
        assertEquals(local(11, 2, 19), at("ce soir"))
        assertEquals(local(11, 5), at("dans 3 jours"))
        assertEquals(local(11, 5), at("dans trois jours"))
        assertEquals(local(11, 9), at("dans une semaine"))
        assertEquals(local(11, 17), at("dans quinze jours"))
        assertEquals(local(12, 2), at("dans 1 mois"))
        assertEquals(local(5, 2, year = 2027), at("dans 6 mois"))
        assertEquals(local(11, 9), at("la semaine prochaine"))
    }

    @Test
    fun should_read_a_span_of_hours_or_minutes_from_now_not_an_hour_of_the_day() {
        assertEquals(local(11, 2, 12), at("dans 2 heures"))
        assertEquals(local(11, 2, 11), at("dans une heure"))
        assertEquals(local(11, 2, 13), at("dans 3 h"))
        assertEquals(local(11, 2, 12), at("dans 2h"))
        assertEquals(local(11, 2, 12, 30), at("dans 2h30"))
        assertEquals(local(11, 2, 10, 45), at("dans 45 minutes"))
        assertEquals(local(11, 2, 12), at("in 2 hours", Lang.EN))
        assertEquals(local(11, 2, 11), at("in an hour", Lang.EN))
        assertEquals(local(11, 2, 10, 45), at("in 45 min", Lang.EN))
        // « à 2 heures » stays an hour of the day: tomorrow, 2:00 being past at 10:00.
        assertEquals(local(11, 3, 2), at("à 2 heures"))
    }

    @Test
    fun should_keep_any_stated_year_with_its_date() {
        assertEquals(local(3, 12, year = 2100), at("le 12 mars 2100"))
        assertEquals(local(3, 12, year = 2019), at("le 12 mars 2019"))
    }

    @Test
    fun should_read_weekdays_strictly_after_today() {
        assertEquals(local(11, 9), at("lundi"), "today is a Monday: « lundi » is next week")
        assertEquals(local(11, 3), at("mardi"))
        assertEquals(local(11, 6, 18, 30), at("vendredi prochain à 18h30"))
        assertEquals(local(11, 9), at("next Monday", Lang.EN))
        assertEquals(local(11, 8, 20), at("on Sunday at 8pm", Lang.EN))
    }

    @Test
    fun should_read_absolute_dates_and_roll_past_ones_to_next_year() {
        assertEquals(local(3, 12, year = 2027), at("le 12 mars"))
        assertEquals(local(3, 12, year = 2027), at("le douze mars"))
        assertEquals(local(11, 20), at("le 20 novembre"))
        assertEquals(local(4, 1, year = 2027), at("le 1er avril"))
        assertEquals(local(12, 24, 18), at("le 24 décembre à 18 h"))
        assertEquals(local(1, 5, year = 2028), at("le 5 janvier 2028"))
        assertEquals(local(3, 12, year = 2027), at("12/03"))
        assertEquals(local(3, 12, year = 2027), at("12/03", Lang.EN))
        assertEquals(local(12, 3), at("12/03", Lang.EN, dayFirst = false))
        assertEquals(local(3, 11, year = 2027), at("March 11", Lang.EN))
        assertEquals(local(3, 11, 18, year = 2027), at("on March 11th at 6 pm", Lang.EN))
        assertEquals(local(3, 11, year = 2027), at("the 11th of March", Lang.EN))
    }

    @Test
    fun should_place_the_day_before() {
        assertEquals(local(3, 11, year = 2027), at("la veille du 12 mars"))
        assertEquals(local(3, 11, year = 2027), at("the day before March 12", Lang.EN))
        assertEquals(local(11, 2, 20), at("la veille de demain à 20h"))
        assertEquals(local(2, 28, year = 2027), at("la veille du 1er mars"))
    }

    @Test
    fun should_take_a_time_alone_as_today_or_tomorrow() {
        assertEquals(local(11, 2, 18), at("à 18h"))
        assertEquals(local(11, 3, 8), at("à 8h"), "8:00 is past at 10:00")
        assertEquals(local(11, 2, 12), at("à midi"))
        assertEquals(local(11, 2, 21, 15), at("à 21:15"))
        assertEquals(local(11, 2, 20), at("à 8h du soir"))
        assertEquals(local(11, 2, 18), at("at 6pm", Lang.EN))
        assertEquals(local(11, 3, 6), at("at 6", Lang.EN), "a bare hour after « at », past today")
        assertEquals(local(11, 2, 12), at("at noon", Lang.EN))
        assertEquals(local(11, 3), at("tomorrow", Lang.EN))
        assertEquals(local(11, 3, 21), at("tomorrow at 9 in the evening", Lang.EN))
        assertEquals(local(11, 2, 19), at("tonight", Lang.EN))
        assertEquals(local(11, 16), at("in two weeks", Lang.EN))
        assertEquals(local(11, 3), at("in a day", Lang.EN))
    }

    @Test
    fun should_leave_the_past_to_the_caller() {
        assertEquals(local(11, 2, 9), at("ce matin"), "past: the capture refuses it")
        assertEquals(local(3, 3, year = 2020), at("le 3 mars 2020"))
    }

    @Test
    fun should_not_mistake_quantities_names_or_impossible_days_for_dates() {
        assertNull(span("12 vis M4 dans la boîte"))
        assertNull(span("le passeport"))
        assertNull(span("la clé à molette"))
        assertNull(span("le 31 avril"))
        assertNull(span("le 30 février"))
        assertNull(span("à 25h"))
        assertNull(span("3 m de câble"))
        assertNull(span("the tent", Lang.EN))
        assertNull(span("2 boxes of screws", Lang.EN))
    }

    @Test
    fun should_report_where_the_expression_sits_with_its_glue() {
        val toks = Rules.of(Lang.FR).clean(Tokens.tokenize("rappelle-moi où est le passeport la veille du 12 mars"))
        val found = DateExpression(Lang.FR).find(toks)!!
        assertEquals("la veille du 12 mars", Tokens.render(toks.subList(found.start, found.end)))
        val en = Rules.of(Lang.EN).clean(Tokens.tokenize("remind me where the passport is on March 11 at 9am"))
        val enFound = DateExpression(Lang.EN).find(en)!!
        assertEquals("on March 11 at 9am", Tokens.render(en.subList(enFound.start, enFound.end)))
    }

    @Test
    fun should_handle_daylight_saving_changes() {
        // Paris springs forward on 28 March 2027 (2:00 → 3:00) and falls back on 31 October 2027 (3:00 → 2:00).
        val gap = span("le 28 mars 2027 à 2h30")!!
        assertEquals(local(3, 28, 3, 30, 2027), DateResolver.resolve(gap.spec, now, paris).toLocalDateTime(paris))
        val overlap = span("le 31 octobre 2027 à 2h30")!!
        assertEquals(
            Instant.parse("2027-10-31T00:30:00Z"),
            DateResolver.resolve(overlap.spec, now, paris),
            "an ambiguous time takes the earlier (summer) offset",
        )
        // « dans 6 mois » across the spring change keeps 9:00 local time.
        assertEquals(local(5, 2, 9, 0, 2027), at("dans 6 mois"))
    }

    @Test
    fun should_wait_for_a_leap_year_and_clamp_month_ends() {
        assertEquals(local(2, 29, year = 2028), at("le 29 février"))
        val endOfJanuary = LocalDateTime(2027, 1, 31, 10, 0).toInstant(paris)
        assertEquals(local(2, 28, year = 2027), at("dans 1 mois", reference = endOfJanuary))
        assertEquals(local(1, 1, year = 2027), at("le 1er janvier", reference = now))
    }
}
