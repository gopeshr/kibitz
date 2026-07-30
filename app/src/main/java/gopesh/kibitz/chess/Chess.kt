package gopesh.kibitz.chess

/**
 * Core chess vocabulary.
 *
 * Squares are plain Ints 0..63 with `index = rank * 8 + file`, so a1 == 0, h1 == 7,
 * a8 == 56, h8 == 63. Rank 0 is White's home rank. This keeps the board a flat
 * 64-element array and makes attack scans cheap.
 */

enum class Color {
    WHITE, BLACK;

    val opposite: Color get() = if (this == WHITE) BLACK else WHITE
}

enum class PieceType(val letter: Char) {
    PAWN('P'), KNIGHT('N'), BISHOP('B'), ROOK('R'), QUEEN('Q'), KING('K');

    /** Rough material value in centipawns. Not used for search — only for hints and UI. */
    val centipawns: Int
        get() = when (this) {
            PAWN -> 100
            KNIGHT -> 320
            BISHOP -> 330
            ROOK -> 500
            QUEEN -> 900
            KING -> 0
        }
}

data class Piece(val color: Color, val type: PieceType) {
    val fenChar: Char
        get() = if (color == Color.WHITE) type.letter else type.letter.lowercaseChar()

    companion object {
        fun fromFenChar(c: Char): Piece {
            val color = if (c.isUpperCase()) Color.WHITE else Color.BLACK
            val type = when (c.uppercaseChar()) {
                'P' -> PieceType.PAWN
                'N' -> PieceType.KNIGHT
                'B' -> PieceType.BISHOP
                'R' -> PieceType.ROOK
                'Q' -> PieceType.QUEEN
                'K' -> PieceType.KING
                else -> throw IllegalArgumentException("Not a piece character: '$c'")
            }
            return Piece(color, type)
        }
    }
}

enum class CastlingRight(val fenChar: Char) {
    WHITE_KING_SIDE('K'),
    WHITE_QUEEN_SIDE('Q'),
    BLACK_KING_SIDE('k'),
    BLACK_QUEEN_SIDE('q');

    companion object {
        fun fromFenChar(c: Char): CastlingRight =
            entries.firstOrNull { it.fenChar == c }
                ?: throw IllegalArgumentException("Not a castling right: '$c'")
    }
}

fun fileOf(square: Int): Int = square and 7

fun rankOf(square: Int): Int = square shr 3

object Squares {
    const val NONE = -1

    const val A1 = 0
    const val H1 = 7
    const val A8 = 56
    const val H8 = 63

    fun of(file: Int, rank: Int): Int = rank * 8 + file

    fun isOnBoard(file: Int, rank: Int): Boolean = file in 0..7 && rank in 0..7

    fun name(square: Int): String =
        if (square == NONE) "-" else "${'a' + fileOf(square)}${rankOf(square) + 1}"

    fun fromName(name: String): Int {
        require(name.length == 2) { "Not a square name: '$name'" }
        val file = name[0].lowercaseChar() - 'a'
        val rank = name[1] - '1'
        require(isOnBoard(file, rank)) { "Not a square name: '$name'" }
        return of(file, rank)
    }
}

/**
 * A move is just origin, destination, and an optional promotion choice. Castling is
 * encoded as the king moving two files; en passant as a pawn moving diagonally onto
 * an empty square. [Position.makeMove] knows how to unpack both.
 */
data class Move(val from: Int, val to: Int, val promotion: PieceType? = null) {
    val uci: String
        get() = Squares.name(from) + Squares.name(to) +
            (promotion?.letter?.lowercaseChar()?.toString() ?: "")

    override fun toString(): String = uci
}

enum class DrawReason { STALEMATE, FIFTY_MOVE, INSUFFICIENT_MATERIAL, THREEFOLD_REPETITION }

sealed interface Status {
    /** Play continues. [inCheck] refers to the side to move. */
    data class Ongoing(val inCheck: Boolean) : Status

    data class Checkmate(val winner: Color) : Status

    data class Draw(val reason: DrawReason) : Status
}
