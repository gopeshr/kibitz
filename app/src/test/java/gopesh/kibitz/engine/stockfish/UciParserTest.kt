package gopesh.kibitz.engine.stockfish

import gopesh.kibitz.chess.Position
import gopesh.kibitz.chess.Squares
import gopesh.kibitz.chess.san
import gopesh.kibitz.engine.Evaluation
import gopesh.kibitz.engine.EvalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing Stockfish's output is pure text handling, so it is tested here on the JVM even though
 * the engine itself only runs on a device. These are the failures that would otherwise be
 * invisible: a score read from the wrong iteration, or a sign flipped somewhere.
 */
class UciParserTest {

    @Test
    fun readsScoreAndBestMove() {
        val info = UciParser.parse(
            listOf(
                "info depth 1 seldepth 1 score cp 24 nodes 20 pv e2e4",
                "info depth 8 seldepth 10 score cp 31 nodes 9000 pv e2e4 e7e5",
                "bestmove e2e4 ponder e7e5",
            )
        )
        assertEquals(31, info.scoreCentipawns)
        assertEquals(8, info.depth)
        assertEquals("e2e4", info.bestMoveUci)
    }

    /** Stockfish reports every iteration; only the deepest one is the real answer. */
    @Test
    fun keepsTheDeepestIterationNotTheLast() {
        val info = UciParser.parse(
            listOf(
                "info depth 12 score cp 150 pv d2d4",
                // A later line at a shallower depth must not overwrite the deeper score.
                "info depth 3 score cp -40 pv a2a3",
                "bestmove d2d4",
            )
        )
        assertEquals(150, info.scoreCentipawns)
        assertEquals(12, info.depth)
    }

    /** Progress lines carry no score and must not wipe out a good one. */
    @Test
    fun ignoresLinesWithoutAScore() {
        val info = UciParser.parse(
            listOf(
                "info depth 10 score cp 88 pv g1f3",
                "info depth 11 currmove b1c3 currmovenumber 2",
                "info string NNUE evaluation using nn-c288c895ea92.nnue",
                "bestmove g1f3",
            )
        )
        assertEquals(88, info.scoreCentipawns)
        assertEquals("g1f3", info.bestMoveUci)
    }

    @Test
    fun mateScoresBecomeLargeSignedValues() {
        val mating = UciParser.parse(listOf("info depth 20 score mate 3 pv h5f7", "bestmove h5f7"))
        assertEquals(3, mating.scoreMate)
        assertTrue("expected a mate-level score, got ${mating.score}", mating.score > Evaluation.MATE - 100)

        val mated = UciParser.parse(listOf("info depth 20 score mate -2 pv a1a2", "bestmove a1a2"))
        assertEquals(-2, mated.scoreMate)
        assertTrue("expected a losing mate score, got ${mated.score}", mated.score < -Evaluation.MATE + 100)
    }

    /** A mate score must survive the trip through the bar's snapshot conversion. */
    @Test
    fun mateScoresSurviveIntoTheEvalBar()
    {
        val info = UciParser.parse(listOf("info depth 20 score mate 2 pv h5f7", "bestmove h5f7"))
        val whiteToMove = EvalSnapshot.from(info.score, whiteToMove = true)
        assertEquals("M2", whiteToMove.label)
        assertEquals(1f, whiteToMove.whiteShare, 0.001f)

        val blackToMove = EvalSnapshot.from(info.score, whiteToMove = false)
        assertEquals("−M2", blackToMove.label)
    }

    @Test
    fun handlesNoLegalMove() {
        val info = UciParser.parse(listOf("info depth 0 score mate 0", "bestmove (none)"))
        assertNull(info.bestMoveUci)
    }

    @Test
    fun handlesEmptyOutputWithoutCrashing() {
        val info = UciParser.parse(emptyList())
        assertNull(info.bestMoveUci)
        assertNull(info.scoreCentipawns)
        assertEquals(0, info.score)
    }

    @Test
    fun promotionMovesResolveToTheRightPiece() {
        val position = Position.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        val queening = position.moveFromUci("a7a8q")
        assertEquals(Squares.fromName("a8"), queening?.to)
        assertEquals("a8=Q+", queening?.let { position.san(it) })

        // A bare from-to without the promotion letter must not silently pick a piece.
        assertNull(position.moveFromUci("a7a8"))
    }

    @Test
    fun unknownMoveStringsResolveToNull() {
        assertNull(Position.start().moveFromUci("e2e5"))
        assertNull(Position.start().moveFromUci("garbage"))
    }
}
