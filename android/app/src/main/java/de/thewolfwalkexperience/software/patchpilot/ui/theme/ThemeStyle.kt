package de.thewolfwalkexperience.software.patchpilot.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Everything a screen needs to draw itself differently per [AppTheme], gathered behind one
 * interface instead of an `if (theme == AppTheme.Steampunk)` at each call site.
 *
 * This is deliberately *not* where `ColorScheme`/`Typography`/`Shapes` live - those are
 * `MaterialTheme`-level tokens, already centralised in the one `when (appTheme)` in
 * [PatchPilotTheme], and every stock Material component (buttons, dialogs, menus, the destructive
 * Delete styling) already gets them for free. This interface exists only for the handful of
 * things a token swap alone cannot produce: bespoke composables (a compass standing in for
 * a spinner) and bespoke decoration (a riveted frame, a mechanical slot bezel), so no screen
 * branches on the raw `AppTheme` value itself.
 *
 * **Adding a third theme** means implementing this interface once (see [SteampunkThemeStyle] for
 * the shape of it) and mapping it in [AppTheme.style] below - no screen file changes, since they
 * only ever read [LocalThemeStyle.current] and never the raw [AppTheme] value.
 */
interface ThemeStyle {
    /** The preset row's drag handle glyph - decorative, the row itself carries the a11y label. */
    val dragHandleGlyph: String

    /** The screen-level frame/border decoration, if this theme draws one. */
    fun screenFrame(base: Modifier): Modifier

    /** The app-wide background texture, applied once at the root ([MainActivity]'s `Surface`). */
    fun screenTexture(base: Modifier): Modifier

    /** The bezel behind a preset row's handle glyph, sized to fit the existing handle column. */
    fun slotBezel(base: Modifier, occupied: Boolean): Modifier

    /** The panel treatment for one preset row, including drag/drop-target highlighting. */
    fun rowPanel(base: Modifier, dragged: Boolean, dropTarget: Boolean): Modifier

    /** The decoration for the bank fast-scroll rail (`BankIndex`). */
    fun railDecoration(base: Modifier): Modifier

    /** Stands in for a bare spinner while the connect screen is searching for/opening a device. */
    @Composable
    fun ProgressIndicator(modifier: Modifier = Modifier)

    /** One candidate in the connect screen's device picker. */
    @Composable
    fun DeviceRow(label: String, sublabel: String, onClick: () -> Unit, modifier: Modifier = Modifier)

    /** A bank caption row in the preset list (e.g. "Bank A"). */
    @Composable
    fun BankHeader(text: String, modifier: Modifier = Modifier)

    /**
     * Space to keep clear at each end of the bank rail, for decoration drawn over it.
     *
     * Zero unless a theme paints something into the rail's own ends. A theme that does has no
     * other way to say so: [railDecoration] draws *over* the rail after its content is laid out,
     * so the labels know nothing about it and will happily sit underneath. That went unnoticed
     * while the longest rail had four banks and cells tall enough to keep every label clear of the
     * ends by luck; the Motif XS's factory listing has eleven, and the first and last labels
     * landed on top of the decoration.
     */
    val railEndInset: Dp get() = 0.dp
}

/**
 * Plain Material: every function here either hands back [base] untouched or renders the stock
 * component.
 */
object DefaultThemeStyle : ThemeStyle {
    override val dragHandleGlyph = "⠿"

    override fun screenFrame(base: Modifier) = base
    override fun screenTexture(base: Modifier) = base
    override fun slotBezel(base: Modifier, occupied: Boolean) = base
    override fun rowPanel(base: Modifier, dragged: Boolean, dropTarget: Boolean) = base
    override fun railDecoration(base: Modifier) = base

    @Composable
    override fun ProgressIndicator(modifier: Modifier) {
        CircularProgressIndicator(modifier)
    }

    @Composable
    override fun DeviceRow(label: String, sublabel: String, onClick: () -> Unit, modifier: Modifier) {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label)
                Text(
                    sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    override fun BankHeader(text: String, modifier: Modifier) {
        // A plain background color isn't enough to set the header apart from its neighbors -
        // alternating row shading means those rows are surfaceVariant half the time, the same
        // color the header itself uses. The dividers give a seam that's visible regardless of
        // which shade landed on either side.
        Column(modifier.fillMaxWidth()) {
            HorizontalDivider()
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            HorizontalDivider()
        }
    }
}

/** [ThemeStyle] for the currently active [AppTheme]. */
val AppTheme.style: ThemeStyle
    get() = when (this) {
        AppTheme.Default -> DefaultThemeStyle
        AppTheme.Steampunk -> SteampunkThemeStyle
    }

/**
 * The active [ThemeStyle], provided by [PatchPilotTheme]. Defaults to [DefaultThemeStyle] so a
 * composable previewed or tested outside it degrades to the plain look rather than crashing on a
 * missing provider.
 */
val LocalThemeStyle = compositionLocalOf<ThemeStyle> { DefaultThemeStyle }
