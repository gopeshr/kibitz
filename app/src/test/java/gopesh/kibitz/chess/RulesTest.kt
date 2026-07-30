package gopesh.kibitz.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour the board UI leans on directly: FEN round-trips, notation, and the terminal
 * states the status line reports.
 */
class RulesTest {

    private fun Position.play(vararg sanLike: String): Position {
        var current = this
        for (uci in sanLike) {
            val from = Squares.fromName(uci.substring(0, 2))
            val to = Squares.fromName(uci.substring(2, 4))
            val promotion = uci.drop(4).firstOrNull()?.let {
                PieceType.entries.first { type -> type.letter == it.uppercaseChar() }
            }
            val move = current.legalMoves().firstOrNull {
                it.from == from && it.to == to && it.promotion == promotion
            } ?: error("$uci is not legal in ${current.fen}")
            current = current.makeMove(move)
        }
        return current
    }

    @Test
    fun fenRoundTripsThroughStartPosition() {
        assertEquals(Position.START_FEN, Position.start().fen)
    }

    @Test
    fun fenRoundTripsThroughAComplexPosition() {
        val fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        assertEquals(fen, Position.fromFen(fen).fen)
    }

    @Test
    fun doublePushSetsEnPassantSquare() {
        val after = Position.start().play("e2e4")
        assertEquals(Squares.fromName("e3"), after.epSquare)
        assertEquals(Squares.NONE, after.play("e7e5", "g1f3").epSquare)
    }

    @Test
    fun enPassantRemovesThePawnBesideTheOrigin() {
        val after = Position.start().play("e2e4", "a7a6", "e4e5", "d7d5", "e5d6")
        assertEquals(null, after[Squares.fromName("d5")])
        assertEquals(Piece(Color.WHITE, PieceType.PAWN), after[Squares.fromName("d6")])
    }

    @Test
    fun castlingMovesTheRookAndClearsRights() {
        val after = Position
            .fromFen("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1")
            .play("e1g1")
        assertEquals(Piece(Color.WHITE, PieceType.KING), after[Squares.fromName("g1")])
        assertEquals(Piece(Color.WHITE, PieceType.ROOK), after[Squares.fromName("f1")])
        assertFalse(CastlingRight.WHITE_KING_SIDE in after.castlingRights)
        assertFalse(CastlingRight.WHITE_QUEEN_SIDE in after.castlingRights)
        assertTrue(CastlingRight.BLACK_KING_SIDE in after.castlingRights)
    }

    @Test
    fun cannotCastleThroughAnAttackedSquare() {
        // A black rook on f8 covers f1 down an open file, so king-side castling is illegal.
        val position = Position.fromFen("5r2/8/8/8/8/8/PPPPP1PP/R3K2R w KQ - 0 1")
        val castles = position.legalMoves().filter { position.isCastling(it) }
        assertEquals(listOf(Squares.fromName("c1")), castles.map { it.to })
    }

    @Test
    fun pinnedPieceCannotMove() {
        // The d2 knight is pinned to e1 by the bishop on a5.
        val position = Position.fromFen("4k3/8/8/b7/8/8/3N4/4K3 w - - 0 1")
        assertTrue(position.legalMovesFrom(Squares.fromName("d2")).isEmpty())
    }

    @Test
    fun detectsBackRankMate() {
        val position = Position.fromFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")
        val mate = position.legalMoves().first {
            it.from == Squares.fromName("a1") && it.to == Squares.fromName("a8")
        }
        assertEquals("Ra8#", position.san(mate))
        assertEquals(Status.Checkmate(Color.WHITE), position.makeMove(mate).status())
    }

    @Test
    fun detectsStalemate() {
        val position = Position.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertEquals(Status.Draw(DrawReason.STALEMATE), position.status())
    }

    @Test
    fun bareKingsAreDrawn() {
        assertEquals(
            Status.Draw(DrawReason.INSUFFICIENT_MATERIAL),
            Position.fromFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1").status(),
        )
        assertEquals(
            Status.Draw(DrawReason.INSUFFICIENT_MATERIAL),
            Position.fromFen("4k3/8/8/8/8/8/8/3BK3 w - - 0 1").status(),
        )
    }

    @Test
    fun sanDisambiguatesByFileThenRank() {
        // Knights on b1 and f1 both reach d2, so the file tells them apart.
        val twoKnights = Position.fromFen("4k3/8/8/8/8/8/8/1N3N1K w - - 0 1")
        val toD2 = twoKnights.legalMoves().first { it.to == Squares.fromName("d2") }
        assertEquals("Nbd2", twoKnights.san(toD2))

        // Rooks on a1 and a8 share a file, so only the rank distinguishes them.
        val twoRooks = Position.fromFen("R7/8/8/8/8/6k1/8/R5K1 w - - 0 1")
        val toA4 = twoRooks.legalMoves().first { it.to == Squares.fromName("a4") }
        assertEquals("R1a4", twoRooks.san(toA4))
    }

    @Test
    fun sanRendersPromotionAndCastling() {
        val queensOn = { fen: String ->
            val p = Position.fromFen(fen)
            p.san(
                p.legalMoves().first {
                    it.to == Squares.fromName("a8") && it.promotion == PieceType.QUEEN
                }
            )
        }
        // A new queen on a8 rakes the 8th rank, so this promotion arrives with check.
        assertEquals("a8=Q+", queensOn("4k3/P7/8/8/8/8/8/4K3 w - - 0 1"))
        assertEquals("a8=Q", queensOn("8/P7/8/8/8/4k3/8/4K3 w - - 0 1"))

        // The a1 rook also reaches c1, so ask for the king's move specifically.
        val castling = Position.fromFen("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1")
        val longCastle = castling.legalMoves().first {
            castling.isCastling(it) && it.to == Squares.fromName("c1")
        }
        assertEquals("O-O-O", castling.san(longCastle))
        assertEquals("Rc1", castling.san(Move(Squares.A1, Squares.fromName("c1"))))
    }

    @Test
    fun formatsAMoveListWithNumbers() {
        assertEquals("1. e4 e5 2. Nf3", formatMoveList(listOf("e4", "e5", "Nf3")))
    }
}
