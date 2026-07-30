package gopesh.kibitz

import android.app.Application
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gopesh.kibitz.chess.Color
import gopesh.kibitz.chess.Move
import gopesh.kibitz.chess.Squares
import gopesh.kibitz.chess.san
import gopesh.kibitz.coach.Drill
import gopesh.kibitz.data.DrillProgress
import gopesh.kibitz.data.TrainingHistory
import kotlinx.coroutines.launch

/** What the player is currently looking at within one drill. */
enum class DrillState { LOADING, EMPTY, ASKING, CORRECT, WRONG }

/**
 * Runs practice on the player's own past mistakes.
 *
 * Separate from [GameViewModel] on purpose: a drill is a single position with a known answer and
 * no opponent, which is a different thing from a game and would only complicate the game state
 * machine if folded into it.
 */
class DrillViewModel(application: Application) : AndroidViewModel(application) {

    private val history = TrainingHistory(application)

    private var queue: List<Drill> = emptyList()
    private var index = 0

    var state by mutableStateOf(DrillState.LOADING)
        private set

    var drill by mutableStateOf<Drill?>(null)
        private set

    var progress by mutableStateOf<DrillProgress?>(null)
        private set

    /** The move the player tried, so a wrong answer can be shown alongside the right one. */
    var attemptedSan by mutableStateOf<String?>(null)
        private set

    var selectedSquare by mutableStateOf<Int?>(null)
        private set

    /** Solved this session, for a sense of progress within a sitting. */
    var solvedThisSession by mutableStateOf(0)
        private set

    var attemptedThisSession by mutableStateOf(0)
        private set

    val legalTargets: Set<Int> by derivedStateOf {
        val from = selectedSquare ?: return@derivedStateOf emptySet()
        val position = drill?.position ?: return@derivedStateOf emptySet()
        position.legalMoves().asSequence().filter { it.from == from }.map { it.to }.toSet()
    }

    /** Which way round to show the board: the player sits behind the side that has to move. */
    val flipped: Boolean by derivedStateOf {
        drill?.position?.sideToMove == Color.BLACK
    }

    /** Highlighted once answered, so the right move is visible on the board itself. */
    val solutionMove: Move? by derivedStateOf {
        if (state == DrillState.CORRECT || state == DrillState.WRONG) {
            drill?.solutions?.firstOrNull()
        } else {
            null
        }
    }

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            state = DrillState.LOADING
            queue = runCatching { history.drillQueue() }.getOrDefault(emptyList())
            progress = runCatching { history.drillProgress() }.getOrNull()
            index = 0
            showCurrent()
        }
    }

    fun onSquareTap(square: Int) {
        // Once answered, taps do nothing: the next drill is an explicit step so the player has
        // time to look at the position they got wrong.
        if (state != DrillState.ASKING) return
        val current = drill ?: return
        val position = current.position

        val from = selectedSquare
        if (from == null) {
            selectedSquare = square.takeIf { position[it]?.color == position.sideToMove }
            return
        }

        val candidates = position.legalMoves().filter { it.from == from && it.to == square }
        when {
            candidates.isEmpty() ->
                selectedSquare = square.takeIf { position[it]?.color == position.sideToMove }
            // Promotion choice is not offered: the stored answer already names a piece, and a
            // queen is right in all but a handful of positions.
            else -> answer(candidates.first())
        }
    }

    fun onDragStart(square: Int) {
        if (state != DrillState.ASKING) return
        val position = drill?.position ?: return
        selectedSquare = square.takeIf { position[it]?.color == position.sideToMove }
    }

    fun onDrop(from: Int, to: Int) {
        if (state != DrillState.ASKING || from == to) return
        val position = drill?.position ?: return
        val candidates = position.legalMoves().filter { it.from == from && it.to == to }
        if (candidates.isEmpty()) selectedSquare = null else answer(candidates.first())
    }

    fun onDragCancel() {
        selectedSquare = null
    }

    private fun answer(move: Move) {
        val current = drill ?: return
        val correct = current.isCorrect(move)

        attemptedSan = current.position.san(move)
        selectedSquare = null
        state = if (correct) DrillState.CORRECT else DrillState.WRONG
        attemptedThisSession++
        if (correct) solvedThisSession++

        viewModelScope.launch {
            runCatching {
                history.recordDrillAttempt(
                    moveId = current.moveId,
                    correct = correct,
                    at = System.currentTimeMillis(),
                )
                progress = history.drillProgress()
            }
        }
    }

    /** Gives up on the current drill and shows the answer, counting as an attempt. */
    fun reveal() {
        if (state != DrillState.ASKING) return
        val current = drill ?: return
        attemptedSan = null
        state = DrillState.WRONG
        attemptedThisSession++
        viewModelScope.launch {
            runCatching {
                history.recordDrillAttempt(
                    moveId = current.moveId,
                    correct = false,
                    at = System.currentTimeMillis(),
                )
                progress = history.drillProgress()
            }
        }
    }

    fun next() {
        index++
        if (index >= queue.size) {
            // Reloading rather than stopping: solved drills sink down the queue instead of
            // vanishing, so there is always something to come back to.
            load()
        } else {
            showCurrent()
        }
    }

    private fun showCurrent() {
        attemptedSan = null
        selectedSquare = null
        val next = queue.getOrNull(index)
        drill = next
        state = if (next == null) DrillState.EMPTY else DrillState.ASKING
    }
}
