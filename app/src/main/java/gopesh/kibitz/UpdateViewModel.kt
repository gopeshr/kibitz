package gopesh.kibitz

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gopesh.kibitz.update.ApkInstaller
import gopesh.kibitz.update.UpdateChecker
import gopesh.kibitz.update.UpdateStatus
import kotlinx.coroutines.launch
import java.io.File

/** Where the update flow currently is. */
enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }

/**
 * Checking for and installing a new build.
 *
 * Nothing here happens on its own. There is no check on launch, no periodic poll and no
 * background work — the app reaches the network only when the player asks it to, which is the
 * only way "runs entirely on your phone" stays true.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val checker = UpdateChecker()

    var phase by mutableStateOf(UpdatePhase.IDLE)
        private set

    var available by mutableStateOf<UpdateStatus.Available?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var downloadProgress by mutableStateOf(0f)
        private set

    private var downloaded: File? = null

    /** The version the app is currently running, for display and for comparison. */
    val currentVersion: String = runCatching {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "unknown" }

    fun check() {
        if (phase == UpdatePhase.CHECKING || phase == UpdatePhase.DOWNLOADING) return
        viewModelScope.launch {
            phase = UpdatePhase.CHECKING
            message = null
            when (val result = checker.check(currentVersion)) {
                is UpdateStatus.UpToDate -> {
                    available = null
                    phase = UpdatePhase.UP_TO_DATE
                }
                is UpdateStatus.Available -> {
                    available = result
                    phase = UpdatePhase.AVAILABLE
                }
                is UpdateStatus.Failed -> {
                    message = result.message
                    phase = UpdatePhase.FAILED
                }
            }
        }
    }

    fun download() {
        val update = available ?: return
        if (phase == UpdatePhase.DOWNLOADING) return
        viewModelScope.launch {
            phase = UpdatePhase.DOWNLOADING
            downloadProgress = 0f
            val file = ApkInstaller.download(
                context = getApplication(),
                url = update.apkUrl,
                expectedBytes = update.sizeBytes,
                onProgress = { downloadProgress = it },
            )
            if (file == null) {
                message = "The download did not finish."
                phase = UpdatePhase.FAILED
                return@launch
            }
            downloaded = file
            phase = UpdatePhase.READY
        }
    }

    /**
     * Opens the system installer, or sends the player to grant permission first — without it the
     * install dialog never appears and the update looks like it silently failed.
     */
    fun install() {
        val file = downloaded ?: return
        val context = getApplication<Application>()
        if (!ApkInstaller.canInstall(context)) {
            message = "Android needs permission to install apps from Kibitz. " +
                "Allow it on the screen that just opened, then press Install again."
            ApkInstaller.requestInstallPermission(context)
            return
        }
        if (!ApkInstaller.install(context, file)) {
            message = "Could not open the installer."
            phase = UpdatePhase.FAILED
        }
    }

    fun dismiss() {
        phase = UpdatePhase.IDLE
        message = null
        downloadProgress = 0f
    }
}
