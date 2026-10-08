// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.midi

import de.thewolfwalkexperience.software.patchpilot.transport.MidiTransport
import de.thewolfwalkexperience.software.patchpilot.transport.Transport
import de.thewolfwalkexperience.software.patchpilot.transport.transportScope

/**
 * The exchange a MIDI-only family's instrument talks through, in its own [transportScope]. A
 * family builds it last in `create`: its collector starts here, so a catalog or asset fault after
 * this point would leave the collector running on a transport nobody owns.
 *
 * @param speaker the instrument as the refusal of any other transport names it, e.g. "A Pro-800".
 * @param scopeName names the scope, as [transportScope] does.
 */
fun familyExchange(
    transport: Transport,
    speaker: String,
    scopeName: String,
    framer: SysExFramer = SysExFramer(),
): SysExExchange {
    val midi = transport as? MidiTransport
        ?: error("$speaker speaks MIDI SysEx, not ${transport::class.simpleName}.")
    return SysExExchange(midi, transportScope(scopeName), framer)
}
