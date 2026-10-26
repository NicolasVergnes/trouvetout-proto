package app.trouvetout.core.capture

/** Small, dependency-free text helpers shared by the parser, the place resolver and the duplicate detector. */
internal object Text {
    private val FOLDS: Map<Char, String> =
        mapOf(
            'à' to "a",
            'á' to "a",
            'â' to "a",
            'ä' to "a",
            'ã' to "a",
            'å' to "a",
            'ā' to "a",
            'æ' to "ae",
            'ç' to "c",
            'ć' to "c",
            'č' to "c",
            'è' to "e",
            'é' to "e",
            'ê' to "e",
            'ë' to "e",
            'ē' to "e",
            'ė' to "e",
            'ę' to "e",
            'î' to "i",
            'ï' to "i",
            'í' to "i",
            'ī' to "i",
            'į' to "i",
            'ì' to "i",
            'ł' to "l",
            'ñ' to "n",
            'ń' to "n",
            'ô' to "o",
            'ö' to "o",
            'ò' to "o",
            'ó' to "o",
            'õ' to "o",
            'ø' to "o",
            'ō' to "o",
            'œ' to "oe",
            'ß' to "ss",
            'ś' to "s",
            'š' to "s",
            'û' to "u",
            'ü' to "u",
            'ù' to "u",
            'ú' to "u",
            'ū' to "u",
            'ÿ' to "y",
            'ý' to "y",
            'ž' to "z",
            'ź' to "z",
            'ż' to "z",
        )

    /** Lowercases and strips the diacritics of the Latin letters a transcription can produce. */
    fun fold(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text.lowercase()) out.append(FOLDS[ch] ?: ch.toString())
        return out.toString()
    }

    /** Classic edit distance (insert, delete, substitute), O(n·m) with two rows. */
    fun levenshtein(
        a: CharSequence,
        b: CharSequence,
    ): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /**
     * 1.0 for identical strings once folded and singularised, 0.0 for nothing in common. Word by word when both
     * sides have the same number of words: the weakest word decides, and a number must match exactly, so
     * « carton 8 » is not « carton 7 » and « passeport de Léo » is not « passeport de Léa ».
     */
    fun similarity(
        a: String,
        b: String,
    ): Double {
        val x = normalize(a)
        val y = normalize(b)
        val longest = maxOf(x.length, y.length)
        return when {
            x == y -> 1.0
            // A length gap alone caps the score below any threshold: skip the quadratic distance.
            kotlin.math.abs(x.length - y.length) > longest * MAX_LENGTH_GAP -> 0.0
            else -> wordAware(x, y, 1.0 - levenshtein(x, y).toDouble() / longest)
        }
    }

    private fun wordAware(
        x: String,
        y: String,
        whole: Double,
    ): Double {
        val wordsA = x.split(' ')
        val wordsB = y.split(' ')
        if (wordsA.size != wordsB.size) return whole
        return minOf(whole, wordsA.indices.minOf { wordSimilarity(wordsA[it], wordsB[it]) })
    }

    private fun wordSimilarity(
        p: String,
        q: String,
    ): Double {
        if (p == q) return 1.0
        if (p.any(Char::isDigit) || q.any(Char::isDigit)) return 0.0
        return 1.0 - levenshtein(p, q).toDouble() / maxOf(p.length, q.length)
    }

    /** Folded, single-spaced, each word without its plural mark (« clés » and « clé » are one word). */
    private fun normalize(text: String): String =
        fold(text).trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ", transform = ::singular)

    private fun singular(word: String): String =
        if (word.length > MIN_PLURAL_LENGTH && (word.endsWith('s') || word.endsWith('x'))) word.dropLast(1) else word

    /** Jaccard index of the folded word sets, ignoring one-letter words (articles, elisions). */
    fun tokenJaccard(
        a: String,
        b: String,
    ): Double {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val union = (x + y).size
        return (x intersect y).size.toDouble() / union
    }

    /** Words of more than one letter, plus numbers (« carton 7 » keeps its 7). */
    private fun words(text: String): Set<String> =
        fold(text).split(' ', '\'', '-', ',').filter { it.length > 1 || it.any(Char::isDigit) }.map(::singular).toSet()

    private val WHITESPACE = Regex("\\s+")
    private const val MIN_PLURAL_LENGTH = 3
    private const val MAX_LENGTH_GAP = 0.4
}
