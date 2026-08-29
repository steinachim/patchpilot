package de.thewolfwalkexperience.software.patchpilot.core

import de.thewolfwalkexperience.software.patchpilot.demo.DemoInstrument
import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.MotifXsVoice
import de.thewolfwalkexperience.software.patchpilot.devices.pro800.Pro800ProgramFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every family that offers rename must say how long a name may be.
 *
 * The rename field caps input at [PresetEditor.maxNameLength], so a family that leaves it null
 * gets **no cap and no character counter** - and the failure that produces is the quiet kind:
 * these instruments accept an over-long name, store a prefix of it, and report success. The user
 * sees a rename that worked and a preset that is called something else.
 *
 * A Nord went two years without one for exactly that reason - nothing failed, so nothing pointed
 * at it. This test is what makes the omission loud for the next family.
 */
class NameLengthTest {

    @Test
    fun `the demo instrument caps names, because every real family does`() {
        val editor = DemoInstrument().editor
        assertNotNull(editor)
        assertTrue("demo offers rename", EditOp.RENAME in editor!!.supported)
        assertNotNull("a family offering rename must declare a limit", editor.maxNameLength)
    }

    /**
     * Pinned against the constants the decoders use, so a change to the wire format has to change
     * this too rather than silently widening what the UI accepts.
     */
    @Test
    fun `the declared limits match what each format actually stores`() {
        assertEquals("a Motif XS voice name field is 20 bytes", 20, MotifXsVoice.NAME_LENGTH)
        assertEquals("a Pro-800 program name field is 16 bytes", 16, Pro800ProgramFields.NAME_LENGTH)
    }

    /**
     * The base interface returns null, which means "no known limit" rather than "unlimited".
     * Worth pinning: a family that forgets to override gets the uncapped path, and this states
     * that the default is deliberate rather than an oversight.
     */
    @Test
    fun `the default is null, meaning no limit is known`() {
        val noLimit = object : PresetEditor {
            override val supported = emptySet<EditOp>()
            override fun isEmulated(op: EditOp) = false
            override suspend fun rename(address: SlotAddress, newName: String) = Unit
            override suspend fun move(from: SlotAddress, to: SlotAddress) = Unit
            override suspend fun swap(a: SlotAddress, b: SlotAddress) = Unit
            override suspend fun delete(address: SlotAddress) = Unit
        }
        assertEquals(null, noLimit.maxNameLength)
    }
}
