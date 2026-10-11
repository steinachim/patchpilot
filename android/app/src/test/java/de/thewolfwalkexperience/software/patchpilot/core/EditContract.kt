// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/**
 * Connects [instrument], then runs every edit its editor declares on its own listing, in turn, and
 * checks each one landed. Needs two occupied slots and an empty one. Shared by the demo, the
 * no-copy fixture and each family's own test, which builds the instrument on its fake: the
 * property is the same whatever the shape.
 */
internal suspend fun assertEveryDeclaredEditCompletes(name: String, instrument: Instrument) {
    instrument.connect()
    val editor = instrument.editor!!
    val occupied = instrument.browser.index().toList()
        .filterIsInstance<IndexUpdate.Slots>()
        .flatMap { it.slots }
        // A Pro-800 lists every address, empty ones included.
        .filterNot { it.isEmpty }
    val empty = instrument.layout.allAddresses().first { address ->
        occupied.none { it.address == address }
    }
    // Where the first preset is, as the edits below move it.
    var at = occupied[0].address

    if (EditOp.RENAME in editor.supported) {
        editor.rename(at, "Renamed")
        assertEquals("Renamed", instrument.browser.refresh(at).name)
    }
    if (EditOp.MOVE in editor.supported) {
        editor.move(at, empty)
        assertNull("$name left the source occupied after a move", instrument.browser.refresh(at).name)
        at = empty
    }
    if (EditOp.SWAP in editor.supported) {
        // With another preset: a Nord, and demo mode with it, refuses a swap with an empty slot.
        val other = occupied[1]
        val moving = instrument.browser.refresh(at).name
        editor.swap(at, other.address)
        assertEquals(other.name, instrument.browser.refresh(at).name)
        assertEquals(moving, instrument.browser.refresh(other.address).name)
        at = other.address
    }
    if (EditOp.COPY in editor.supported) {
        val stillEmpty = instrument.layout.allAddresses().first { address ->
            instrument.browser.refresh(address).name == null
        }
        val copyName = editor.copyProgram(at, stillEmpty)
        assertEquals(copyName, instrument.browser.refresh(stillEmpty).name)
    }
    if (EditOp.DELETE in editor.supported) {
        editor.delete(at)
        assertNull(instrument.browser.refresh(at).name)
    }
}
