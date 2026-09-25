// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui.theme

/**
 * Which skin the app is drawn in: [Default] is stock Material, which is what anyone who never
 * opens Settings sees. An enum rather than a sealed hierarchy, since the settings screen lists
 * every value as a plain choice; what each theme draws differently lives behind [style].
 */
enum class AppTheme {
    Default,
    Steampunk,
}
