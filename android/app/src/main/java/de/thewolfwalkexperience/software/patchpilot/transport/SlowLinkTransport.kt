// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.transport

/** [inner], marked as a [MidiTransport.slowLink]: the instrument sits behind another MIDI interface. */
class SlowLinkTransport(inner: MidiTransport) : MidiTransport by inner {
    override val slowLink: Boolean get() = true
}
