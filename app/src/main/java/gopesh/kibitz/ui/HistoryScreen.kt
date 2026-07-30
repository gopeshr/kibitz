package gopesh.kibitz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import gopesh.kibitz.HistoryViewModel
import gopesh.kibitz.coach.MoveQuality
import gopesh.kibitz.data.GameRecord
import gopesh.kibitz.data.MoveRecord
import gopesh.kibitz.engine.OpponentLevel
import gopesh.kibitz.ui.theme.Bad
import gopesh.kibitz.ui.theme.Brass
import gopesh.kibitz.ui.theme.Good
import gopesh.kibitz.ui.theme.Muted
import gopesh.kibitz.ui.theme.Parchment
import gopesh.kibitz.ui.theme.Surface1
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Past games, and the moves inside them.
 *
 * Shows accuracy oldest-first rather than as a single lifetime average, because "am I getting
 * better" is the question a training app exists to answer and an average hides it.
 */
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onDone: () -> Unit) {
    // The view model outlives this screen, so re-reading on entry is what keeps a game played
    // since the last visit from being missing.
    LaunchedEffect(Unit) { viewModel.refresh() }

    val open = viewModel.openGame

    // Nested back: close the open game first. Enabled only when there is one, so otherwise the
    // app-level handler takes the gesture and leaves the screen.
    BackHandler(enabled = open != null) { viewModel.closeGame() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (open == null) "Your games" else "Game review",
                    color = Parchment,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (open == null) {
                        "${viewModel.games.size} recorded"
                    } else {
                        "${open.movesAssessed} of your moves judged"
                    },
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
            TextButton(onClick = { if (!viewModel.closeGame()) onDone() }) {
                Text(if (open == null) "Done" else "Back", color = Brass, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            viewModel.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Brass)
            }

            open != null -> GameDetail(open, viewModel.openGameMoves)

            viewModel.games.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No games yet", color = Parchment, fontSize = 16.sp)
                    Text(
                        text = "Finish a game and it will be reviewed and kept here.",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
            }

            else -> {
                viewModel.accuracy?.let { AccuracyHeader(it, viewModel.trend) }
                Spacer(Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(viewModel.games) { game ->
                        GameRow(game) { viewModel.open(game) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccuracyHeader(
    accuracy: gopesh.kibitz.data.AccuracySummary,
    trend: List<Int>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionLabel("Across every game")
        Row {
            Text(
                text = pawnsLost(accuracy.averageLoss.toInt()),
                color = Parchment,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "pawns lost per move, over ${accuracy.movesJudged} moves",
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp),
            )
        }
        // Comparing the first few games with the last few is the honest version of a trend:
        // enough to say "better" or "not yet" without pretending to a regression line.
        if (trend.size >= 4) {
            val half = trend.size / 2
            val early = trend.take(half).average()
            val recent = trend.drop(half).average()
            val better = recent < early
            Text(
                text = if (better) {
                    "Improving — ${pawnsLost(early.toInt())} early, " +
                        "${pawnsLost(recent.toInt())} lately."
                } else {
                    "Not moving yet — ${pawnsLost(early.toInt())} early, " +
                        "${pawnsLost(recent.toInt())} lately."
                },
                color = if (better) Good else Muted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun GameRow(game: GameRecord, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = outcomeFor(game),
                    color = outcomeColour(game),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (game.wasLevelCheck) "level check" else opponentLabel(game),
                    color = Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = "${pawnsLost(game.averageLoss)} avg · " +
                    "${game.blunders} blunder${if (game.blunders == 1) "" else "s"} · " +
                    formatDate(game.playedAt),
                color = Muted,
                fontSize = 11.sp,
            )
        }
        Text("›", color = Brass, fontSize = 20.sp)
    }
}

@Composable
private fun GameDetail(game: GameRecord, moves: List<MoveRecord>) {
    if (moves.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = Brass)
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(moves) { move ->
            val quality = runCatching { MoveQuality.valueOf(move.quality) }.getOrNull()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Surface1)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${move.moveNumber}.",
                    color = Muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = move.san,
                    color = Parchment,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Box(Modifier.weight(1f))
                if (quality != null && quality != MoveQuality.BEST) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = quality.label,
                            color = quality.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        move.bestSan?.let {
                            Text("$it was better", color = Muted, fontSize = 10.sp)
                        }
                    }
                } else {
                    Text("Best", color = Good, fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * The stored level is an enum name, so it reads as "MAXIMUM" unless it is mapped back to the
 * label the player was actually shown when they chose it.
 */
private fun opponentLabel(game: GameRecord): String =
    runCatching { OpponentLevel.valueOf(game.opponentLevel).label }
        .getOrDefault(game.opponentLevel.lowercase())

/** Stated from the player's point of view, matching the result card. */
private fun outcomeFor(game: GameRecord): String = when (game.result) {
    "1-0" -> if (game.playerIsWhite) "You won" else "Kibitz won"
    "0-1" -> if (game.playerIsWhite) "Kibitz won" else "You won"
    "1/2-1/2" -> "Drawn"
    else -> "Unfinished"
}

private fun outcomeColour(game: GameRecord) = when {
    outcomeFor(game) == "You won" -> Good
    outcomeFor(game) == "Kibitz won" -> Bad
    else -> Parchment
}

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMillis))
