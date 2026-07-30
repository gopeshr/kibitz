package gopesh.kibitz.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A game that was started and never finished.
 *
 * Stored as the move list rather than as positions: replaying from the start rebuilds the board,
 * the move history and the repetition counts together, so there is no second representation of a
 * position that could drift out of step with [gopesh.kibitz.chess.Position].
 */
data class GameSnapshot(
    val id: String,
    /** Moves in UCI, in order. */
    val moves: List<String>,
    /** "WHITE" or "BLACK" — the side the engine plays. */
    val engineSide: String,
    val opponentLevel: String,
    val playerIsWhite: Boolean,
    val savedAt: Long,
) {
    /** Full moves played, which is what a player recognises a game by. */
    val moveNumber: Int get() = moves.size / 2 + 1
}

/**
 * Keeps unfinished games so they can be picked up again.
 *
 * Holds a *list*, not a single slot. It used to keep one, cleared whenever another game started,
 * which meant beginning a second game silently destroyed the first — a game you had not finished
 * simply vanished.
 *
 * Level checks are deliberately not stored. Their result depends on a running list of judged
 * moves that would be lost, so a resumed one would produce an estimate from half the evidence
 * while looking like a complete measurement.
 */
class GameStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Adds or updates one game. Newest first, oldest dropped past [MAX_GAMES]. */
    suspend fun save(snapshot: GameSnapshot) = withContext(Dispatchers.IO) {
        val kept = read().filter { it.id != snapshot.id }
        write((listOf(snapshot) + kept).take(MAX_GAMES))
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        write(read().filter { it.id != id })
    }

    /** Unfinished games, most recent first. */
    suspend fun list(): List<GameSnapshot> = withContext(Dispatchers.IO) {
        read().sortedByDescending { it.savedAt }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_GAMES).apply()
        Unit
    }

    private fun read(): List<GameSnapshot> {
        val raw = prefs.getString(KEY_GAMES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val moves = item.optString("moves").split(' ').filter { it.isNotBlank() }
            // A game with no moves is not worth offering to continue.
            if (moves.isEmpty()) return@mapNotNull null
            GameSnapshot(
                id = item.optString("id").ifBlank { return@mapNotNull null },
                moves = moves,
                engineSide = item.optString("engineSide").ifBlank { return@mapNotNull null },
                opponentLevel = item.optString("level", "CLUB"),
                playerIsWhite = item.optBoolean("playerIsWhite", true),
                savedAt = item.optLong("savedAt"),
            )
        }
    }

    private fun write(games: List<GameSnapshot>) {
        val array = JSONArray()
        for (game in games) {
            array.put(
                JSONObject()
                    .put("id", game.id)
                    .put("moves", game.moves.joinToString(" "))
                    .put("engineSide", game.engineSide)
                    .put("level", game.opponentLevel)
                    .put("playerIsWhite", game.playerIsWhite)
                    .put("savedAt", game.savedAt)
            )
        }
        prefs.edit().putString(KEY_GAMES, array.toString()).apply()
    }

    private companion object {
        const val FILE_NAME = "kibitz_game_in_progress"
        const val KEY_GAMES = "unfinished_games"

        /** Enough to cover forgetting about a game or two, not enough to become a graveyard. */
        const val MAX_GAMES = 10
    }
}
