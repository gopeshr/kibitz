package gopesh.kibitz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gopesh.kibitz.coach.LevelCalibration.CALIBRATED_SAMPLE_MOVES
import gopesh.kibitz.ui.theme.Brass
import gopesh.kibitz.ui.theme.Ink
import gopesh.kibitz.ui.theme.Muted
import gopesh.kibitz.ui.theme.Parchment
import gopesh.kibitz.ui.theme.Surface1

/**
 * First run. Asks for a name and sets the expectation that a short game comes next — people
 * abandon an assessment they did not know they were starting.
 */
@Composable
fun OnboardingScreen(onStart: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val canStart = name.trim().isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))

        KibitzMark(size = 88, corner = 24)

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Kibitz",
            color = Parchment,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "A coach that watches how you play",
            color = Muted,
            fontSize = 14.sp,
        )

        Spacer(Modifier.height(36.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surface1)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("How this starts")
            StepLine("1", "Tell me your name.")
            // Read from the calibration rather than written out, because it already drifted
            // once: this promised 12 moves long after the sample grew to 30, so the level check
            // asked for more than two and a half times the moves the player had agreed to.
            StepLine("2", "Play a quick game against me — $CALIBRATED_SAMPLE_MOVES moves.")
            StepLine("3", "I'll judge every move you make and estimate your level.")
            Text(
                text = "One short game can only give a rough range, not a real rating. " +
                    "It gets sharper the more you play.",
                color = Muted,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= 24) name = it },
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (canStart) onStart(name) },
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Parchment,
                unfocusedTextColor = Parchment,
                focusedBorderColor = Brass,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLabelColor = Brass,
                unfocusedLabelColor = Muted,
                cursorColor = Brass,
            ),
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { onStart(name) },
            enabled = canStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Brass,
                contentColor = Ink,
                disabledContainerColor = Surface1,
                disabledContentColor = Muted,
            ),
        ) {
            Text(
                text = "Start the quick game",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Your name stays on this device.",
            color = Muted.copy(alpha = 0.8f),
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepLine(number: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = number,
            color = Brass,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(text = text, color = Parchment.copy(alpha = 0.92f), fontSize = 13.sp)
    }
}
