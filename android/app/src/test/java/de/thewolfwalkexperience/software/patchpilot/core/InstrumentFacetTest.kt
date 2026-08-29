package de.thewolfwalkexperience.software.patchpilot.core

import de.thewolfwalkexperience.software.patchpilot.demo.DemoInstrument
import de.thewolfwalkexperience.software.patchpilot.devices.nord.DemoUsbTransport
import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordDevice
import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordInstrument
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.MotifXsBank
import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.MotifXsBlanks
import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.MotifXsConfig
import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.MotifXsInstrument
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import de.thewolfwalkexperience.software.patchpilot.transport.MidiTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import de.thewolfwalkexperience.software.patchpilot.devices.nord.DemoProfile

/**
 * The check that closes the gap a `Set<Capability>` beside a fat interface would have left open:
 * **every operation an instrument declares in `editor.supported` has to actually work.**
 *
 * A declared-but-unimplemented capability is a bug that otherwise only shows up on hardware, in
 * front of a user, after a UI has already drawn the button for it.
 */
class InstrumentFacetTest {

    private fun nord(): NordInstrument = NordInstrument(NordDevice(DemoUsbTransport(), DemoProfile.profile()))

    /** A Motif XS with no transport behind it - enough to ask what its editor declares. */
    private fun motifXs(scope: CoroutineScope = CoroutineScope(SupervisorJob())): MotifXsInstrument =
        MotifXsInstrument(
            SysExExchange(NullMidiTransport, scope),
            MotifXsConfig(
                banks = listOf(
                    MotifXsBank(label = "USR1", slotCount = 4, addressHi = 0x0C, addressMid = 0x0A,
                        displayLabel = "USER 1", selectLsb = 0x08),
                ),
            ),
            MotifXsBlanks(normal = ByteArray(0), drum = ByteArray(0)),
        )

    private object NullMidiTransport : MidiTransport {
        override val rebuildOnResume = false
        override fun send(bytes: ByteArray) = Unit
        override val incoming: Flow<ByteArray> = emptyFlow()
        override fun close() = Unit
    }

    // ---- Every declared edit actually completes ----

    @Test
    fun `every edit a Nord declares completes against the wire-level fake`() = runTest {
        val instrument = nord()
        instrument.connect()
        val editor = instrument.editor!!

        assertTrue(EditOp.RENAME in editor.supported)
        editor.rename(SlotAddress(0, 0), "Renamed")
        assertEquals("Renamed", instrument.refresh(SlotAddress(0, 0)).name)

        // (0, 0) is occupied; (3, 24) is left empty by the fixture's catalog.
        assertTrue(EditOp.MOVE in editor.supported)
        editor.move(SlotAddress(0, 0), SlotAddress(3, 24))
        assertEquals("Renamed", instrument.refresh(SlotAddress(3, 24)).name)
        assertNull(instrument.refresh(SlotAddress(0, 0)).name)

        assertTrue(EditOp.SWAP in editor.supported)
        val before = instrument.refresh(SlotAddress(0, 1)).name
        editor.swap(SlotAddress(0, 1), SlotAddress(3, 24))
        assertEquals("Renamed", instrument.refresh(SlotAddress(0, 1)).name)
        assertEquals(before, instrument.refresh(SlotAddress(3, 24)).name)
    }

    /** Shared by both instruments below - the property is the same regardless of shape. */
    private suspend fun assertEveryDeclaredEditCompletes(name: String, instrument: Instrument) {
        instrument.connect()
        val editor = instrument.editor!!
        val occupied = instrument.browser.index().toList()
            .filterIsInstance<IndexUpdate.Slots>()
            .flatMap { it.slots }
        val first = occupied.first().address
        val empty = instrument.layout.allAddresses().first { address ->
            occupied.none { it.address == address }
        }

        if (EditOp.RENAME in editor.supported) {
            editor.rename(first, "Renamed")
            assertEquals("Renamed", instrument.browser.refresh(first).name)
        }
        if (EditOp.MOVE in editor.supported) {
            editor.move(first, empty)
            assertNull("$name left the source occupied after a move", instrument.browser.refresh(first).name)
        }
        if (EditOp.SWAP in editor.supported) {
            editor.swap(empty, first)
            assertEquals("Renamed", instrument.browser.refresh(first).name)
        }
        if (EditOp.DELETE in editor.supported) {
            editor.delete(first)
            assertNull(instrument.browser.refresh(first).name)
        }
    }

    @Test
    fun `every edit the demo instrument declares completes`() = runTest {
        assertEveryDeclaredEditCompletes("the demo instrument", DemoInstrument())
    }

    @Test
    fun `every edit the no-copy fixture declares completes`() = runTest {
        assertEveryDeclaredEditCompletes("the no-copy fixture", NoCopyFixtureInstrument())
    }

    /**
     * The other half of the same rule: an op *not* declared must not be quietly implemented
     * either, or the UI is hiding something that works.
     *
     * **The example has now moved twice, and that is the point.** It was a Nord delete until that
     * was ported (sub-opcode 20/21), then the Motif XS's rename until Yamaha's documented write
     * path was implemented and that was ported too. It is now [NoCopyFixtureInstrument]'s copy,
     * which is a fixture rather than a family - so
     * the next port cannot invalidate it, and the property stops being hostage to how much of
     * each instrument happens to be understood this month.
     */
    @Test
    fun `an undeclared edit throws rather than silently working`() = runTest {
        val editor = NoCopyFixtureInstrument().editor!!
        assertFalse("the fixture deliberately does not copy", EditOp.COPY in editor.supported)
        try {
            editor.copyProgram(SlotAddress(0, 0), SlotAddress(0, 1))
            throw AssertionError("copy is undeclared but ran anyway")
        } catch (expected: UnsupportedOperationException) {
            // exactly what an undeclared op should do
        }
    }

    /**
     * A share filename can be derived for **every** instrument, reporter or not.
     *
     * This is the regression guard for a real crash: the debug menu's regression test is offered
     * for every family and named its shared file with the *reporter's* helper, which requires a
     * facet a Motif XS does not have. Tapping Share killed the app. The rule it broke is the one
     * the whole facet design rests on - a null facet means "cannot do this at all", so a
     * feature outside that facet must not route through it.
     *
     * The Motif XS case is the one that mattered, and it is included by name because it is the
     * shipping family whose `report` is null.
     */
    @Test
    fun `a filename stem exists for every instrument, with or without a reporter`() = runTest {
        val motif = motifXs()
        assertNull("this test is pointless if the Motif XS grows a reporter", motif.report)

        // Asserted as a property rather than a literal: the stem comes from the *fixture's*
        // configured name, and pinning the string would make this test about the fixture instead
        // of about the rule. What matters is that it exists and is usable as a filename.
        for (instrument in listOf(motif, DemoInstrument())) {
            val stem = stemFor(instrument)
            assertTrue("a stem must never be blank", stem.isNotBlank())
            assertTrue("'$stem' is not filename-safe", stem.all { it.isDigit() || it in 'a'..'z' || it == '_' })
        }

        // The one literal worth keeping, because it is the name that crashed.
        assertEquals("yamaha_motif_xs", slugifyDeviceId("Yamaha Motif XS"))
    }

    /** Every family that declares DELETE must actually implement it - the point of this change. */
    @Test
    fun `delete is declared by every family that can do it`() = runTest {
        val nordOps = nord().also { it.connect() }.editor!!.supported
        assertTrue("a Nord deletes with sub-opcode 20/21", EditOp.DELETE in nordOps)
        assertTrue("the Motif XS erases the slot the way a move clears its source",
            EditOp.DELETE in motifXs().editor!!.supported)
    }

    // ---- Facet presence is what the UI gates on ----

    @Test
    fun `a Nord offers the facets its protocol has, and only those`() {
        val instrument = nord()
        assertNotNull(instrument.selector)
        assertNotNull(instrument.editor)
        assertNotNull(instrument.report)
        // Until the Nord's own item-data read/write path is implemented here, there is nothing to hand out.
        assertNull(instrument.transfer)
    }

    /**
     * The whole point of keeping this fixture: without it, nothing exercises the screens' facet
     * checks until real Pro-800 or Motif XS hardware exists.
     */
    @Test
    fun `the no-copy fixture withholds the facets a family without a report has no equivalent for`() {
        val fixture = NoCopyFixtureInstrument()
        assertNull(fixture.report)
        assertNotNull(fixture.browser)
        assertNotNull(fixture.selector)
        assertNotNull(fixture.editor)

        val demo = DemoInstrument()
        assertNotNull(demo.report)
    }

    @Test
    fun `emulated edits are declared as such, native ones are not`() {
        val nordEditor = nord().editor!!
        EditOp.entries.forEach { assertFalse(nordEditor.isEmulated(it)) }

        val fixtureEditor = NoCopyFixtureInstrument().editor!!
        EditOp.entries.forEach { assertTrue(fixtureEditor.isEmulated(it)) }
    }

    // ---- Index shape ----

    @Test
    fun `a Nord index arrives in one batch and completes`() = runTest {
        val instrument = nord()
        instrument.connect()
        val updates = instrument.browser.index().toList()
        assertEquals(1, updates.count { it is IndexUpdate.Slots })
        assertTrue(updates.last() is IndexUpdate.Complete)
        val slots = updates.filterIsInstance<IndexUpdate.Slots>().single().slots
        assertTrue(slots.isNotEmpty())
        // Every row carries its own bank label, so no screen has to take an id apart.
        assertTrue(slots.all { it.bankLabel.isNotBlank() && it.displayId.startsWith(it.bankLabel) })
    }

    @Test
    fun `a slow index reports progress and arrives in several batches`() = runTest {
        val fixture = NoCopyFixtureInstrument()
        val updates = fixture.browser.index().toList()
        assertTrue("expected several batches", updates.count { it is IndexUpdate.Slots } > 1)
        assertTrue("expected progress reports", updates.any { it is IndexUpdate.Progress })
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    /**
     * The two shapes' selectors say different things on purpose: one verifies the address the
     * instrument echoes back, the other sends a message nothing answers.
     */
    @Test
    fun `a selector's confirmation reflects what its instrument can honestly claim`() {
        assertEquals("Selected A:1:1.", nord().selector!!.confirmationFor("A:1:1"))
        assertEquals("Sent A00.", NoCopyFixtureInstrument().selector!!.confirmationFor("A00"))
    }
}
