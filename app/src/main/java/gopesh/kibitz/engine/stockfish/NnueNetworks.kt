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
    internal val NETWORKS = listOf(
        "nn-c288c895ea92.nnue",
        "nn-37f18f62d772.nnue",
    )

    /**
     * Exact byte length of each network, so a half-written one can be recognised in constant
     * time on every launch.
     *
     * Trusting any non-empty file used to be enough to hard-brick the app: an extraction cut
     * short — the player switching away during the first launch, or the system reclaiming the
     * process — left a partial network that every later launch accepted without re-reading.
     * Stockfish then aborted on the truncated net (SIGABRT), on that launch and every one
     * after, and no amount of reopening the app could recover it. Reproduced with the big net
     * at 11,993,088 of 108,919,594 bytes.
     *
     * A length check is O(1) where verifying the SHA-256 means reading 104 MB, and it is only
     * a guard against truncation — the checksum below is what actually establishes the
     * contents, and it still runs on arrival.
     */
    internal val EXPECTED_BYTES = mapOf(
        "nn-c288c895ea92.nnue" to 108_919_594L,
        "nn-37f18f62d772.nnue" to 3_519_630L,
    )

    /** Suffix for a network still being written. Never handed to Stockfish. */
    private const val STAGING_SUFFIX = ".part"

    /**
     * Ensures both networks are present, returning the directory holding them, or null if
     * extraction failed and Stockfish therefore cannot run.
     */
    suspend fun ensureExtracted(context: Context): File? = withContext(Dispatchers.IO) {
        val target = File(context.filesDir, "stockfish").apply { mkdirs() }

        for (name in NETWORKS) {
            val destination = File(target, name)
            // Present and the right length: trust it and read no further. A file of the wrong
            // length is a half-written one from an interrupted launch, and is replaced rather
            // than handed to Stockfish, which would abort on it.
            val wanted = EXPECTED_BYTES.getValue(name)
            val found = destination.length()
            if (found == wanted) continue
            if (found > 0) {
                Log.w(TAG, "$name is $found bytes, expected $wanted — extracting again")
                destination.delete()
            }

            Log.i(TAG, "extracting $name")
            // Written under a temporary name and moved into place only once verified, so a
            // network that Stockfish can see is always a complete one. Without this, killing
            // the app mid-copy leaves a partial file under the real name.
            val staging = File(target, name + STAGING_SUFFIX)
            staging.delete()
            val copied = runCatching {
                context.assets.open(name).use { input ->
                    staging.outputStream().buffered(1 shl 16).use { output ->
                        input.copyTo(output, bufferSize = 1 shl 16)
                    }
                }
            }
            if (copied.isFailure) {
                Log.e(TAG, "failed to extract $name", copied.exceptionOrNull())
                staging.delete()
                return@withContext null
            }

            // Stockfish names each network after the first 12 hex digits of its SHA-256, so the
            // filename is the checksum — the same trick the Gradle fetch task uses. This is what
            // establishes the contents; the length check above only catches truncation cheaply.
            val expected = name.removePrefix("nn-").removeSuffix(".nnue")
            val actual = sha256Prefix(staging)
            if (actual != expected) {
                Log.e(TAG, "$name failed verification: expected $expected, got $actual")
                staging.delete()
                return@withContext null
            }
            if (!staging.renameTo(destination)) {
                Log.e(TAG, "could not move $name into place")
                staging.delete()
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
