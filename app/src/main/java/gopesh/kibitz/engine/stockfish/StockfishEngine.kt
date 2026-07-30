package gopesh.kibitz.engine.stockfish

import android.content.Context
import android.util.Log
import gopesh.kibitz.chess.Color
import gopesh.kibitz.chess.Move
import gopesh.kibitz.chess.Position
import gopesh.kibitz.engine.ChessEngine
import gopesh.kibitz.engine.EvalSnapshot
import gopesh.kibitz.engine.MoveCost
import gopesh.kibitz.engine.OpponentLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stockfish 18, driven over UCI.
 *
 * All access is serialised through a mutex: there is one native engine and it can only run one
 * search at a time. Callers are free to cancel — a cancelled search sends `stop` and drains to
 * the `bestmove` before releasing the lock, so the next caller never inherits a half-finished
 * search.
 */
class StockfishEngine private constructor() : ChessEngine {

    override val id: String = "stockfish-18"

    override val isFullStrength: Boolean = true

    private val lines = Channel<String>(Channel.UNLIMITED)
    private val mutex = Mutex()

    /** Strength options are sticky in UCI, so only send them when they actually change. */
    private var appliedLevel: OpponentLevel? = null

    override suspend fun evaluate(position: Position, depth: Int): EvalSnapshot {
        if (position.legalMoves().isEmpty()) {
            return if (position.isInCheck(position.sideToMove)) {
                EvalSnapshot.checkmate(whiteWon = position.sideToMove == Color.BLACK)
            } else {
                EvalSnapshot.drawn()
            }
        }
        val info = search(position, "go depth $depth", OpponentLevel.MAXIMUM)
        return EvalSnapshot.from(info.score, position.sideToMove == Color.WHITE)
    }

    override suspend fun chooseMove(position: Position, level: OpponentLevel): Move? {
        val info = search(position, "go depth ${level.depth}", level)
        return info.bestMoveUci?.let { position.moveFromUci(it) }
    }

    override suspend fun priceMove(position: Position, played: Move, depth: Int): MoveCost {
        // Full strength for analysis regardless of how weakly the opponent is playing:
        // coaching must not be limited by the sparring level.
        val best = search(position, "go depth $depth", OpponentLevel.MAXIMUM)

        // `searchmoves` restricts the search to one move, giving its score on the same scale
        // as the unrestricted search above. Two searches, but exact for the move played.
        val playedInfo = search(
            position = position,
            command = "go depth $depth searchmoves ${played.uci}",
            level = OpponentLevel.MAXIMUM,
        )

        return MoveCost(
            bestMove = best.bestMoveUci?.let { position.moveFromUci(it) },
            bestScore = best.score,
            playedScore = playedInfo.score,
        )
    }

    private suspend fun search(
        position: Position,
        command: String,
        level: OpponentLevel,
    ): UciInfo = mutex.withLock {
        applyLevel(level)
        drain()
        send("position fen ${position.fen}")
        send(command)

        val collected = mutableListOf<String>()
        try {
            while (true) {
                val line = lines.receive()
                collected += line
                if (line.startsWith("bestmove")) break
            }
        } catch (cancelled: CancellationException) {
            // Leave the engine idle and the pipe clean before handing the lock on.
            withContext(NonCancellable) {
                send("stop")
                withTimeoutOrNull(STOP_TIMEOUT_MS) {
                    while (true) if (lines.receive().startsWith("bestmove")) break
                }
            }
            throw cancelled
        }
        UciParser.parse(collected)
    }

    private suspend fun applyLevel(level: OpponentLevel) {
        if (appliedLevel == level) return
        val elo = level.uciElo
        if (elo == null) {
            send("setoption name UCI_LimitStrength value false")
        } else {
            send("setoption name UCI_LimitStrength value true")
            send("setoption name UCI_Elo value $elo")
        }
        awaitReady()
        appliedLevel = level
    }

    private suspend fun awaitReady(): Boolean {
        send("isready")
        return withTimeoutOrNull(READY_TIMEOUT_MS) {
            while (true) if (lines.receive().trim() == "readyok") return@withTimeoutOrNull true
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
    }

    private fun send(command: String) = Stockfish.nativeWrite(command)

    /** Discards anything left from a previous exchange so a stale line cannot be misread. */
    private fun drain() {
        while (lines.tryReceive().isSuccess) Unit
    }

    override fun shutdown() {
        runCatching { send("quit") }
    }

    companion object {
        private const val TAG = "Kibitz/Stockfish"
        private const val READY_TIMEOUT_MS = 60_000L
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * Brings Stockfish up: extract the networks, load the library, start the UCI loop and
         * complete the handshake. Returns null on any failure so the caller can fall back to
         * the Kotlin engine rather than leave the app without an engine at all.
         */
        suspend fun start(context: Context): StockfishEngine? {
            val networkDirectory = NnueNetworks.ensureExtracted(context) ?: return null
            if (!Stockfish.load()) {
                Log.e(TAG, "native library unavailable on this device")
                return null
            }
            if (!withContext(Dispatchers.IO) {
                    Stockfish.nativeStart(networkDirectory.absolutePath)
                }
            ) {
                Log.e(TAG, "native start failed")
                return null
            }

            val engine = StockfishEngine()
            engine.startReader()

            // Loading a 104 MB network takes a moment, so the handshake gets a long timeout.
            if (!engine.handshake()) {
                Log.e(TAG, "UCI handshake failed")
                return null
            }
            return engine
        }
    }

    private fun startReader() {
        // A dedicated thread, because nativeReadLine blocks in JNI and would pin a coroutine
        // dispatcher thread for the lifetime of the app.
        Thread({
            while (true) {
                val line = Stockfish.nativeReadLine() ?: break
                lines.trySend(line)
            }
        }, "stockfish-reader").apply { isDaemon = true }.start()
    }

    private suspend fun handshake(): Boolean {
        send("uci")
        val identified = withTimeoutOrNull(READY_TIMEOUT_MS) {
            while (true) if (lines.receive().trim() == "uciok") return@withTimeoutOrNull true
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
        if (!identified) return false

        send("setoption name Threads value ${recommendedThreads()}")
        send("setoption name Hash value 64")
        return awaitReady()
    }

    /** Leave cores for the UI; a phone throttling itself is worse than a shallower search. */
    private fun recommendedThreads(): Int =
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
}

/** Matches a UCI move string against the legal moves, so promotions resolve correctly. */
internal fun Position.moveFromUci(uci: String): Move? =
    legalMoves().firstOrNull { it.uci.equals(uci, ignoreCase = true) }
