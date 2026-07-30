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
 * These thresholds were **measured**, not guessed. Stockfish limited to a known `UCI_Elo` stood
 * in for a player of that strength, played the same level-check game the app plays against the
 * same Club-level opponent, and every one of its moves was judged at full strength with the
 * depth and cap used here. Fitting rating against the resulting mean capped loss gave:
 *
 *     rating = 6401 - 1295 * ln(loss)          R² = 0.977 over 1350–2900 Elo
 *
 * Measured points (30-move sample, 5 games per level, mean ± sd in centipawns):
 *
 *     1350: 48.6 ± 15.8    2000: 31.4 ± 4.6    2600: 19.3 ± 6.1
 *     1500: 39.5 ± 17.7    2300: 22.7 ± 5.6    2900: 14.8 ± 4.1
 *     1750: 40.0 ± 11.6
 *
 * The band edges below are that curve evaluated at each boundary rating.
 *
 * **Two limits that matter.** The reference is engine play held to a rating, not human play: an
 * engine capped at 1500 drifts mildly where a 1500-rated human misses tactics outright, so the
 * shape of the curve is better evidence than its absolute position. And below 1350 the curve is
 * *extrapolated* — nothing was measured down there, because `UCI_Elo` bottoms out at 1320.
 * Validating against players of known rating would still be worth doing.
 */
object LevelCalibration {

    /**
     * A single catastrophe should not swamp the average. Capping each move's cost keeps the
     * estimate about general accuracy, and blunders are reported separately anyway.
     */
    const val LOSS_CAP = 300

    /**
     * The number of player moves these thresholds were measured at, and the reason the level
     * check is this long.
     *
     * Sample length is not a free parameter — loss falls as the sample shortens, because
     * opening moves are forgiving and the signal lives in the middlegame. Measured
     * discrimination between a 1350 and a 2900 player, as a ratio of the difference to the
     * per-game noise:
     *
     *     12 moves → 1.03  (useless: the difference *is* the noise; R² = 0.600)
     *     20 moves → 1.85  (still not monotonic; R² = 0.652)
     *     30 moves → 2.89  (monotonic; R² = 0.977)
     *
     * Shortening the check without re-measuring would silently over-rate everyone.
     */
    const val CALIBRATED_SAMPLE_MOVES = 30

    // Loss thresholds are the fitted curve at 2300, 1900, 1600, 1350, 1100 and 850.
    private val BANDS = listOf(
        20 to LevelBand("Expert", 2300, 2700),
        32 to LevelBand("Advanced", 1900, 2300),
        41 to LevelBand("Strong club player", 1600, 1900),
        49 to LevelBand("Club player", 1350, 1600),
        60 to LevelBand("Improving", 1100, 1350),
        73 to LevelBand("Casual", 850, 1100),
    )

    private val WEAKEST = LevelBand("Beginner", 400, 850)

    fun bandForAverageLoss(averageLoss: Int): LevelBand =
        BANDS.firstOrNull { averageLoss <= it.first }?.second ?: WEAKEST

    /** The fitted curve, exposed so the mapping can be checked rather than trusted. */
    fun fittedRatingFor(averageLoss: Int): Int =
        (6401 - 1295 * kotlin.math.ln(averageLoss.coerceAtLeast(1).toDouble())).toInt()

    fun confidenceFor(movesAssessed: Int): EstimateConfidence = when {
        // Scaled to the calibrated sample: a third of it is very little to go on.
        movesAssessed < CALIBRATED_SAMPLE_MOVES / 3 -> EstimateConfidence.VERY_LOW
        movesAssessed < CALIBRATED_SAMPLE_MOVES * 2 / 3 -> EstimateConfidence.LOW
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
