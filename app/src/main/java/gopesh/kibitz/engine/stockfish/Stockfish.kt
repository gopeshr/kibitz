package gopesh.kibitz.engine.stockfish

/**
 * Raw bridge to the native Stockfish process.
 *
 * This is a singleton because the native side redirects the *process's* stdin and stdout onto
 * its pipes — there can only ever be one Stockfish per app process, and a second instance
 * would silently steal the first one's I/O.
 */
internal object Stockfish {

    /** Null until [load] has been attempted; then true or false for good. */
    @Volatile
    private var libraryLoaded: Boolean? = null

    /**
     * Loads the native library. Returns false rather than throwing if the device has no
     * compatible build — callers fall back to the Kotlin engine instead of crashing.
     */
    fun load(): Boolean {
        libraryLoaded?.let { return it }
        synchronized(this) {
            libraryLoaded?.let { return it }
            val loaded = runCatching { System.loadLibrary("stockfish") }.isSuccess
            libraryLoaded = loaded
            return loaded
        }
    }

    /**
     * Starts the UCI loop on a native thread. [workingDirectory] becomes the process working
     * directory, which is how Stockfish finds its `.nnue` networks by their default names.
     */
    external fun nativeStart(workingDirectory: String): Boolean

    external fun nativeWrite(command: String)

    /** Blocks until a full line is available, or returns null once the pipe closes. */
    external fun nativeReadLine(): String?
}
