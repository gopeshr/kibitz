package gopesh.kibitz.engine

import gopesh.kibitz.chess.Move
import gopesh.kibitz.chess.Position

/**
 * What the app asks of an engine. Everything above this line — the eval bar, the opponent, the
 * coach — talks only to this interface, so which engine answers is an implementation detail.
 *
 * Two implementations exist on purpose: Stockfish for real play and analysis, and the
 * pure-Kotlin engine as the fallback while Stockfish is still loading its 104 MB network, and
 * as a test double in JVM unit tests where no native library is available.
 */
interface ChessEngine {

    /** Shown in diagnostics so it is always clear which engine produced a number. */
    val id: String

    /** True for a real engine; false while a stand-in is answering. */
    val isFullStrength: Boolean

    suspend fun evaluate(position: Position, depth: Int = DEFAULT_EVAL_DEPTH): EvalSnapshot

    suspend fun chooseMove(position: Position, level: OpponentLevel): Move?

    /**
     * Prices a move the player actually made: what the best move was worth, and what theirs
     * was worth, from the same position.
     */
    suspend fun priceMove(
        position: Position,
        played: Move,
        depth: Int = DEFAULT_ANALYSIS_DEPTH,
    ): MoveCost

    fun shutdown() = Unit

    companion object {
        /**
         * The bar re-evaluates after every move by either side, so it runs far more often
         * than anything else and is kept shallow.
         */
        const val DEFAULT_EVAL_DEPTH = 10

        /** Coaching is once per player move and can afford to look harder. */
        const val DEFAULT_ANALYSIS_DEPTH = 14
    }
}

/**
 * The gap between [bestScore] and [playedScore] is what a move cost. Both are centipawns from
 * the moving side's point of view, so the difference is never negative for a legal comparison.
 */
data class MoveCost(
    val bestMove: Move?,
    val bestScore: Int,
    val playedScore: Int,
) {
    val centipawnLoss: Int get() = (bestScore - playedScore).coerceAtLeast(0)
}

/**
 * Opponent strength as a ladder of levels.
 *
 * Stockfish limits its own strength properly through `UCI_LimitStrength` and `UCI_Elo`, which
 * is far better than picking deliberately worse moves: it plays like a weaker player rather
 * than like a strong player having a stroke. The advertised range is 1320–3190, so [uciElo] of
 * null means "no limit, full strength".
 */
enum class OpponentLevel(
    val label: String,
    val uciElo: Int?,
    val depth: Int,
) {
    BEGINNER("Beginner", uciElo = 1350, depth = 6),
    CASUAL("Casual", uciElo = 1500, depth = 8),
    CLUB("Club player", uciElo = 1750, depth = 10),
    STRONG("Strong", uciElo = 2000, depth = 12),
    EXPERT("Expert", uciElo = 2300, depth = 14),
    MAXIMUM("Full strength", uciElo = null, depth = 18);

    companion object {
        /**
         * Level used for the assessment game. Middling on purpose: a beginner flattened in
         * six moves and a strong player handed a free win both produce a useless estimate.
         */
        val ASSESSMENT = CLUB

        /** Closest level to a player's estimated rating, for a fair game. */
        fun nearest(rating: Int): OpponentLevel =
            entries.filter { it.uciElo != null }.minByOrNull {
                kotlin.math.abs((it.uciElo ?: 0) - rating)
            } ?: CLUB
    }
}
