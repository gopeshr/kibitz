package gopesh.kibitz

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gopesh.kibitz.coach.LevelEstimate
import gopesh.kibitz.data.GameStore
import gopesh.kibitz.profile.ProfileStore
import gopesh.kibitz.profile.UserProfile
import kotlinx.coroutines.launch

/** Which screen the app is showing. */
enum class Route { LOADING, ONBOARDING, ASSESSMENT, RESULT, NEW_GAME, PLAY, DRILLS, HISTORY }

/**
 * Owns the player's profile and which screen is in front.
 *
 * A returning player lands straight on the board: the name and level are already known, so
 * making them sit through onboarding again would be pointless.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ProfileStore(application)
    private val games = GameStore(application)

    var profile by mutableStateOf<UserProfile?>(null)
        private set

    var route by mutableStateOf(Route.LOADING)
        private set

    /**
     * Where back goes. Without this the system back gesture pops the activity and leaves the
     * app entirely, which is wrong everywhere except the screen the app opens on.
     */
    private val backStack = mutableListOf<Route>()

    val canGoBack: Boolean get() = backStack.isNotEmpty()

    /** Moves to [destination], remembering where we came from. */
    private fun navigateTo(destination: Route) {
        if (destination == route) return
        backStack.add(route)
        route = destination
    }

    /**
     * Pops one screen. Returns false when there is nowhere left to go, so the caller can let the
     * system close the app instead of swallowing the gesture.
     */
    fun goBack(): Boolean {
        val previous = backStack.removeLastOrNull() ?: return false
        // Never reverse into a transient screen: LOADING is gone for good, and returning to a
        // finished level check or its result would be a dead end.
        route = when (previous) {
            Route.LOADING, Route.ASSESSMENT, Route.RESULT, Route.ONBOARDING -> Route.NEW_GAME
            else -> previous
        }
        return true
    }

    /** Forgets the history, for points where going back would make no sense. */
    private fun resetBackStack() = backStack.clear()

    init {
        viewModelScope.launch {
            val saved = store.load()
            profile = saved
            route = when {
                saved == null -> Route.ONBOARDING
                // Named but never assessed — finish what onboarding started, unless they have
                // already said no. Forcing it again would trap anyone who cannot complete it.
                !saved.hasLevel && !saved.assessmentDeclined -> Route.ASSESSMENT
                // The home screen either way: it lists any unfinished games to continue, which
                // is better than silently reopening whichever one happened to be last.
                else -> Route.NEW_GAME
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

    fun goToPlay() = navigateTo(Route.PLAY)

    /** The app's home. Back from here should close the app, so the history is dropped. */
    fun goToNewGame() {
        resetBackStack()
        route = Route.NEW_GAME
    }

    fun goToDrills() = navigateTo(Route.DRILLS)

    fun goToHistory() = navigateTo(Route.HISTORY)

    fun retakeAssessment() {
        resetBackStack()
        route = Route.ASSESSMENT
    }

    /** Abandoning the level check without a result: land on the home screen, unrated. */
    fun skipAssessment() {
        val current = profile ?: return
        viewModelScope.launch {
            val updated = current.copy(assessmentDeclined = true)
            store.save(updated)
            profile = updated
            goToNewGame()
        }
    }
}
