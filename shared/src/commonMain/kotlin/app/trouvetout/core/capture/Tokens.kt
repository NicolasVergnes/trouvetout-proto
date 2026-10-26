package app.trouvetout.core.capture

/** One word of the sentence: [text] as the user said it (for display), [key] folded for matching. */
internal data class Tok(
    val text: String,
    val key: String = Text.fold(text),
) {
    val isElided: Boolean get() = text.endsWith("'")
    val isNumber: Boolean get() = NUMBER.matches(key)

    companion object {
        val NUMBER = Regex("""\d+(?:[.,]\d+)?""")
    }
}

/** Whitespace + punctuation + elision tokenizer, identical for the sentence and for the lexicon phrases. */
internal object Tokens {
    private val ELISION = Regex("""^((?:[ljdmnstcLJDMNSTC]|[qQ]u)')(.+)$""")
    private const val LEADING = "«\"([{“"
    private const val TRAILING = "?!.,;:»\")]}”…"

    fun tokenize(text: String): List<Tok> {
        val unified = text.replace('’', '\'').replace('‘', '\'').replace('`', '\'')
        val out = ArrayList<Tok>()
        for (word in unified.split(' ', '\t', '\n', '\r', '\u00A0', '\u202F', '\u2009').filter { it.isNotEmpty() }) {
            emitWord(word, out)
        }
        return out
    }

    private fun emitWord(
        word: String,
        out: MutableList<Tok>,
    ) {
        val (core, trailing) = peel(word)
        if (core.isNotEmpty()) emitCore(core, out)
        for (c in trailing) if (c == '?' || c == ',') out += Tok(c.toString())
    }

    /** Strips surrounding punctuation; the trailing characters are returned in order. */
    private fun peel(word: String): Pair<String, List<Char>> {
        var w = word
        while (w.isNotEmpty() && w[0] in LEADING) w = w.substring(1)
        val trailing = ArrayList<Char>()
        while (w.isNotEmpty() && w.last() in TRAILING) {
            trailing.add(0, w.last())
            w = w.dropLast(1)
        }
        return w to trailing
    }

    private fun emitCore(
        w: String,
        out: MutableList<Tok>,
    ) {
        val at = w.indexOf('@')
        val elision = ELISION.matchEntire(w)
        when {
            w == "@" || w == "+" -> out += Tok(w)
            at >= 0 -> {
                if (at > 0) emitWord(w.substring(0, at), out)
                out += Tok("@")
                if (at < w.length - 1) emitWord(w.substring(at + 1), out)
            }
            elision != null -> {
                out += Tok(elision.groupValues[1])
                emitWord(elision.groupValues[2], out)
            }
            else -> out += Tok(w)
        }
    }

    /** Rebuilds a display string: no space after an elided article, none before a comma. */
    fun render(toks: List<Tok>): String {
        val sb = StringBuilder()
        for ((i, t) in toks.withIndex()) {
            if (i > 0 && !toks[i - 1].isElided && t.text != ",") sb.append(' ')
            sb.append(t.text)
        }
        return sb.toString()
    }

    /** Number of tokens of [toks] starting at [from] that spell [phrase] (already tokenized keys); 0 if none. */
    fun matchAt(
        toks: List<Tok>,
        from: Int,
        phrase: List<String>,
    ): Int {
        if (phrase.isEmpty() || from + phrase.size > toks.size) return 0
        for (i in phrase.indices) if (toks[from + i].key != phrase[i]) return 0
        return phrase.size
    }
}

/** Lexicon phrases tokenized once, longest first, so that « où sont » wins over « où ». */
internal class Phrases(
    phrases: Collection<String>,
) {
    val entries: List<List<String>> =
        phrases.map { Tokens.tokenize(it).map(Tok::key) }.filter { it.isNotEmpty() }.sortedByDescending { it.size }

    /** Length of the longest entry matching at [from], or 0. */
    fun matchAt(
        toks: List<Tok>,
        from: Int,
    ): Int {
        for (entry in entries) {
            val n = Tokens.matchAt(toks, from, entry)
            if (n > 0) return n
        }
        return 0
    }

    /** First index ≥ [from] where an entry matches, with its length. */
    fun find(
        toks: List<Tok>,
        from: Int = 0,
    ): Pair<Int, Int>? {
        for (i in from until toks.size) {
            val n = matchAt(toks, i)
            if (n > 0) return i to n
        }
        return null
    }

    fun endsWith(toks: List<Tok>): Int {
        for (entry in entries) {
            val start = toks.size - entry.size
            if (start >= 0 && Tokens.matchAt(toks, start, entry) > 0) return entry.size
        }
        return 0
    }
}
