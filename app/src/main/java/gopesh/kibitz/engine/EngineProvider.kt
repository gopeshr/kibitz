package gopesh.kibitz.engine

import android.content.Context
import android.util.Log
import gopesh.kibitz.engine.stockfish.StockfishEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hands out the best engine currently available.
 *
 * Stockfish needs to extract a 104 MB network and load it before it can answer anything, which
 * takes long enough to notice on a first launch. Rather than block the board behind a spinner,
 * the Kotlin engine answers from the first frame and Stockfish replaces it the moment it is
 * ready. Nothing above this class has to know the swap happened.
 *
 * A process-wide singleton because the native engine is process-wide: see
 * [gopesh.kibitz.engine.stockfish.Stockfish].
 */
object EngineProvider {

    private const val TAG = "Kibitz/Engine"

    private val fallback = KotlinEngine()
    private val startupLock = Mutex()

    private val _engine = MutableStateFlow<ChessEngine>(fallback)

    /** The engine to use right now. Observe it to react when Stockfish arrives. */
    val engine: StateFlow<ChessEngine> = _engine.asStateFlow()

    private var startupAttempted = false

    /**
     * Begins bringing Stockfish up, once per process. Safe to call from every view model
     * `init`; later calls are no-ops.
     */
    fun warmUp(context: Context, scope: CoroutineScope) {
        scope.launch {
            startupLock.withLock {
                if (startupAttempted) return@withLock
                startupAttempted = true

                val stockfish = runCatching { StockfishEngine.start(context) }
                    .onFailure { Log.e(TAG, "Stockfish failed to start", it) }
                    .getOrNull()

                if (stockfish != null) {
                    _engine.value = stockfish
                    Log.i(TAG, "Stockfish ready; ${stockfish.id} now serving")
                } else {
                    // Not fatal: the app stays playable, just not at full strength.
                    Log.w(TAG, "continuing on ${fallback.id}")
                }
            }
        }
    }

    /** Current engine, for one-off calls that do not need to observe changes. */
    fun current(): ChessEngine = _engine.value
}
