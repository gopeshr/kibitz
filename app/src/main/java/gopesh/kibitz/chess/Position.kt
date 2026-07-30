package gopesh.kibitz.chess

import kotlin.math.abs

private val KNIGHT_DELTAS = arrayOf(
    1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2
)
private val KING_DELTAS = arrayOf(
    0 to 1, 1 to 1, 1 to 0, 1 to -1, 0 to -1, -1 to -1, -1 to 0, -1 to 1
)
private val DIAGONAL_DIRS = arrayOf(1 to 1, 1 to -1, -1 to -1, -1 to 1)
private val ORTHOGONAL_DIRS = arrayOf(0 to 1, 1 to 0, 0 to -1, -1 to 0)

private val PROMOTION_CHOICES =
    arrayOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)

/**
 * An immutable chess position. [makeMove] returns a new [Position] rather than mutating,
 * which keeps undo, variation trees and Compose state trivial — copying 64 references per
 * move is not a cost worth optimising away here.
 */
class Position(
    private val squares: Array<Piece?>,
    val sideToMove: Color,
    val castlingRights: Set<CastlingRight>,
    /** Square a pawn could be captured on by en passant, or [Squares.NONE]. */
    val epSquare: Int,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
) {
    init {
        require(squares.size == 64) { "A board has 64 squares, got ${squares.size}" }
    }

    operator fun get(square: Int): Piece? = squares[square]

    fun pieceAt(file: Int, rank: Int): Piece? = squares[Squares.of(file, rank)]

    // ---------------------------------------------------------------- attacks

    fun kingSquare(color: Color): Int {
        for (sq in 0..63) {
            val p = squares[sq]
            if (p != null && p.color == color && p.type == PieceType.KING) return sq
        }
        return Squares.NONE
    }

    /** True if [color] attacks [square], whether or not a piece stands there. */
    fun isAttacked(square: Int, color: Color): Boolean {
        val f = fileOf(square)
        val r = rankOf(square)

        // Pawns. A white pawn attacks upward, so it must sit one rank below the target.
        val pawnRank = if (color == Color.WHITE) r - 1 else r + 1
        if (pawnRank in 0..7) {
            for (df in intArrayOf(-1, 1)) {
                val nf = f + df
                if (nf in 0..7) {
                    val p = squares[Squares.of(nf, pawnRank)]
                    if (p != null && p.color == color && p.type == PieceType.PAWN) return true
                }
            }
        }

        for ((df, dr) in KNIGHT_DELTAS) {
            val nf = f + df
            val nr = r + dr
            if (Squares.isOnBoard(nf, nr)) {
                val p = squares[Squares.of(nf, nr)]
                if (p != null && p.color == color && p.type == PieceType.KNIGHT) return true
            }
        }

        for ((df, dr) in KING_DELTAS) {
            val nf = f + df
            val nr = r + dr
            if (Squares.isOnBoard(nf, nr)) {
                val p = squares[Squares.of(nf, nr)]
                if (p != null && p.color == color && p.type == PieceType.KING) return true
            }
        }

        if (isAttackedAlong(f, r, color, DIAGONAL_DIRS, PieceType.BISHOP)) return true
        if (isAttackedAlong(f, r, color, ORTHOGONAL_DIRS, PieceType.ROOK)) return true

        return false
    }

    /** Walks each ray until it hits a piece; a matching slider or queen means attacked. */
    private fun isAttackedAlong(
        file: Int,
        rank: Int,
        color: Color,
        dirs: Array<Pair<Int, Int>>,
        slider: PieceType,
    ): Boolean {
        for ((df, dr) in dirs) {
            var nf = file + df
            var nr = rank + dr
            while (Squares.isOnBoard(nf, nr)) {
                val p = squares[Squares.of(nf, nr)]
                if (p != null) {
                    if (p.color == color && (p.type == slider || p.type == PieceType.QUEEN)) {
                        return true
                    }
                    break
                }
                nf += df
                nr += dr
            }
        }
        return false
    }

    fun isInCheck(color: Color): Boolean {
        val king = kingSquare(color)
        return king != Squares.NONE && isAttacked(king, color.opposite)
    }

    // ------------------------------------------------------- move generation

    /**
     * Moves that respect piece movement rules but may leave the mover's king in check.
     * [legalMoves] filters those out.
     */
    fun pseudoLegalMoves(): List<Move> {
        val moves = ArrayList<Move>(48)
        for (from in 0..63) {
            val piece = squares[from] ?: continue
            if (piece.color != sideToMove) continue
            when (piece.type) {
                PieceType.PAWN -> generatePawnMoves(from, piece.color, moves)
                PieceType.KNIGHT -> generateStepMoves(from, piece.color, KNIGHT_DELTAS, moves)
                PieceType.KING -> {
                    generateStepMoves(from, piece.color, KING_DELTAS, moves)
                    generateCastlingMoves(from, piece.color, moves)
                }
                PieceType.BISHOP -> generateSlidingMoves(from, piece.color, DIAGONAL_DIRS, moves)
                PieceType.ROOK -> generateSlidingMoves(from, piece.color, ORTHOGONAL_DIRS, moves)
                PieceType.QUEEN -> {
                    generateSlidingMoves(from, piece.color, DIAGONAL_DIRS, moves)
                    generateSlidingMoves(from, piece.color, ORTHOGONAL_DIRS, moves)
                }
            }
        }
        return moves
    }

    /** Every move the side to move may actually play. */
    fun legalMoves(): List<Move> = pseudoLegalMoves().filter { isLegal(it) }

    /** Legal moves starting from [square] — what the board UI needs on a tap. */
    fun legalMovesFrom(square: Int): List<Move> = legalMoves().filter { it.from == square }

    private fun isLegal(move: Move): Boolean {
        val mover = squares[move.from]?.color ?: return false
        return !makeMove(move).isInCheck(mover)
    }

    private fun generatePawnMoves(from: Int, color: Color, out: MutableList<Move>) {
        val dir = if (color == Color.WHITE) 1 else -1
        val startRank = if (color == Color.WHITE) 1 else 6
        val promotionRank = if (color == Color.WHITE) 7 else 0
        val f = fileOf(from)
        val r = rankOf(from)

        val oneRank = r + dir
        if (oneRank in 0..7) {
            val one = Squares.of(f, oneRank)
            if (squares[one] == null) {
                addPawnMove(from, one, oneRank == promotionRank, out)
                if (r == startRank) {
                    val two = Squares.of(f, r + 2 * dir)
                    if (squares[two] == null) out.add(Move(from, two))
                }
            }
            for (df in intArrayOf(-1, 1)) {
                val nf = f + df
                if (nf !in 0..7) continue
                val target = Squares.of(nf, oneRank)
                val occupant = squares[target]
                if (occupant != null) {
                    if (occupant.color != color) {
                        addPawnMove(from, target, oneRank == promotionRank, out)
                    }
                } else if (target == epSquare) {
                    out.add(Move(from, target))
                }
            }
        }
    }

    private fun addPawnMove(from: Int, to: Int, promoting: Boolean, out: MutableList<Move>) {
        if (promoting) {
            for (choice in PROMOTION_CHOICES) out.add(Move(from, to, choice))
        } else {
            out.add(Move(from, to))
        }
    }

    private fun generateStepMoves(
        from: Int,
        color: Color,
        deltas: Array<Pair<Int, Int>>,
        out: MutableList<Move>,
    ) {
        val f = fileOf(from)
        val r = rankOf(from)
        for ((df, dr) in deltas) {
            val nf = f + df
            val nr = r + dr
            if (!Squares.isOnBoard(nf, nr)) continue
            val to = Squares.of(nf, nr)
            val occupant = squares[to]
            if (occupant == null || occupant.color != color) out.add(Move(from, to))
        }
    }

    private fun generateSlidingMoves(
        from: Int,
        color: Color,
        dirs: Array<Pair<Int, Int>>,
        out: MutableList<Move>,
    ) {
        val f = fileOf(from)
        val r = rankOf(from)
        for ((df, dr) in dirs) {
            var nf = f + df
            var nr = r + dr
            while (Squares.isOnBoard(nf, nr)) {
                val to = Squares.of(nf, nr)
                val occupant = squares[to]
                if (occupant == null) {
                    out.add(Move(from, to))
                } else {
                    if (occupant.color != color) out.add(Move(from, to))
                    break
                }
                nf += df
                nr += dr
            }
        }
    }

    private fun generateCastlingMoves(from: Int, color: Color, out: MutableList<Move>) {
        val homeRank = if (color == Color.WHITE) 0 else 7
        // Only castle from the true starting square; rights alone are not enough.
        if (from != Squares.of(4, homeRank)) return
        val enemy = color.opposite
        if (isAttacked(from, enemy)) return

        val kingSide =
            if (color == Color.WHITE) CastlingRight.WHITE_KING_SIDE else CastlingRight.BLACK_KING_SIDE
        val queenSide =
            if (color == Color.WHITE) CastlingRight.WHITE_QUEEN_SIDE else CastlingRight.BLACK_QUEEN_SIDE

        if (kingSide in castlingRights &&
            squares[Squares.of(5, homeRank)] == null &&
            squares[Squares.of(6, homeRank)] == null &&
            !isAttacked(Squares.of(5, homeRank), enemy)
        ) {
            out.add(Move(from, Squares.of(6, homeRank)))
        }

        if (queenSide in castlingRights &&
            squares[Squares.of(3, homeRank)] == null &&
            squares[Squares.of(2, homeRank)] == null &&
            squares[Squares.of(1, homeRank)] == null &&
            !isAttacked(Squares.of(3, homeRank), enemy)
        ) {
            out.add(Move(from, Squares.of(2, homeRank)))
        }
    }

    // ------------------------------------------------------------- move play

    fun isCastling(move: Move): Boolean =
        squares[move.from]?.type == PieceType.KING &&
            abs(fileOf(move.to) - fileOf(move.from)) == 2

    fun isEnPassant(move: Move): Boolean =
        squares[move.from]?.type == PieceType.PAWN &&
            move.to == epSquare &&
            fileOf(move.from) != fileOf(move.to)

    fun isCapture(move: Move): Boolean = squares[move.to] != null || isEnPassant(move)

    /** True when a pawn reaching its last rank means the caller must pick a piece. */
    fun isPromotion(move: Move): Boolean {
        val piece = squares[move.from] ?: return false
        if (piece.type != PieceType.PAWN) return false
        return rankOf(move.to) == if (piece.color == Color.WHITE) 7 else 0
    }

    /**
     * Applies [move] and returns the resulting position. The move must be pseudo-legal;
     * king safety is not re-checked here (that is [legalMoves]' job).
     */
    fun makeMove(move: Move): Position {
        val next = squares.copyOf()
        val piece = next[move.from]
            ?: throw IllegalArgumentException("No piece on ${Squares.name(move.from)}")
        val mover = piece.color
        val captured = next[move.to]

        next[move.to] = piece
        next[move.from] = null

        var newEpSquare = Squares.NONE
        var resetsClock = captured != null || piece.type == PieceType.PAWN

        if (piece.type == PieceType.PAWN) {
            val dir = if (mover == Color.WHITE) 1 else -1
            if (captured == null && fileOf(move.from) != fileOf(move.to)) {
                // En passant: the captured pawn sits beside the origin, not on the target.
                next[Squares.of(fileOf(move.to), rankOf(move.to) - dir)] = null
                resetsClock = true
            }
            if (abs(rankOf(move.to) - rankOf(move.from)) == 2) {
                newEpSquare = Squares.of(fileOf(move.from), rankOf(move.from) + dir)
            }
            if (move.promotion != null) next[move.to] = Piece(mover, move.promotion)
        }

        if (piece.type == PieceType.KING && abs(fileOf(move.to) - fileOf(move.from)) == 2) {
            val homeRank = rankOf(move.from)
            if (fileOf(move.to) == 6) {
                next[Squares.of(5, homeRank)] = next[Squares.of(7, homeRank)]
                next[Squares.of(7, homeRank)] = null
            } else {
                next[Squares.of(3, homeRank)] = next[Squares.of(0, homeRank)]
                next[Squares.of(0, homeRank)] = null
            }
        }

        val rights = castlingRights.toMutableSet()
        if (piece.type == PieceType.KING) {
            if (mover == Color.WHITE) {
                rights.remove(CastlingRight.WHITE_KING_SIDE)
                rights.remove(CastlingRight.WHITE_QUEEN_SIDE)
            } else {
                rights.remove(CastlingRight.BLACK_KING_SIDE)
                rights.remove(CastlingRight.BLACK_QUEEN_SIDE)
            }
        }
        // A rook leaving — or being captured on — a corner kills that side's right.
        for (corner in intArrayOf(move.from, move.to)) {
            when (corner) {
                Squares.A1 -> rights.remove(CastlingRight.WHITE_QUEEN_SIDE)
                Squares.H1 -> rights.remove(CastlingRight.WHITE_KING_SIDE)
                Squares.A8 -> rights.remove(CastlingRight.BLACK_QUEEN_SIDE)
                Squares.H8 -> rights.remove(CastlingRight.BLACK_KING_SIDE)
            }
        }

        return Position(
            squares = next,
            sideToMove = mover.opposite,
            castlingRights = rights,
            epSquare = newEpSquare,
            halfmoveClock = if (resetsClock) 0 else halfmoveClock + 1,
            fullmoveNumber = if (mover == Color.BLACK) fullmoveNumber + 1 else fullmoveNumber,
        )
    }

    // --------------------------------------------------------------- outcome

    fun status(): Status {
        if (legalMoves().isEmpty()) {
            return if (isInCheck(sideToMove)) {
                Status.Checkmate(sideToMove.opposite)
            } else {
                Status.Draw(DrawReason.STALEMATE)
            }
        }
        if (halfmoveClock >= 100) return Status.Draw(DrawReason.FIFTY_MOVE)
        if (hasInsufficientMaterial()) return Status.Draw(DrawReason.INSUFFICIENT_MATERIAL)
        return Status.Ongoing(isInCheck(sideToMove))
    }

    /**
     * Only the positions where mate is outright impossible: bare kings, king and a single
     * minor, and king plus bishop each on same-coloured bishops. Cases like KNN vs K are
     * unwinnable against a defence but not dead, so they are left as ongoing.
     */
    private fun hasInsufficientMaterial(): Boolean {
        val minors = ArrayList<Pair<Color, Int>>(4)
        for (sq in 0..63) {
            val p = squares[sq] ?: continue
            when (p.type) {
                PieceType.KING -> Unit
                PieceType.KNIGHT -> minors.add(p.color to -1)
                PieceType.BISHOP -> minors.add(p.color to (fileOf(sq) + rankOf(sq)) % 2)
                else -> return false // a pawn, rook or queen can still mate
            }
        }
        if (minors.size <= 1) return true
        if (minors.size == 2) {
            val (a, b) = minors
            // Bishops of the same colour complex can never mate.
            if (a.first != b.first && a.second >= 0 && b.second >= 0 && a.second == b.second) {
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------- FEN

    val fen: String
        get() = buildString {
            for (rank in 7 downTo 0) {
                var empty = 0
                for (file in 0..7) {
                    val piece = squares[Squares.of(file, rank)]
                    if (piece == null) {
                        empty++
                    } else {
                        if (empty > 0) {
                            append(empty)
                            empty = 0
                        }
                        append(piece.fenChar)
                    }
                }
                if (empty > 0) append(empty)
                if (rank > 0) append('/')
            }
            append(' ')
            append(if (sideToMove == Color.WHITE) 'w' else 'b')
            append(' ')
            if (castlingRights.isEmpty()) {
                append('-')
            } else {
                for (right in CastlingRight.entries) {
                    if (right in castlingRights) append(right.fenChar)
                }
            }
            append(' ')
            append(Squares.name(epSquare))
            append(' ')
            append(halfmoveClock)
            append(' ')
            append(fullmoveNumber)
        }

    /**
     * Identifies a position for repetition purposes: placement, side to move, castling rights
     * and en passant, but *not* the move counters — those differ on every repetition by
     * definition, and including them would mean no position ever repeated.
     *
     * Repetition cannot be answered by a position alone, only by a game, so counting happens
     * where the move history lives.
     *
     * Approximation: FIDE only counts en passant when the capture is actually available, while
     * this keys on the square being set at all. It can therefore miss a genuine repetition in
     * the rare case where a pawn double-push created an en passant square nobody could use. It
     * never reports a repetition that did not happen.
     */
    val repetitionKey: String
        get() = fen.split(' ').take(4).joinToString(" ")

    override fun toString(): String = fen

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun start(): Position = fromFen(START_FEN)

        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+"))
            require(parts.size >= 4) { "FEN needs at least 4 fields: '$fen'" }

            val squares = arrayOfNulls<Piece>(64)
            val rows = parts[0].split('/')
            require(rows.size == 8) { "FEN board needs 8 ranks: '${parts[0]}'" }
            for ((rowIndex, row) in rows.withIndex()) {
                val rank = 7 - rowIndex
                var file = 0
                for (c in row) {
                    if (c.isDigit()) {
                        file += c - '0'
                    } else {
                        require(file < 8) { "Rank ${rank + 1} overflows in '$fen'" }
                        squares[Squares.of(file, rank)] = Piece.fromFenChar(c)
                        file++
                    }
                }
                require(file == 8) { "Rank ${rank + 1} does not fill 8 files in '$fen'" }
            }

            val sideToMove = when (parts[1].lowercase()) {
                "w" -> Color.WHITE
                "b" -> Color.BLACK
                else -> throw IllegalArgumentException("Bad side to move: '${parts[1]}'")
            }

            val rights = if (parts[2] == "-") {
                emptySet()
            } else {
                parts[2].map { CastlingRight.fromFenChar(it) }.toSet()
            }

            val ep = if (parts[3] == "-") Squares.NONE else Squares.fromName(parts[3])

            return Position(
                squares = squares,
                sideToMove = sideToMove,
                castlingRights = rights,
                epSquare = ep,
                halfmoveClock = parts.getOrNull(4)?.toIntOrNull() ?: 0,
                fullmoveNumber = parts.getOrNull(5)?.toIntOrNull() ?: 1,
            )
        }
    }
}
