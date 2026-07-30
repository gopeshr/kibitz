package gopesh.kibitz.engine

import gopesh.kibitz.chess.Color
import gopesh.kibitz.chess.Move
import gopesh.kibitz.chess.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The built-in Kotlin engine behind the [ChessEngine] interface.
 *
 * It is no longer the engine the app plays with, but it is not dead code: it answers while
 * Stockfish is still loading its network at startup, it covers devices where the native
 * library fails to load, and it is the only engine available in JVM unit tests. Its depths are
 * much shallower than Stockfish's, so the numbers it produces are not directly comparable —
 * which is exactly why [isFullStrength] exists.
 */
class KotlinEngine(
    private val random: Random = Random.Default,
) : ChessEngine {

    override val id: String = "kotlin-builtin"

    override val isFullStrength: Boolean = false

    override suspend fun evaluate(position: Position, depth: Int): EvalSnapshot =
        withContext(Dispatchers.Default) {
            if (position.legalMoves().isEmpty()) {
                return@withContext terminalSnapshot(position)
            }
            val analysis = Search(depth = shallow(depth)).analyze(position)
            EvalSnapshot.from(analysis.score, position.sideToMove == Color.WHITE)
        }

    override suspend fun chooseMove(position: Position, level: OpponentLevel): Move? =
        withContext(Dispatchers.Default) {
            // Rating limits are a Stockfish feature; here strength is only depth plus a
            // little slack, so weaker levels get a wider band of acceptable moves.
            val slack = when (level) {
                OpponentLevel.BEGINNER -> 250
                OpponentLevel.CASUAL -> 150
                OpponentLevel.CLUB -> 70
                OpponentLevel.STRONG -> 30
                else -> 0
            }
            val analysis = Search(depth = shallow(level.depth)).analyze(position)
            val best = analysis.rootMoves.firstOrNull() ?: return@withContext null
            val candidates = analysis.rootMoves.filter { best.score - it.score <= slack }
            val weighted = candidates.flatMapIndexed { index, scored ->
                List(maxOf(1, candidates.size - index)) { scored }
            }
            weighted[random.nextInt(weighted.size)].move
        }

    override suspend fun priceMove(position: Position, played: Move, depth: Int): MoveCost =
        withContext(Dispatchers.Default) {
            val analysis = Search(depth = shallow(depth)).analyze(position)
            MoveCost(
                bestMove = analysis.bestMove,
                bestScore = analysis.score,
                playedScore = analysis.scoreOf(played) ?: analysis.score,
            )
        }

    /**
     * Depths meant for Stockfish would take minutes here, so they are capped. The interface
     * asks for a depth; this implementation honours the intent, not the number.
     */
    private fun shallow(requested: Int): Int = requested.coerceIn(1, 3)

    private fun terminalSnapshot(position: Position): EvalSnapshot =
        if (position.isInCheck(position.sideToMove)) {
            EvalSnapshot.checkmate(whiteWon = position.sideToMove == Color.BLACK)
        } else {
            EvalSnapshot.drawn()
        }
}
