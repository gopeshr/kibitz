package gopesh.kibitz.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import gopesh.kibitz.engine.OpponentLevel
import gopesh.kibitz.profile.UserProfile
import gopesh.kibitz.ui.theme.Brass
import gopesh.kibitz.ui.theme.Ink
import gopesh.kibitz.ui.theme.Muted
import gopesh.kibitz.ui.theme.Parchment
import gopesh.kibitz.ui.theme.SquareDark
import gopesh.kibitz.ui.theme.Surface1
import gopesh.kibitz.ui.theme.Surface2
import kotlin.random.Random

/** Which side the player wants. Random is offered because always having White gets stale. */
enum class SideChoice(val label: String) { WHITE("White"), BLACK("Black"), RANDOM("Random") }

/**
 * Opponent picker.
 *
 * The level nearest the player's estimated rating is preselected and marked, so the default is
 * a fair game rather than whatever happens to be first in the list. Everything else is one tap
 * away — being told you are 1500 and then handed a 2900 opponent teaches nothing.
 */
@Composable
fun NewGameScreen(
    profile: UserProfile?,
    onStart: (OpponentLevel, Boolean) -> Unit,
    onPractise: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    val matched = remember(profile) {
        if (profile != null && profile.hasLevel) {
            OpponentLevel.nearest((profile.ratingLow + profile.ratingHigh) / 2)
        } else {
            OpponentLevel.CLUB
        }
    }
    var selected by remember(matched) { mutableStateOf(matched) }
    var side by remember { mutableStateOf(SideChoice.WHITE) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
      // The choices scroll; "Start game" stays put. Adding the practise and history cards
      // pushed the primary action off the bottom of the screen, which is exactly the thing a
      // primary action must never require scrolling to reach.
      Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KibitzMark(size = 40, corner = 12)
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    text = "Play Kibitz",
                    color = Parchment,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (profile?.hasLevel == true) {
                        "You're around ${profile.ratingText}"
                    } else {
                        "Pick an opponent"
                    },
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
        }

        if (onPractise != null) {
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface1)
                    .clickable(onClick = onPractise)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Practise your mistakes",
                        color = Parchment,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Positions from your own games, where you went wrong",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                }
                Text("›", color = Brass, fontSize = 22.sp)
            }
        }

        if (onHistory != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface1)
                    .clickable(onClick = onHistory)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Your games",
                        color = Parchment,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Every game reviewed, and whether you are improving",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                }
                Text("›", color = Brass, fontSize = 22.sp)
            }
        }

        Spacer(Modifier.height(22.dp))

        SectionLabel("Opponent strength")
        Spacer(Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (level in OpponentLevel.entries) {
                LevelRow(
                    level = level,
                    isSelected = level == selected,
                    isMatched = level == matched,
                    onClick = { selected = level },
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        SectionLabel("Your colour")
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in SideChoice.entries) {
                SideChip(
                    choice = choice,
                    isSelected = choice == side,
                    onClick = { side = choice },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
      }

        Button(
            onClick = {
                val playerIsWhite = when (side) {
                    SideChoice.WHITE -> true
                    SideChoice.BLACK -> false
                    SideChoice.RANDOM -> Random.nextBoolean()
                }
                onStart(selected, playerIsWhite)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = Ink),
        ) {
            Text("Start game", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        if (onCancel != null) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Back to the board", color = Muted, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun LevelRow(
    level: OpponentLevel,
    isSelected: Boolean,
    isMatched: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Surface2 else Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isSelected) Brass else Muted.copy(alpha = 0.35f)),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                text = level.label,
                color = if (isSelected) Parchment else Muted,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = level.uciElo?.let { "around $it" } ?: "no limit — it will not miss",
                color = Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Box(Modifier.weight(1f))
        if (isMatched) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brass.copy(alpha = 0.18f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "YOUR LEVEL",
                    color = Brass,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
            }
        }
    }
}

@Composable
private fun SideChip(
    choice: SideChoice,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Surface2 else Surface1)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (choice != SideChoice.RANDOM) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(if (choice == SideChoice.WHITE) Parchment else SquareDark),
            )
            Spacer(Modifier.size(7.dp))
        }
        Text(
            text = choice.label,
            color = if (isSelected) Parchment else Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
