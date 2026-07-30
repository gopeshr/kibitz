package gopesh.kibitz

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gopesh.kibitz.data.AccuracySummary
import gopesh.kibitz.data.GameRecord
import gopesh.kibitz.data.MoveRecord
import gopesh.kibitz.data.TrainingHistory
import kotlinx.coroutines.launch

/**
 * Browsing what has already been played.
 *
 * Read-only, and separate from the game and drill view models because it owns nothing they need:
 * the record of a finished game is not the game.
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val history = TrainingHistory(application)

    var loading by mutableStateOf(true)
        private set

    var games by mutableStateOf<List<GameRecord>>(emptyList())
        private set

    var accuracy by mutableStateOf<AccuracySummary?>(null)
        private set

    /** Per-game average loss, oldest first, for showing whether accuracy is actually moving. */
    var trend by mutableStateOf<List<Int>>(emptyList())
        private set

    /** The game being looked at in detail, or null while on the list. */
    var openGame by mutableStateOf<GameRecord?>(null)
        private set

    var openGameMoves by mutableStateOf<List<MoveRecord>>(emptyList())
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            runCatching {
                games = history.recentGames(limit = 50)
                accuracy = history.accuracy()
                trend = history.accuracyTrend()
            }
            loading = false
        }
    }

    fun open(game: GameRecord) {
        openGame = game
        openGameMoves = emptyList()
        viewModelScope.launch {
            openGameMoves = runCatching { history.movesFor(game.id) }.getOrDefault(emptyList())
        }
    }

    /** Back to the list. Returns false when already there, so the caller can leave the screen. */
    fun closeGame(): Boolean {
        if (openGame == null) return false
        openGame = null
        openGameMoves = emptyList()
        return true
    }
}
