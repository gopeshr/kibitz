package gopesh.kibitz.chess

/**
 * Standard Algebraic Notation. Needed well before any engine work: it is what a move list
 * shows, what a PGN stores, and the only move format a language model reliably understands.
 *
 * Must be called on the position *before* [move] is played.
 */
fun Position.san(move: Move): String {
    val piece = this[move.from] ?: return move.uci
    val capture = isCapture(move)
    val body = when {
        isCastling(move) -> if (fileOf(move.to) == 6) "O-O" else "O-O-O"

        piece.type == PieceType.PAWN -> buildString {
            if (capture) {
                append('a' + fileOf(move.from))
                append('x')
            }
            append(Squares.name(move.to))
            move.promotion?.let {
                append('=')
                append(it.letter)
            }
        }

        else -> buildString {
            append(piece.type.letter)
            append(disambiguationFor(move, piece))
            if (capture) append('x')
            append(Squares.name(move.to))
        }
    }

    val after = makeMove(move)
    val suffix = when {
        !after.isInCheck(after.sideToMove) -> ""
        after.legalMoves().isEmpty() -> "#"
        else -> "+"
    }
    return body + suffix
}

/**
 * The minimum file/rank hint that tells this move apart from another identical piece
 * reaching the same square — file if that is enough, else rank, else both.
 */
private fun Position.disambiguationFor(move: Move, piece: Piece): String {
    val rivals = legalMoves().filter { other ->
        other.to == move.to &&
            other.from != move.from &&
            this[other.from] == piece
    }
    if (rivals.isEmpty()) return ""

    val fileIsUnique = rivals.none { fileOf(it.from) == fileOf(move.from) }
    if (fileIsUnique) return ('a' + fileOf(move.from)).toString()

    val rankIsUnique = rivals.none { rankOf(it.from) == rankOf(move.from) }
    if (rankIsUnique) return ('1' + rankOf(move.from)).toString()

    return Squares.name(move.from)
}

/** Renders a move list as "1. e4 e5 2. Nf3" for display. */
fun formatMoveList(sanMoves: List<String>, firstMoveNumber: Int = 1): String =
    buildString {
        sanMoves.forEachIndexed { index, san ->
            if (index % 2 == 0) {
                if (index > 0) append(' ')
                append(firstMoveNumber + index / 2).append(". ")
            } else {
                append(' ')
            }
            append(san)
        }
    }
