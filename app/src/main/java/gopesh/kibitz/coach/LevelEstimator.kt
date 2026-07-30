package gopesh.kibitz.coach

/** A rating range with a human label. Deliberately a range: a point estimate would lie. */
data class LevelBand(val label: String, val ratingLow: Int, val ratingHigh: Int) {
    val ratingText: String get() = "$ratingLow–$ratingHigh"
}

enum class EstimateConfidence(val label: String, val explanation: String) {
    VERY_LOW(
        "Very rough",
        "Only a handful of moves to go on. Play a full game to sharpen this.",
    ),
    LOW(
        "Rough",
        "Based on one short game, so treat it as a starting point, not a verdict.",
    ),
    MODERATE(
        "Provisional",
        "Based on one game. It will settle down as you play more.",
    ),
}

data class LevelEstimate(
    val band: LevelBand,
    val confidence: EstimateConfidence,
    val movesAssessed: Int,
    val averageLoss: Int,
    val blunders: Int,
    val mistakes: Int,
    val topMoveRate: Int,
    val costliestMove: MoveAssessment?,
    val strengths: List<String>,
    val weaknesses: List<String>,
)

/**
 * Maps playing accuracy onto a rating band.
 *
 * **This calibration is an assumption, not a measurement.** The bands below follow the
 * widely-reported inverse relationship between average centipawn loss and rating, but the
 * numbers a shallow evaluator produces are not on the same scale as the deep engine analysis
 * those figures come from. Everything tunable lives in this one object so it can be
 * re-fitted against real games once Stockfish is embedded — ideally against players whose
 * ratings are already known.
 */
object LevelCalibration {

    /**
     * A single catastrophe should not swamp the average. Capping each move's cost keeps the
     * estimate about general accuracy, and blunders are reported separately anyway.
     */
    const val LOSS_CAP = 300

    private val BANDS = listOf(
        20 to LevelBand("Advanced", 1900, 2300),
        35 to LevelBand("Strong club player", 1600, 1900),
        55 to LevelBand("Club player", 1350, 1600),
        80 to LevelBand("Improving", 1100, 1350),
        120 to LevelBand("Casual", 850, 1100),
    )

    private val WEAKEST = LevelBand("Beginner", 400, 850)

    fun bandForAverageLoss(averageLoss: Int): LevelBand =
        BANDS.firstOrNull { averageLoss <= it.first }?.second ?: WEAKEST

    fun confidenceFor(movesAssessed: Int): EstimateConfidence = when {
        movesAssessed < 6 -> EstimateConfidence.VERY_LOW
        movesAssessed < 12 -> EstimateConfidence.LOW
        // One game never earns more than "provisional", however many moves it ran to.
        else -> EstimateConfidence.MODERATE
    }
}

object LevelEstimator {

    fun estimate(assessments: List<MoveAssessment>): LevelEstimate {
        if (assessments.isEmpty()) {
            return LevelEstimate(
                band = LevelCalibration.bandForAverageLoss(Int.MAX_VALUE),
                confidence = EstimateConfidence.VERY_LOW,
                movesAssessed = 0,
                averageLoss = 0,
                blunders = 0,
                mistakes = 0,
                topMoveRate = 0,
                costliestMove = null,
                strengths = emptyList(),
                weaknesses = listOf("No moves were played, so there is nothing to judge yet."),
            )
        }

        val cappedLosses = assessments.map {
            it.centipawnLoss.coerceAtMost(LevelCalibration.LOSS_CAP)
        }
        val averageLoss = cappedLosses.sum() / cappedLosses.size
        val blunders = assessments.count { it.quality == MoveQuality.BLUNDER }
        val mistakes = assessments.count { it.quality == MoveQuality.MISTAKE }
        val topMoves = assessments.count { it.quality == MoveQuality.BEST }
        val topMoveRate = topMoves * 100 / assessments.size

        return LevelEstimate(
            band = LevelCalibration.bandForAverageLoss(averageLoss),
            confidence = LevelCalibration.confidenceFor(assessments.size),
            movesAssessed = assessments.size,
            averageLoss = averageLoss,
            blunders = blunders,
            mistakes = mistakes,
            topMoveRate = topMoveRate,
            costliestMove = assessments.maxByOrNull { it.centipawnLoss },
            strengths = strengthsFor(assessments, averageLoss, blunders, topMoveRate),
            weaknesses = weaknessesFor(assessments, averageLoss, blunders, mistakes),
        )
    }

    private fun strengthsFor(
        assessments: List<MoveAssessment>,
        averageLoss: Int,
        blunders: Int,
        topMoveRate: Int,
    ): List<String> = buildList {
        // Kept deliberately reachable: a real signal should be credited rather than falling
        // through to the generic line, which reads as though nothing good happened at all.
        if (topMoveRate >= 25) {
            add("Found the strongest move in $topMoveRate% of positions.")
        }
        if (blunders == 0) {
            add("No blunders — you never handed anything over outright.")
        }
        if (averageLoss <= 45) {
            add("Steady accuracy, with few wasted moves.")
        }
        val cleanRun = assessments.takeWhile { it.centipawnLoss <= 50 }.size
        if (cleanRun >= 3) {
            add("Your first $cleanRun moves were all sound — the opening is solid ground.")
        }
        if (isEmpty()) {
            add("You finished the game — that is the baseline we build from.")
        }
    }

    private fun weaknessesFor(
        assessments: List<MoveAssessment>,
        averageLoss: Int,
        blunders: Int,
        mistakes: Int,
    ): List<String> = buildList {
        if (blunders > 0) {
            val plural = if (blunders == 1) "blunder" else "blunders"
            add("$blunders $plural — moves that changed the result of the game.")
        }
        if (mistakes > 0) {
            add("$mistakes mistake${if (mistakes == 1) "" else "s"} that gave away real ground.")
        }
        if (averageLoss > 80) {
            add("Accuracy drifts move to move; the plan tends to change every turn.")
        }
        val lateSlip = assessments.drop(assessments.size / 2).count { it.centipawnLoss > 120 }
        if (lateSlip >= 2 && assessments.size >= 8) {
            add("Most of the damage came later in the game rather than at the start.")
        }
        if (isEmpty()) {
            add("Nothing clearly went wrong here. A longer game will find the edges.")
        }
    }
}
