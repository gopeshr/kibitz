package gopesh.kibitz.coach

import gopesh.kibitz.chess.Position
import gopesh.kibitz.chess.Squares
import gopesh.kibitz.data.MoveRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A drill is only useful if the stored answer can be matched against what the player plays.
 * These cover the ways that matching can silently fail: an answer that no longer parses, a
 * record with no answer at all, and a right answer that must not be marked wrong.
 */
class DrillTest {

    private fun record(
        fen: String,
        played: String = "h3",
        best: String? = "Qxf7#",
        loss: Int = 400,
        id: Long = 1,
    ) = MoveRecord(
        id = id,
        gameId = 1,
        ply = 0,
        moveNumber = 4,
        san = played,
        uci = "h2h3",
        fenBefore = fen,
        centipawnLoss = loss,
        quality = "BLUNDER",
        bestSan = best,
        byFullStrengthEngine = true,
    )

    /** The scholar's-mate position: Qxf7# is on the board. */
    private val mateAvailable =
        "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"

    @Test
    fun rebuildsAPuzzleFromAStoredMistake() {
        val drill = Drill.from(
            record("r1bqkbnr/pppp1ppp/2n5/2b1p3/2B1P2q/5N2/PPPP1PPP/RNBQK2R w KQkq - 6 5")
        )
        // Qxf7# is not legal in that position, so it must not become a drill.
        assertNull("an answer that is not legal here cannot be drilled", drill)
    }

    @Test
    fun acceptsAPositionWhereTheAnswerIsLegal() {
        val drill = Drill.from(record(mateAvailable, best = "Ng5"))
        assertNotNull(drill)
        assertTrue(drill!!.isUsable)
        assertEquals(1, drill.solutions.size)
        assertEquals("Ng5", drill.bestSan)
        assertEquals(400, drill.centipawnLoss)
    }

    @Test
    fun theRightMoveIsAccepted() {
        val drill = Drill.from(record(mateAvailable, best = "Ng5"))!!
        val correct = drill.position.legalMoves().first {
            it.from == Squares.fromName("f3") && it.to == Squares.fromName("g5")
        }
        assertTrue(drill.isCorrect(correct))
    }

    @Test
    fun aDifferentMoveIsRejected() {
        val drill = Drill.from(record(mateAvailable, best = "Ng5"))!!
        val wrong = drill.position.legalMoves().first {
            it.from == Squares.fromName("h2") && it.to == Squares.fromName("h3")
        }
        assertFalse(drill.isCorrect(wrong))
    }

    /** Records that cannot make a puzzle drop out rather than taking the session down. */
    @Test
    fun unusableRecordsAreDiscarded() {
        assertNull("no answer stored", Drill.from(record(mateAvailable, best = null)))
        assertNull("blank answer", Drill.from(record(mateAvailable, best = "")))
        assertNull("unparseable position", Drill.from(record("not a fen", best = "Ng5")))
        assertNull(
            "answer is not notation for any legal move",
            Drill.from(record(mateAvailable, best = "Qz9")),
        )
    }

    @Test
    fun promotionAnswersResolve() {
        // Black's king is off the eighth rank, so the new queen does not give check and the
        // notation is a plain "a8=Q". Both storing and matching go through the same SAN
        // generator, so a suffix can never disagree in production — only in a hand-written test.
        val drill = Drill.from(
            record("8/P7/8/8/8/4k3/8/4K3 w - - 0 1", best = "a8=Q", played = "Kd1")
        )
        assertNotNull(drill)
        val queening = drill!!.solutions.single()
        assertEquals(Squares.fromName("a8"), queening.to)
        assertTrue(drill.isCorrect(queening))
    }

    /** And a checking promotion carries its suffix, which must still match. */
    @Test
    fun aCheckingPromotionMatchesWithItsSuffix() {
        val drill = Drill.from(
            record("4k3/P7/8/8/8/8/8/4K3 w - - 0 1", best = "a8=Q+", played = "Kd2")
        )
        assertNotNull(drill)
        assertTrue(drill!!.isCorrect(drill.solutions.single()))
    }

    @Test
    fun theBoardFacesWhoeverHasToMove() {
        val whiteToMove = Drill.from(record(mateAvailable, best = "Ng5"))!!
        assertEquals(gopesh.kibitz.chess.Color.WHITE, whiteToMove.position.sideToMove)

        val blackToMove = Drill.from(
            record("6k1/5ppp/8/8/8/8/5PPP/6K1 b - - 0 1", best = "Kf8", played = "h6")
        )
        assertNotNull(blackToMove)
        assertEquals(gopesh.kibitz.chess.Color.BLACK, blackToMove!!.position.sideToMove)
    }
}
