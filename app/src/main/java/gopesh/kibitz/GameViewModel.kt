package gopesh.kibitz

import android.app.Application
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gopesh.kibitz.chess.Color
import gopesh.kibitz.chess.DrawReason
import gopesh.kibitz.chess.Move
import gopesh.kibitz.chess.PieceType
import gopesh.kibitz.chess.Position
import gopesh.kibitz.chess.Squares
import gopesh.kibitz.chess.Status
import gopesh.kibitz.chess.san
import gopesh.kibitz.data.AccuracySummary
import gopesh.kibitz.data.TrainingHistory
import gopesh.kibitz.coach.LevelCalibration
import gopesh.kibitz.coach.LevelEstimate
import gopesh.kibitz.coach.LevelEstimator
import gopesh.kibitz.coach.MoveAnalyst
import gopesh.kibitz.coach.MoveAssessment
import gopesh.kibitz.engine.ChessEngine
import gopesh.kibitz.engine.EngineProvider
import gopesh.kibitz.engine.EvalSnapshot
import gopesh.kibitz.engine.OpponentLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Holds the game being played. Positions are immutable, so history is just the list of plies
 * and undo is a matter of restoring the position recorded before a move.
 *
 * The same view model runs both free play and the assessment game. It never names an engine:
 * everything goes through [EngineProvider], which starts on the built-in Kotlin engine and
 * upgrades itself to Stockfish once the network has loaded.
 */
class GameViewModel(application: Application) : AndroidViewModel(application) {

    private data class Ply(val before: Position, val move: Move, val san: String)

    private val plies = mutableStateListOf<Ply>()

    var position by mutableStateOf(Position.start())
        private set

    var flipped by mutableStateOf(false)
        private set

    var selectedSquare by mutableStateOf<Int?>(null)
        private set

    /** Set when a pawn reaches the last rank and the player still has to pick a piece. */
    var promotionPrompt by mutableStateOf<Move?>(null)
        private set

    /**
     * True when the last move was dragged. The board uses this to skip the slide animation,
     * since the finger has already carried the piece to its destination.
     */
    var lastMoveWasDrag by mutableStateOf(false)
        private set

    private var promotionWasDragged = false

    // ---------------------------------------------------------------- engine

    /** Reads "Stockfish 18" once ready, or the fallback while it is still starting. */
    var engineId by mutableStateOf(EngineProvider.current().id)
        private set

    var engineIsFullStrength by mutableStateOf(EngineProvider.current().isFullStrength)
        private set

    var opponentLevel by mutableStateOf(OpponentLevel.ASSESSMENT)
        private set

    private val history = TrainingHistory(application)

    private fun engine(): ChessEngine = EngineProvider.current()

    // ------------------------------------------------------------ assessment

    /** Which side the engine plays, or null in free play. */
    var engineSide by mutableStateOf<Color?>(null)
        private set

    var engineThinking by mutableStateOf(false)
        private set

    /** How many of the player's moves the assessment game runs for. */
    var assessmentTarget by mutableStateOf(0)
        private set

    val assessments = mutableStateListOf<MoveAssessment>()

    /**
     * The position and move behind each assessment, kept in step with [assessments].
     * Stored so a past mistake can be replayed as a puzzle from the exact position it
     * happened in — notation alone could not be reconstructed into a board.
     */
    private val assessedFens = mutableListOf<String>()
    private val assessedUcis = mutableListOf<String>()

    /** Accuracy across every game ever recorded, or null before it has been read. */
    var allTimeAccuracy by mutableStateOf<AccuracySummary?>(null)
        private set

    var gamesRecorded by mutableStateOf(0)
        private set

    var levelEstimate by mutableStateOf<LevelEstimate?>(null)
        private set

    /** True once the assessment game is over and [levelEstimate] is ready. */
    var assessmentComplete by mutableStateOf(false)
        private set

    val isAssessing: Boolean get() = assessmentTarget > 0 && !assessmentComplete

    /** The most recent judged move, for the running feedback line. */
    val latestAssessment: MoveAssessment? get() = assessments.lastOrNull()

    // ------------------------------------------------------------ evaluation

    /** Live evaluation of the current position, from White's point of view. */
    var evaluation by mutableStateOf<EvalSnapshot?>(null)
        private set

    var evaluating by mutableStateOf(false)
        private set

    /** Held so a superseded evaluation is cancelled the moment the position moves on. */
    private var evalJob: Job? = null

    /**
     * Re-evaluates the current position. Any evaluation still running is cancelled first: its
     * answer describes a position that is no longer on the board, and letting it finish would
     * let a stale score overwrite a fresh one.
     */
    private fun refreshEvaluation() {
        evalJob?.cancel()
        val target = position
        evalJob = viewModelScope.launch {
            evaluating = true
            try {
                evaluation = engine().evaluate(target)
            } finally {
                evaluating = false
            }
        }
    }

    // ------------------------------------------------------------ board state

    val legalMoves: List<Move> by derivedStateOf { position.legalMoves() }

    /**
     * How many times each position has occurred this game. Repetition is a property of the
     * game, not of a position, so it is counted here rather than in [Position].
     */
    private val repetitionCounts = mutableStateMapOf<String, Int>()

    /**
     * The game's outcome, with threefold repetition layered on top of what a single position
     * can determine by itself.
     */
    val status: Status by derivedStateOf {
        val fromPosition = position.status()
        // A mate or a stalemate outranks a repetition; the game ended on the board.
        if (fromPosition is Status.Ongoing &&
            (repetitionCounts[position.repetitionKey] ?: 0) >= 3
        ) {
            Status.Draw(DrawReason.THREEFOLD_REPETITION)
        } else {
            fromPosition
        }
    }

    val isGameOver: Boolean by derivedStateOf { status !is Status.Ongoing }

    /** True once a finished game has been analysed and filed away. */
    var gameRecorded by mutableStateOf(false)
        private set

    // -------------------------------------------------------- post-game review

    var reviewing by mutableStateOf(false)
        private set

    var reviewDone by mutableStateOf(0)
        private set

    var reviewTotal by mutableStateOf(0)
        private set

    /** Every move the player made in the finished game, judged. */
    val reviewedMoves = mutableStateListOf<MoveAssessment>()

    var reviewSummary by mutableStateOf<LevelEstimate?>(null)
        private set

    /**
     * Reviews and files a finished game.
     *
     * Runs automatically rather than behind a button: an unreviewed game is invisible to the
     * app afterwards, and the mistakes in it are exactly the material training needs. Analysis
     * happens after the game rather than during it so play is never slowed by it.
     */
    private fun reviewFinishedGame() {
        // Set immediately, not inside the coroutine, or a second game-ending move could start
        // a duplicate review before the first has begun.
        gameRecorded = true

        val playerColor = engineSide?.opposite ?: return
        val playerPlies = plies.filter { it.before.sideToMove == playerColor }
        if (playerPlies.isEmpty()) return

        val finalPosition = position
        val level = opponentLevel
        val engine = engine()
        val playerIsWhite = playerColor == Color.WHITE

        viewModelScope.launch {
            reviewing = true
            reviewDone = 0
            reviewTotal = playerPlies.size
            reviewedMoves.clear()
            try {
                val analyst = MoveAnalyst(engine, depth = REVIEW_DEPTH)
                val fens = ArrayList<String>(playerPlies.size)
                val ucis = ArrayList<String>(playerPlies.size)
                for (ply in playerPlies) {
                    reviewedMoves.add(analyst.assess(ply.before, ply.move))
                    fens.add(ply.before.fen)
                    ucis.add(ply.move.uci)
                    reviewDone = reviewedMoves.size
                }
                val summary = LevelEstimator.estimate(reviewedMoves.toList())
                reviewSummary = summary

                runCatching {
                    history.recordGame(
                        assessments = reviewedMoves.toList(),
                        fensBefore = fens,
                        ucis = ucis,
                        estimate = summary,
                        playerIsWhite = playerIsWhite,
                        wasLevelCheck = false,
                        opponentLevel = level.name,
                        engineId = engine.id,
                        finalPosition = finalPosition,
                        playedAt = System.currentTimeMillis(),
                    )
                    refreshHistorySummary()
                }.onFailure { Log.e(TAG, "could not record the game", it) }
            } finally {
                reviewing = false
            }
        }
    }

    /** Plays the same opponent again, same colour. */
    fun rematch() {
        startGame(opponentLevel, playerIsWhite = engineSide != Color.WHITE)
    }

    val legalTargets: Set<Int> by derivedStateOf {
        val from = selectedSquare ?: return@derivedStateOf emptySet()
        legalMoves.asSequence().filter { it.from == from }.map { it.to }.toSet()
    }

    val checkedKingSquare: Int? by derivedStateOf {
        if (!position.isInCheck(position.sideToMove)) return@derivedStateOf null
        position.kingSquare(position.sideToMove).takeIf { it != Squares.NONE }
    }

    val moveLog: List<String> get() = plies.map { it.san }

    val lastMove: Move? get() = plies.lastOrNull()?.move

    val plyCount: Int get() = plies.size

    val canUndo: Boolean get() = plies.isNotEmpty() && engineSide == null

    // ------------------------------------------------------------- gestures

    fun onSquareTap(square: Int) {
        if (!acceptsInput()) return

        val from = selectedSquare
        if (from == null) {
            selectedSquare = square.takeIf { holdsOwnPiece(it) }
            return
        }

        val candidates = legalMoves.filter { it.from == from && it.to == square }
        when {
            // Tapping elsewhere either re-selects another of your pieces or clears.
            candidates.isEmpty() -> selectedSquare = square.takeIf { holdsOwnPiece(it) }
            candidates.any { it.promotion != null } -> promotionPrompt = Move(from, square)
            else -> play(candidates.first())
        }
    }

    /** Picking up a piece highlights it, so its legal destinations show during the drag. */
    fun onDragStart(square: Int) {
        if (!acceptsInput()) return
        selectedSquare = square.takeIf { holdsOwnPiece(it) }
    }

    fun onDrop(from: Int, to: Int) {
        if (!acceptsInput()) return
        // Dropping a piece back where it started leaves it selected, ready for a tap move.
        if (from == to) return

        val candidates = legalMoves.filter { it.from == from && it.to == to }
        when {
            candidates.isEmpty() -> selectedSquare = null
            candidates.any { it.promotion != null } -> {
                promotionWasDragged = true
                promotionPrompt = Move(from, to)
            }
            else -> play(candidates.first(), wasDragged = true)
        }
    }

    fun onDragCancel() {
        selectedSquare = null
    }

    fun choosePromotion(type: PieceType) {
        val pending = promotionPrompt ?: return
        promotionPrompt = null
        val dragged = promotionWasDragged
        promotionWasDragged = false
        val move = legalMoves.firstOrNull {
            it.from == pending.from && it.to == pending.to && it.promotion == type
        } ?: return
        play(move, wasDragged = dragged)
    }

    fun cancelPromotion() {
        promotionPrompt = null
        promotionWasDragged = false
    }

    // -------------------------------------------------------------- controls

    fun undo() {
        if (engineSide != null) return
        val last = plies.removeLastOrNull() ?: return
        // Decrement before rewinding, or an undone position keeps its sighting and a later
        // repetition is reported one move early.
        val key = position.repetitionKey
        repetitionCounts[key] = ((repetitionCounts[key] ?: 1) - 1).coerceAtLeast(0)
        position = last.before
        selectedSquare = null
        promotionPrompt = null
        refreshEvaluation()
    }

    fun flipBoard() {
        flipped = !flipped
    }

    fun newGame() {
        resetBoard()
        engineSide = null
        assessmentTarget = 0
        assessments.clear()
        assessedFens.clear()
        assessedUcis.clear()
        levelEstimate = null
        assessmentComplete = false
    }

    /** Loads a position for drills and puzzles. Returns false if the FEN is malformed. */
    fun loadFen(fen: String): Boolean = runCatching {
        val loaded = Position.fromFen(fen)
        plies.clear()
        repetitionCounts.clear()
        position = loaded
        countPosition()
        selectedSquare = null
        promotionPrompt = null
        refreshEvaluation()
    }.isSuccess

    /**
     * Starts an ordinary game against the engine at [level].
     *
     * The difference from [startAssessment] is only that no target is set, which turns the
     * coaching off: a game you are playing for its own sake should not stop after thirty moves
     * or narrate every move back at you. The reply machinery is the same.
     */
    fun startGame(level: OpponentLevel, playerIsWhite: Boolean = true) {
        resetBoard()
        assessments.clear()
        assessedFens.clear()
        assessedUcis.clear()
        levelEstimate = null
        assessmentComplete = false
        assessmentTarget = 0
        opponentLevel = level
        engineSide = if (playerIsWhite) Color.BLACK else Color.WHITE
        // Sit behind your own pieces whichever colour you took.
        flipped = !playerIsWhite

        // With White, the engine has to open before the player can do anything.
        if (!playerIsWhite) {
            viewModelScope.launch { playEngineReply() }
        }
    }

    /**
     * Starts the level-assessment game. The player takes White so they move first and the
     * opening is theirs to choose, which says more about them than replying to the engine.
     */
    fun startAssessment(
        moves: Int = DEFAULT_ASSESSMENT_MOVES,
        level: OpponentLevel = OpponentLevel.ASSESSMENT,
    ) {
        resetBoard()
        assessments.clear()
        assessedFens.clear()
        assessedUcis.clear()
        levelEstimate = null
        assessmentComplete = false
        assessmentTarget = moves
        opponentLevel = level
        engineSide = Color.BLACK
        flipped = false
    }

    // --------------------------------------------------------------- private

    private fun acceptsInput(): Boolean =
        promotionPrompt == null && !engineThinking && !assessmentComplete && !isGameOver &&
            position.sideToMove != engineSide

    private fun resetBoard() {
        plies.clear()
        repetitionCounts.clear()
        position = Position.start()
        countPosition()
        selectedSquare = null
        promotionPrompt = null
        lastMoveWasDrag = false
        engineThinking = false
        gameRecorded = false
        reviewing = false
        reviewDone = 0
        reviewTotal = 0
        reviewedMoves.clear()
        reviewSummary = null
        refreshEvaluation()
    }

    private fun holdsOwnPiece(square: Int): Boolean =
        position[square]?.color == position.sideToMove

    /** Puts a move on the board and refreshes everything derived from the new position. */
    private fun applyMove(move: Move, wasDragged: Boolean) {
        val before = position
        plies.add(Ply(before = before, move = move, san = before.san(move)))
        position = before.makeMove(move)
        selectedSquare = null
        lastMoveWasDrag = wasDragged
        countPosition()
        refreshEvaluation()

        // An ordinary game that has just ended gets reviewed and filed. Level checks have
        // their own path, which already collected assessments as they were played.
        if (assessmentTarget == 0 && engineSide != null && !gameRecorded && isGameOver) {
            reviewFinishedGame()
        }
    }

    /** Records the current position for repetition counting. */
    private fun countPosition() {
        val key = position.repetitionKey
        repetitionCounts[key] = (repetitionCounts[key] ?: 0) + 1
    }

    private fun play(move: Move, wasDragged: Boolean = false) {
        val before = position
        applyMove(move, wasDragged)

        val engineColor = engineSide ?: return
        if (before.sideToMove == engineColor) return

        // The player's move is already on the board; replying and judging happen off the main
        // thread so the board never waits on a search.
        viewModelScope.launch {
            // Reply *before* analysing. Both need the one engine and it serialises them, so
            // whichever runs first is what the player sits waiting for — and they are waiting
            // for the opponent's move, not for a verdict on a move they have already made.
            // The verdict lands a moment later, which is exactly when it is wanted anyway.
            val isFinalPlayerMove = isAssessing && assessments.size + 1 >= assessmentTarget
            if (!isFinalPlayerMove) playEngineReply()

            if (isAssessing) {
                assessments.add(MoveAnalyst(engine()).assess(before, move))
                assessedFens.add(before.fen)
                assessedUcis.add(move.uci)
            }
            // Either the target is met, or a move ended the game outright.
            if (reachedEndOfAssessment()) finishAssessment()
        }
    }

    private suspend fun playEngineReply() {
        if (position.legalMoves().isEmpty()) return
        engineThinking = true
        val reply = try {
            engine().chooseMove(position, opponentLevel)
        } finally {
            engineThinking = false
        }
        if (reply != null) applyMove(reply, wasDragged = false)
    }

    private fun reachedEndOfAssessment(): Boolean =
        assessmentTarget > 0 &&
            (assessments.size >= assessmentTarget || isGameOver)

    private fun finishAssessment() {
        val estimate = LevelEstimator.estimate(assessments.toList())
        levelEstimate = estimate
        assessmentComplete = true
        engineThinking = false

        // Copy the state the write needs, then persist off the UI path. A storage failure must
        // never cost the player the result they are looking at.
        val judged = assessments.toList()
        val fens = assessedFens.toList()
        val ucis = assessedUcis.toList()
        val finalPosition = position
        val engine = engine()
        val level = opponentLevel
        val playerIsWhite = engineSide != Color.WHITE
        val wasLevelCheck = assessmentTarget > 0

        viewModelScope.launch {
            runCatching {
                history.recordGame(
                    assessments = judged,
                    fensBefore = fens,
                    ucis = ucis,
                    estimate = estimate,
                    playerIsWhite = playerIsWhite,
                    wasLevelCheck = wasLevelCheck,
                    opponentLevel = level.name,
                    engineId = engine.id,
                    finalPosition = finalPosition,
                    playedAt = System.currentTimeMillis(),
                )
                refreshHistorySummary()
            }.onFailure { Log.e(TAG, "could not record the game", it) }
        }
    }

    /** Reads the stored history back, so the result screen can show a lifetime picture. */
    private suspend fun refreshHistorySummary() {
        runCatching {
            gamesRecorded = history.gamesPlayed()
            allTimeAccuracy = history.accuracy()
        }.onFailure { Log.e(TAG, "could not read history", it) }
    }

    /**
     * Deliberately the last thing in the class body. Kotlin runs initialisers in declaration
     * order, and this one reaches [refreshEvaluation], which touches state declared above —
     * `EngineProvider.engine` is a StateFlow, so `collect` fires synchronously with its current
     * value during construction. Placed any earlier, that write lands on a property whose
     * backing state does not exist yet.
     */
    init {
        EngineProvider.warmUp(application, viewModelScope)
        viewModelScope.launch {
            EngineProvider.engine.collect { engine ->
                engineId = engine.id
                engineIsFullStrength = engine.isFullStrength
                // Re-evaluate now that a stronger engine can answer — the fallback's number
                // was only a placeholder.
                refreshEvaluation()
            }
        }
    }

    companion object {
        private const val TAG = "Kibitz/Game"

        /**
         * Tied to the calibration rather than chosen for feel. A shorter check is a nicer
         * onboarding but cannot separate a 1350 player from a 2900 one — measured, not
         * assumed. See [gopesh.kibitz.coach.LevelCalibration.CALIBRATED_SAMPLE_MOVES].
         */
        const val DEFAULT_ASSESSMENT_MOVES = LevelCalibration.CALIBRATED_SAMPLE_MOVES

        /**
         * Post-game review depth. Reviewing runs one search pair per move played, so it sits
         * below live coaching depth to finish while the result is still on screen.
         */
        const val REVIEW_DEPTH = 12
    }
}
