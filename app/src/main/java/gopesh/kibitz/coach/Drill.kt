package gopesh.kibitz.coach

import gopesh.kibitz.chess.Move
import gopesh.kibitz.chess.Position
import gopesh.kibitz.chess.san
import gopesh.kibitz.data.MoveRecord

/**
 * A puzzle built from one of the player's own mistakes.
 *
 * The position is reconstructed from the FEN stored at the time, which is why that field is
 * recorded: notation alone could never be turned back into a board.
 */
data class Drill(
    val moveId: Long,
    val position: Position,
    /** What the player actually played, in the position below. */
    val playedSan: String,
    /** The move that was better — the answer. */
    val bestSan: String,
    val centipawnLoss: Int,
    val moveNumber: Int,
) {
    /**
     * The set of moves that count as correct.
     *
     * Compared as notation rather than as squares because the answer was stored as notation.
     * More than one legal move can share a SAN string only if the notation is ambiguous, which
     * SAN generation exists to prevent — so in practice this is one move, but returning a set
     * keeps a transposition from being marked wrong on a technicality.
     */
    val solutions: Set<Move> by lazy {
        position.legalMoves().filter { position.san(it) == bestSan }.toSet()
    }

    /** True when a drill is answerable at all; a puzzle with no findable answer is unusable. */
    val isUsable: Boolean get() = solutions.isNotEmpty()

    fun isCorrect(move: Move): Boolean = move in solutions

    companion object {
        /**
         * Rebuilds a drill from a stored move, or null when it cannot be trusted.
         *
         * Returns null rather than throwing: a record written by an older version, or with a
         * FEN that no longer parses, should quietly drop out of the queue instead of taking the
         * whole drill session down with it.
         */
        fun from(record: MoveRecord): Drill? {
            val best = record.bestSan?.takeIf { it.isNotBlank() } ?: return null
            val position = runCatching { Position.fromFen(record.fenBefore) }.getOrNull()
                ?: return null

            val drill = Drill(
                moveId = record.id,
                position = position,
                playedSan = record.san,
                bestSan = best,
                centipawnLoss = record.centipawnLoss,
                moveNumber = record.moveNumber,
            )
            return drill.takeIf { it.isUsable }
        }
    }
}
