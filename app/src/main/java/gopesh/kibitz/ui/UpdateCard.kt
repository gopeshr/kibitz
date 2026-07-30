package gopesh.kibitz.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gopesh.kibitz.UpdatePhase
import gopesh.kibitz.UpdateViewModel
import gopesh.kibitz.ui.theme.Bad
import gopesh.kibitz.ui.theme.Brass
import gopesh.kibitz.ui.theme.Good
import gopesh.kibitz.ui.theme.Ink
import gopesh.kibitz.ui.theme.Muted
import gopesh.kibitz.ui.theme.Parchment
import gopesh.kibitz.ui.theme.Surface1

/**
 * Version line, and the update flow when one is asked for.
 *
 * Collapsed to a single tappable row until pressed, because an app that shouts about updates on
 * every launch is worse than one you have to ask. Nothing here contacts the network until the
 * row is tapped.
 */
@Composable
fun UpdateCard(viewModel: UpdateViewModel, modifier: Modifier = Modifier) {
    val update = viewModel.available
    val progress by animateFloatAsState(viewModel.downloadProgress, label = "update-download")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = viewModel.phase != UpdatePhase.DOWNLOADING) {
                    if (viewModel.phase == UpdatePhase.IDLE ||
                        viewModel.phase == UpdatePhase.UP_TO_DATE ||
                        viewModel.phase == UpdatePhase.FAILED
                    ) {
                        viewModel.check()
                    }
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Kibitz ${viewModel.currentVersion}",
                    color = Parchment,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when (viewModel.phase) {
                        UpdatePhase.IDLE -> "Tap to check for a new version"
                        UpdatePhase.CHECKING -> "Checking GitHub…"
                        UpdatePhase.UP_TO_DATE -> "Up to date"
                        UpdatePhase.AVAILABLE -> "Version ${update?.version} is available"
                        UpdatePhase.DOWNLOADING ->
                            "Downloading… ${(viewModel.downloadProgress * 100).toInt()}%"
                        UpdatePhase.READY -> "Downloaded — ready to install"
                        UpdatePhase.FAILED -> viewModel.message ?: "Check failed"
                    },
                    color = when (viewModel.phase) {
                        UpdatePhase.UP_TO_DATE -> Good
                        UpdatePhase.FAILED -> Bad
                        UpdatePhase.AVAILABLE, UpdatePhase.READY -> Brass
                        else -> Muted
                    },
                    fontSize = 11.sp,
                )
            }
            when (viewModel.phase) {
                UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Brass,
                    strokeWidth = 2.dp,
                )
                UpdatePhase.IDLE, UpdatePhase.UP_TO_DATE, UpdatePhase.FAILED ->
                    Text("↻", color = Brass, fontSize = 18.sp)
                else -> Unit
            }
        }

        if (viewModel.phase == UpdatePhase.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = Brass,
                trackColor = Surface1,
                drawStopIndicator = {},
            )
        }

        if (viewModel.phase == UpdatePhase.AVAILABLE && update != null) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${update.sizeBytes / 1_048_576} MB" +
                        if (update.isPrerelease) " · pre-release" else "",
                    color = Muted,
                    fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::download,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Brass,
                            contentColor = Ink,
                        ),
                    ) {
                        Text("Download", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    TextButton(onClick = viewModel::dismiss) {
                        Text("Not now", color = Muted, fontSize = 13.sp)
                    }
                }
            }
        }

        if (viewModel.phase == UpdatePhase.READY) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                viewModel.message?.let {
                    Text(text = it, color = Bad, fontSize = 11.sp)
                }
                Text(
                    text = "Android will ask you to confirm, and will refuse the file unless it " +
                        "was signed with the same key as this app.",
                    color = Muted,
                    fontSize = 11.sp,
                )
                Button(
                    onClick = viewModel::install,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Brass,
                        contentColor = Ink,
                    ),
                ) {
                    Text("Install", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        Box(Modifier.height(2.dp))
    }
}
