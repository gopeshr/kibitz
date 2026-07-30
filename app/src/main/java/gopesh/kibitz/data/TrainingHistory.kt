package gopesh.kibitz.data

import android.content.Context
import gopesh.kibitz.chess.Color
import gopesh.kibitz.chess.DrawReason
import gopesh.kibitz.chess.Position
import gopesh.kibitz.chess.Status
import gopesh.kibitz.coach.LevelCalibration
import gopesh.kibitz.coach.Drill
import gopesh.kibitz.coach.LevelEstimate
import gopesh.kibitz.coach.MoveAssessment

/**
 * The player's training history.
 *
 * This is the substrate the coaching layer needs: without it, advice can only be about the game
 * just played. With it, advice can be about what this particular player keeps getting wrong.
 */
class TrainingHistory(private val dao: HistoryDao) {

    constructor(context: Context) : this(KibitzDatabase.get(context).historyDao())

    /**
     * Records a finished game. Assessments arrive with the position each move was played from,
     * so every stored move can later be replayed as a puzzle.
     */
    suspend fun recordGame(
        assessments: List<MoveAssessment>,
        fensBefore: List<String>,
        ucis: List<String>,
        estimate: LevelEstimate,
        playerIsWhite: Boolean,
        wasLevelCheck: Boolean,
        opponentLevel: String,
        engineId: String,
        finalPosition: Position,
        playedAt: Long,
    ): Long {
        val game = GameRecord(
            playedAt = playedAt,
            playerIsWhite = playerIsWhite,
            wasLevelCheck = wasLevelCheck,
            opponentLevel = opponentLevel,
            engineId = engineId,
            movesAssessed = assessments.size,
            averageLoss = estimate.averageLoss,
            blunders = estimate.blunders,
            bandLabel = estimate.band.label,
            ratingLow = estimate.band.ratingLow,
            ratingHigh = estimate.band.ratingHigh,
            result = resultOf(finalPosition),
        )

        val moves = assessments.mapIndexed { index, assessment ->
            MoveRecord(
                gameId = 0, // set by saveGame once the game row exists
                ply = index,
                moveNumber = assessment.moveNumber,
                san = assessment.san,
                uci = ucis.getOrElse(index) { "" },
                fenBefore = fensBefore.getOrElse(index) { "" },
                centipawnLoss = assessment.centipawnLoss,
                quality = assessment.quality.name,
                bestSan = assessment.bestSan,
                byFullStrengthEngine = assessment.byFullStrengthEngine,
            )
        }

        return dao.saveGame(game, moves)
    }

    suspend fun gamesPlayed(): Int = dao.gameCount()

    suspend fun recentGames(limit: Int = 20): List<GameRecord> = dao.recentGames(limit)

    suspend fun accuracy(): AccuracySummary = dao.accuracySummary(LevelCalibration.LOSS_CAP)

    /** Past mistakes bad enough to be worth drilling, worst first. */
    suspend fun drillCandidates(limit: Int = 20): List<MoveRecord> =
        dao.costliestMoves(minimumLoss = 250, limit = limit)

    /**
     * The next drills to practise, hardest-unsolved first, already rebuilt into positions.
     *
     * Records that cannot be turned into an answerable puzzle are dropped here rather than
     * being handed to the UI to fail on.
     */
    suspend fun drillQueue(limit: Int = 30): List<Drill> =
        dao.drillQueue(minimumLoss = DRILL_MINIMUM_LOSS, limit = limit)
            .mapNotNull { Drill.from(it) }

    suspend fun drillProgress(): DrillProgress = dao.drillProgress(DRILL_MINIMUM_LOSS)

    suspend fun recordDrillAttempt(moveId: Long, correct: Boolean, at: Long) {
        dao.insertDrillAttempt(DrillAttempt(moveId = moveId, attemptedAt = at, correct = correct))
    }

    /** Every move of a stored game, for reviewing it later. */
    suspend fun movesFor(gameId: Long): List<MoveRecord> = dao.movesFor(gameId)

    suspend fun qualityBreakdown(): List<QualityCount> = dao.qualityBreakdown()

    suspend fun accuracyTrend(): List<Int> = dao.accuracyTrend()

    private companion object {
        /**
         * Below this a "mistake" is not worth drilling: at 1.2 pawns something concrete went
         * wrong, whereas a 0.4 inaccuracy usually has no single findable answer.
         */
        const val DRILL_MINIMUM_LOSS = 120
    }

    private fun resultOf(position: Position): String = when (val status = position.status()) {
        is Status.Checkmate -> if (status.winner == Color.WHITE) "1-0" else "0-1"
        is Status.Draw -> when (status.reason) {
            DrawReason.STALEMATE,
            DrawReason.FIFTY_MOVE,
            DrawReason.INSUFFICIENT_MATERIAL,
            DrawReason.THREEFOLD_REPETITION,
            -> "1/2-1/2"
        }
        is Status.Ongoing -> "unfinished"
    }
}
