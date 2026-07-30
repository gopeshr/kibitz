package gopesh.kibitz.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One game the player finished, with the summary that was shown at the time.
 *
 * [engineId] and [byFullStrengthEngine] on the moves matter more than they look: a game judged
 * by the fallback engine is not measured on the same scale as one judged by Stockfish, and
 * averaging the two together would quietly corrupt any long-run trend.
 */
@Entity(tableName = "games")
data class GameRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playedAt: Long,
    val playerIsWhite: Boolean,
    val wasLevelCheck: Boolean,
    val opponentLevel: String,
    val engineId: String,
    val movesAssessed: Int,
    val averageLoss: Int,
    val blunders: Int,
    val bandLabel: String,
    val ratingLow: Int,
    val ratingHigh: Int,
    val result: String,
)

/**
 * One judged move.
 *
 * [fenBefore] is the field that makes future training possible: with it, any past mistake can
 * be handed back to the player as a puzzle from the exact position it happened in. Storing only
 * the notation would make that impossible to reconstruct.
 */
@Entity(
    tableName = "moves",
    foreignKeys = [
        ForeignKey(
            entity = GameRecord::class,
            parentColumns = ["id"],
            childColumns = ["gameId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("gameId"), Index("centipawnLoss"), Index("quality")],
)
data class MoveRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: Long,
    val ply: Int,
    val moveNumber: Int,
    val san: String,
    val uci: String,
    val fenBefore: String,
    val centipawnLoss: Int,
    val quality: String,
    val bestSan: String?,
    @ColumnInfo(defaultValue = "1") val byFullStrengthEngine: Boolean,
)

/** How many moves of each quality the player has made. */
data class QualityCount(val quality: String, val count: Int)

/** Aggregate accuracy, restricted to moves judged by a full-strength engine. */
data class AccuracySummary(
    val movesJudged: Int,
    val averageLoss: Double,
    val blunders: Int,
)
