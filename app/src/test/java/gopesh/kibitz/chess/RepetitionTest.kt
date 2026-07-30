package gopesh.kibitz.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The repetition key decides what "the same position" means. Getting it wrong is invisible in
 * normal play and then either reports draws that did not happen or misses ones that did.
 */
class RepetitionTest {

    private fun Position.play(vararg ucis: String): Position {
        var current = this
        for (uci in ucis) {
            val move = current.legalMoves().firstOrNull {
                it.uci.equals(uci, ignoreCase = true)
            } ?: error("$uci is not legal in ${current.fen}")
            current = current.makeMove(move)
        }
        return current
    }

    @Test
    fun theKeyIgnoresMoveCountersButNotThePosition() {
        val start = Position.start()
        // Knights out and back: the position returns, but the clocks have moved on.
        val returned = start.play("g1f3", "g8f6", "f3g1", "f6g8")

        assertNotEquals(
            "the FENs differ because the counters advanced",
            start.fen,
            returned.fen,
        )
        assertEquals(
            "yet it is the same position for repetition purposes",
            start.repetitionKey,
            returned.repetitionKey,
        )
    }

    @Test
    fun sideToMoveIsPartOfTheKey() {
        val whiteToMove = Position.start()
        val blackToMove = whiteToMove.play("g1f3")
        assertNotEquals(whiteToMove.repetitionKey, blackToMove.repetitionKey)
    }

    /** Losing the right to castle changes the position even when nothing has moved back. */
    @Test
    fun castlingRightsArePartOfTheKey() {
        val before = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        // King out and back: same placement, but both white castling rights are gone.
        val after = before.play("e1e2", "e8e7", "e2e1", "e7e8")

        assertEquals(
            "placement is identical",
            before.fen.split(' ').first(),
            after.fen.split(' ').first(),
        )
        assertNotEquals(
            "but the castling rights are not, so it is a different position",
            before.repetitionKey,
            after.repetitionKey,
        )
    }

    @Test
    fun enPassantAvailabilityIsPartOfTheKey() {
        val doublePush = Position.start().play("e2e4")
        val viaTwoSingles = Position.start().play("e2e3", "a7a6", "e3e4", "a6a5")
        // Same pawn on e4 either way, but only the double push leaves an ep square.
        assertNotEquals(doublePush.repetitionKey, viaTwoSingles.repetitionKey)
    }

    /** A three-time occurrence has to be countable from the key alone. */
    @Test
    fun aRepeatedPositionYieldsThreeIdenticalKeys()  {
        val start = Position.start()
        val keys = mutableListOf(start.repetitionKey)

        var current = start.play("g1f3", "g8f6", "f3g1", "f6g8")
        keys.add(current.repetitionKey)
        current = current.play("g1f3", "g8f6", "f3g1", "f6g8")
        keys.add(current.repetitionKey)

        assertEquals("all three occurrences share a key", 1, keys.distinct().size)
        assertEquals(3, keys.size)
    }

    @Test
    fun differentPositionsDoNotShareAKey() {
        val a = Position.start().play("e2e4")
        val b = Position.start().play("d2d4")
        assertNotEquals(a.repetitionKey, b.repetitionKey)
    }
}
