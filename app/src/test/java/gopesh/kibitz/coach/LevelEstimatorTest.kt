package gopesh.kibitz.coach

import gopesh.kibitz.chess.Color
import gopesh.kibitz.chess.Position
import gopesh.kibitz.chess.Squares
import gopesh.kibitz.engine.KotlinEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelEstimatorTest {

    private fun moves(vararg losses: Int): List<MoveAssessment> =
        losses.mapIndexed { index, loss ->
            MoveAssessment(
                moveNumber = index + 1,
                side = Color.WHITE,
                san = "Nf3",
                centipawnLoss = loss,
                quality = MoveQuality.forLoss(loss),
                bestSan = if (loss > 10) "e4" else null,
            )
        }

    @Test
    fun accuratePlayLandsInAHighBand() {
        val estimate = LevelEstimator.estimate(moves(0, 5, 8, 0, 12, 4, 9, 6, 0, 11, 3, 7))
        assertEquals("Expert", estimate.band.label)
        assertEquals(0, estimate.blunders)
        assertTrue("expected a high top-move rate", estimate.topMoveRate >= 50)
    }

    @Test
    fun sloppyPlayLandsInALowBand() {
        val estimate = LevelEstimator.estimate(
            moves(400, 250, 600, 180, 320, 90, 500, 210, 260, 700, 150, 380)
        )
        assertEquals("Beginner", estimate.band.label)
        assertTrue("expected blunders to be counted", estimate.blunders >= 4)
    }

    @Test
    fun middlingPlayLandsBetween() {
        val estimate = LevelEstimator.estimate(moves(30, 60, 45, 80, 20, 55, 70, 35, 50, 40, 65, 25))
        assertEquals("Club player", estimate.band.label)
        assertTrue(estimate.band.ratingLow in 1200..1500)
    }

    /** One disaster in an otherwise clean game must not drag the whole estimate down. */
    @Test
    fun aSingleCatastropheIsCapped() {
        val clean = moves(5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5)
        val cleanWithOneDisaster = moves(5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 4000)

        val capped = LevelEstimator.estimate(cleanWithOneDisaster)
        // 11 moves at 5 plus one capped at 300 averages 29, not 338.
        assertEquals(29, capped.averageLoss)
        assertEquals(1, capped.blunders)
        assertTrue(
            "the disaster should still cost a band relative to a clean game",
            capped.averageLoss > LevelEstimator.estimate(clean).averageLoss,
        )
    }

    @Test
    fun confidenceScalesWithTheCalibratedSampleAndNeverExceedsProvisional() {
        val calibrated = LevelCalibration.CALIBRATED_SAMPLE_MOVES
        assertEquals(
            EstimateConfidence.VERY_LOW,
            LevelEstimator.estimate(moves(*IntArray(calibrated / 3 - 1) { 10 })).confidence,
        )
        assertEquals(
            EstimateConfidence.LOW,
            LevelEstimator.estimate(moves(*IntArray(calibrated / 2) { 10 })).confidence,
        )
        // Even a very long game is still only one game.
        val long = LevelEstimator.estimate(moves(*IntArray(calibrated * 3) { 10 }))
        assertEquals(EstimateConfidence.MODERATE, long.confidence)
    }

    /** The bands must agree with the curve they were derived from. */
    @Test
    fun bandsAgreeWithTheFittedCurve() {
        // Each band edge should sit within its own band's rating range.
        for (loss in listOf(20, 32, 41, 49, 60, 73)) {
            val band = LevelCalibration.bandForAverageLoss(loss)
            val fitted = LevelCalibration.fittedRatingFor(loss)
            assertTrue(
                "loss ${'$'}loss fits to ${'$'}fitted but was placed in ${'$'}{band.label} " +
                    "(${'$'}{band.ratingLow}-${'$'}{band.ratingHigh})",
                fitted in (band.ratingLow - 60)..(band.ratingHigh + 60),
            )
        }
    }

    /** Accuracy must map monotonically: cleaner play can never rate lower. */
    @Test
    fun cleanerPlayNeverRatesLower() {
        var previous = Int.MAX_VALUE
        for (loss in listOf(10, 25, 35, 45, 55, 68, 120, 300)) {
            val band = LevelCalibration.bandForAverageLoss(loss)
            assertTrue(
                "loss ${'$'}loss rated ${'$'}{band.ratingHigh}, above the previous ${'$'}previous",
                band.ratingHigh <= previous,
            )
            previous = band.ratingHigh
        }
    }

    @Test
    fun reportsTheCostliestMove() {
        val estimate = LevelEstimator.estimate(moves(10, 500, 20))
        assertEquals(500, estimate.costliestMove?.centipawnLoss)
        assertEquals(MoveQuality.BLUNDER, estimate.costliestMove?.quality)
    }

    @Test
    fun handlesAnEmptyGameWithoutCrashing() {
        val estimate = LevelEstimator.estimate(emptyList())
        assertEquals(0, estimate.movesAssessed)
        assertEquals(EstimateConfidence.VERY_LOW, estimate.confidence)
        assertTrue(estimate.weaknesses.isNotEmpty())
    }

    @Test
    fun alwaysSaysSomethingInBothDirections() {
        // Even a flawless game gets a "work on" line, and a terrible one gets a positive.
        val flawless = LevelEstimator.estimate(moves(0, 0, 0, 0, 0, 0, 0, 0))
        assertTrue(flawless.strengths.isNotEmpty())
        assertTrue(flawless.weaknesses.isNotEmpty())

        val awful = LevelEstimator.estimate(moves(900, 900, 900, 900, 900, 900))
        assertTrue(awful.strengths.isNotEmpty())
        assertTrue(awful.weaknesses.isNotEmpty())
    }

    /**
     * The analyst is exercised against the Kotlin engine: Stockfish is a native library and is
     * not available to JVM unit tests, which is one of the reasons the fallback engine exists.
     */
    private val analyst = MoveAnalyst(KotlinEngine())

    /** The analyst must charge nothing for playing the engine's own top choice. */
    @Test
    fun analystChargesNothingForTheBestMove() = runBlocking {
        val position = Position.fromFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")
        val mate = position.legalMoves().first {
            it.from == Squares.A1 && it.to == Squares.fromName("a8")
        }
        val assessment = analyst.assess(position, mate)
        assertEquals("Ra8#", assessment.san)
        assertEquals(0, assessment.centipawnLoss)
        assertEquals(MoveQuality.BEST, assessment.quality)
        assertEquals(null, assessment.bestSan)
    }

    /** And it must charge a lot for throwing a mate away. */
    @Test
    fun analystPunishesMissingAMate() = runBlocking {
        val position = Position.fromFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")
        val shuffle = position.legalMoves().first {
            it.from == Squares.fromName("g1") && it.to == Squares.fromName("h1")
        }
        val assessment = analyst.assess(position, shuffle)
        assertEquals(MoveQuality.BLUNDER, assessment.quality)
        assertNotNull(assessment.bestSan)
        assertTrue(
            "missing a forced mate should cost a lot, got ${assessment.centipawnLoss}",
            assessment.centipawnLoss > 1_000,
        )
    }

    /** A judgement must carry which engine produced it, so scales are never mixed silently. */
    @Test
    fun assessmentsRecordWhetherTheEngineWasFullStrength() = runBlocking {
        val position = Position.start()
        val move = position.legalMoves().first()
        assertEquals(false, analyst.assess(position, move).byFullStrengthEngine)
    }
}
