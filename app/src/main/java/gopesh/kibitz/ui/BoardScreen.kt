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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import gopesh.kibitz.GameViewModel
import gopesh.kibitz.chess.Color as SideColor
import gopesh.kibitz.chess.DrawReason
import gopesh.kibitz.chess.PieceType
import gopesh.kibitz.chess.Position
import gopesh.kibitz.chess.Status
import gopesh.kibitz.engine.EvalSnapshot
import gopesh.kibitz.profile.UserProfile
import gopesh.kibitz.ui.theme.Bad
import gopesh.kibitz.ui.theme.Brass
import gopesh.kibitz.ui.theme.Ink
import gopesh.kibitz.ui.theme.Muted
import gopesh.kibitz.ui.theme.Parchment
import gopesh.kibitz.ui.theme.SquareDark
import gopesh.kibitz.ui.theme.Surface1
import gopesh.kibitz.ui.theme.Surface2

@Composable
fun BoardScreen(
    viewModel: GameViewModel = viewModel(),
    profile: UserProfile? = null,
    onRetakeAssessment: (() -> Unit)? = null,
    onNewGame: (() -> Unit)? = null,
) {
    val position = viewModel.position
    val status = viewModel.status
    val engineSide = viewModel.engineSide
    val playerIsWhite = engineSide != SideColor.WHITE

    // Reset every time a new game starts, so a dismissed card does not stay dismissed.
    var showResult by remember(viewModel.plyCount == 0) { mutableStateOf(true) }
    var confirmResign by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (profile != null) {
            ProfileBar(profile = profile, onRetakeAssessment = onRetakeAssessment)
            Spacer(Modifier.height(10.dp))
        }

        StatusHeader(
            position = position,
            status = status,
            evaluation = viewModel.evaluation,
            // Once dismissed the result must still be reachable, or the player is stranded on
            // a finished board.
            onShowResult = if (engineSide != null && viewModel.isGameOver && !showResult) {
                { showResult = true }
            } else {
                null
            },
        )

        // The board gets the height that is left over after the fixed chrome, and is centred
        // in it. It used to sit between two weighted spacers while sizing itself from the
        // screen width alone, so on a short screen it overflowed and pushed the move log and
        // the entire controls row — Resign with them — off the bottom of the window.
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // With an engine opponent the board gets name plates, the same way the level check
            // does, so it is always clear who is on the clock and how strong they are.
            if (engineSide != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerStrip(
                        name = "Kibitz",
                        subtitle = if (viewModel.engineThinking) {
                            "Thinking…"
                        } else {
                            viewModel.opponentLevel.label +
                                (viewModel.opponentLevel.uciElo?.let { " · $it" } ?: "")
                        },
                        isWhite = engineSide == SideColor.WHITE,
                        isActive = position.sideToMove == engineSide,
                        trailing = {
                            if (viewModel.engineThinking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Brass,
                                    strokeWidth = 2.dp,
                                )
                            }
                        },
                    )
                    // The plates are measured first; fill = false keeps the board at its
                    // square size when there is more height than it needs.
                    BoardWithEvalBar(
                        game = viewModel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    )
                    PlayerStrip(
                        name = profile?.shortName ?: "You",
                        subtitle = "You — ${if (playerIsWhite) "White" else "Black"}",
                        isWhite = playerIsWhite,
                        isActive = position.sideToMove != engineSide,
                    )
                }
            } else {
                BoardWithEvalBar(game = viewModel, modifier = Modifier.fillMaxWidth())
            }
        }

        MoveLog(moveLog = viewModel.moveLog)

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                // "New" means choosing an opponent, not silently dropping to a board with
                // nobody on the other side.
                onClick = onNewGame ?: viewModel::newGame,
                modifier = Modifier.weight(1f),
            ) {
                Text("New")
            }
            OutlinedButton(onClick = viewModel::flipBoard, modifier = Modifier.weight(1f)) {
                Text("Flip")
            }
            if (engineSide != null) {
                // Undo is meaningless against an opponent, so the slot carries the action that
                // a losing position actually needs.
                OutlinedButton(
                    onClick = { confirmResign = true },
                    enabled = viewModel.canResign,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Resign")
                }
            } else {
                OutlinedButton(
                    onClick = viewModel::undo,
                    enabled = viewModel.canUndo,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Undo")
                }
            }
        }
    }

    viewModel.promotionPrompt?.let {
        PromotionDialog(
            side = position.sideToMove,
            onPick = viewModel::choosePromotion,
            onDismiss = viewModel::cancelPromotion,
        )
    }

    if (confirmResign) {
        AlertDialog(
            onDismissRequest = { confirmResign = false },
            containerColor = Surface2,
            title = { Text("Resign this game?", color = Parchment, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "It counts as a loss, and there is no taking it back. The game will " +
                        "still be reviewed and its mistakes kept for practice.",
                    color = Muted,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmResign = false
                        showResult = true
                        viewModel.resign()
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Bad, contentColor = Ink),
                ) {
                    Text("Resign", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { confirmResign = false },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Keep playing")
                }
            },
        )
    }

    // Only against an engine: a two-player board has no "you" to report a result to.
    if (engineSide != null && viewModel.isGameOver && showResult) {
        GameOverCard(
            status = status,
            playerIsWhite = playerIsWhite,
            reviewing = viewModel.reviewing,
            reviewDone = viewModel.reviewDone,
            reviewTotal = viewModel.reviewTotal,
            summary = viewModel.reviewSummary,
            onRematch = {
                showResult = true
                viewModel.rematch()
            },
            onNewOpponent = { onNewGame?.invoke() },
            // Dismissing leaves the finished board visible to look over, and the card can be
            // brought back from the status header.
            onDismiss = { showResult = false },
        )
    }
}

/** Who is playing and what Kibitz currently thinks of them. */
@Composable
private fun ProfileBar(profile: UserProfile, onRetakeAssessment: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KibitzMark(size = 34, corner = 10)
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                text = profile.shortName,
                color = Parchment,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (profile.hasLevel) {
                    "${profile.bandLabel} · ${profile.ratingText}"
                } else {
                    "Unrated"
                },
                color = Muted,
                fontSize = 11.sp,
            )
        }
        Box(Modifier.weight(1f))
        if (onRetakeAssessment != null) {
            TextButton(onClick = onRetakeAssessment) {
                Text("Level check", color = Brass, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StatusHeader(
    position: Position,
    status: Status,
    evaluation: EvalSnapshot?,
    onShowResult: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // A dot in the colour of whoever is on the clock.
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(
                    if (position.sideToMove == SideColor.WHITE) Parchment else SquareDark
                ),
        )
        Spacer(Modifier.size(10.dp))
        Column {
            Text(
                text = headlineFor(position, status),
                color = Parchment,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Move ${position.fullmoveNumber}",
                color = Muted,
                fontSize = 12.sp,
            )
        }
        Box(Modifier.weight(1f))
        if (onShowResult != null) {
            TextButton(onClick = onShowResult) {
                Text("Result", color = Brass, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        EvalReadout(evaluation)
    }
}

private fun headlineFor(position: Position, status: Status): String = when (status) {
    is Status.Checkmate -> "Checkmate — ${status.winner.label} wins"
    is Status.Resigned -> "Resigned — ${status.winner.label} wins"
    is Status.Draw -> when (status.reason) {
        DrawReason.STALEMATE -> "Draw — stalemate"
        DrawReason.FIFTY_MOVE -> "Draw — fifty-move rule"
        DrawReason.INSUFFICIENT_MATERIAL -> "Draw — insufficient material"
        DrawReason.THREEFOLD_REPETITION -> "Draw — threefold repetition"
    }
    is Status.Ongoing ->
        if (status.inCheck) "${position.sideToMove.label} to move — check"
        else "${position.sideToMove.label} to move"
}

private val SideColor.label: String
    get() = if (this == SideColor.WHITE) "White" else "Black"

@Composable
private fun MoveLog(moveLog: List<String>) {
    val pairs = remember(moveLog) {
        moveLog.chunked(2).mapIndexed { index, ply ->
            "${index + 1}. ${ply.joinToString(" ")}"
        }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(pairs.size) {
        if (pairs.isNotEmpty()) listState.animateScrollToItem(pairs.lastIndex)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Surface1),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (pairs.isEmpty()) {
            Text(
                text = "No moves yet — tap a piece to begin",
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        } else {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(pairs.size) { index ->
                    Text(
                        text = pairs[index],
                        color = if (index == pairs.lastIndex) Parchment else Muted,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PromotionDialog(
    side: SideColor,
    onPick: (PieceType) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("Promote to", color = Parchment) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                for (type in listOf(
                    PieceType.QUEEN,
                    PieceType.ROOK,
                    PieceType.BISHOP,
                    PieceType.KNIGHT,
                )) {
                    Button(
                        onClick = { onPick(type) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Surface1,
                            contentColor = if (side == SideColor.WHITE) Parchment else SquareDark,
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Text(
                            text = glyphFor(type),
                            fontSize = 28.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Brass)
            }
        },
    )
}
