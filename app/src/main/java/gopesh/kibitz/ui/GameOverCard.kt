package gopesh.kibitz.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gopesh.kibitz.chess.Color as SideColor
import gopesh.kibitz.chess.DrawReason
import gopesh.kibitz.chess.Status
import gopesh.kibitz.coach.LevelEstimate
import gopesh.kibitz.ui.theme.Bad
import gopesh.kibitz.ui.theme.Brass
import gopesh.kibitz.ui.theme.Good
import gopesh.kibitz.ui.theme.Ink
import gopesh.kibitz.ui.theme.Muted
import gopesh.kibitz.ui.theme.Parchment
import gopesh.kibitz.ui.theme.Surface1
import gopesh.kibitz.ui.theme.Surface2

/**
 * What happens when a game ends.
 *
 * The result is stated from the player's point of view — "You won", not "White wins" — because
 * a player who took Black should not have to work out which one they were. The review runs
 * while this is on screen, so the wait for it is spent reading rather than staring at a
 * finished board with nothing to do.
 */
@Composable
fun GameOverCard(
    status: Status,
    playerIsWhite: Boolean,
    reviewing: Boolean,
    reviewDone: Int,
    reviewTotal: Int,
    summary: LevelEstimate?,
    onRematch: () -> Unit,
    onNewOpponent: () -> Unit,
    onDismiss: () -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (reviewTotal == 0) 0f else reviewDone.toFloat() / reviewTotal,
        label = "review-progress",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = {
            Column {
                Text(
                    text = headline(status, playerIsWhite),
                    color = accent(status, playerIsWhite),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = detail(status), color = Muted, fontSize = 12.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    reviewing -> {
                        Text(
                            text = "Going back over your moves… $reviewDone of $reviewTotal",
                            color = Muted,
                            fontSize = 13.sp,
                        )
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
                    }

                    summary != null -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Metric("${pawnsLost(summary.averageLoss)}", "avg loss", Modifier.weight(1f))
                            Metric("${summary.blunders}", "blunders", Modifier.weight(1f))
                            Metric("${summary.topMoveRate}%", "top move", Modifier.weight(1f))
                        }
                        summary.costliestMove?.let { worst ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Surface1)
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                SectionLabel("Costliest moment")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${worst.moveNumber}. ${worst.san}",
                                        color = Parchment,
                                        fontSize = 14.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = worst.quality.label,
                                        color = worst.quality.accent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                                worst.bestSan?.let {
                                    Text("$it was better.", color = Muted, fontSize = 11.sp)
                                }
                            }
                        }
                        Text(
                            text = "Saved. Your mistakes from this game are now part of your " +
                                "training history.",
                            color = Muted,
                            fontSize = 11.sp,
                        )
                    }

                    else -> Text(
                        text = "Nothing to review — no moves were judged.",
                        color = Muted,
                        fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRematch,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = Ink),
            ) {
                Text("Rematch", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onNewOpponent, shape = RoundedCornerShape(10.dp)) {
                Text("New opponent")
            }
        },
    )
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Surface1)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = Parchment, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

/** Stated from the player's side, so nobody has to translate "White wins" into a result. */
private fun headline(status: Status, playerIsWhite: Boolean): String = when (status) {
    is Status.Checkmate ->
        if ((status.winner == SideColor.WHITE) == playerIsWhite) "You won" else "Kibitz won"
    // Only the player can resign here, so this is always a loss — but it is read off the winner
    // rather than assumed, so it stays correct if Kibitz is ever allowed to resign too.
    is Status.Resigned ->
        if ((status.winner == SideColor.WHITE) == playerIsWhite) "You won" else "You resigned"
    is Status.Draw -> "Drawn"
    is Status.Ongoing -> "Game over"
}

private fun accent(status: Status, playerIsWhite: Boolean): androidx.compose.ui.graphics.Color =
    when (status) {
        is Status.Checkmate ->
            if ((status.winner == SideColor.WHITE) == playerIsWhite) Good else Bad
        is Status.Resigned ->
            if ((status.winner == SideColor.WHITE) == playerIsWhite) Good else Bad
        else -> Brass
    }

private fun detail(status: Status): String = when (status) {
    is Status.Checkmate -> "by checkmate"
    is Status.Resigned -> "the game was given up"
    is Status.Draw -> when (status.reason) {
        DrawReason.STALEMATE -> "by stalemate"
        DrawReason.FIFTY_MOVE -> "by the fifty-move rule"
        DrawReason.INSUFFICIENT_MATERIAL -> "neither side has enough left to mate"
        DrawReason.THREEFOLD_REPETITION -> "the same position occurred three times"
    }
    is Status.Ongoing -> ""
}
