package gopesh.kibitz.engine

import gopesh.kibitz.chess.Color
import gopesh.kibitz.chess.PieceType
import gopesh.kibitz.chess.Position
import gopesh.kibitz.chess.fileOf
import gopesh.kibitz.chess.rankOf

/**
 * Static evaluation: material plus piece-square tables, in centipawns.
 *
 * This is deliberately a simple, well-understood evaluator rather than something clever.
 * Its job is to be good enough to play a reasonable game and to rank a player's move
 * against the alternatives. Stockfish will take over deep analysis later; when it does,
 * the level calibration in `LevelCalibration` needs revisiting, because centipawn losses
 * measured by a shallow evaluator are not on the same scale.
 *
 * Tables are written in reading order (a8 first, h1 last) because that is how they are
 * published and reviewed. [tableIndex] maps a board square onto that order.
 */
object Evaluation {

    /** Score assigned to being checkmated. Well outside any material swing. */
    const val MATE = 100_000

    /** Below this much non-pawn material for both sides, the king should walk forward. */
    private const val ENDGAME_MATERIAL = 1_300

    private val PAWN_TABLE = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
        5, 5, 10, 25, 25, 10, 5, 5,
        0, 0, 0, 20, 20, 0, 0, 0,
        5, -5, -10, 0, 0, -10, -5, 5,
        5, 10, 10, -20, -20, 10, 10, 5,
        0, 0, 0, 0, 0, 0, 0, 0,
    )

    private val KNIGHT_TABLE = intArrayOf(
        -50, -40, -30, -30, -30, -30, -40, -50,
        -40, -20, 0, 0, 0, 0, -20, -40,
        -30, 0, 10, 15, 15, 10, 0, -30,
        -30, 5, 15, 20, 20, 15, 5, -30,
        -30, 0, 15, 20, 20, 15, 0, -30,
        -30, 5, 10, 15, 15, 10, 5, -30,
        -40, -20, 0, 5, 5, 0, -20, -40,
        -50, -40, -30, -30, -30, -30, -40, -50,
    )

    private val BISHOP_TABLE = intArrayOf(
        -20, -10, -10, -10, -10, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 10, 10, 5, 0, -10,
        -10, 5, 5, 10, 10, 5, 5, -10,
        -10, 0, 10, 10, 10, 10, 0, -10,
        -10, 10, 10, 10, 10, 10, 10, -10,
        -10, 5, 0, 0, 0, 0, 5, -10,
        -20, -10, -10, -10, -10, -10, -10, -20,
    )

    private val ROOK_TABLE = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        5, 10, 10, 10, 10, 10, 10, 5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        0, 0, 0, 5, 5, 0, 0, 0,
    )

    private val QUEEN_TABLE = intArrayOf(
        -20, -10, -10, -5, -5, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 5, 5, 5, 0, -10,
        -5, 0, 5, 5, 5, 5, 0, -5,
        0, 0, 5, 5, 5, 5, 0, -5,
        -10, 5, 5, 5, 5, 5, 0, -10,
        -10, 0, 5, 0, 0, 0, 0, -10,
        -20, -10, -10, -5, -5, -10, -10, -20,
    )

    private val KING_MIDGAME_TABLE = intArrayOf(
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -20, -30, -30, -40, -40, -30, -30, -20,
        -10, -20, -20, -20, -20, -20, -20, -10,
        20, 20, 0, 0, 0, 0, 20, 20,
        20, 30, 10, 0, 0, 10, 30, 20,
    )

    private val KING_ENDGAME_TABLE = intArrayOf(
        -50, -40, -30, -20, -20, -30, -40, -50,
        -30, -20, -10, 0, 0, -10, -20, -30,
        -30, -10, 20, 30, 30, 20, -10, -30,
        -30, -10, 30, 40, 40, 30, -10, -30,
        -30, -10, 30, 40, 40, 30, -10, -30,
        -30, -10, 20, 30, 30, 20, -10, -30,
        -30, -30, 0, 0, 0, 0, -30, -30,
        -50, -30, -30, -30, -30, -30, -30, -50,
    )

    /** Positive favours White, negative favours Black. */
    fun evaluate(position: Position): Int {
        var material = 0
        var nonPawnMaterial = 0
        for (square in 0..63) {
            val piece = position[square] ?: continue
            if (piece.type != PieceType.PAWN && piece.type != PieceType.KING) {
                nonPawnMaterial += piece.type.centipawns
            }
            material += if (piece.color == Color.WHITE) piece.type.centipawns
            else -piece.type.centipawns
        }

        val endgame = nonPawnMaterial <= ENDGAME_MATERIAL
        var placement = 0
        for (square in 0..63) {
            val piece = position[square] ?: continue
            val table = when (piece.type) {
                PieceType.PAWN -> PAWN_TABLE
                PieceType.KNIGHT -> KNIGHT_TABLE
                PieceType.BISHOP -> BISHOP_TABLE
                PieceType.ROOK -> ROOK_TABLE
                PieceType.QUEEN -> QUEEN_TABLE
                PieceType.KING -> if (endgame) KING_ENDGAME_TABLE else KING_MIDGAME_TABLE
            }
            val bonus = table[tableIndex(square, piece.color)]
            placement += if (piece.color == Color.WHITE) bonus else -bonus
        }

        // A pair of bishops is worth a little more than the sum of its parts.
        val bishopPairs = bishopPairBonus(position)

        return material + placement + bishopPairs
    }

    /** Evaluation from the perspective of whoever is to move, as negamax expects. */
    fun forSideToMove(position: Position): Int {
        val score = evaluate(position)
        return if (position.sideToMove == Color.WHITE) score else -score
    }

    private fun bishopPairBonus(position: Position): Int {
        var white = 0
        var black = 0
        for (square in 0..63) {
            val piece = position[square] ?: continue
            if (piece.type != PieceType.BISHOP) continue
            if (piece.color == Color.WHITE) white++ else black++
        }
        return (if (white >= 2) 30 else 0) - (if (black >= 2) 30 else 0)
    }

    /**
     * Board square to reading-order table index. White reads from rank 8 down; Black uses
     * the vertical mirror so the same table describes both sides.
     */
    private fun tableIndex(square: Int, color: Color): Int {
        val file = fileOf(square)
        val rank = rankOf(square)
        return if (color == Color.WHITE) (7 - rank) * 8 + file else rank * 8 + file
    }
}
