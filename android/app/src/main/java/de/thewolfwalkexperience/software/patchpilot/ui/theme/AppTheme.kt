package de.thewolfwalkexperience.software.patchpilot.ui.theme

/**
 * Which skin the app is drawn in. [Default] is stock Material (system light/dark, dynamic color
 * where available) - anyone who never opens Settings sees exactly that and nothing else.
 *
 * Two entries, not a sealed hierarchy: the settings screen lists every value ([entries]) as a
 * plain choice, and nothing here yet needs a case to carry data of its own. What each theme draws
 * differently lives behind [style] (see [ThemeStyle]), not here - this type is just the
 * persisted, user-facing choice.
 */
enum class AppTheme {
    Default,
    Steampunk,
}
