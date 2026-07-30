package gopesh.kibitz.engine

import gopesh.kibitz.chess.Move
import gopesh.kibitz.chess.PieceType
import gopesh.kibitz.chess.Position

/** A root move together with the score the search assigned it, in centipawns. */
data class ScoredMove(val move: Move, val score: Int)

/**
 * The result of analysing a position. [rootMoves] is sorted best first and holds a score for
 * *every* legal move, which is what makes coaching possible: the cost of a player's move is
 * the gap between its score and the best score.
 */
data class Analysis(
    val bestMove: Move?,
    val score: Int,
    val rootMoves: List<ScoredMove>,
) {
    fun scoreOf(move: Move): Int? = rootMoves.firstOrNull { it.move == move }?.score
}

/**
 * Negamax with alpha-beta pruning, a quiescence search over captures, and MVV-LVA move
 * ordering.
 *
 * Every root move is searched with a full window rather than the narrowed window alpha-beta
 * would allow. That costs time, but a narrowed window only proves a move is *worse* than the
 * best one, not by how much — and the margin is exactly what move assessment needs.
 *
 * [maxNodes] bounds the work so a pathological position cannot stall the UI; hitting it
 * degrades scores rather than failing, which is acceptable for a coaching aid.
 */
class Search(
    private val depth: Int = 3,
    private val maxNodes: Int = 300_000,
) {
    private var nodes = 0

    /** Nodes visited by the most recent call. Useful for tuning and tests. */
    var lastNodeCount: Int = 0
        private set

    fun analyze(position: Position): Analysis {
        nodes = 0
        val moves = position.legalMoves()
        if (moves.isEmpty()) {
            val terminal = if (position.isInCheck(position.sideToMove)) -Evaluation.MATE else 0
            lastNodeCount = 0
            return Analysis(bestMove = null, score = terminal, rootMoves = emptyList())
        }

        val scored = ArrayList<ScoredMove>(moves.size)
        for (move in order(position, moves)) {
            val score = -negamax(
                position = position.makeMove(move),
                depth = depth - 1,
                alpha = -Evaluation.MATE,
                beta = Evaluation.MATE,
                ply = 1,
            )
            scored.add(ScoredMove(move, score))
        }
        scored.sortByDescending { it.score }
        lastNodeCount = nodes
        return Analysis(scored.first().move, scored.first().score, scored)
    }

    /** Convenience for callers that only want a move to play. */
    fun bestMove(position: Position): Move? = analyze(position).bestMove

    private fun negamax(position: Position, depth: Int, alpha: Int, beta: Int, ply: Int): Int {
        nodes++

        // Move generation has to happen before the depth check so mate and stalemate are
        // recognised as terminal rather than evaluated as ordinary quiet positions.
        val moves = position.legalMoves()
        if (moves.isEmpty()) {
            // Prefer mates that arrive sooner by making distant mates less attractive.
            return if (position.isInCheck(position.sideToMove)) -Evaluation.MATE + ply else 0
        }
        if (position.halfmoveClock >= 100) return 0
        if (depth <= 0) return quiescence(position, alpha, beta, ply)
        if (nodes >= maxNodes) return Evaluation.forSideToMove(position)

        var best = alpha
        for (move in order(position, moves)) {
            val score = -negamax(position.makeMove(move), depth - 1, -beta, -best, ply + 1)
            if (score >= beta) return beta
            if (score > best) best = score
        }
        return best
    }

    /**
     * Searches only captures and promotions past the depth limit, so the evaluator is never
     * asked to judge a position in the middle of a trade. Without this, the engine happily
     * "wins" a queen on the last ply and never sees the recapture.
     */
    private fun quiescence(position: Position, alpha: Int, beta: Int, ply: Int): Int {
        nodes++
        val standPat = Evaluation.forSideToMove(position)
        if (standPat >= beta) return beta
        var best = maxOf(alpha, standPat)
        if (nodes >= maxNodes) return best

        val forcing = position.legalMoves().filter {
            position.isCapture(it) || it.promotion != null
        }
        for (move in order(position, forcing)) {
            val score = -quiescence(position.makeMove(move), -beta, -best, ply + 1)
            if (score >= beta) return beta
            if (score > best) best = score
        }
        return best
    }

    /**
     * Most-valuable-victim / least-valuable-attacker ordering. Trying the moves most likely
     * to be good first is what makes alpha-beta actually prune.
     */
    private fun order(position: Position, moves: List<Move>): List<Move> =
        moves.sortedByDescending { move -> orderingScore(position, move) }

    private fun orderingScore(position: Position, move: Move): Int {
        val victim = position[move.to]?.type?.centipawns
            ?: if (position.isEnPassant(move)) PieceType.PAWN.centipawns else 0
        val attacker = position[move.from]?.type?.centipawns ?: 0
        val promotion = move.promotion?.centipawns ?: 0
        return if (victim > 0) 100_000 + victim * 8 - attacker + promotion else promotion
    }
}
