package gopesh.kibitz.engine.stockfish

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gets Stockfish's neural networks out of the APK and onto the filesystem, because Stockfish
 * opens them as ordinary files and cannot read from an Android asset.
 *
 * The big network is 104 MB, so this copy is done once and then skipped on every later launch
 * by comparing sizes. Note the cost of that decision: the weights exist twice on the device,
 * once inside the APK and once extracted. Streaming them straight out of the APK would need a
 * custom `std::streambuf` over the asset file descriptor on the native side.
 */
object NnueNetworks {

    private const val TAG = "Kibitz/NNUE"

    /**
     * Names must match `EvalFileDefaultNameBig` / `EvalFileDefaultNameSmall` in Stockfish's
     * `evaluate.h`. Stockfish looks them up by exactly these names in its working directory,
     * so renaming them here would silently leave it without an evaluation.
     */
    private val NETWORKS = listOf(
        "nn-c288c895ea92.nnue",
        "nn-37f18f62d772.nnue",
    )

    /**
     * Ensures both networks are present, returning the directory holding them, or null if
     * extraction failed and Stockfish therefore cannot run.
     */
    suspend fun ensureExtracted(context: Context): File? = withContext(Dispatchers.IO) {
        val target = File(context.filesDir, "stockfish").apply { mkdirs() }

        for (name in NETWORKS) {
            val destination = File(target, name)
            val expected = assetSize(context, name)
            if (expected <= 0) {
                Log.e(TAG, "asset $name missing from the APK")
                return@withContext null
            }
            if (destination.length() == expected) continue

            Log.i(TAG, "extracting $name (${expected / 1_048_576} MB)")
            val copied = runCatching {
                context.assets.open(name).use { input ->
                    destination.outputStream().buffered(1 shl 16).use { output ->
                        input.copyTo(output, bufferSize = 1 shl 16)
                    }
                }
            }
            if (copied.isFailure || destination.length() != expected) {
                Log.e(TAG, "failed to extract $name", copied.exceptionOrNull())
                destination.delete()
                return@withContext null
            }
        }
        target
    }

    /** Uncompressed length of a bundled asset, or -1 if it is not there. */
    private fun assetSize(context: Context, name: String): Long =
        runCatching {
            context.assets.openFd(name).use { it.length }
        }.recoverCatching {
            // openFd only works for uncompressed assets; fall back to counting bytes.
            context.assets.open(name).use { input ->
                var total = 0L
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
        }.getOrDefault(-1L)
}
