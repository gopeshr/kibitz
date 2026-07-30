package gopesh.kibitz.engine.stockfish

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

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
            // Already there: trust it and read nothing. Comparing against the asset's length
            // would mean decompressing 104 MB on every launch just to learn its size, since
            // the assets are stored deflated and AssetManager cannot report that cheaply.
            if (destination.length() > 0) continue

            Log.i(TAG, "extracting $name")
            val copied = runCatching {
                context.assets.open(name).use { input ->
                    destination.outputStream().buffered(1 shl 16).use { output ->
                        input.copyTo(output, bufferSize = 1 shl 16)
                    }
                }
            }
            if (copied.isFailure) {
                Log.e(TAG, "failed to extract $name", copied.exceptionOrNull())
                destination.delete()
                return@withContext null
            }

            // Verified on arrival rather than on every launch. Stockfish names each network
            // after the first 12 hex digits of its SHA-256, so the filename is the checksum —
            // the same trick the Gradle fetch task uses, and it catches a truncated copy that
            // a length check could not.
            val expected = name.removePrefix("nn-").removeSuffix(".nnue")
            val actual = sha256Prefix(destination)
            if (actual != expected) {
                Log.e(TAG, "$name failed verification: expected $expected, got $actual")
                destination.delete()
                return@withContext null
            }
            Log.i(TAG, "extracted and verified $name (${destination.length() / 1_048_576} MB)")
        }
        target
    }

    private fun sha256Prefix(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    }
}
