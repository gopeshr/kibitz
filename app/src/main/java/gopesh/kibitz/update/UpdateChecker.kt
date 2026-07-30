package gopesh.kibitz.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** A version like 0.1.0, compared component by component. */
data class Version(val parts: List<Int>) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        val width = maxOf(parts.size, other.parts.size)
        for (i in 0 until width) {
            val mine = parts.getOrElse(i) { 0 }
            val theirs = other.parts.getOrElse(i) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        /**
         * Parses leniently, because the two sides come from different places: the tag on GitHub
         * ("v0.1.0") and the app's own `versionName` ("0.1"). Missing components count as zero,
         * so 0.1 and 0.1.0 compare equal rather than the app forever thinking it is behind.
         */
        fun parse(text: String): Version {
            val digits = text.trim().removePrefix("v").removePrefix("V")
            val parts = digits.split('.', '-', '+')
                .map { component -> component.takeWhile { it.isDigit() } }
                .filter { it.isNotEmpty() }
                .mapNotNull { it.toIntOrNull() }
            return Version(parts.ifEmpty { listOf(0) })
        }
    }
}

sealed interface UpdateStatus {
    data object UpToDate : UpdateStatus

    data class Available(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
        val isPrerelease: Boolean,
    ) : UpdateStatus

    data class Failed(val message: String) : UpdateStatus
}

/**
 * Looks for a newer build on the project's GitHub releases.
 *
 * Only ever called because someone pressed a button. The app makes no network request otherwise,
 * which is a property worth keeping rather than quietly trading away for a background check.
 *
 * Uses `/releases` rather than `/releases/latest` on purpose: the "latest" endpoint ignores
 * pre-releases entirely and returns 404 when every published release is one, which is exactly
 * the situation this app shipped in.
 */
class UpdateChecker(
    private val repository: String = DEFAULT_REPOSITORY,
    private val includePrereleases: Boolean = true,
) {

    suspend fun check(currentVersionName: String): UpdateStatus = withContext(Dispatchers.IO) {
        val body = runCatching { fetch("$API_ROOT/$repository/releases?per_page=10") }
            .getOrElse { return@withContext UpdateStatus.Failed(readableError(it)) }

        val releases = runCatching { JSONArray(body) }
            .getOrElse { return@withContext UpdateStatus.Failed("Could not read the release list.") }

        val current = Version.parse(currentVersionName)
        var best: UpdateStatus.Available? = null

        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft")) continue
            val prerelease = release.optBoolean("prerelease")
            if (prerelease && !includePrereleases) continue

            val tag = release.optString("tag_name")
            if (tag.isBlank()) continue
            val candidate = Version.parse(tag)
            if (candidate <= current) continue

            // A release with no APK cannot be installed, so it is not an update.
            val assets = release.optJSONArray("assets") ?: continue
            val apk = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: continue

            val found = UpdateStatus.Available(
                version = candidate.toString(),
                notes = release.optString("body").take(NOTES_LIMIT),
                apkUrl = apk.optString("browser_download_url"),
                sizeBytes = apk.optLong("size"),
                isPrerelease = prerelease,
            )
            // Releases arrive newest first, but keep the highest version rather than trusting
            // the ordering — a tag can be published out of order.
            if (best == null || Version.parse(found.version) > Version.parse(best.version)) {
                best = found
            }
        }

        best ?: UpdateStatus.UpToDate
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub rejects requests with no user agent.
            setRequestProperty("User-Agent", "Kibitz-Updater")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("GitHub returned ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Network failures are normal, not exceptional, so they get a sentence rather than a stack. */
    private fun readableError(cause: Throwable): String = when (cause) {
        is java.net.UnknownHostException -> "No connection."
        is java.net.SocketTimeoutException -> "GitHub took too long to answer."
        else -> cause.message?.takeIf { it.isNotBlank() } ?: "Could not reach GitHub."
    }

    companion object {
        const val DEFAULT_REPOSITORY = "gopeshr/kibitz"
        private const val API_ROOT = "https://api.github.com/repos"
        private const val TIMEOUT_MS = 15_000
        private const val NOTES_LIMIT = 4_000
    }
}
