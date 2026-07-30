package gopesh.kibitz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import gopesh.kibitz.AppViewModel
import gopesh.kibitz.GameViewModel
import gopesh.kibitz.Route
import gopesh.kibitz.ui.theme.Brass

/**
 * Top-level routing. The flow is short and linear — name, level check, verdict, then play —
 * so a state enum reads more clearly here than a navigation graph would.
 */
@Composable
fun KibitzApp(
    app: AppViewModel = viewModel(),
    game: GameViewModel = viewModel(),
) {
    val profile = app.profile

    when (app.route) {
        Route.LOADING -> Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Brass)
        }

        Route.ONBOARDING -> OnboardingScreen(onStart = app::submitName)

        Route.ASSESSMENT -> AssessmentScreen(
            game = game,
            playerName = profile?.shortName ?: "You",
            onComplete = app::onAssessmentComplete,
        )

        Route.RESULT -> {
            val estimate = game.levelEstimate
            // Falling back to the assessment covers process death between finishing a game
            // and reading the verdict, where the estimate is gone but the profile is saved.
            if (estimate == null) {
                AssessmentScreen(
                    game = game,
                    playerName = profile?.shortName ?: "You",
                    onComplete = app::onAssessmentComplete,
                )
            } else {
                ResultScreen(
                    playerName = profile?.shortName ?: "there",
                    estimate = estimate,
                    onStartPlaying = {
                        game.newGame()
                        app.goToPlay()
                    },
                    onPlayAgain = {
                        game.startAssessment()
                        app.retakeAssessment()
                    },
                )
            }
        }

        Route.PLAY -> BoardScreen(
            viewModel = game,
            profile = profile,
            onRetakeAssessment = {
                game.startAssessment()
                app.retakeAssessment()
            },
        )
    }
}
