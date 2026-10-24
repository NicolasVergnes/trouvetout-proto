package app.trouvetout.tools

import java.io.File

/**
 * Skeleton (lot 0). Lot 6 replaces the body with the real replay of `corpus/recherche.jsonl`
 * over `corpus/fiches-reference.json` and fails the build under 80 % top-1 / 95 % top-3 / 1 s.
 */
fun main(args: Array<String>) {
    val corpusDir = File(args.getOrElse(0) { "corpus" })
    val queries = File(corpusDir, "recherche.jsonl")
    val count = if (queries.isFile) queries.readLines().count { it.isNotBlank() } else 0
    println("eval-search: search not implemented yet (lot 6); $count queries found")
}
