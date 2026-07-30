package gopesh.kibitz

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import gopesh.kibitz.chess.Color
import gopesh.kibitz.chess.DrawReason
import gopesh.kibitz.chess.Squares
import gopesh.kibitz.chess.Status
import gopesh.kibitz.engine.OpponentLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Threefold repetition is counted by the game rather than the position, so the counting itself
 * needs testing: the bookkeeping has to survive undo, and a drawn game has to stop accepting
 * moves even though legal moves still exist on the board.
 *
 * Runs in free play, where both sides are tapped by hand, so no engine is involved.
 */
@RunWith(RobolectricTestRunner::class)
class GameRepetitionTest {

    private fun newGame(): GameViewModel =
        GameViewModel(ApplicationProvider.getApplicationContext<Application>()).apply {
            newGame()
        }

    /** Taps a move by squares, the way the board does. */
    private fun GameViewModel.move(from: String, to: String) {
        onSquareTap(Squares.fromName(from))
        onSquareTap(Squares.fromName(to))
    }

    /** Knights out and back for both sides returns the position exactly. */
    private fun GameViewModel.shuffleOnce() {
        move("g1", "f3")
        move("g8", "f6")
        move("f3", "g1")
        move("f6", "g8")
    }

    @Test
    fun theStartingPositionCountsAsTheFirstOccurrence() {
        val game = newGame()
        // One shuffle brings it back a second time, which is not yet a draw.
        game.shuffleOnce()
        assertTrue(game.status is Status.Ongoing)
        assertFalse(game.isGameOver)
    }

    @Test
    fun theThirdOccurrenceIsADraw() {
        val game = newGame()
        game.shuffleOnce()
        game.shuffleOnce()

        assertEquals(Status.Draw(DrawReason.THREEFOLD_REPETITION), game.status)
        assertTrue(game.isGameOver)
    }

    /**
     * A drawn-by-repetition position still has legal moves, so input has to be refused on the
     * strength of the result rather than on there being nothing to play.
     */
    @Test
    fun noMoreMovesAreAcceptedOnceDrawn() {
        val game = newGame()
        game.shuffleOnce()
        game.shuffleOnce()
        val pliesAtDraw = game.plyCount
        assertTrue("legal moves still exist", game.legalMoves.isNotEmpty())

        game.move("e2", "e4")

        assertEquals("the board must not move on", pliesAtDraw, game.plyCount)
    }

    /** Undo has to give the sighting back, or the draw reappears a move too early. */
    @Test
    fun undoTakesBackARepetitionCount() {
        val game = newGame()
        game.shuffleOnce()
        game.shuffleOnce()
        assertTrue(game.isGameOver)

        game.undo()

        assertTrue("rewinding must un-draw the game", game.status is Status.Ongoing)
        assertFalse(game.isGameOver)

        // Replaying the same move reaches the third occurrence again.
        game.move("f6", "g8")
        assertEquals(Status.Draw(DrawReason.THREEFOLD_REPETITION), game.status)
    }

    @Test
    fun startingANewGameForgetsPreviousRepetitions() {
        val game = newGame()
        game.shuffleOnce()
        game.shuffleOnce()
        assertTrue(game.isGameOver)

        game.newGame()

        assertTrue(game.status is Status.Ongoing)
        assertEquals(0, game.plyCount)
        // And the counting starts over rather than resuming where it left off.
        game.shuffleOnce()
        assertTrue(game.status is Status.Ongoing)
    }

    @Test
    fun anIrreversibleMoveBreaksTheRepetition() {
        val game = newGame()
        game.shuffleOnce()
        // A pawn move can never be undone, so the position can never recur.
        game.move("e2", "e4")
        game.move("e7", "e5")
        game.shuffleOnce()

        assertTrue(
            "the position after the pawn moves has only occurred twice",
            game.status is Status.Ongoing,
        )
    }

    // -------------------------------------------------------------- resignation

    /**
     * Resignation is a fact about the game, not the position, so the board is left perfectly
     * playable — which is exactly why the outcome has to override rather than be derived.
     */
    @Test
    fun resigningEndsTheGameAsALoss() {
        val game = newGame()
        game.startGame(OpponentLevel.BEGINNER, playerIsWhite = true)
        game.move("e2", "e4")

        game.resign()

        assertEquals(Status.Resigned(winner = Color.BLACK), game.status)
        assertTrue(game.isGameOver)
        assertTrue("legal moves still exist on the board", game.legalMoves.isNotEmpty())
    }

    @Test
    fun resigningAsBlackHandsTheGameToWhite() {
        val game = newGame()
        game.startGame(OpponentLevel.BEGINNER, playerIsWhite = false)
        game.resign()
        assertEquals(Status.Resigned(winner = Color.WHITE), game.status)
    }

    @Test
    fun noMoreMovesAreAcceptedAfterResigning() {
        val game = newGame()
        game.startGame(OpponentLevel.BEGINNER, playerIsWhite = true)
        game.move("e2", "e4")
        game.resign()
        val pliesAtResignation = game.plyCount

        game.move("d2", "d4")

        assertEquals(pliesAtResignation, game.plyCount)
    }

    /** Nothing to give up in free play, and nothing to give up before a move is made. */
    @Test
    fun resignIsOnlyOfferedWhenItMeansSomething() {
        val freePlay = newGame()
        assertFalse("no opponent to resign to", freePlay.canResign)

        val vsEngine = newGame()
        vsEngine.startGame(OpponentLevel.BEGINNER, playerIsWhite = true)
        assertFalse("nothing played yet", vsEngine.canResign)

        vsEngine.move("e2", "e4")
        assertTrue(vsEngine.canResign)

        vsEngine.resign()
        assertFalse("already over", vsEngine.canResign)
    }

    /** A game already decided on the board cannot be re-decided by giving up. */
    @Test
    fun resigningAfterCheckmateChangesNothing() {
        val game = newGame()
        // Fool's mate, both sides by hand, so the position itself is terminal.
        game.move("f2", "f3"); game.move("e7", "e5")
        game.move("g2", "g4"); game.move("d8", "h4")
        assertEquals(Status.Checkmate(Color.BLACK), game.status)

        game.resign()

        assertEquals("the mate stands", Status.Checkmate(Color.BLACK), game.status)
    }

    @Test
    fun aNewGameClearsTheResignation() {
        val game = newGame()
        game.startGame(OpponentLevel.BEGINNER, playerIsWhite = true)
        game.move("e2", "e4")
        game.resign()
        assertTrue(game.isGameOver)

        game.newGame()

        assertTrue(game.status is Status.Ongoing)
        assertFalse(game.isGameOver)
    }
}
