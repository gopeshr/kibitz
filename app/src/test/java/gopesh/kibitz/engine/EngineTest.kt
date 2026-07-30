package gopesh.kibitz.engine

import gopesh.kibitz.chess.Position
import gopesh.kibitz.chess.Squares
import gopesh.kibitz.chess.san
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    private fun bestSan(fen: String, depth: Int = 3): String {
        val position = Position.fromFen(fen)
        val move = Search(depth = depth).analyze(position).bestMove
        assertNotNull("no move found for $fen", move)
        return position.san(move!!)
    }

    @Test
    fun startingPositionIsBalanced() {
        assertEquals(0, Evaluation.evaluate(Position.start()))
    }

    @Test
    fun developingAPieceBeatsLeavingItHome() {
        val start = Position.start()
        val afterKnight = start.makeMove(
            start.legalMoves().first {
                it.from == Squares.fromName("g1") && it.to == Squares.fromName("f3")
            }
        )
        // Evaluation is from White's perspective, so a developed knight should read higher.
        assertTrue(
            "expected a developed knight to score above the start position",
            Evaluation.evaluate(afterKnight) > Evaluation.evaluate(start),
        )
    }

    @Test
    fun findsMateInOne() {
        assertEquals("Ra8#", bestSan("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1"))
    }

    @Test
    fun reportsAMateScoreWhenMateIsForced() {
        val position = Position.fromFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")
        val analysis = Search(depth = 3).analyze(position)
        assertTrue(
            "expected a mate-level score, got ${analysis.score}",
            analysis.score > Evaluation.MATE - 100,
        )
    }

    @Test
    fun takesAFreeQueen() {
        assertEquals("Bxd5", bestSan("4k3/8/8/3q4/4B3/8/8/4K3 w - - 0 1"))
    }

    /** Quiescence exists for this: the pawn on d5 is defended twice over. */
    @Test
    fun declinesAPawnThatCostsTheQueen() {
        val fen = "4k3/8/2p1p3/3p4/8/8/8/3QK3 w - - 0 1"
        val position = Position.fromFen(fen)
        val analysis = Search(depth = 3).analyze(position)
        val queenGrab = position.legalMoves().first {
            it.from == Squares.fromName("d1") && it.to == Squares.fromName("d5")
        }
        assertTrue(
            "Qxd5 should not be the choice; engine picked ${position.san(analysis.bestMove!!)}",
            analysis.bestMove != queenGrab,
        )
        // And it should understand the grab is bad, not merely rank it second.
        assertTrue(
            "Qxd5 should score far below best",
            analysis.score - analysis.scoreOf(queenGrab)!! > 400,
        )
    }

    @Test
    fun scoresEveryLegalMoveAtTheRoot() {
        val position = Position.start()
        val analysis = Search(depth = 2).analyze(position)
        assertEquals(position.legalMoves().size, analysis.rootMoves.size)
        // Sorted best first, which the coach relies on.
        val scores = analysis.rootMoves.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun theFallbackEngineAlwaysPlaysALegalMove() = kotlinx.coroutines.runBlocking {
        var position = Position.start()
        val engine = KotlinEngine()
        repeat(6) {
            val move = engine.chooseMove(position, OpponentLevel.CLUB)
            assertNotNull("engine had no move at ${position.fen}", move)
            assertTrue("engine returned an illegal move $move", move in position.legalMoves())
            position = position.makeMove(move!!)
        }
    }

    /** Weak levels must stay legal, not merely bad. */
    @Test
    fun everyLevelPlaysLegally() = kotlinx.coroutines.runBlocking {
        val position = Position.fromFen(
            "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"
        )
        for (level in OpponentLevel.entries) {
            val move = KotlinEngine().chooseMove(position, level)
            assertTrue("$level produced $move", move in position.legalMoves())
        }
    }

    /** The fallback must never claim to be as good as Stockfish. */
    @Test
    fun theFallbackDoesNotClaimFullStrength() {
        assertTrue(!KotlinEngine().isFullStrength)
    }

    @Test
    fun levelsMapOntoNearbyRatings() {
        assertEquals(OpponentLevel.BEGINNER, OpponentLevel.nearest(1300))
        assertEquals(OpponentLevel.CLUB, OpponentLevel.nearest(1750))
        assertEquals(OpponentLevel.EXPERT, OpponentLevel.nearest(2600))
        // "Full strength" has no rating, so it can never be picked as a nearest match.
        assertTrue(OpponentLevel.nearest(9_000).uciElo != null)
    }

    /** A move has to come back fast enough that a person does not feel the app stall. */
    @Test
    fun searchStaysWithinAnInteractiveBudget() {
        val position = Position.fromFen(
            "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"
        )
        val search = Search(depth = 3)
        val elapsed = System.currentTimeMillis().let { start ->
            search.analyze(position)
            System.currentTimeMillis() - start
        }
        println("depth 3 midgame: ${search.lastNodeCount} nodes in ${elapsed}ms")
        assertTrue("depth-3 search took ${elapsed}ms", elapsed < 4_000)
    }
}
