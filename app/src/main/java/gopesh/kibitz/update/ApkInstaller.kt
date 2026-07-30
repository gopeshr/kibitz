package gopesh.kibitz.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an update and hands it to Android's package installer.
 *
 * What keeps this safe is not anything here: Android refuses to install an APK signed by a
 * different key than the installed app, so a substituted download cannot replace Kibitz. The
 * download is over HTTPS from the project's own releases, and the user still has to confirm the
 * install in the system dialog. This code cannot install anything silently.
 */
object ApkInstaller {

    private const val TAG = "Kibitz/Update"

    /** Kept out of `filesDir` so a half-finished download is cleaned up with the cache. */
    private fun downloadDirectory(context: Context) =
        File(context.cacheDir, "updates").apply { mkdirs() }

    /**
     * Fetches the APK, reporting progress as a 0..1 fraction. Returns null on failure.
     *
     * The whole file is verified only by its length, because GitHub does not publish a digest
     * for release assets. The real integrity check happens at install time, where the signature
     * has to match — a truncated or tampered file is rejected there rather than here.
     */
    suspend fun download(
        context: Context,
        url: String,
        expectedBytes: Long,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val destination = File(downloadDirectory(context), "kibitz-update.apk")
        destination.delete()

        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Kibitz-Updater")
            }
        }.getOrElse {
            Log.e(TAG, "could not open $url", it)
            return@withContext null
        }

        try {
            if (connection.responseCode !in 200..299) {
                Log.e(TAG, "download failed with ${connection.responseCode}")
                return@withContext null
            }
            val total = if (expectedBytes > 0) expectedBytes else connection.contentLengthLong
            var written = 0L
            connection.inputStream.use { input ->
                destination.outputStream().buffered(1 shl 16).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (total > 0 && written != total) {
                Log.e(TAG, "download truncated: $written of $total bytes")
                destination.delete()
                return@withContext null
            }
            onProgress(1f)
            destination
        } catch (failure: Exception) {
            Log.e(TAG, "download failed", failure)
            destination.delete()
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * True when the system will let this app ask to install. Without it the install dialog never
     * appears and the update looks like it silently did nothing.
     */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Sends the user to the one settings screen that can grant it. */
    fun requestInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.e(TAG, "could not open install-permission settings", it) }
    }

    /** Opens the system installer. The user confirms; Android checks the signature. */
    fun install(context: Context, apk: File): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        }.getOrElse {
            Log.e(TAG, "could not share the downloaded APK", it)
            return false
        }

        @Suppress("DEPRECATION") // The PackageInstaller session API buys nothing for a single APK.
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        return runCatching { context.startActivity(intent); true }
            .getOrElse {
                Log.e(TAG, "no installer available", it)
                false
            }
    }
}
