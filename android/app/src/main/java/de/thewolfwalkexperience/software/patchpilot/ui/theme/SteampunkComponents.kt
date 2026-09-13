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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.R
import kotlin.math.min
import kotlin.math.roundToInt

/** A brass rivet dot, radial-shaded, at one point. */
private fun DrawScope.rivet(center: Offset, radius: Float, light: Color, dark: Color) {
    drawCircle(
        brush = Brush.radialGradient(colors = listOf(light, dark), center = center, radius = radius),
        radius = radius,
        center = center,
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
 * The riveted-plate frame from the mockup: a rounded brass border inset from the box's own true
 * edges, with a rivet sitting on each of its four rounded corners. Drawn **after** content
 * (`drawWithContent`, not `drawBehind`), so it always reads as sitting on top.
 *
 * One frame, one placement rule, used identically everywhere a screen wants it -
 * `ConnectScreen`'s root and (via `PatchPilotScaffold`) every `Scaffold`-based screen. The first
 * cut of this feature had two different, inconsistent rivet treatments (plain corner dots on
 * `ConnectScreen`, two bar-only dots on the app bar elsewhere) with no border to justify either -
 * rivets floating near a sharp rectangular corner with nothing rounded to fasten don't read as
 * anything. This is also why they only make sense *with* [SteampunkShapes]'s generous rounding:
 * the corner they sit on has to actually be a corner.
 */
fun Modifier.steampunkFrame(): Modifier = composed {
    val brassLight = SteampunkAccents.brassLight
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
        // Each rivet sits just inside the border's own arc - a bit past the corner's 45-degree
        // point (`radiusPx * (1 - cos 45°)` in from the inset edge on both axes), pushed further
        // in by `inward` so it reads as fastening the plate from inside the line rather than
        // sitting on top of the stroke itself.
        val rivetRadius = 4.dp.toPx()
        val inward = 3.dp.toPx()
        val onArc = radiusPx * 0.2929f + inward
        listOf(
            Offset(inset + onArc, inset + onArc),
            Offset(size.width - inset - onArc, inset + onArc),
            Offset(inset + onArc, size.height - inset - onArc),
            Offset(size.width - inset - onArc, size.height - inset - onArc),
        ).forEach { rivet(it, rivetRadius, brassLight, brassDark) }
    }
}

/**
 * Two rivets flanking a short horizontal brass bar - left and right, vertically centred - for
 * a bank header row (see `ProgramsScreen`). Meant for something that is already clipped to a
 * rounded [SteampunkShapes] shape and coloured as a brass plate; the rivets alone don't read as
 * fastened to anything without that rounding.
 */
fun Modifier.steampunkBarRivets(): Modifier = composed {
    val brassLight = SteampunkAccents.brassLight
    val brassDark = SteampunkAccents.brassDark
    drawWithContent {
        drawContent()
        val inset = 12.dp.toPx()
        val radius = 3.dp.toPx()
        val y = size.height / 2f
        rivet(Offset(inset, y), radius, brassLight, brassDark)
        rivet(Offset(size.width - inset, y), radius, brassLight, brassDark)
    }
}

/**
 * Two rivets top and bottom - the same idea as [steampunkBarRivets] rotated 90 degrees, for a
 * tall narrow pill-shaped element like the bank fast-scroll rail (`BankIndex`). The rail is fully
 * rounded (`RoundedCornerShape(percent = 50)`), so each end is a true semicircle of radius
 * `size.width / 2` - the rivet sits at that semicircle's own centre, not at a fixed distance from
 * the edge, so it lands in the middle of the curve regardless of the rail's actual width.
 */
fun Modifier.steampunkVerticalRivets(): Modifier = composed {
    val brassLight = SteampunkAccents.brassLight
    val brassDark = SteampunkAccents.brassDark
    drawWithContent {
        drawContent()
        val capRadius = size.width / 2f
        val radius = 3.dp.toPx()
        val x = size.width / 2f
        rivet(Offset(x, capRadius), radius, brassLight, brassDark)
        rivet(Offset(x, size.height - capRadius), radius, brassLight, brassDark)
    }
}

/**
 * The brushed-metal backdrop from the mockup - a faint warm wash from the top plus fine diagonal
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
 * The circular brass bezel drawn behind a preset row's handle glyph (see [dragHandleGlyph]) -
 * the "mechanical slot" identity from the mockup, sized to fit the existing
 * `ProgramListMetrics.handleSize` column so it changes nothing about the row's layout or the
 * drag gesture's hit-testing, which is keyed on that column's pixel width alone.
 */
fun Modifier.steampunkSlotBezel(occupied: Boolean): Modifier = composed {
    val ring = if (occupied) SteampunkAccents.brassLight else SteampunkAccents.parchmentDim
    val fill = if (occupied) SteampunkAccents.brassDark else MaterialTheme.colorScheme.surface
    this
        .padding(2.dp)
        .background(fill, CircleShape)
        .border(1.5.dp, ring, CircleShape)
}

/**
 * The riveted-plate treatment for one preset row: a small vertical gap (added *inside* the row's
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
 * The pressure gauge that stands in for `CircularProgressIndicator` while the connect screen is
 * searching for or opening an instrument, and while the preset screen waits for its first rows.
 *
 * Two rendered bitmaps rather than a drawing - a dial (`steampunk_gauge_plate`) and a needle
 * (`steampunk_gauge_needle`) - because that is how the app icon got its look. A flat disc with
 * a two-pixel ring and a stroke for a needle read as a line icon next to it, and a redraw with
 * gradients and hairlines was closer but still visibly a drawing.
 *
 * The two assets are authored to one scale, which is what keeps this composable short: the
 * needle is drawn with the same scale factor as the plate, and the plate is padded so that the
 * needle's axle (the centre screw) is its exact centre. So drawing is "fit the plate, put the
 * needle's pivot on the plate's centre, rotate". [NEEDLE_PIVOT] is where the needle's own screw
 * sits in its image, as a fraction of its size; the needle points straight up at zero rotation,
 * which on this dial is the 60 mark.
 *
 * Both live in `drawable-nodpi` at 480 px, one and a half times what an 80.dp gauge needs on a
 * 4x display - the plate at its authored 923 px would be 1.6 MB for something drawn at a
 * fraction of that - and are
 * scaled here rather than by the resource system, so the two always share a factor. The
 * source art is in `android/icons/`; the drawables are derived from it by padding the plate
 * 2 px on the left and 6 px on the top (its axle is at pixel (460, 470) of 923 x 947, so that
 * moves it to the centre), then resampling both by the same factor, 480 / 953.
 *
 * The needle hunts between the 40 and 80 marks unless [rememberReduceMotion] says not to, in
 * which case it holds still at 60 - the same information (something is in progress), without
 * the motion.
 */
@Composable
fun SteampunkGauge(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val needleDeg = if (reduceMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "steampunk-gauge")
        val angle by transition.animateFloat(
            initialValue = -42f,
            targetValue = 42f,
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
    val plate = ImageBitmap.imageResource(R.drawable.steampunk_gauge_plate)
    val needle = ImageBitmap.imageResource(R.drawable.steampunk_gauge_needle)
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

/** The needle's axle within `steampunk_gauge_needle`: on its vertical axis, at pixel row 343 of
 *  412 in the source art (measured at pixel centres). */
private val NEEDLE_PIVOT = Offset(0.5f, 343.5f / 412f)

/**
 * A riveted-panel-styled stand-in for the `OutlinedButton` the connect screen's device picker
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
