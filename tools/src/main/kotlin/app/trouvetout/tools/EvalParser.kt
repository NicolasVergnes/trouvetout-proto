package app.trouvetout.tools

import java.io.File

/**
 * Skeleton (lot 0). Lot 3 replaces the body with the real replay of `corpus/parser.jsonl`
 * and fails the build under the thresholds (>= 95 % intents, >= 90 % object/place split).
 */
fun main(args: Array<String>) {
    val corpus = File(args.getOrElse(0) { "corpus/parser.jsonl" })
    val lines = if (corpus.isFile) corpus.readLines().count { it.isNotBlank() } else 0
    println("eval-parser: parser not implemented yet (lot 3); $lines phrases found in ${corpus.name}")
}
