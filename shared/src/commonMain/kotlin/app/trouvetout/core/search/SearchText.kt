package app.trouvetout.core.search

import app.trouvetout.core.capture.Text

/**
 * Turns a query or a field of an item into search terms: folded (accents and case), split on anything that is
 * not a letter or a digit (« tire-bouchon », « l'armoire »), singular, without the grammatical words that
 * carry no meaning for a search (« où est le », « where are my », « le truc pour »). Same rules for both sides.
 */
internal object SearchText {
    fun terms(text: String): List<String> = words(text).map(::stem).filter { it !in STOP_WORDS }

    /**
     * Folded words of more than one letter (or numbers), before stemming. A hyphenated word also gives its
     * joined form (« tire-bouchon » → « tire », « bouchon », « tirebouchon ») so that a compound can be a synonym.
     */
    fun words(text: String): List<String> {
        val out = ArrayList<String>()
        for (chunk in chunks(Text.fold(text))) {
            val parts = chunk.split('-').filter { it.isNotEmpty() }
            for (part in parts) if (part.length > 1 || part.any(Char::isDigit)) out += part
            if (parts.size > 1) out += parts.joinToString("")
        }
        return out
    }

    /** Runs of letters, digits and inner hyphens; everything else separates (spaces, apostrophes, punctuation). */
    private fun chunks(folded: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (ch in folded) {
            if (ch.isLetterOrDigit() || ch == '-') {
                current.append(ch)
            } else if (current.isNotEmpty()) {
                out += current.toString()
                current.clear()
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** Plural mark removed (« clés » → « cle », « tuyaux » → « tuyau »); numbers are left alone. */
    fun stem(word: String): String = when {
        word.any(Char::isDigit) -> word
        word.length > MIN_PLURAL_LENGTH && (word.endsWith('s') || word.endsWith('x')) -> word.dropLast(1)
        else -> word
    }

    private const val MIN_PLURAL_LENGTH = 3

    /** Grammatical words, written naturally and folded/stemmed once like any other word. */
    val STOP_WORDS: Set<String> =
        listOf(
            // French
            "le la les l un une des de du d au aux à et ou où est sont se sa son ses s mon ma mes ton ta tes",
            "notre nos votre vos leur leurs ce cet cette ces c qui que qu quoi quel quelle quels quelles",
            "dans sur sous pour par avec sans en il elle elles ils on je j nous vous y ai a mis mise rangé rangée",
            "rangés trouve trouver cherche chercher truc trucs machin chose choses bidule ça cela ici chez moi",
            "toi lui tout tous toute toutes était étaient",
            // English
            "the a an of in on at to for and or is are was were where my your our his her its it their which",
            "what with without into from by this that these those thing things stuff did do does put find kept",
            "keep some any",
        ).flatMap { it.split(' ') }.map { stem(Text.fold(it)) }.toSet()
}
