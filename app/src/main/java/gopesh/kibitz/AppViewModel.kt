package gopesh.kibitz

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gopesh.kibitz.coach.LevelEstimate
import gopesh.kibitz.profile.ProfileStore
import gopesh.kibitz.profile.UserProfile
import kotlinx.coroutines.launch

/** Which screen the app is showing. */
enum class Route { LOADING, ONBOARDING, ASSESSMENT, RESULT, PLAY }

/**
 * Owns the player's profile and which screen is in front.
 *
 * A returning player lands straight on the board: the name and level are already known, so
 * making them sit through onboarding again would be pointless.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ProfileStore(application)

    var profile by mutableStateOf<UserProfile?>(null)
        private set

    var route by mutableStateOf(Route.LOADING)
        private set

    init {
        viewModelScope.launch {
            val saved = store.load()
            profile = saved
            route = when {
                saved == null -> Route.ONBOARDING
                // Named but never assessed — finish what onboarding started.
                !saved.hasLevel -> Route.ASSESSMENT
                else -> Route.PLAY
            }
        }
    }

    fun submitName(name: String) {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return
        viewModelScope.launch {
            val created = UserProfile(name = cleaned)
            store.save(created)
            profile = created
            route = Route.ASSESSMENT
        }
    }

    fun onAssessmentComplete(estimate: LevelEstimate) {
        val current = profile ?: return
        viewModelScope.launch {
            val updated = current.copy(
                ratingLow = estimate.band.ratingLow,
                ratingHigh = estimate.band.ratingHigh,
                bandLabel = estimate.band.label,
                averageLoss = estimate.averageLoss,
                assessmentsCompleted = current.assessmentsCompleted + 1,
            )
            store.save(updated)
            profile = updated
            route = Route.RESULT
        }
    }

    fun goToPlay() {
        route = Route.PLAY
    }

    fun retakeAssessment() {
        route = Route.ASSESSMENT
    }
}
