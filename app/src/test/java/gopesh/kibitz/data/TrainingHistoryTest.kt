package gopesh.kibitz.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import gopesh.kibitz.chess.Color
import gopesh.kibitz.chess.Position
import gopesh.kibitz.coach.EstimateConfidence
import gopesh.kibitz.coach.LevelBand
import gopesh.kibitz.coach.LevelEstimate
import gopesh.kibitz.coach.MoveAssessment
import gopesh.kibitz.coach.MoveQuality
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the history layer against a real in-memory Room database via Robolectric, so the
 * SQL is actually executed rather than assumed to be correct.
 */
@RunWith(RobolectricTestRunner::class)
class TrainingHistoryTest {

    private lateinit var database: KibitzDatabase
    private lateinit var history: TrainingHistory

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KibitzDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        history = TrainingHistory(database.historyDao())
    }

    @After
    fun tearDown() = database.close()

    private fun assessment(
        loss: Int,
        number: Int = 1,
        fullStrength: Boolean = true,
    ) = MoveAssessment(
        moveNumber = number,
        side = Color.WHITE,
        san = "Nf3",
        centipawnLoss = loss,
        quality = MoveQuality.forLoss(loss),
        bestSan = "e4",
        byFullStrengthEngine = fullStrength,
    )

    private fun estimate(average: Int, blunders: Int) = LevelEstimate(
        band = LevelBand("Club player", 1350, 1600),
        confidence = EstimateConfidence.LOW,
        movesAssessed = 3,
        averageLoss = average,
        blunders = blunders,
        mistakes = 0,
        topMoveRate = 33,
        costliestMove = null,
        strengths = emptyList(),
        weaknesses = emptyList(),
    )

    private suspend fun record(
        losses: List<Int>,
        fullStrength: Boolean = true,
        fens: List<String> = losses.map { Position.START_FEN },
    ): Long = history.recordGame(
        assessments = losses.mapIndexed { i, l -> assessment(l, i + 1, fullStrength) },
        fensBefore = fens,
        ucis = losses.map { "e2e4" },
        estimate = estimate(losses.average().toInt(), losses.count { it > 250 }),
        playerIsWhite = true,
        wasLevelCheck = true,
        opponentLevel = "CLUB",
        engineId = "stockfish-18",
        finalPosition = Position.start(),
        playedAt = 1_000L,
    )

    @Test
    fun storesAGameWithItsMoves() = runBlocking {
        val id = record(listOf(5, 140, 400))

        assertEquals(1, history.gamesPlayed())
        val moves = database.historyDao().movesFor(id)
        assertEquals(3, moves.size)
        assertEquals(listOf(0, 1, 2), moves.map { it.ply })
        assertEquals(listOf(5, 140, 400), moves.map { it.centipawnLoss })
        assertEquals("BLUNDER", moves.last().quality)
        // Every move must carry the position it came from, or it can never become a drill.
        assertTrue(moves.all { it.fenBefore.isNotBlank() })
    }

    @Test
    fun movesAreLinkedToTheirOwnGame() = runBlocking {
        val first = record(listOf(10, 20))
        val second = record(listOf(300))

        assertEquals(2, database.historyDao().movesFor(first).size)
        assertEquals(1, database.historyDao().movesFor(second).size)
        assertEquals(2, history.gamesPlayed())
    }

    @Test
    fun deletingAGameRemovesItsMoves() = runBlocking {
        val id = record(listOf(10, 20, 30))
        assertEquals(3, database.historyDao().movesFor(id).size)
        database.historyDao().deleteAllGames()
        // The foreign key cascade must clean up, or moves accumulate forever.
        assertEquals(0, database.historyDao().movesFor(id).size)
        assertEquals(0, history.gamesPlayed())
    }

    @Test
    fun surfacesTheCostliestMovesAsDrillCandidates() = runBlocking {
        record(listOf(10, 300, 50))
        record(listOf(900, 20))

        val drills = history.drillCandidates()
        assertEquals(listOf(900, 300), drills.map { it.centipawnLoss })
        assertTrue("a drill needs a position", drills.all { it.fenBefore.isNotBlank() })
    }

    /** The whole point of the flag: weak-engine numbers must not pollute the trend. */
    @Test
    fun accuracyIgnoresMovesJudgedByTheFallbackEngine() = runBlocking {
        record(listOf(10, 20, 30), fullStrength = true)
        record(listOf(900, 900, 900), fullStrength = false)

        val accuracy = history.accuracy()
        assertEquals(3, accuracy.movesJudged)
        assertEquals(20.0, accuracy.averageLoss, 0.01)
        assertEquals(0, accuracy.blunders)

        // ...and the fallback game's moves must not appear as drills either.
        assertTrue(history.drillCandidates().isEmpty())
    }

    @Test
    fun accuracyCapsRunawayLossesTheSameWayTheEstimatorDoes() = runBlocking {
        record(listOf(100, 4_000))
        // 4000 is capped to 300, so the average is 200 rather than 2050.
        assertEquals(200.0, history.accuracy().averageLoss, 0.01)
    }

    @Test
    fun countsMoveQualities() = runBlocking {
        record(listOf(5, 8, 200, 900))
        val byQuality = history.qualityBreakdown().associate { it.quality to it.count }
        assertEquals(2, byQuality["BEST"])
        assertEquals(1, byQuality["MISTAKE"])
        assertEquals(1, byQuality["BLUNDER"])
    }

    @Test
    fun reportsAccuracyOverTimeOldestFirst() = runBlocking {
        record(listOf(90, 90))
        record(listOf(40, 40))
        record(listOf(10, 10))
        assertEquals(listOf(90, 40, 10), history.accuracyTrend())
    }

    @Test
    fun handlesAnEmptyHistory() = runBlocking {
        assertEquals(0, history.gamesPlayed())
        assertEquals(0, history.accuracy().movesJudged)
        assertEquals(0.0, history.accuracy().averageLoss, 0.001)
        assertTrue(history.recentGames().isEmpty())
        assertTrue(history.drillCandidates().isEmpty())
    }

    // ------------------------------------------------------------------ drills

    /**
     * Room validates a hand-written migration's DDL against what it would have generated, and
     * refuses to open the database if they differ. Building the database here with the drill
     * table in the schema is what proves the migration SQL is right.
     */
    @Test
    fun theDrillTableMatchesWhatRoomExpects() = runBlocking {
        // Reaching it at all means the schema validated on open.
        val progress = history.drillProgress()
        assertEquals(0, progress.available)
        assertEquals(0, progress.attempted)
        assertEquals(0, progress.solved)
    }

    @Test
    fun onlyMistakesWithAKnownAnswerAreDrillable() = runBlocking {
        val id = history.recordGame(
            assessments = listOf(
                assessment(400).copy(bestSan = "Qxf7"),
                // Costly, but nothing better was recorded, so it cannot be marked right.
                assessment(500).copy(bestSan = null),
                // A known answer, but too small a slip to be worth drilling.
                assessment(30).copy(bestSan = "e4"),
            ),
            fensBefore = List(3) { Position.START_FEN },
            ucis = List(3) { "e2e4" },
            estimate = estimate(310, 2),
            playerIsWhite = true,
            wasLevelCheck = false,
            opponentLevel = "CLUB",
            engineId = "stockfish-18",
            finalPosition = Position.start(),
            playedAt = 1L,
        )
        assertTrue(id > 0)
        assertEquals(1, history.drillProgress().available)
    }

    @Test
    fun attemptsAreRecordedAndCounted() = runBlocking {
        record(listOf(400))
        val moveId = database.historyDao().recentGames(1).first()
            .let { database.historyDao().movesFor(it.id) }.first().id

        history.recordDrillAttempt(moveId, correct = false, at = 10L)
        assertEquals(1, history.drillProgress().attempted)
        assertEquals(0, history.drillProgress().solved)

        history.recordDrillAttempt(moveId, correct = true, at = 20L)
        // Two attempts at one position still counts as one position attempted, now solved.
        assertEquals(1, history.drillProgress().attempted)
        assertEquals(1, history.drillProgress().solved)
    }

    /** Unsolved mistakes must come first, or practice repeats what is already known. */
    @Test
    fun unsolvedDrillsAreQueuedAhead() = runBlocking {
        record(listOf(500, 200))
        val moves = database.historyDao()
            .movesFor(database.historyDao().recentGames(1).first().id)
        val worst = moves.maxBy { it.centipawnLoss }

        // Worst first while nothing is solved.
        assertEquals(worst.id, database.historyDao().drillQueue(120, 10).first().id)

        history.recordDrillAttempt(worst.id, correct = true, at = 30L)

        val queue = database.historyDao().drillQueue(120, 10)
        assertEquals("the solved one sinks to the bottom", worst.id, queue.last().id)
        assertEquals(2, queue.size)
    }
}
