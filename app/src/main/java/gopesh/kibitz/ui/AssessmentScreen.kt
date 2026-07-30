package gopesh.kibitz.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gopesh.kibitz.GameViewModel
import gopesh.kibitz.chess.Color as SideColor
import gopesh.kibitz.coach.LevelEstimate
import gopesh.kibitz.coach.MoveAssessment
import gopesh.kibitz.coach.MoveQuality
import gopesh.kibitz.ui.theme.Brass
import gopesh.kibitz.ui.theme.Ink
import gopesh.kibitz.ui.theme.Surface2
import gopesh.kibitz.ui.theme.Muted
import gopesh.kibitz.ui.theme.Parchment
import gopesh.kibitz.ui.theme.Surface1

/**
 * The assessment game. The player has White; Kibitz replies and judges each move as it lands.
 *
 * Feedback appears move by move rather than only at the end, because a verdict you cannot
 * connect to a specific move teaches nothing.
 */
@Composable
fun AssessmentScreen(
    game: GameViewModel,
    playerName: String,
    onComplete: (LevelEstimate) -> Unit,
    onSkip: (() -> Unit)? = null,
) {
    var confirmStop by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!game.isAssessing && !game.assessmentComplete) game.startAssessment()
    }

    LaunchedEffect(game.assessmentComplete) {
        if (game.assessmentComplete) game.levelEstimate?.let(onComplete)
    }

    val done = game.assessments.size
    val target = game.assessmentTarget.coerceAtLeast(1)
    val progress by animateFloatAsState(
        targetValue = (done.toFloat() / target).coerceIn(0f, 1f),
        label = "assessment-progress",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Level check")
            Box(Modifier.weight(1f))
            Text(
                text = "Move ${(done + 1).coerceAtMost(target)} of $target",
                color = Muted,
                fontSize = 12.sp,
            )
            // Thirty moves is a long commitment, and this screen used to have no exit at all.
            TextButton(onClick = { confirmStop = true }) {
                Text(
                    text = if (game.canResign) "Resign" else "Skip",
                    color = Brass,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = Brass,
            trackColor = Surface1,
            drawStopIndicator = {},
        )

        Spacer(Modifier.weight(1f))

        // The two name plates belong against the board, not floating at the screen edges,
        // so they travel with it as one centred group.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerStrip(
                name = "Kibitz",
                subtitle = if (game.engineThinking) "Thinking…" else "Steady",
                isWhite = false,
                isActive = game.position.sideToMove == SideColor.BLACK,
                trailing = {
                    if (game.engineThinking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Brass,
                            strokeWidth = 2.dp,
                        )
                    }
                },
            )

            BoardWithEvalBar(game = game, modifier = Modifier.fillMaxWidth())

            PlayerStrip(
                name = playerName,
                subtitle = "You — White",
                isWhite = true,
                isActive = game.position.sideToMove == SideColor.WHITE,
                trailing = { EvalReadout(game.evaluation) },
            )
        }

        Spacer(Modifier.weight(1f))

        FeedbackCard(latest = game.latestAssessment, thinking = game.engineThinking)
    }

    if (confirmStop) {
        val resigning = game.canResign
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            containerColor = Surface2,
            title = {
                Text(
                    text = if (resigning) "Resign the level check?" else "Skip the level check?",
                    color = Parchment,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = if (resigning) {
                        "It counts as a loss. You will still get an estimate, but from only the " +
                            "moves you have played, so it will be a rough one."
                    } else {
                        "You can play without a level, and take the check whenever you like from " +
                            "the board screen."
                    },
                    color = Muted,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmStop = false
                        if (resigning) game.resign() else onSkip?.invoke()
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = Ink),
                ) {
                    Text(if (resigning) "Resign" else "Skip", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { confirmStop = false },
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Keep playing") }
            },
        )
    }

    game.promotionPrompt?.let {
        PromotionDialog(
            side = game.position.sideToMove,
            onPick = game::choosePromotion,
            onDismiss = game::cancelPromotion,
        )
    }
}

/** Centipawns are engine units; players think in pawns, so show pawns to one decimal. */
internal fun pawnsLost(centipawns: Int): String {
    val whole = centipawns / 100
    val tenths = (centipawns % 100) / 10
    return "$whole.$tenths"
}

/**
 * Above this, a loss is not a material count at all — it is the difference between a normal
 * evaluation and a mate score, so rendering it as pawns produces things like "999.3".
 * No real material swing comes close: every piece on the board is worth about 100 pawns.
 */
private const val FORCED_LOSS_CENTIPAWNS = 5_000

internal fun isForcedLoss(centipawns: Int): Boolean = centipawns >= FORCED_LOSS_CENTIPAWNS

/** For "cost you …". A mate is not a quantity of pawns, so it is named rather than counted. */
internal fun lossPhrase(centipawns: Int): String =
    if (isForcedLoss(centipawns)) "the game" else "${pawnsLost(centipawns)} pawns"

/** Compact form for a chip beside a move. */
internal fun lossChip(centipawns: Int): String =
    if (isForcedLoss(centipawns)) "allows mate" else "−${pawnsLost(centipawns)}"

/** The running commentary: what the last move was worth, and what would have been better. */
@Composable
private fun FeedbackCard(latest: MoveAssessment?, thinking: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (latest == null) {
            Text(
                text = if (thinking) "Let me think…" else "Make your first move whenever you're ready.",
                color = Muted,
                fontSize = 13.sp,
            )
            return@Box
        }

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = latest.san,
                    color = Parchment,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = latest.quality.label,
                    color = latest.quality.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp),
                )
                if (latest.quality != MoveQuality.BEST && latest.centipawnLoss > 0) {
                    Text(
                        text = lossChip(latest.centipawnLoss),
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            val better = latest.bestSan
            Text(
                text = when {
                    latest.quality == MoveQuality.BEST -> "Nothing was better here."
                    better != null -> "$better was stronger."
                    else -> "There was a better option."
                },
                color = Muted,
                fontSize = 12.sp,
            )
        }
    }
}
