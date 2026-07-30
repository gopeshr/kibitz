package gopesh.kibitz.engine

import gopesh.kibitz.chess.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalSnapshotTest {

    @Test
    fun deadLevelSitsExactlyMidBar() {
        assertEquals(0.5f, EvalSnapshot(0).whiteShare, 0.001f)
        assertEquals("0.0", EvalSnapshot(0).label)
    }

    @Test
    fun scoresAreConvertedToWhitesPointOfView() {
        // The same +200 means opposite things depending on who is to move.
        assertEquals(200, EvalSnapshot.from(200, whiteToMove = true).whiteCentipawns)
        assertEquals(-200, EvalSnapshot.from(200, whiteToMove = false).whiteCentipawns)
    }

    @Test
    fun theBarIsSymmetricAroundLevel() {
        for (cp in intArrayOf(50, 100, 300, 900)) {
            val ahead = EvalSnapshot(cp).whiteShare
            val behind = EvalSnapshot(-cp).whiteShare
            assertEquals("±$cp should mirror", 1f, ahead + behind, 0.001f)
        }
    }

    @Test
    fun shareRisesWithTheScoreButNeverSaturates() {
        val shares = intArrayOf(0, 100, 200, 500, 1500).map { EvalSnapshot.shareFor(it) }
        assertEquals(shares.sorted(), shares)
        assertTrue("a huge lead should still be below full", shares.last() < 1f)
        assertTrue("all shares stay in range", shares.all { it in 0f..1f })
    }

    /** The reason for the curve: a one-pawn edge has to be visible, not a rounding error. */
    @Test
    fun aSinglePawnIsClearlyVisible() {
        val share = EvalSnapshot.shareFor(100)
        assertTrue("one pawn read as $share, expected a noticeable shift", share > 0.55f)
        assertTrue("one pawn should not look winning, read as $share", share < 0.65f)
    }

    @Test
    fun labelsFormatPawnsToOneDecimal() {
        assertEquals("+1.2", EvalSnapshot(123).label)
        assertEquals("−0.8", EvalSnapshot(-84).label)
        assertEquals("+0.1", EvalSnapshot(5).label)
        assertEquals("+9.9", EvalSnapshot(990).label)
    }

    @Test
    fun mateScoresBecomeMoveCountsAndFillTheBar() {
        val whiteMates = EvalSnapshot.from(Evaluation.MATE - 3, whiteToMove = true)
        assertEquals("M2", whiteMates.label)
        assertEquals(1f, whiteMates.whiteShare, 0.001f)

        val blackMates = EvalSnapshot.from(Evaluation.MATE - 3, whiteToMove = false)
        assertEquals("−M2", blackMates.label)
        assertEquals(0f, blackMates.whiteShare, 0.001f)
    }

    @Test
    fun finishedGamesShowAResultRatherThanAForecast() {
        assertEquals("1–0", EvalSnapshot.checkmate(whiteWon = true).label)
        assertEquals("0–1", EvalSnapshot.checkmate(whiteWon = false).label)
        assertEquals("½–½", EvalSnapshot.drawn().label)
        assertEquals(1f, EvalSnapshot.checkmate(whiteWon = true).whiteShare, 0.001f)
        assertEquals(0.5f, EvalSnapshot.drawn().whiteShare, 0.001f)
    }

    /** End to end: a real search on a lopsided position must point the right way. */
    @Test
    fun searchAndSnapshotAgreeOnWhoIsWinning() {
        // White is a whole queen up.
        val whiteAhead = Position.fromFen("4k3/8/8/8/8/8/8/3QK3 w - - 0 1")
        val whiteScore = Search(depth = 2).analyze(whiteAhead).score
        val whiteSnap = EvalSnapshot.from(whiteScore, whiteToMove = true)
        assertTrue("expected White ahead, got ${whiteSnap.label}", whiteSnap.whiteCentipawns > 300)
        assertTrue(whiteSnap.whiteShare > 0.6f)

        // Same position mirrored: Black a queen up, Black to move.
        val blackAhead = Position.fromFen("3qk3/8/8/8/8/8/8/4K3 b - - 0 1")
        val blackScore = Search(depth = 2).analyze(blackAhead).score
        val blackSnap = EvalSnapshot.from(blackScore, whiteToMove = false)
        assertTrue("expected Black ahead, got ${blackSnap.label}", blackSnap.whiteCentipawns < -300)
        assertTrue(blackSnap.whiteShare < 0.4f)
    }

    @Test
    fun graduationsAreOrderedAndInsideTheBar() {
        val shares = EvalSnapshot.LEVELS.map { EvalSnapshot.shareFor(it) }
        assertEquals(shares.sorted(), shares)
        assertTrue(shares.all { it > 0f && it < 1f })
    }
}
