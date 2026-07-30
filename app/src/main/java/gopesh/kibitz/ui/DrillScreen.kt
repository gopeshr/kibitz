package gopesh.kibitz.ui

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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gopesh.kibitz.DrillState
import gopesh.kibitz.DrillViewModel
import gopesh.kibitz.chess.Color as SideColor
import gopesh.kibitz.ui.theme.Bad
import gopesh.kibitz.ui.theme.Brass
import gopesh.kibitz.ui.theme.Good
import gopesh.kibitz.ui.theme.Ink
import gopesh.kibitz.ui.theme.Muted
import gopesh.kibitz.ui.theme.Parchment
import gopesh.kibitz.ui.theme.Surface1

/**
 * Practice on the player's own mistakes.
 *
 * Every position here is one they actually reached and actually got wrong, which is the whole
 * argument for it over a generic puzzle set: the mistakes are theirs, so the practice is too.
 */
@Composable
fun DrillScreen(
    viewModel: DrillViewModel,
    onDone: () -> Unit,
) {
    // Same reason as the history screen: mistakes made since the last visit have to appear.
    LaunchedEffect(Unit) { viewModel.load() }

    val drill = viewModel.drill
    val progress = viewModel.progress

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    text = "Your mistakes",
                    color = Parchment,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = progress?.let { "${it.solved} of ${it.available} solved" }
                        ?: "Loading…",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
            Box(Modifier.weight(1f))
            if (viewModel.attemptedThisSession > 0) {
                Text(
                    text = "${viewModel.solvedThisSession}/${viewModel.attemptedThisSession} " +
                        "this session",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
            TextButton(onClick = onDone) {
                Text("Done", color = Brass, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            viewModel.state == DrillState.LOADING -> Centered {
                CircularProgressIndicator(color = Brass)
            }

            viewModel.state == DrillState.EMPTY || drill == null -> Centered {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KibitzMark(size = 64, corner = 18)
                    Text(
                        text = "Nothing to practise yet",
                        color = Parchment,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Play a game and I'll collect the moments worth revisiting.",
                        color = Muted,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onDone,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Brass,
                            contentColor = Ink,
                        ),
                    ) {
                        Text("Play a game")
                    }
                }
            }

            else -> {
                Prompt(
                    moveNumber = drill.moveNumber,
                    playedSan = drill.playedSan,
                    centipawnLoss = drill.centipawnLoss,
                    sideToMove = drill.position.sideToMove,
                )

                Spacer(Modifier.weight(1f))

                ChessBoard(
                    position = drill.position,
                    modifier = Modifier.fillMaxWidth(),
                    flipped = viewModel.flipped,
                    selectedSquare = viewModel.selectedSquare,
                    legalTargets = viewModel.legalTargets,
                    // Reusing the last-move highlight to show the answer once it is revealed.
                    lastMove = viewModel.solutionMove,
                    checkedKing = null,
                    plyCount = 0,
                    animateLastMove = false,
                    onSquareTap = viewModel::onSquareTap,
                    onDragStart = viewModel::onDragStart,
                    onDrop = viewModel::onDrop,
                    onDragCancel = viewModel::onDragCancel,
                )

                Spacer(Modifier.weight(1f))

                Feedback(
                    state = viewModel.state,
                    attemptedSan = viewModel.attemptedSan,
                    bestSan = drill.bestSan,
                )

                Spacer(Modifier.height(10.dp))

                if (viewModel.state == DrillState.ASKING) {
                    OutlinedButton(
                        onClick = viewModel::reveal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Show me")
                    }
                } else {
                    Button(
                        onClick = viewModel::next,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Brass,
                            contentColor = Ink,
                        ),
                    ) {
                        Text("Next", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Prompt(
    moveNumber: Int,
    playedSan: String,
    centipawnLoss: Int,
    sideToMove: SideColor,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionLabel("From one of your games")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$moveNumber. $playedSan",
                color = Parchment,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "cost you ${lossPhrase(centipawnLoss)}",
                color = Bad,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            text = "Find the better move for " +
                if (sideToMove == SideColor.WHITE) "White." else "Black.",
            color = Muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun Feedback(state: DrillState, attemptedSan: String?, bestSan: String) {
    val (headline, colour, detail) = when (state) {
        DrillState.CORRECT -> Triple("Correct — $bestSan", Good, "That's the move you missed.")
        DrillState.WRONG -> Triple(
            "The move was $bestSan",
            Bad,
            attemptedSan?.let { "You tried $it." } ?: "Highlighted on the board.",
        )
        else -> Triple(null, Muted, "Make a move when you see it.")
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (headline != null) {
                Text(
                    text = headline,
                    color = colour,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(text = detail, color = Muted, fontSize = 12.sp)
        }
    }
}
