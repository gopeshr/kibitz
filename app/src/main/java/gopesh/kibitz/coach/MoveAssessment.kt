package gopesh.kibitz.coach

import gopesh.kibitz.chess.Color
import gopesh.kibitz.chess.Move
import gopesh.kibitz.chess.Position
import gopesh.kibitz.chess.san
import gopesh.kibitz.engine.ChessEngine

/**
 * How much a move cost, in the vocabulary players already use. Thresholds are in centipawns
 * of lost evaluation, matching the conventions of public analysis tools closely enough that
 * the labels mean what a player expects.
 */
enum class MoveQuality(val label: String) {
    BEST("Best move"),
    GOOD("Good"),
    INACCURACY("Inaccuracy"),
    MISTAKE("Mistake"),
    BLUNDER("Blunder");

    companion object {
        fun forLoss(centipawnLoss: Int): MoveQuality = when {
            centipawnLoss <= 10 -> BEST
            centipawnLoss <= 50 -> GOOD
            centipawnLoss <= 120 -> INACCURACY
            centipawnLoss <= 250 -> MISTAKE
            else -> BLUNDER
        }
    }
}

/**
 * One judged move. [centipawnLoss] is the gap between the best move available and the move
 * actually played, so 0 means nothing better existed.
 *
 * [byFullStrengthEngine] records whether Stockfish or the fallback produced the number, because
 * the two are not on the same scale and a game judged by a mixture should not be treated as a
 * single clean measurement.
 */
data class MoveAssessment(
    val moveNumber: Int,
    val side: Color,
    val san: String,
    val centipawnLoss: Int,
    val quality: MoveQuality,
    /** The move the engine preferred, when it differs from the one played. */
    val bestSan: String?,
    val byFullStrengthEngine: Boolean = true,
)

/**
 * Judges a single move by asking the engine what the position was worth before and after.
 */
class MoveAnalyst(private val engine: ChessEngine) {

    suspend fun assess(before: Position, played: Move): MoveAssessment {
        val cost = engine.priceMove(before, played)
        val loss = cost.centipawnLoss

        return MoveAssessment(
            moveNumber = before.fullmoveNumber,
            side = before.sideToMove,
            san = before.san(played),
            centipawnLoss = loss,
            quality = MoveQuality.forLoss(loss),
            bestSan = cost.bestMove
                ?.takeIf { it != played }
                ?.let { before.san(it) },
            byFullStrengthEngine = engine.isFullStrength,
        )
    }
}
