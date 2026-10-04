// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.core.InstrumentNotice

/**
 * The notice to show next, or null: the first of [notices] that is neither hidden for good nor
 * already shown since the app started. Once per app run rather than once per session, since a
 * USB family rebuilds its session on every return to the foreground.
 */
internal fun pendingNotice(
    notices: List<InstrumentNotice>,
    hidden: Set<String>,
    shownThisRun: Set<String>,
): InstrumentNotice? = notices.firstOrNull { it.id !in hidden && it.id !in shownThisRun }
