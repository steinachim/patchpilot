package de.thewolfwalkexperience.software.patchpilot.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The "Brass & Aether" [ThemeStyle] - every bespoke piece a `MaterialTheme` token swap alone
 * can't produce, gathered from the free functions/composables in `SteampunkComponents.kt`
 * (`steampunkFrame`, `SteampunkCompass`, etc.) into the shape screens actually call.
 */
object SteampunkThemeStyle : ThemeStyle {
    override val dragHandleGlyph = "⚙"

    override fun screenFrame(base: Modifier): Modifier = base.steampunkFrame()
    override fun screenTexture(base: Modifier): Modifier = base.steampunkTexture()
    override fun slotBezel(base: Modifier, occupied: Boolean): Modifier = base.steampunkSlotBezel(occupied)

    override fun rowPanel(base: Modifier, dragged: Boolean, dropTarget: Boolean): Modifier =
        base.steampunkRowPanel(dragged, dropTarget)

    /**
     * Clear of the cap rivets [steampunkVerticalRivets] draws.
     *
     * The rivet sits at the centre of each semicircular end cap - `railWidth / 2`, so 24.dp in -
     * with a 3.dp radius, hence 27.dp plus a little air. Stated rather than derived because the
     * rail's width is the screen's to choose, and a theme that guessed it would be wrong quietly.
     */
    override val railEndInset = 30.dp

    override fun railDecoration(base: Modifier): Modifier {
        // A riveted brass rail rather than a plain M3 pill - border plus a rivet at each end, the
        // same two-rivet language as a bank header rotated vertical. Recomputing the pill shape
        // here (rather than sharing BankIndex's own) is cheap - it's just a shape descriptor, not
        // a layout - and keeps this function self-contained.
        val pillShape = RoundedCornerShape(percent = 50)
        return base.border(1.dp, SteampunkAccents.brassDark, pillShape).steampunkVerticalRivets()
    }

    @Composable
    override fun ProgressIndicator(modifier: Modifier) {
        SteampunkCompass(modifier)
    }

    @Composable
    override fun DeviceRow(label: String, sublabel: String, onClick: () -> Unit, modifier: Modifier) {
        SteampunkDeviceRow(label, sublabel, onClick, modifier)
    }

    @Composable
    override fun BankHeader(text: String, modifier: Modifier) {
        // A riveted brass plate, like the preset rows below it, rather than a plain divided band
        // - the theme's own rounding needs a real edge to sit inside, which a full-bleed
        // background doesn't give it (see steampunkFrame's doc comment for the general rule).
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                .steampunkBarRivets(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
