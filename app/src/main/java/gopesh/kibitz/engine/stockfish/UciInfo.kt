package gopesh.kibitz.engine.stockfish

import gopesh.kibitz.engine.Evaluation

/**
 * The part of a UCI search worth keeping: the deepest score reported and the move chosen.
 *
 * Scores are from the moving side's point of view, per the UCI specification, which matches
 * what the rest of the app expects from a search.
 */
data class UciInfo(
    val scoreCentipawns: Int? = null,
    /** Mate distance in moves. Positive: the mover mates. Negative: the mover gets mated. */
    val scoreMate: Int? = null,
    val bestMoveUci: String? = null,
    val depth: Int = 0,
) {
    /**
     * A single signed number the rest of the app can use, with mates mapped onto the same
     * scale the Kotlin engine uses so both engines are interchangeable.
     */
    val score: Int
        get() = when {
            scoreMate != null && scoreMate >= 0 -> Evaluation.MATE - (2 * scoreMate - 1).coerceAtLeast(0)
            scoreMate != null -> -Evaluation.MATE + (2 * -scoreMate).coerceAtLeast(0)
            else -> scoreCentipawns ?: 0
        }
}

/**
 * Parses the lines of one `go` exchange.
 *
 * Only the deepest `info` line matters — Stockfish reports every iteration on the way up, and
 * the shallow ones are superseded. Lines without a score (`currmove` progress reports, for
 * instance) are ignored rather than allowed to clobber a good score with a null.
 */
object UciParser {

    fun parse(lines: List<String>): UciInfo {
        var best = UciInfo()
        for (line in lines) {
            when {
                line.startsWith("info ") -> parseInfo(line)?.let { candidate ->
                    if (candidate.depth >= best.depth) {
                        best = candidate.copy(bestMoveUci = best.bestMoveUci)
                    }
                }
                line.startsWith("bestmove") -> {
                    val move = line.split(Regex("\\s+")).getOrNull(1)
                        ?.takeIf { it != "(none)" && it.length >= 4 }
                    best = best.copy(bestMoveUci = move)
                }
            }
        }
        return best
    }

    private fun parseInfo(line: String): UciInfo? {
        val tokens = line.split(Regex("\\s+"))
        var depth = 0
        var cp: Int? = null
        var mate: Int? = null

        var index = 0
        while (index < tokens.size) {
            when (tokens[index]) {
                "depth" -> tokens.getOrNull(index + 1)?.toIntOrNull()?.let { depth = it }
                "score" -> when (tokens.getOrNull(index + 1)) {
                    "cp" -> cp = tokens.getOrNull(index + 2)?.toIntOrNull()
                    "mate" -> mate = tokens.getOrNull(index + 2)?.toIntOrNull()
                }
            }
            index++
        }

        if (cp == null && mate == null) return null
        return UciInfo(scoreCentipawns = cp, scoreMate = mate, depth = depth)
    }
}
