package gopesh.kibitz.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An unfinished game, small enough to write on every move.
 *
 * Stored as the move list rather than as positions: replaying from the start rebuilds the
 * board, the move history and the repetition counts together, so there is no second
 * representation of a position that could drift out of step with [gopesh.kibitz.chess.Position].
 */
data class GameSnapshot(
    /** Moves in UCI, in order. */
    val moves: List<String>,
    /** "WHITE" or "BLACK" — the side the engine plays. */
    val engineSide: String,
    val opponentLevel: String,
    val playerIsWhite: Boolean,
)

/**
 * Keeps the game in progress across the app being killed.
 *
 * Level checks are deliberately *not* resumed. Their result depends on a running list of judged
 * moves that would be lost, so a resumed one would produce an estimate from half the evidence
 * while looking like a complete measurement. A level check is short; starting it again is
 * honest, and cheap.
 */
class GameStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    suspend fun save(snapshot: GameSnapshot) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_MOVES, snapshot.moves.joinToString(" "))
            .putString(KEY_ENGINE_SIDE, snapshot.engineSide)
            .putString(KEY_LEVEL, snapshot.opponentLevel)
            .putBoolean(KEY_PLAYER_WHITE, snapshot.playerIsWhite)
            .apply()
        Unit
    }

    suspend fun load(): GameSnapshot? = withContext(Dispatchers.IO) {
        val engineSide = prefs.getString(KEY_ENGINE_SIDE, null) ?: return@withContext null
        val moves = prefs.getString(KEY_MOVES, "").orEmpty()
            .split(' ')
            .filter { it.isNotBlank() }
        // A game with no moves is not worth resuming; the picker is a better place to land.
        if (moves.isEmpty()) return@withContext null

        GameSnapshot(
            moves = moves,
            engineSide = engineSide,
            opponentLevel = prefs.getString(KEY_LEVEL, "CLUB").orEmpty(),
            playerIsWhite = prefs.getBoolean(KEY_PLAYER_WHITE, true),
        )
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
        Unit
    }

    /** Cheap enough to call during routing, before anything else has loaded. */
    fun hasSavedGame(): Boolean =
        prefs.getString(KEY_ENGINE_SIDE, null) != null &&
            !prefs.getString(KEY_MOVES, "").isNullOrBlank()

    private companion object {
        const val FILE_NAME = "kibitz_game_in_progress"
        const val KEY_MOVES = "moves"
        const val KEY_ENGINE_SIDE = "engine_side"
        const val KEY_LEVEL = "level"
        const val KEY_PLAYER_WHITE = "player_white"
    }
}
