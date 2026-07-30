package gopesh.kibitz.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gopesh.kibitz.R
import gopesh.kibitz.coach.MoveQuality
import gopesh.kibitz.engine.EvalSnapshot
import gopesh.kibitz.ui.theme.Bad
import gopesh.kibitz.ui.theme.Caution
import gopesh.kibitz.ui.theme.EvalBlack
import gopesh.kibitz.ui.theme.EvalWhite
import gopesh.kibitz.ui.theme.Good
import gopesh.kibitz.ui.theme.Muted
import gopesh.kibitz.ui.theme.Parchment
import gopesh.kibitz.ui.theme.SquareDark
import gopesh.kibitz.ui.theme.Surface1
import androidx.compose.ui.graphics.Color as UiColor

/** The app mark: board behind, eye in front, reusing the launcher icon layers. */
@Composable
fun KibitzMark(size: Int = 72, corner: Int = 20, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(corner.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp),
        )
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "Kibitz",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp),
        )
    }
}

/**
 * One side's name plate. Two of these sandwiching the board is the layout players already
 * know from every other chess app, and it gives the screen a top and a bottom.
 */
@Composable
fun PlayerStrip(
    name: String,
    subtitle: String,
    isWhite: Boolean,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) Surface1 else UiColor.Transparent)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(11.dp)
                .clip(CircleShape)
                .background(if (isWhite) Parchment else SquareDark),
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                text = name,
                color = if (isActive) Parchment else Muted,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = subtitle, color = Muted, fontSize = 11.sp)
        }
        Box(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * The numeric evaluation, in the same white-on-dark / dark-on-white convention the bar uses:
 * whoever is ahead owns the filled chip. A dash while the first search is still running keeps
 * the chip from resizing as digits appear.
 */
@Composable
fun EvalReadout(snapshot: EvalSnapshot?, modifier: Modifier = Modifier) {
    val favoursWhite = snapshot?.favoursWhite ?: true
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (favoursWhite) EvalWhite else EvalBlack)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = snapshot?.label ?: "–",
            color = if (favoursWhite) EvalBlack else EvalWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Section heading used on the result screen. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = Muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = modifier,
    )
}

@Composable
fun BulletLine(text: String, color: UiColor, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Text(text = "•", color = color, fontSize = 14.sp)
        Text(
            text = text,
            color = Parchment.copy(alpha = 0.9f),
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

val MoveQuality.accent: UiColor
    get() = when (this) {
        MoveQuality.BEST, MoveQuality.GOOD -> Good
        MoveQuality.INACCURACY -> Caution
        MoveQuality.MISTAKE, MoveQuality.BLUNDER -> Bad
    }
