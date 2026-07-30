package gopesh.kibitz.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import gopesh.kibitz.chess.Move
import gopesh.kibitz.chess.Piece
import gopesh.kibitz.chess.PieceType
import gopesh.kibitz.chess.Position
import gopesh.kibitz.chess.Squares
import gopesh.kibitz.chess.fileOf
import gopesh.kibitz.chess.rankOf
import gopesh.kibitz.ui.theme.CheckTint
import gopesh.kibitz.ui.theme.LastMoveTint
import gopesh.kibitz.ui.theme.MarkerTint
import gopesh.kibitz.ui.theme.PieceBlack
import gopesh.kibitz.ui.theme.PieceOutline
import gopesh.kibitz.ui.theme.PieceWhite
import gopesh.kibitz.ui.theme.SelectTint
import gopesh.kibitz.ui.theme.SquareDark
import gopesh.kibitz.ui.theme.SquareLight
import kotlin.math.abs
import androidx.compose.ui.graphics.Color as UiColor
import gopesh.kibitz.chess.Color as SideColor

/** How long a tapped move takes to slide to its destination. */
private const val SLIDE_MILLIS = 190

/** The dragged piece is lifted slightly so it reads as being above the board. */
private const val LIFT_SCALE = 1.12f

/**
 * The board. Everything is drawn on a single [Canvas] rather than composed from 64 child
 * layouts — one draw pass, no per-square recomposition, and full control over highlights,
 * drag rendering and future overlays like engine arrows.
 *
 * Pieces can be moved either by tapping origin then destination, which slides the piece
 * across, or by dragging, which needs no animation because the finger already moved it.
 */
@Composable
fun ChessBoard(
    position: Position,
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
    selectedSquare: Int? = null,
    legalTargets: Set<Int> = emptySet(),
    lastMove: Move? = null,
    checkedKing: Int? = null,
    /** Number of moves played. A rise means a new move to animate; undo leaves it lower. */
    plyCount: Int = 0,
    /** False right after a drag, where sliding the piece again would look wrong. */
    animateLastMove: Boolean = true,
    showCoordinates: Boolean = true,
    onSquareTap: (Int) -> Unit = {},
    onDragStart: (Int) -> Unit = {},
    onDrop: (from: Int, to: Int) -> Unit = { _, _ -> },
    onDragCancel: () -> Unit = {},
) {
    // Three separate measurers on purpose. A text layout cache keys only on attributes that
    // affect layout, and neither colour nor drawStyle does — so a filled glyph and the same
    // glyph stroked collide in one cache, and whichever was measured last silently styles
    // both passes. Separate caches keep the body, the rim and the labels independent.
    val bodyMeasurer = rememberTextMeasurer(cacheSize = 16)
    val rimMeasurer = rememberTextMeasurer(cacheSize = 16)
    val labelMeasurer = rememberTextMeasurer(cacheSize = 32)

    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragPoint by remember { mutableStateOf(Offset.Unspecified) }

    val slide = remember { Animatable(1f) }
    var slidingMove by remember { mutableStateOf<Move?>(null) }
    var previousPlyCount by remember { mutableIntStateOf(plyCount) }

    LaunchedEffect(plyCount) {
        val advanced = plyCount > previousPlyCount
        previousPlyCount = plyCount
        if (!advanced || !animateLastMove || lastMove == null) {
            slidingMove = null
            return@LaunchedEffect
        }
        slidingMove = lastMove
        try {
            slide.snapTo(0f)
            slide.animateTo(1f, animationSpec = tween(SLIDE_MILLIS, easing = FastOutSlowInEasing))
        } finally {
            // Also runs if a further move cancels this one, so no piece is left mid-flight.
            slidingMove = null
        }
    }

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(flipped, position) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cell = size.width / 8f
                    if (cell <= 0f) return@awaitEachGesture
                    val origin = squareAtPoint(down.position, cell, flipped)
                        ?: return@awaitEachGesture

                    // A press that never travels past touch slop is a tap; anything more is
                    // a drag. Deciding here keeps one gesture detector authoritative instead
                    // of racing a tap detector against a drag detector.
                    val past = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                        change.consume()
                    }
                    if (past == null) {
                        onSquareTap(origin)
                        return@awaitEachGesture
                    }
                    if (position[origin] == null) return@awaitEachGesture

                    dragFrom = origin
                    dragPoint = past.position
                    onDragStart(origin)

                    val finished = drag(down.id) { change ->
                        change.consume()
                        dragPoint = change.position
                    }
                    val target = squareAtPoint(dragPoint, cell, flipped)
                    dragFrom = null
                    dragPoint = Offset.Unspecified

                    // Releasing outside the board, or an interrupted gesture, puts it back.
                    if (finished && target != null) onDrop(origin, target) else onDragCancel()
                }
            }
    ) {
        val cell = size.minDimension / 8f

        for (row in 0..7) {
            for (col in 0..7) {
                val square = squareAt(row, col, flipped)
                drawRect(
                    color = if (isLightSquare(square)) SquareLight else SquareDark,
                    topLeft = Offset(col * cell, row * cell),
                    size = Size(cell, cell),
                )
            }
        }

        lastMove?.let { move ->
            tintSquare(move.from, flipped, cell, LastMoveTint, 0.45f)
            tintSquare(move.to, flipped, cell, LastMoveTint, 0.45f)
        }
        selectedSquare?.let { tintSquare(it, flipped, cell, SelectTint, 0.55f) }

        checkedKing?.let { square ->
            val center = centerOf(square, cell, flipped)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(CheckTint.copy(alpha = 0.85f), CheckTint.copy(alpha = 0f)),
                    center = center,
                    radius = cell * 0.62f,
                ),
                radius = cell * 0.62f,
                center = center,
            )
        }

        if (showCoordinates) drawCoordinates(labelMeasurer, cell, flipped)

        // Move hints sit under the pieces so a capture ring frames the target piece.
        for (target in legalTargets) {
            val center = centerOf(target, cell, flipped)
            if (position[target] == null) {
                drawCircle(MarkerTint.copy(alpha = 0.22f), cell * 0.15f, center)
            } else {
                drawCircle(
                    color = MarkerTint.copy(alpha = 0.28f),
                    radius = cell * 0.42f,
                    center = center,
                    style = Stroke(width = cell * 0.09f),
                )
            }
        }

        // While dragging, outline whichever square the finger is currently over.
        val hovered = if (dragFrom != null && dragPoint != Offset.Unspecified) {
            squareAtPoint(dragPoint, cell, flipped)
        } else {
            null
        }
        if (hovered != null && hovered != dragFrom) {
            val (row, col) = rowColOf(hovered, flipped)
            drawRect(
                color = SelectTint.copy(alpha = 0.9f),
                topLeft = Offset(col * cell, row * cell),
                size = Size(cell, cell),
                style = Stroke(width = cell * 0.05f),
            )
        }

        // A sliding move is already applied to `position`, so the piece must be skipped at
        // its destination and drawn in flight instead. Castling slides the rook as well.
        val inFlight = ArrayList<Pair<Int, Int>>(2)
        slidingMove?.let { move ->
            inFlight.add(move.from to move.to)
            val moved = position[move.to]
            if (moved?.type == PieceType.KING &&
                abs(fileOf(move.to) - fileOf(move.from)) == 2
            ) {
                val homeRank = rankOf(move.to)
                if (fileOf(move.to) == 6) {
                    inFlight.add(Squares.of(7, homeRank) to Squares.of(5, homeRank))
                } else {
                    inFlight.add(Squares.of(0, homeRank) to Squares.of(3, homeRank))
                }
            }
        }
        val flightDestinations = inFlight.mapTo(HashSet()) { it.second }

        for (square in 0..63) {
            if (square == dragFrom || square in flightDestinations) continue
            val piece = position[square] ?: continue
            drawPiece(bodyMeasurer, rimMeasurer, piece, centerOf(square, cell, flipped), cell)
        }

        val progress = slide.value
        for ((from, to) in inFlight) {
            val piece = position[to] ?: continue
            val start = centerOf(from, cell, flipped)
            val end = centerOf(to, cell, flipped)
            val center = Offset(
                start.x + (end.x - start.x) * progress,
                start.y + (end.y - start.y) * progress,
            )
            drawPiece(bodyMeasurer, rimMeasurer, piece, center, cell)
        }

        // The lifted piece draws last so it sits above everything, including its neighbours.
        val lifted = dragFrom?.let { position[it] }
        if (lifted != null && dragPoint != Offset.Unspecified) {
            val shadowCenter = Offset(dragPoint.x, dragPoint.y + cell * 0.08f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(UiColor.Black.copy(alpha = 0.38f), UiColor.Transparent),
                    center = shadowCenter,
                    radius = cell * 0.52f,
                ),
                radius = cell * 0.52f,
                center = shadowCenter,
            )
            drawPiece(bodyMeasurer, rimMeasurer, lifted, dragPoint, cell, scale = LIFT_SCALE)
        }
    }
}

private fun DrawScope.tintSquare(
    square: Int,
    flipped: Boolean,
    cell: Float,
    color: UiColor,
    alpha: Float,
) {
    val (row, col) = rowColOf(square, flipped)
    drawRect(
        color = color.copy(alpha = alpha),
        topLeft = Offset(col * cell, row * cell),
        size = Size(cell, cell),
    )
}

private fun DrawScope.drawPiece(
    bodyMeasurer: TextMeasurer,
    rimMeasurer: TextMeasurer,
    piece: Piece,
    center: Offset,
    cell: Float,
    scale: Float = 1f,
) {
    val glyph = AnnotatedString(glyphFor(piece.type))
    val fontSize = (cell * 0.76f * scale).toSp()

    // Neither layout carries a colour: the two sides share one cache entry per glyph and get
    // their colour as a draw-time override, which is the only part of the style that a cached
    // layout cannot confuse.
    val body = bodyMeasurer.measure(glyph, TextStyle(fontSize = fontSize))
    val rim = rimMeasurer.measure(
        glyph,
        TextStyle(fontSize = fontSize, drawStyle = Stroke(width = cell * 0.022f)),
    )
    val topLeft = Offset(
        center.x - body.size.width / 2f,
        center.y - body.size.height / 2f,
    )

    drawText(
        textLayoutResult = body,
        color = if (piece.color == SideColor.WHITE) PieceWhite else PieceBlack,
        topLeft = topLeft,
    )
    // The rim keeps White legible on a light square. It must stay thin: these silhouettes
    // have narrow limbs, and a wide stroke eats the fill it is meant to frame.
    drawText(textLayoutResult = rim, color = PieceOutline, topLeft = topLeft)
}

private fun DrawScope.drawCoordinates(measurer: TextMeasurer, cell: Float, flipped: Boolean) {
    val fontSize = (cell * 0.2f).toSp()

    for (col in 0..7) {
        val square = squareAt(7, col, flipped)
        val layout = measurer.measure(
            AnnotatedString("${'a' + fileOf(square)}"),
            TextStyle(
                fontSize = fontSize,
                color = labelColorOn(square),
                fontWeight = FontWeight.SemiBold,
            ),
        )
        drawText(
            layout,
            topLeft = Offset(
                col * cell + cell - layout.size.width - cell * 0.07f,
                7 * cell + cell - layout.size.height - cell * 0.04f,
            ),
        )
    }

    for (row in 0..7) {
        val square = squareAt(row, 0, flipped)
        val layout = measurer.measure(
            AnnotatedString("${rankOf(square) + 1}"),
            TextStyle(
                fontSize = fontSize,
                color = labelColorOn(square),
                fontWeight = FontWeight.SemiBold,
            ),
        )
        drawText(layout, topLeft = Offset(cell * 0.07f, row * cell + cell * 0.04f))
    }
}

/** Labels take the opposite square colour so they read on either shade. */
private fun labelColorOn(square: Int): UiColor =
    if (isLightSquare(square)) SquareDark else SquareLight

private fun isLightSquare(square: Int): Boolean = (fileOf(square) + rankOf(square)) % 2 == 1

private fun centerOf(square: Int, cell: Float, flipped: Boolean): Offset {
    val (row, col) = rowColOf(square, flipped)
    return Offset(col * cell + cell / 2f, row * cell + cell / 2f)
}

/** The square under a touch point, or null when the point is off the board. */
internal fun squareAtPoint(point: Offset, cell: Float, flipped: Boolean): Int? {
    if (point == Offset.Unspecified) return null
    val col = (point.x / cell).toInt()
    val row = (point.y / cell).toInt()
    if (col !in 0..7 || row !in 0..7) return null
    return squareAt(row, col, flipped)
}

/** Row 0 is the top of the screen; a1 sits bottom-left until the board is flipped. */
internal fun squareAt(row: Int, col: Int, flipped: Boolean): Int {
    val file = if (flipped) 7 - col else col
    val rank = if (flipped) row else 7 - row
    return Squares.of(file, rank)
}

internal fun rowColOf(square: Int, flipped: Boolean): Pair<Int, Int> {
    val file = fileOf(square)
    val rank = rankOf(square)
    val col = if (flipped) 7 - file else file
    val row = if (flipped) rank else 7 - rank
    return row to col
}

/**
 * Both sides use the *solid* Unicode set, U+265A..265F, and take their colour from the fill.
 * Unicode's companion set U+2654..2659 is line art rather than a silhouette, so filling it
 * paints only thin strokes and a rim then covers them completely — it cannot stand in for
 * White here. Swapping to vector piece art later means changing only this function and
 * [drawPiece].
 */
internal fun glyphFor(type: PieceType): String = when (type) {
    PieceType.KING -> "♚"
    PieceType.QUEEN -> "♛"
    PieceType.ROOK -> "♜"
    PieceType.BISHOP -> "♝"
    PieceType.KNIGHT -> "♞"
    PieceType.PAWN -> "♟"
}
