package gopesh.kibitz.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Warm, low-glare dark shell so the board itself is the brightest thing on screen.
val Ink = Color(0xFF12100E)
val Surface1 = Color(0xFF1C1917)
val Surface2 = Color(0xFF262220)
val Parchment = Color(0xFFEDE8E1)
val Muted = Color(0xFF9A9187)
val Brass = Color(0xFFC8A26A)

// Board palette.
val SquareLight = Color(0xFFF0D9B5)
val SquareDark = Color(0xFFB58863)
val SelectTint = Color(0xFF7FA650)
val LastMoveTint = Color(0xFFCBD05F)
val CheckTint = Color(0xFFD64545)
val PieceWhite = Color(0xFFFCFBF7)
val PieceBlack = Color(0xFF201C1A)

/** Rim around a piece, so White holds its shape on a light square. */
val PieceOutline = Color(0xFF13110F)

// Move-quality accents, used for coach feedback.
val Good = Color(0xFF8FB45C)
val Caution = Color(0xFFD8A13A)
val Bad = Color(0xFFD35F5F)

// Evaluation bar. Deliberately white-against-black rather than the board's browns, so it
// reads as "which side is winning" and not as an extra file of squares.
val EvalWhite = Color(0xFFF2EDE4)
val EvalBlack = Color(0xFF2A2522)
val EvalTick = Color(0x598A8175)
val EvalMidline = Color(0x99C8A26A)
val MarkerTint = Color(0xFF14110E)

private val KibitzColors = darkColorScheme(
    primary = Brass,
    onPrimary = Ink,
    secondary = SquareDark,
    onSecondary = Ink,
    background = Ink,
    onBackground = Parchment,
    surface = Surface1,
    onSurface = Parchment,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    outline = Color(0xFF3A3430),
)

@Composable
fun KibitzTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KibitzColors, content = content)
}
