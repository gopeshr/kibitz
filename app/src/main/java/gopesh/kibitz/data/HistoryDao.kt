package gopesh.kibitz.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * Queries the coaching layer needs.
 *
 * These are written as SQL rather than "load everything and filter in Kotlin" because the
 * interesting questions are aggregate ones — which mistakes recur, is accuracy improving — and
 * they will be asked against a history that grows for as long as the player uses the app.
 *
 * Anything reporting accuracy filters on [MoveRecord.byFullStrengthEngine]. Mixing Stockfish's
 * centipawn losses with the fallback engine's would produce a trend line that moves when the
 * engine changes rather than when the player does.
 */
@Dao
interface HistoryDao {

    @Insert
    suspend fun insertGame(game: GameRecord): Long

    @Insert
    suspend fun insertMoves(moves: List<MoveRecord>)

    /** Saves a game and its moves together, so a crash cannot leave orphaned halves. */
    @Transaction
    suspend fun saveGame(game: GameRecord, moves: List<MoveRecord>): Long {
        val gameId = insertGame(game)
        insertMoves(moves.map { it.copy(gameId = gameId) })
        return gameId
    }

    @Query("SELECT * FROM games ORDER BY playedAt DESC LIMIT :limit")
    suspend fun recentGames(limit: Int = 20): List<GameRecord>

    @Query("SELECT COUNT(*) FROM games")
    suspend fun gameCount(): Int

    @Query("SELECT * FROM moves WHERE gameId = :gameId ORDER BY ply")
    suspend fun movesFor(gameId: Long): List<MoveRecord>

    /**
     * The player's worst moments, worst first. This is the raw material for drills: each row
     * carries the FEN it happened in, so the position can be handed straight back to them.
     */
    @Query(
        """
        SELECT * FROM moves
        WHERE centipawnLoss >= :minimumLoss AND byFullStrengthEngine = 1
        ORDER BY centipawnLoss DESC
        LIMIT :limit
        """
    )
    suspend fun costliestMoves(minimumLoss: Int = 250, limit: Int = 20): List<MoveRecord>

    @Query(
        """
        SELECT quality, COUNT(*) AS count FROM moves
        WHERE byFullStrengthEngine = 1
        GROUP BY quality
        ORDER BY count DESC
        """
    )
    suspend fun qualityBreakdown(): List<QualityCount>

    @Query(
        """
        SELECT COUNT(*) AS movesJudged,
               COALESCE(AVG(MIN(centipawnLoss, :lossCap)), 0.0) AS averageLoss,
               COALESCE(SUM(CASE WHEN quality = 'BLUNDER' THEN 1 ELSE 0 END), 0) AS blunders
        FROM moves
        WHERE byFullStrengthEngine = 1
        """
    )
    suspend fun accuracySummary(lossCap: Int): AccuracySummary

    /**
     * Per-game average loss over time, oldest first, for showing whether accuracy is actually
     * improving rather than just asserting that it is.
     */
    @Query(
        """
        SELECT averageLoss FROM games
        WHERE movesAssessed > 0
        ORDER BY playedAt ASC
        LIMIT :limit
        """
    )
    suspend fun accuracyTrend(limit: Int = 50): List<Int>

    // ------------------------------------------------------------------ drills

    @Insert
    suspend fun insertDrillAttempt(attempt: DrillAttempt)

    /**
     * Mistakes worth drilling, hardest-still-unsolved first.
     *
     * Requires [MoveRecord.bestSan]: a puzzle with no known answer cannot be marked right or
     * wrong. Positions already solved sink to the bottom rather than disappearing, so there is
     * always something to practise once the backlog is cleared.
     */
    @Query(
        """
        SELECT m.* FROM moves m
        LEFT JOIN (
            SELECT moveId, MAX(CASE WHEN correct THEN 1 ELSE 0 END) AS solved
            FROM drill_attempts GROUP BY moveId
        ) a ON a.moveId = m.id
        WHERE m.centipawnLoss >= :minimumLoss
          AND m.byFullStrengthEngine = 1
          AND m.bestSan IS NOT NULL
        ORDER BY COALESCE(a.solved, 0) ASC, m.centipawnLoss DESC
        LIMIT :limit
        """
    )
    suspend fun drillQueue(minimumLoss: Int = 120, limit: Int = 30): List<MoveRecord>

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM moves
             WHERE centipawnLoss >= :minimumLoss AND byFullStrengthEngine = 1
               AND bestSan IS NOT NULL) AS available,
          (SELECT COUNT(DISTINCT moveId) FROM drill_attempts) AS attempted,
          (SELECT COUNT(DISTINCT moveId) FROM drill_attempts WHERE correct) AS solved
        """
    )
    suspend fun drillProgress(minimumLoss: Int = 120): DrillProgress

    @Query("DELETE FROM games")
    suspend fun deleteAllGames()
}
