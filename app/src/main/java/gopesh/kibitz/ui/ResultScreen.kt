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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gopesh.kibitz.coach.LevelEstimate
import gopesh.kibitz.data.AccuracySummary
import gopesh.kibitz.ui.theme.Bad
import gopesh.kibitz.ui.theme.Brass
import gopesh.kibitz.ui.theme.Good
import gopesh.kibitz.ui.theme.Ink
import gopesh.kibitz.ui.theme.Muted
import gopesh.kibitz.ui.theme.Parchment
import gopesh.kibitz.ui.theme.Surface1
import gopesh.kibitz.ui.theme.Surface2

/**
 * The verdict. Leads with a range and a confidence caveat rather than a single number: one
 * short game genuinely cannot support more precision than that, and pretending otherwise
 * would make every later estimate look like a contradiction.
 */
@Composable
fun ResultScreen(
    playerName: String,
    estimate: LevelEstimate,
    gamesRecorded: Int,
    allTimeAccuracy: AccuracySummary?,
    onStartPlaying: () -> Unit,
    onPlayAgain: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(
            text = "Nice game, $playerName.",
            color = Parchment,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Here's what I saw across ${estimate.movesAssessed} of your moves.",
            color = Muted,
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(20.dp))

        // Headline estimate.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface1)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SectionLabel("Estimated level")
            Spacer(Modifier.height(8.dp))
            Text(
                text = estimate.band.label,
                color = Brass,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "around ${estimate.band.ratingText}",
                color = Parchment,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Surface2)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = estimate.confidence.label,
                    color = Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = estimate.confidence.explanation,
                color = Muted,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Avg. loss", "${pawnsLost(estimate.averageLoss)}", "pawns per move", Modifier.weight(1f))
            StatTile("Blunders", "${estimate.blunders}", "game-changing", Modifier.weight(1f))
            StatTile("Top move", "${estimate.topMoveRate}%", "found the best", Modifier.weight(1f))
        }

        estimate.costliestMove?.let { worst ->
            Spacer(Modifier.height(16.dp))
            SectionLabel("Costliest moment")
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface1)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${worst.moveNumber}. ${worst.san}",
                        color = Parchment,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = worst.quality.label,
                        color = worst.quality.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Text(
                    text = worst.bestSan?.let { "$it would have held the position." }
                        ?: "This was the turning point.",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
        }

        if (estimate.strengths.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionLabel("What went well")
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                estimate.strengths.forEach { BulletLine(it, Good) }
            }
        }

        if (estimate.weaknesses.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionLabel("What to work on")
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                estimate.weaknesses.forEach { BulletLine(it, Bad) }
            }
        }

        // Read back out of the stored history, not from this game — proof the record persists
        // and the beginning of a picture that sharpens with every game played.
        if (allTimeAccuracy != null && allTimeAccuracy.movesJudged > 0) {
            Spacer(Modifier.height(18.dp))
            SectionLabel("Across all your games")
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface1)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "$gamesRecorded game${if (gamesRecorded == 1) "" else "s"} recorded · " +
                        "${allTimeAccuracy.movesJudged} moves judged",
                    color = Parchment,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Lifetime average loss " +
                        "${pawnsLost(allTimeAccuracy.averageLoss.toInt())} pawns per move, " +
                        "${allTimeAccuracy.blunders} blunder" +
                        if (allTimeAccuracy.blunders == 1) "" else "s",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(26.dp))

        Button(
            onClick = onStartPlaying,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = Ink),
        ) {
            Text("Start playing", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = onPlayAgain,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Play another level check")
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = value, color = Parchment, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(text = caption, color = Muted.copy(alpha = 0.7f), fontSize = 9.sp)
    }
}
