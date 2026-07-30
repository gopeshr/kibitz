package gopesh.kibitz.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import gopesh.kibitz.GameViewModel
import gopesh.kibitz.engine.EvalSnapshot
import gopesh.kibitz.ui.theme.EvalBlack
import gopesh.kibitz.ui.theme.EvalMidline
import gopesh.kibitz.ui.theme.EvalTick
import gopesh.kibitz.ui.theme.EvalWhite

private val BAR_WIDTH = 16.dp
private val BAR_GAP = 10.dp

/**
 * The board with its evaluation bar down the left edge, sized so the two are exactly the same
 * height. [BoxWithConstraints] is used rather than intrinsic sizing because the board derives
 * its height from its own width, which leaves nothing for the bar to match against.
 */
@Composable
fun BoardWithEvalBar(game: GameViewModel, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val side = maxWidth - BAR_WIDTH - BAR_GAP

        Row {
            EvalBar(
                snapshot = game.evaluation,
                flipped = game.flipped,
                modifier = Modifier
                    .width(BAR_WIDTH)
                    .height(side),
            )

            Spacer(Modifier.width(BAR_GAP))

            ChessBoard(
                position = game.position,
                modifier = Modifier.width(side),
                flipped = game.flipped,
                selectedSquare = game.selectedSquare,
                legalTargets = game.legalTargets,
                lastMove = game.lastMove,
                checkedKing = game.checkedKingSquare,
                plyCount = game.plyCount,
                animateLastMove = !game.lastMoveWasDrag,
                onSquareTap = game::onSquareTap,
                onDragStart = game::onDragStart,
                onDrop = game::onDrop,
                onDragCancel = game::onDragCancel,
            )
        }
    }
}

/**
 * White's share fills from the bottom of the bar, or the top when the board is flipped, so
 * the fill always grows towards whoever is sitting at the near edge.
 */
@Composable
fun EvalBar(
    snapshot: EvalSnapshot?,
    flipped: Boolean,
    modifier: Modifier = Modifier,
) {
    // A level, centred bar while the first evaluation is still running reads better than an
    // empty gap; 0.5 is also the honest answer for the starting position.
    val target = snapshot?.whiteShare ?: 0.5f
    val share by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 320),
        label = "eval-share",
    )

    Box(modifier.clip(RoundedCornerShape(5.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            val whiteHeight = size.height * share.coerceIn(0f, 1f)

            drawRect(color = EvalBlack, size = size)
            drawRect(
                color = EvalWhite,
                topLeft = Offset(0f, if (flipped) 0f else size.height - whiteHeight),
                size = Size(size.width, whiteHeight),
            )

            // Graduations. Drawn over both halves in a translucent grey so a single colour
            // stays visible whichever side of the boundary each tick lands on.
            val tickWidth = 1.dp.toPx()
            for (level in EvalSnapshot.LEVELS) {
                val levelShare = EvalSnapshot.shareFor(level)
                val y = if (flipped) size.height * levelShare else size.height * (1f - levelShare)
                val inset = if (level % 200 == 0) 0f else size.width * 0.32f
                drawLine(
                    color = EvalTick,
                    start = Offset(inset, y),
                    end = Offset(size.width - inset, y),
                    strokeWidth = tickWidth,
                )
            }

            // Dead level, the reference the rest is read against.
            drawLine(
                color = EvalMidline,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }
}
