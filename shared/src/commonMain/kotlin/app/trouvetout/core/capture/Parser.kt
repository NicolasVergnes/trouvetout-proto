package app.trouvetout.core.capture

/** What the sentence asks for (architecture §4.2). */
public enum class Intent { STORE, TAKE, RETURN, MOVE_TO, LEND, RETURN_FROM_LEND, QUERY, UNKNOWN }

/** One object named in the sentence, with an optional quantity (« 12 vis M4 », « 3 m de câble »). */
public data class ItemMention(
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
)

/**
 * Pure result of [Parser.parse]: no place resolution, no database. [place] is the place text as dictated, with a
 * position preposition kept when it carries meaning (« sous le canapé ») and a plain one dropped (« tiroir »).
 */
public data class ParseResult(
    val intent: Intent,
    val items: List<ItemMention>,
    val place: String?,
    val person: String?,
    val query: String?,
    val lang: Lang,
    val confidence: Float,
    val input: String,
)

/**
 * Rule-based sentence parser (ADR-004: no language model in v1). Pure and deterministic; FR and EN.
 * [knownItemNames] keeps « pain et chocolat » together when such an item already exists (B14).
 */
public class Parser(
    private val knownItemNames: Collection<String> = emptyList(),
    private val defaultLang: Lang = Lang.FR,
) {
    public fun parse(
        text: String,
        lang: Lang? = null,
    ): ParseResult {
        val raw = Tokens.tokenize(text)
        val language = lang ?: detectLang(raw)
        val rules = Rules.of(language)
        val question = raw.any { it.key == "?" }
        val toks = rules.clean(raw)
        if (toks.isEmpty()) return ParseResult(Intent.UNKNOWN, emptyList(), null, null, null, language, 0f, text)
        val sentence = Sentence(toks, rules, knownItemNames, question, text)
        return sentence.query()
            ?: sentence.lend()
            ?: sentence.borrow()
            ?: sentence.returnFromLend()
            ?: sentence.take()
            ?: sentence.putBack()
            ?: sentence.moveTo()
            ?: sentence.store()
    }

    /** Counts the tokens each language claims as markers; ties go to [defaultLang]. */
    public fun detectLang(text: String): Lang = detectLang(Tokens.tokenize(text))

    private fun detectLang(toks: List<Tok>): Lang {
        val fr = toks.count { it.key in Lexicon.FR.markers }
        val en = toks.count { it.key in Lexicon.EN.markers }
        return when {
            en > fr -> Lang.EN
            fr > en -> Lang.FR
            else -> defaultLang
        }
    }
}

/** Tokenized lexicon of one language. */
internal class Rules private constructor(
    val lex: Lexicon,
) {
    val plainPrepositions = Phrases(lex.plainPrepositions)
    val positionPrepositions = Phrases(lex.positionPrepositions)
    val queryPrefixes = Phrases(lex.queryPrefixes)
    val querySuffixes = Phrases(lex.querySuffixes)
    val takePrefixes = Phrases(lex.takePrefixes)
    val returnPrefixes = Phrases(lex.returnPrefixes)
    val returnInfixes = Phrases(lex.returnInfixes)
    val storePrefixes = Phrases(lex.storePrefixes)
    val copulas = Phrases(lex.copulas)
    val moveNowMarkers = Phrases(lex.moveNowMarkers)
    val movePrefixes = Phrases(lex.movePrefixes)
    val listSeparators = Phrases(lex.listSeparators)
    val returnFromLendTrailers = Phrases(lex.returnFromLendTrailers)
    val returnFromLendFromLink = Phrases(lex.returnFromLendFromLink)
    val moveLinks = Phrases(
        if (lex.lang ==
            Lang.FR
        ) {
            listOf("vers", "jusqu'a", "jusque dans")
        } else {
            listOf("to", "over to")
        },
    )
    val putPrefixes = Phrases(
        if (lex.lang ==
            Lang.EN
        ) {
            listOf("i put", "i've put", "putting", "i'm putting", "put")
        } else {
            emptyList()
        },
    )
    val openers = Phrases(lex.openers)

    /** Drops fillers, leading openers and trailing punctuation; spells number words as digits. */
    fun clean(raw: List<Tok>): List<Tok> {
        var toks = raw.filter { it.key != "?" && it.key !in lex.fillers }
        while (true) {
            val n = openers.matchAt(toks, 0)
            if (n == 0) break
            toks = toks.drop(n)
        }
        while (toks.isNotEmpty() && toks.last().key == ",") toks = toks.dropLast(1)
        return toks.map { t -> lex.numberWords[t.key]?.let { Tok(it.toString()) } ?: t }
    }

    /**
     * Length of the location preposition starting at [i] and whether it is kept in the place text. A bare « à »
     * counts only before an article (« à la cave »), never inside a name (« clé à molette »).
     */
    fun prepositionAt(
        toks: List<Tok>,
        i: Int,
    ): Pair<Int, Boolean>? {
        val position = positionPrepositions.matchAt(toks, i)
        val plain = plainPrepositions.matchAt(toks, i)
        val bareA = lex.lang == Lang.FR && toks[i].key == "a"
        return when {
            position > 0 -> position to true
            plain == 0 -> null
            bareA && toks.getOrNull(i + plain)?.key !in FR_ARTICLES_AFTER_A -> null
            else -> plain to false
        }
    }

    companion object {
        private val FR = Rules(Lexicon.FR)
        private val EN = Rules(Lexicon.EN)
        private val FR_ARTICLES_AFTER_A = setOf("la", "le", "les", "l'")

        fun of(lang: Lang): Rules = if (lang == Lang.FR) FR else EN
    }
}

/** One cleaned sentence and the recognisers applied to it, in order of priority. */
internal class Sentence(
    private val toks: List<Tok>,
    private val rules: Rules,
    private val knownItemNames: Collection<String>,
    private val question: Boolean,
    private val input: String,
) {
    private val lex = rules.lex

    fun query(): ParseResult? {
        val prefix = rules.queryPrefixes.matchAt(toks, 0)
        val suffix = if (prefix == 0) rules.querySuffixes.endsWith(toks) else 0
        val (rest, confidence) =
            when {
                prefix > 0 -> toks.drop(prefix) to HIGH
                suffix > 0 -> toks.dropLast(suffix) to HIGH
                question &&
                    toks.any {
                        it.key in lex.interrogatives
                    } -> toks.filter { it.key !in lex.interrogatives } to
                    LOW
                else -> return null
            }
        val query = stripArticles(stripCopula(rest))
        if (query.isEmpty()) return null
        val text = Tokens.render(query)
        return result(Intent.QUERY, listOf(ItemMention(text)), null, null, text, confidence)
    }

    /** « j'ai prêté la tente à Marc », "lent the tent to Mark". */
    fun lend(): ParseResult? {
        val verb = toks.indexOfFirst { it.key in lex.lendVerbs }
        if (verb < 0) return null
        val link = toks.indexOfLast { it.key == lex.lendPersonLink }
        val objectEnd = if (link > verb) link else toks.size
        val person = if (link > verb) Tokens.render(toks.drop(link + 1)).ifBlank { null } else null
        val after = mentions(toks.subList(verb + 1, objectEnd))
        // « tente prêtée à Léa »: the participle form names the object before the verb.
        val items = if (after.isNotEmpty()) after else mentions(toks.subList(0, verb))
        return if (items.isEmpty()) {
            null
        } else {
            result(
                Intent.LEND,
                items,
                null,
                person,
                null,
                if (person !=
                    null
                ) {
                    HIGH
                } else {
                    LOW
                },
            )
        }
    }

    /** « Marc a emprunté la tente », "Mark borrowed the tent": the borrower comes first. */
    fun borrow(): ParseResult? {
        val verb = toks.indexOfFirst { it.key in lex.borrowVerbs }
        if (verb < 1 || !startsWithName()) return null
        val subjectEnd =
            when {
                lex.lang == Lang.EN -> verb
                toks[verb - 1].key != "a" -> return null
                toks.getOrNull(verb - 2)?.key == "m'" -> verb - 2
                else -> verb - 1
            }
        val items = mentions(toks.drop(verb + 1))
        if (subjectEnd < 1 || items.isEmpty()) return null
        return result(Intent.LEND, items, null, Tokens.render(toks.subList(0, subjectEnd)), null, MEDIUM)
    }

    fun returnFromLend(): ParseResult? {
        val verb = toks.indexOfFirst { it.key in lex.returnFromLendVerbs }
        if (verb < 1) return null
        return if (lex.lang == Lang.FR) returnFromLendFr(verb) else returnFromLendEn(verb)
    }

    /** « Marc a rendu … », « Marc m'a rendu … », « … rendu par Marc ». */
    private fun returnFromLendFr(verb: Int): ParseResult? {
        val par = rules.returnFromLendFromLink.find(toks, verb + 1)
        if (par != null) {
            val items = mentions(toks.subList(0, verb))
            val person = Tokens.render(toks.drop(par.first + par.second))
            return if (items.isEmpty()) null else result(Intent.RETURN_FROM_LEND, items, null, person, null, MEDIUM)
        }
        if (toks[verb - 1].key != "a" || !startsWithName()) return null
        val subjectEnd = if (verb >= 2 && toks[verb - 2].key == "m'") verb - 2 else verb - 1
        val items = mentions(toks.drop(verb + 1))
        if (subjectEnd < 1 || items.isEmpty()) return null
        return result(Intent.RETURN_FROM_LEND, items, null, Tokens.render(toks.subList(0, subjectEnd)), null, HIGH)
    }

    /** "Mark returned the tent", "Mark brought the tent back", "Mark gave back the tent". */
    private fun returnFromLendEn(verb: Int): ParseResult? {
        if (!startsWithName()) return null
        var objectToks = toks.drop(verb + 1)
        if (objectToks.firstOrNull()?.key == "back") objectToks = objectToks.drop(1)
        val trailer = rules.returnFromLendTrailers.endsWith(objectToks)
        if (trailer > 0) objectToks = objectToks.dropLast(trailer)
        val items = mentions(objectToks)
        if (items.isEmpty()) return null
        return result(Intent.RETURN_FROM_LEND, items, null, Tokens.render(toks.subList(0, verb)), null, MEDIUM)
    }

    fun take(): ParseResult? {
        val n = rules.takePrefixes.matchAt(toks, 0)
        if (n == 0) return null
        val items = mentions(toks.drop(n))
        return if (items.isEmpty()) null else result(Intent.TAKE, items, null, null, null, HIGH)
    }

    fun putBack(): ParseResult? = putBackPrefix() ?: putBackEnglish() ?: putBackParticiple() ?: putBackInfix()

    private fun putBackPrefix(): ParseResult? {
        val prefix = rules.returnPrefixes.matchAt(toks, 0)
        return if (prefix > 0) located(Intent.RETURN, toks.drop(prefix), HIGH) else null
    }

    /** "put the drill back (in the garage)". */
    private fun putBackEnglish(): ParseResult? {
        val put = rules.putPrefixes.matchAt(toks, 0)
        val back = toks.indexOfFirst { it.key == "back" }
        if (put == 0 || back <= put) return null
        val items = mentions(toks.subList(put, back))
        val place = placeFrom(toks.drop(back + 1), requirePreposition = true)
        return if (items.isEmpty()) null else result(Intent.RETURN, items, place, null, null, HIGH)
    }

    /** « perceuse remise au garage ». */
    private fun putBackParticiple(): ParseResult? {
        val participle = toks.indexOfFirst { it.key in lex.returnParticiples }
        if (participle < 1) return null
        val items = mentions(toks.subList(0, participle))
        val place = placeFrom(toks.drop(participle + 1), requirePreposition = true)
        return if (items.isEmpty()) null else result(Intent.RETURN, items, place, null, null, MEDIUM)
    }

    /** « la perceuse est de retour au garage », "the drill is back in the garage". */
    private fun putBackInfix(): ParseResult? {
        val infix = rules.returnInfixes.find(toks, 1) ?: return null
        val after = toks.drop(infix.first + infix.second)
        if (after.isNotEmpty() && rules.prepositionAt(after, 0) == null) return null
        val items = mentions(stripCopula(toks.subList(0, infix.first)))
        val place = placeFrom(after, requirePreposition = true)
        return if (items.isEmpty()) null else result(Intent.RETURN, items, place, null, null, MEDIUM)
    }

    fun moveTo(): ParseResult? = moveWithVerb() ?: moveNowMarker()

    /** « j'ai déplacé la perceuse vers le garage », "moved the drill to the garage". */
    private fun moveWithVerb(): ParseResult? {
        val prefix = rules.movePrefixes.matchAt(toks, 0)
        if (prefix == 0) return null
        val rest = toks.drop(prefix)
        val link = rules.moveLinks.find(rest, 1) ?: return located(Intent.MOVE_TO, rest, MEDIUM)
        val items = mentions(rest.subList(0, link.first))
        val place = Tokens.render(stripArticles(rest.drop(link.first + link.second)))
        if (items.isEmpty() || place.isEmpty()) return located(Intent.MOVE_TO, rest, MEDIUM)
        return result(Intent.MOVE_TO, items, place, null, null, HIGH)
    }

    /** « la perceuse est maintenant dans le garage », "the drill is now in the garage". */
    private fun moveNowMarker(): ParseResult? {
        val marker = rules.moveNowMarkers.find(toks, 1) ?: return null
        val items = mentions(stripCopula(toks.subList(0, marker.first)))
        val place = placeFrom(toks.drop(marker.first + marker.second), requirePreposition = false)
        return if (items.isEmpty() || place == null) null else result(Intent.MOVE_TO, items, place, null, null, HIGH)
    }

    fun store(): ParseResult {
        val prefix = rules.storePrefixes.matchAt(toks, 0)
        if (prefix > 0) return located(Intent.STORE, toks.drop(prefix), MEDIUM)
        val split = splitAtPreposition(toks)
        val locatedItems = split?.let { mentions(stripCopula(it.first)) }.orEmpty()
        val bareItems = if (locatedItems.isEmpty()) mentions(toks) else emptyList()
        return when {
            locatedItems.isNotEmpty() -> result(Intent.STORE, locatedItems, split?.second, null, null, MEDIUM)
            bareItems.isEmpty() -> result(Intent.UNKNOWN, emptyList(), null, null, null, 0f)
            // A bare noun phrase: an object without a place (the confirmation card asks where).
            else -> result(Intent.STORE, bareItems, null, null, null, BARE)
        }
    }

    // ---- helpers

    private fun located(
        intent: Intent,
        rest: List<Tok>,
        confidence: Float,
    ): ParseResult {
        val split = splitAtPreposition(rest) ?: splitAtMoveLink(rest)
        val items = mentions(stripCopula(split?.first ?: rest))
        if (items.isEmpty()) return result(Intent.UNKNOWN, emptyList(), null, null, null, 0f)
        return result(
            intent,
            items,
            split?.second,
            null,
            null,
            if (split !=
                null
            ) {
                confidence
            } else {
                confidence * NO_PLACE
            },
        )
    }

    /** "returned the tent to the basement": the destination link of a movement verb, when no preposition did. */
    private fun splitAtMoveLink(list: List<Tok>): Pair<List<Tok>, String>? {
        val link = rules.moveLinks.find(list, 1) ?: return null
        val place = Tokens.render(stripArticles(list.drop(link.first + link.second)))
        return if (place.isEmpty()) null else list.subList(0, link.first) to place
    }

    /**
     * Object tokens and the place text, cut at the first location preposition that leaves a non-empty object.
     * When the sentence has a copula (« la boîte aux lettres **est** au crochet »), the cut comes after it.
     */
    private fun splitAtPreposition(list: List<Tok>): Pair<List<Tok>, String>? {
        val copula = rules.copulas.find(list, 1)
        val from = if (copula != null) copula.first + copula.second else 1
        for (i in from until list.size) {
            val prep = rules.prepositionAt(list, i) ?: continue
            val place = placeText(list, i, prep) ?: continue
            return list.subList(0, i) to place
        }
        return null
    }

    /** Place text from tokens that start with a preposition (or, when not required, directly with the place). */
    private fun placeFrom(
        list: List<Tok>,
        requirePreposition: Boolean,
    ): String? {
        val prep = if (list.isEmpty()) null else rules.prepositionAt(list, 0)
        return when {
            list.isEmpty() -> null
            prep != null -> placeText(list, 0, prep)
            requirePreposition -> null
            else -> Tokens.render(stripArticles(list)).ifEmpty { null }
        }
    }

    private fun placeText(
        list: List<Tok>,
        at: Int,
        prep: Pair<Int, Boolean>,
    ): String? {
        val (length, kept) = prep
        val after = list.drop(at + length)
        if (after.isEmpty()) return null
        val rendered = if (kept) Tokens.render(list.subList(at, list.size)) else Tokens.render(stripArticles(after))
        return rendered.ifEmpty { null }
    }

    private fun stripArticles(list: List<Tok>): List<Tok> {
        var i = 0
        while (i < list.size && list[i].key in lex.articles) i++
        return list.drop(i)
    }

    private fun stripCopula(list: List<Tok>): List<Tok> {
        val n = rules.copulas.endsWith(list)
        return if (n > 0 && n < list.size) list.dropLast(n) else list
    }

    /** A sentence opening with a capitalised word that is neither « je » nor an article names a person. */
    private fun startsWithName(): Boolean {
        val first = toks.first()
        return first.text.first().isUpperCase() && first.key !in PRONOUNS && first.key !in lex.articles
    }

    /** Splits a list of objects (« passeport et carnet »), unless the whole phrase names a known item. */
    private fun mentions(list: List<Tok>): List<ItemMention> {
        val cleaned = stripArticles(list)
        if (cleaned.isEmpty()) return emptyList()
        val whole = Tokens.render(cleaned)
        if (knownItemNames.any {
                Text.similarity(it, whole) >= KNOWN_ITEM_MATCH
            }
        ) {
            return listOfNotNull(mention(cleaned))
        }
        val segments = ArrayList<List<Tok>>()
        var start = 0
        var i = 0
        while (i < cleaned.size) {
            val n = if (i > start) rules.listSeparators.matchAt(cleaned, i) else 0
            if (n > 0) {
                segments += cleaned.subList(start, i)
                i += n
                start = i
            } else {
                i++
            }
        }
        segments += cleaned.subList(start, cleaned.size)
        return segments.mapNotNull(::mention)
    }

    private fun mention(segment: List<Tok>): ItemMention? {
        var list = stripArticles(segment)
        var quantity: Double? = null
        var unit: String? = null
        if (list.isNotEmpty() && list[0].isNumber) {
            quantity = list[0].key.replace(',', '.').toDouble()
            list = list.drop(1)
            if (list.isNotEmpty() && list[0].key in lex.units) {
                unit = list[0].text.lowercase()
                list = list.drop(1)
            }
            if (list.isNotEmpty() && list[0].key in lex.quantityLinks) list = list.drop(1)
            list = stripArticles(list)
        }
        val name = Tokens.render(list)
        return if (name.isEmpty()) null else ItemMention(name, quantity, unit)
    }

    private fun result(
        intent: Intent,
        items: List<ItemMention>,
        place: String?,
        person: String?,
        query: String?,
        confidence: Float,
    ): ParseResult = ParseResult(intent, items, place, person, query, lex.lang, confidence, input)

    private companion object {
        const val KNOWN_ITEM_MATCH = 0.9
        const val HIGH = 0.95f
        const val MEDIUM = 0.85f
        const val LOW = 0.7f
        const val BARE = 0.5f
        const val NO_PLACE = 0.8f
        val PRONOUNS = setOf("i", "i'm", "i've", "je", "j'", "on", "it", "we", "nous", "il", "elle", "ils", "elles")
    }
}
