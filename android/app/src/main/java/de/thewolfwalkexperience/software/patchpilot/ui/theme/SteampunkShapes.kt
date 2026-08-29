package de.thewolfwalkexperience.software.patchpilot.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Generously rounded, matching the mockup's own screen/card corners rather than Material's
 * defaults (4/8/12/16/28.dp) - a tighter, more faceted set was tried first and read as a
 * different, colder object than the brass instrument panels it was meant to evoke. Applied once
 * here and inherited by every `Surface`, `Card`, `AlertDialog`, `TextField` and menu in the app.
 */
val SteampunkShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
