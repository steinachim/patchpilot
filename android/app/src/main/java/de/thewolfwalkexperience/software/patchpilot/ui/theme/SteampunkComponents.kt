// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A brass screw head (`steampunk_screw`) drawn centred on one point, [diameter] tall and at the
 * art's own aspect ratio - the one fastener every plate in this theme is held together with.
 */
private fun DrawScope.screwHead(image: ImageBitmap, center: Offset, diameter: Float) {
    val width = diameter * image.width / image.height
    drawImage(
        image = image,
        dstOffset = IntOffset(
            (center.x - width / 2f).roundToInt(),
            (center.y - diameter / 2f).roundToInt(),
        ),
        dstSize = IntSize(width.roundToInt(), diameter.roundToInt()),
    )
}

/**
 * Whether the platform's "remove animations" accessibility setting is on
 * (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`) - Android's equivalent of
 * `prefers-reduced-motion`. Read once per composition; nobody toggles it mid-session.
 */
@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * The screen frame: a rounded brass border inset from the box's own true edges, echoing
 * [SteampunkShapes]'s generous rounding. Drawn **after** content (`drawWithContent`, not
 * `drawBehind`), so it always reads as sitting on top.
 *
 * One frame, one placement rule, used identically everywhere a screen wants it -
 * `ConnectScreen`'s root and (via `PatchPilotScaffold`) every `Scaffold`-based screen.
 */
fun Modifier.steampunkFrame(): Modifier = composed {
    val brassDark = SteampunkAccents.brassDark
    val cornerRadius = 24.dp
    drawWithContent {
        drawContent()
        val inset = 8.dp.toPx()
        val radiusPx = cornerRadius.toPx()
        val strokeWidth = 2.dp.toPx()
        drawRoundRect(
            color = brassDark,
            topLeft = Offset(inset, inset),
            size = Size(size.width - 2 * inset, size.height - 2 * inset),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx),
            style = Stroke(strokeWidth),
        )
    }
}

/**
 * One scratch on a brass plate: where it starts as a fraction of the plate's width and height,
 * how long it is, and how steeply it runs. Length is a real distance rather than a fraction, so a
 * wide plate gets more marks of the same size instead of longer ones.
 */
private class BrassWear(val x: Float, val y: Float, val length: Dp, val slope: Float)

/**
 * The wear on a brass plate, fixed rather than generated: a plate has to look the same every time
 * it is drawn, and a random set would reshuffle on every recomposition. Alternating entries are
 * lit and shaded, which is what makes a scratch read as a groove rather than a line, and the
 * slopes vary so they read as knocks rather than as something combed.
 */
private val BRASS_WEAR = listOf(
    BrassWear(0.04f, 0.30f, 13.dp, 0.5f),
    BrassWear(0.10f, 0.74f, 8.dp, -0.3f),
    BrassWear(0.22f, 0.20f, 11.dp, 0.2f),
    BrassWear(0.31f, 0.55f, 6.dp, 1.1f),
    BrassWear(0.38f, 0.68f, 15.dp, -0.15f),
    BrassWear(0.51f, 0.34f, 9.dp, 0.7f),
    BrassWear(0.63f, 0.78f, 12.dp, -0.5f),
    BrassWear(0.72f, 0.42f, 7.dp, 0.9f),
    BrassWear(0.80f, 0.24f, 14.dp, 0.25f),
    BrassWear(0.92f, 0.64f, 9.dp, -0.8f),
)

/**
 * Patina: faint dark clouds, so the metal between the scratches is not uniformly lit. Held to the
 * shaded tone rather than the lit one, which keeps the plate's text contrast on the safe side.
 */
private val BRASS_PATINA = listOf(
    Triple(0.16f, 0.78f, 26.dp),
    Triple(0.44f, 0.18f, 34.dp),
    Triple(0.61f, 0.82f, 20.dp),
    Triple(0.88f, 0.34f, 28.dp),
)

/**
 * A worn brass fill for a plate: `primaryContainer` through the middle, a lit bevel along the top
 * edge, a shaded foot along the bottom, and [BRASS_WEAR]'s scratches over it. Meant for something
 * already clipped to a rounded shape, like a bank header (see `SteampunkThemeStyle.BankHeader`).
 *
 * The middle band keeps the container colour exactly, so the contrast its `onPrimaryContainer`
 * text was chosen against does not move: the wear lives in the top and bottom eighths and in
 * marks too faint to shift a luminance ratio.
 *
 * Drawn rather than a bitmap because the plate is as wide as its label needs, and one texture
 * would smear stretched across a tablet's header and tile visibly on a phone's.
 */
fun Modifier.steampunkWornBrass(): Modifier = composed {
    val plate = MaterialTheme.colorScheme.primaryContainer
    val lit = lerp(plate, SteampunkAccents.brassLight, 0.8f)
    val shaded = lerp(plate, MaterialTheme.colorScheme.surface, 0.7f)
    drawBehind {
        drawRect(
            Brush.verticalGradient(
                0f to lit,
                0.12f to plate,
                0.88f to plate,
                1f to shaded,
            ),
        )
        BRASS_PATINA.forEach { (x, y, spread) ->
            val center = Offset(x * size.width, y * size.height)
            val radius = spread.toPx()
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(shaded.copy(alpha = 0.35f), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
        val hairline = 1.dp.toPx()
        BRASS_WEAR.forEachIndexed { i, wear ->
            val start = Offset(wear.x * size.width, wear.y * size.height)
            val run = wear.length.toPx()
            drawLine(
                color = if (i % 2 == 0) lit.copy(alpha = 0.24f) else shaded.copy(alpha = 0.30f),
                start = start,
                end = Offset(start.x + run, start.y + run * wear.slope),
                strokeWidth = hairline,
            )
        }
    }
}

/**
 * Two screws flanking a short horizontal brass bar - left and right, vertically centred - for
 * a bank header row (see `ProgramsScreen`). Meant for something that is already clipped to a
 * rounded [SteampunkShapes] shape and coloured as a brass plate; the screws alone don't read as
 * fastened to anything without that rounding.
 */
fun Modifier.steampunkBarScrews(): Modifier = composed {
    val screw = ImageBitmap.imageResource(R.drawable.steampunk_screw)
    drawWithContent {
        drawContent()
        val inset = 12.dp.toPx()
        val screwSize = 10.dp.toPx()
        val y = size.height / 2f
        screwHead(screw, Offset(inset, y), screwSize)
        screwHead(screw, Offset(size.width - inset, y), screwSize)
    }
}

/**
 * Two screws top and bottom - the same idea as [steampunkBarScrews] rotated 90 degrees, for a
 * tall narrow pill-shaped element like the bank fast-scroll rail (`BankIndex`). The rail is fully
 * rounded (`RoundedCornerShape(percent = 50)`), so each end is a true semicircle of radius
 * `size.width / 2` - the screw sits at that semicircle's own centre, not at a fixed distance from
 * the edge, so it lands in the middle of the curve regardless of the rail's actual width.
 */
fun Modifier.steampunkVerticalScrews(): Modifier = composed {
    val screw = ImageBitmap.imageResource(R.drawable.steampunk_screw)
    drawWithContent {
        drawContent()
        val capRadius = size.width / 2f
        val screwSize = 10.dp.toPx()
        val x = size.width / 2f
        screwHead(screw, Offset(x, capRadius), screwSize)
        screwHead(screw, Offset(x, size.height - capRadius), screwSize)
    }
}

/**
 * The brushed-metal backdrop: a faint warm wash from the top plus fine diagonal
 * hairlines - applied once at the app's own root ([MainActivity]'s outer `Surface`) rather than
 * per screen, so every screen shares one continuous texture instead of each redrawing its own.
 * A flat theme color alone reads as "a dark theme"; this is what makes it read as a panel.
 */
fun Modifier.steampunkTexture(): Modifier = composed {
    val wash = SteampunkAccents.brassLight.copy(alpha = 0.22f)
    val vignette = Color.Black.copy(alpha = 0.55f)
    val line = SteampunkAccents.parchment.copy(alpha = 0.07f)
    drawBehind {
        // A warm glow from the top, like light catching the top of a brass panel...
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(wash, Color.Transparent),
                center = Offset(size.width * 0.5f, -size.height * 0.05f),
                radius = size.width * 1.3f,
            ),
        )
        // ...and a vignette darkening the corners, so the centre reads as lit rather than the
        // whole panel being one flat fill.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, vignette),
                center = Offset(size.width * 0.5f, size.height * 0.4f),
                radius = size.width * 1.1f,
            ),
        )
        val spacing = 9.dp.toPx()
        val stroke = 1.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            drawLine(
                color = line,
                start = Offset(x, size.height),
                end = Offset(x + size.height, 0f),
                strokeWidth = stroke,
            )
            x += spacing
        }
    }
}

/**
 * The brass-plate treatment for one preset row: a small vertical gap (added *inside* the row's
 * own modifier chain, so it counts as part of the row's own measured height rather than a true
 * inter-row gap - `ProgramListDragState.hitRowInfo` resolves a touch to whichever item's
 * `[offset, offset + size)` range contains it, straight off `LazyListItemInfo`, and a real gap
 * between two items' ranges would be a dead zone that mis-resolves as "the last item"), a
 * brass-tinted border and the theme's own faceted corner radius. Border-only otherwise - it draws
 * over the row's existing bounds rather than growing them, so the drag gesture's 56.dp
 * handle-zone threshold (an x-only measurement) is untouched.
 */
fun Modifier.steampunkRowPanel(dragged: Boolean, dropTarget: Boolean): Modifier = composed {
    val borderColor = when {
        dragged -> SteampunkAccents.brassLight
        dropTarget -> MaterialTheme.colorScheme.tertiary
        else -> SteampunkAccents.brassDark.copy(alpha = 0.7f)
    }
    val shape = MaterialTheme.shapes.small
    this
        .padding(vertical = 2.dp)
        .clip(shape)
        .border(1.dp, borderColor, shape)
}

/**
 * The compass that stands in for `CircularProgressIndicator` while the connect screen is
 * searching for or opening an instrument, and while the preset screen waits for its first rows.
 *
 * Two rendered bitmaps rather than a drawing - a dial (`steampunk_compass_plate`) and a needle
 * (`steampunk_compass_needle`) - because that is how the app icon got its look. A flat disc with
 * a two-pixel ring and a stroke for a needle read as a line icon next to it, and a redraw with
 * gradients and hairlines was closer but still visibly a drawing. (A pressure gauge in the same
 * technique came between; the compass replaced it as the better fit for "looking for".)
 *
 * The two assets are authored to one scale, which is what keeps this composable short: the
 * needle is drawn with the same scale factor as the plate, and the plate is padded so that the
 * needle's axle (the centre screw) is its exact centre. So drawing is "fit the plate, put the
 * needle's pivot on the plate's centre, rotate". [NEEDLE_PIVOT] is where the needle's own screw
 * sits in its image, as a fraction of its size; the needle's north end points straight up at
 * zero rotation.
 *
 * Both live in `drawable-nodpi` at 480 px, one and a half times what an 80.dp compass needs on
 * a 4x display - the plate at its authored 990 px would be 1.9 MB for something drawn at a
 * fraction of that - and are scaled here rather than by the resource system, so the two always
 * share a factor. The source art is in `android/icons/`; the drawables are derived from it by
 * padding the plate 7 px on the right and 19 px on the top (its axle is at pixel (498, 487) of
 * 990 x 994, so that moves it to the centre), then resampling both by the same factor,
 * 480 / 1013.
 *
 * The needle swings thirty degrees either side of north, the way a compass hunts before it
 * settles, unless [rememberReduceMotion] says not to, in which case it holds still on north -
 * the same information (something is in progress), without the motion.
 */
@Composable
fun SteampunkCompass(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val needleDeg = if (reduceMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "steampunk-compass")
        val angle by transition.animateFloat(
            initialValue = -30f,
            targetValue = 30f,
            animationSpec = infiniteRepeatable(
                // A symmetric easing, so the reversed leg looks like the forward one: the needle
                // slows into each end of its swing and out again, as a damped needle does,
                // rather than bouncing off it.
                animation = tween(1600, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "needle",
        )
        angle
    }
    val plate = ImageBitmap.imageResource(R.drawable.steampunk_compass_plate)
    val needle = ImageBitmap.imageResource(R.drawable.steampunk_compass_needle)
    Canvas(modifier = modifier.size(80.dp)) {
        val scale = min(size.width / plate.width, size.height / plate.height)
        val plateSize = IntSize((plate.width * scale).roundToInt(), (plate.height * scale).roundToInt())
        val plateOffset = IntOffset(
            ((size.width - plateSize.width) / 2f).roundToInt(),
            ((size.height - plateSize.height) / 2f).roundToInt(),
        )
        drawImage(plate, dstOffset = plateOffset, dstSize = plateSize)
        val pivot = Offset(plateOffset.x + plateSize.width / 2f, plateOffset.y + plateSize.height / 2f)
        val needleSize = IntSize((needle.width * scale).roundToInt(), (needle.height * scale).roundToInt())
        val needleOffset = IntOffset(
            (pivot.x - needleSize.width * NEEDLE_PIVOT.x).roundToInt(),
            (pivot.y - needleSize.height * NEEDLE_PIVOT.y).roundToInt(),
        )
        rotate(needleDeg, pivot) {
            drawImage(needle, dstOffset = needleOffset, dstSize = needleSize)
        }
    }
}

/** The needle's axle within `steampunk_compass_needle`: on its vertical axis, at pixel row 339
 *  of 688 in the source art - the middle of its hub, which is 4.5 px above the image's centre. */
private val NEEDLE_PIVOT = Offset(0.5f, 339f / 688f)

/**
 * A brass-panel stand-in for the `OutlinedButton` the connect screen's device picker
 * uses per candidate. Same tap target and content as the default theme's button - a `Surface`
 * with its own `onClick` slot, which is what gives it the same semantics/ripple as a real button
 * rather than a `Row` wearing a `clickable` modifier.
 */
@Composable
fun SteampunkDeviceRow(label: String, sublabel: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.padding(end = 8.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(
                    sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
