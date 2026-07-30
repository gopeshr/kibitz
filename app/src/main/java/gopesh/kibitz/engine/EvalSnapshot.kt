package gopesh.kibitz.engine

import kotlin.math.abs
import kotlin.math.exp

/**
 * A position's evaluation, always from White's point of view so the bar does not jump around
 * as the turn changes.
 *
 * [whiteShare] is what the bar actually draws. A raw centipawn number cannot be shown
 * linearly: +1 pawn is a big deal and +9 is barely different from +10, so the score is
 * squashed through a logistic curve. That keeps small, meaningful advantages visible instead
 * of invisible next to the occasional queen swing.
 */
data class EvalSnapshot(
    val whiteCentipawns: Int,
    /** Positive: White mates in this many moves. Negative: Black does. Null: no mate found. */
    val mateInMoves: Int? = null,
    /** True when the game is already over, so the bar shows a result rather than a forecast. */
    val finished: Boolean = false,
) {

    /** Fraction of the bar belonging to White, 0..1. */
    val whiteShare: Float
        get() = when {
            finished || mateInMoves != null ->
                if (whiteCentipawns > 0) 1f else if (whiteCentipawns < 0) 0f else 0.5f
            else -> shareFor(whiteCentipawns)
        }

    /** Short readout: "+1.2", "−0.8", "M3", or a result once the game is over. */
    val label: String
        get() = when {
            finished -> when {
                whiteCentipawns > 0 -> "1–0"
                whiteCentipawns < 0 -> "0–1"
                else -> "½–½"
            }
            mateInMoves != null ->
                if (mateInMoves > 0) "M$mateInMoves" else "−M${-mateInMoves}"
            whiteCentipawns > 0 -> "+${pawns(whiteCentipawns)}"
            whiteCentipawns < 0 -> "−${pawns(-whiteCentipawns)}"
            else -> "0.0"
        }

    val favoursWhite: Boolean get() = whiteCentipawns > 0

    companion object {
        /**
         * Larger values flatten the curve. 350 puts a one-pawn edge at roughly 57% of the
         * bar, which reads as "slightly better" rather than "winning".
         */
        private const val SCALE = 350.0

        /** Anything this close to mate is a mate score rather than a material count. */
        private const val MATE_MARGIN = 1_000

        /**
         * Graduations drawn on the bar, in centipawns. Marking pawn boundaries gives the
         * fill something to be read against — without them a bar is just a vague blob.
         */
        val LEVELS = intArrayOf(-500, -300, -200, -100, 100, 200, 300, 500)

        fun shareFor(centipawns: Int): Float =
            (1.0 / (1.0 + exp(-centipawns / SCALE))).toFloat()

        /**
         * Builds a snapshot from a search score, which negamax reports from the point of
         * view of whoever is to move.
         */
        fun from(scoreForSideToMove: Int, whiteToMove: Boolean): EvalSnapshot {
            val white = if (whiteToMove) scoreForSideToMove else -scoreForSideToMove
            if (abs(white) >= Evaluation.MATE - MATE_MARGIN) {
                val pliesToMate = Evaluation.MATE - abs(white)
                val moves = ((pliesToMate + 1) / 2).coerceAtLeast(1)
                return EvalSnapshot(white, if (white > 0) moves else -moves)
            }
            return EvalSnapshot(white)
        }

        fun checkmate(whiteWon: Boolean): EvalSnapshot =
            EvalSnapshot(if (whiteWon) Evaluation.MATE else -Evaluation.MATE, finished = true)

        fun drawn(): EvalSnapshot = EvalSnapshot(0, finished = true)

        /** Centipawns to a one-decimal pawn string, without locale-dependent formatting. */
        private fun pawns(centipawns: Int): String {
            val tenths = (centipawns + 5) / 10
            return "${tenths / 10}.${tenths % 10}"
        }
    }
}
