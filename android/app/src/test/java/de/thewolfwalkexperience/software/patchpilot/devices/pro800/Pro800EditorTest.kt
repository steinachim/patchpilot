package de.thewolfwalkexperience.software.patchpilot.devices.pro800

import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The write path, against an instrument that actually stores what it is told.
 *
 * Each operation here is composed from reads and writes because the Pro-800 has no command for
 * any of them, and the tests are written around the moments where that composition can lose a
 * preset rather than around the happy path.
 */
class Pro800EditorTest {

    private val config = Pro800Config(bankCount = 2, slotsPerBank = 5, slotDigits = 2)

    /** A00=0 "Organ I", A01=1 "Strings", A02=2 unnamed; A03, A04 and all of bank B empty. */
    private fun fake() = FakePro800(
        mapOf(
            0 to FakePro800.preset("Organ I"),
            1 to FakePro800.preset("Strings", version = 109),
            2 to FakePro800.preset(null),
        ),
    )

    private fun instrument(fake: FakePro800, scope: CoroutineScope) =
        Pro800Instrument(SysExExchange(fake.transport, scope), config)

    private fun a(slot: Int) = SlotAddress(0, slot)

    /** Bank B. With five slots per bank in this fixture, B03 is program number 8. */
    private fun b(slot: Int) = SlotAddress(1, slot)

    // ---- Copy ----

    @Test
    fun `copy duplicates the preset and leaves the source alone`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        val name = pro800.editor!!.copyProgram(a(1), b(3))

        assertEquals("Strings", name)
        assertEquals("Strings", fake.nameAt(8))
        assertEquals("the source must survive its own copy", "Strings", fake.nameAt(1))
        assertArrayEquals(
            "the copy is byte-identical to what was read",
            fake.contentsOf(1),
            fake.contentsOf(8),
        )
    }

    /** The copy keeps the source's format rather than being upgraded on the way across. */
    @Test
    fun `copy preserves the preset format version`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        pro800.editor!!.copyProgram(a(1), b(3))
        assertEquals(109, Pro800Program.fromEncoded(fake.contentsOf(8)!!).version)
    }

    /**
     * **An occupied destination is refused, not overwritten.**
     *
     * Where this operation is native the instrument enforces it - a Nord answers status 4, "file
     * exists". Composed from reads and writes, nothing enforces it unless the editor does, and the
     * failure mode is destroying a preset the user never mentioned.
     */
    @Test
    fun `copy onto an occupied slot is refused and changes nothing`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        val before = fake.contentsOf(0)

        try {
            pro800.editor!!.copyProgram(a(1), a(0))
            throw AssertionError("expected a refusal")
        } catch (e: InstrumentException.NotSupported) {
            assertTrue(e.message!!.contains("occupied"))
        }
        assertArrayEquals("the destination must be untouched", before, fake.contentsOf(0))
        assertEquals("Strings", fake.nameAt(1))
    }

    @Test
    fun `copying an empty slot is refused rather than creating one`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        try {
            pro800.editor!!.copyProgram(a(3), b(3))
            throw AssertionError("expected a refusal")
        } catch (e: InstrumentException.NotSupported) {
            assertTrue(e.message!!.contains("empty"))
        }
        assertNull("no preset may be conjured at the destination", fake.contentsOf(8))
    }

    @Test
    fun `copy onto itself is refused`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        try {
            pro800.editor!!.copyProgram(a(1), a(1))
            throw AssertionError("expected a refusal")
        } catch (e: InstrumentException.NotSupported) {
            assertTrue(e.message!!.contains("itself"))
        }
        assertEquals("Strings", fake.nameAt(1))
    }

    /**
     * An unnamed preset copies fine, and reports the label the browser shows for it.
     *
     * `RegressionTester` compares this return value against what the browser reports for the
     * destination, so the two have to agree - and a Pro-800 preset may legitimately carry no name.
     */
    @Test
    fun `copying an unnamed preset reports the same label the browser shows`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        val name = pro800.editor!!.copyProgram(a(2), b(3))

        assertEquals(Pro800Instrument.UNNAMED, name)
        assertEquals(
            "the browser and the copy must agree on what to call it",
            name,
            pro800.browser.refresh(b(3)).name,
        )
    }

    /** The instrument can do this, so the facet must say so - the regression test gates on it. */
    @Test
    fun `copy is declared as a supported edit`() = runTest {
        val pro800 = instrument(fake(), backgroundScope)
        assertTrue(EditOp.COPY in pro800.editor!!.supported)
        assertTrue("it is composed from reads and writes like the rest", pro800.editor!!.isEmulated(EditOp.COPY))
    }

    // ---- Rename ----

    @Test
    fun `rename changes the name and leaves the preset format alone`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        pro800.editor!!.rename(a(1), "Warm Pad")

        assertEquals("Warm Pad", fake.nameAt(1))
        // Format 109 must stay 109: the reference implementation silently upgrades a short record
        // to 111 when it resizes one, which converts a preset nobody asked to convert (§11.7).
        assertEquals(109, Pro800Program.fromEncoded(fake.contentsOf(1)!!).version)
    }

    @Test
    fun `renaming an empty slot is refused rather than creating one`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        try {
            pro800.editor!!.rename(a(3), "New")
            throw AssertionError("renamed a slot that holds nothing")
        } catch (expected: InstrumentException.NotSupported) {
            assertTrue(fake.isEmptyAt(3))
        }
    }

    /**
     * The instrument's name field is sixteen characters wide, and it keeps the first sixteen of
     * anything longer *without complaint* - so an over-long name is not rejected by the device,
     * it is quietly truncated, and the only symptom is the write verification failing on a preset
     * that was in fact saved. "I don't know my name" came back as "I dont know my n".
     */
    @Test
    fun `a name longer than the field is refused before anything is written`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        assertEquals(16, pro800.editor!!.maxNameLength)

        try {
            pro800.editor!!.rename(a(0), "I don't know my name")
            throw AssertionError("accepted a 20-character name")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("16"))
        }
        assertTrue("nothing should have been written", fake.writeLog.isEmpty())
        assertEquals("Organ I", fake.nameAt(0))
    }

    @Test
    fun `a name of exactly the field width is accepted`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        val exactly16 = "ABCDEFGHIJKLMNOP"
        pro800.editor!!.rename(a(0), exactly16)
        assertEquals(exactly16, fake.nameAt(0))
    }

    @Test
    fun `a blank or non-ASCII name is refused before anything is written`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        listOf("", "   ", "Café").forEach { bad ->
            try {
                pro800.editor!!.rename(a(0), bad)
                throw AssertionError("accepted '$bad'")
            } catch (expected: IllegalArgumentException) {
                // and nothing went to the instrument
            }
        }
        assertTrue(fake.writeLog.isEmpty())
        assertEquals("Organ I", fake.nameAt(0))
    }

    // ---- Delete ----

    @Test
    fun `delete clears the slot`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        pro800.editor!!.delete(a(0))
        assertTrue(fake.isEmptyAt(0))
    }

    @Test
    fun `deleting an already-empty slot is a no-op, not an error`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        pro800.editor!!.delete(a(4))
        assertTrue(fake.isEmptyAt(4))
        assertTrue("nothing needed writing", fake.writeLog.isEmpty())
    }

    // ---- Move ----

    @Test
    fun `move copies to the destination and clears the source`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        pro800.editor!!.move(a(0), b(3))

        assertEquals("Organ I", fake.nameAt(8))
        assertTrue(fake.isEmptyAt(0))
    }

    /**
     * The ordering rule: an interruption must leave a duplicate, never a hole. The destination is
     * written first, so the source is only cleared once its contents demonstrably exist elsewhere.
     */
    @Test
    fun `move writes the destination before erasing the source`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        pro800.editor!!.move(a(0), b(3))

        val writes = fake.writeLog
        assertEquals("destination first", 8, writes.first().first)
        assertTrue("destination write carried data", writes.first().second > 0)
        assertEquals("source erased second", 0, writes[1].first)
        assertEquals("the erase is an empty payload", 0, writes[1].second)
    }

    /** Losing the source's contents to a failed destination write is the thing to avoid. */
    @Test
    fun `a move whose destination write fails leaves the source untouched`() = runTest {
        val fake = fake()
        fake.failWritesTo = setOf(8)
        val pro800 = instrument(fake, backgroundScope)

        try {
            pro800.editor!!.move(a(0), b(3))
            throw AssertionError("a move onto a slot that refused the write reported success")
        } catch (expected: InstrumentException.ProtocolDesync) {
            assertEquals("Organ I", fake.nameAt(0))
            assertTrue(fake.isEmptyAt(8))
        }
    }

    @Test
    fun `move onto an occupied slot is refused - that is what swap is for`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        try {
            pro800.editor!!.move(a(0), a(1))
            throw AssertionError("moved onto an occupied slot")
        } catch (expected: InstrumentException.NotSupported) {
            assertEquals("Organ I", fake.nameAt(0))
            assertEquals("Strings", fake.nameAt(1))
        }
    }

    // ---- Swap ----

    @Test
    fun `swap exchanges two presets`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        pro800.editor!!.swap(a(0), a(1))

        assertEquals("Strings", fake.nameAt(0))
        assertEquals("Organ I", fake.nameAt(1))
    }

    @Test
    fun `swapping with an empty slot moves the preset and empties the source`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        pro800.editor!!.swap(a(0), a(3))

        assertTrue(fake.isEmptyAt(0))
        assertEquals("Organ I", fake.nameAt(3))
    }

    /**
     * **The one operation that can destroy a preset outright.** Once `a` has been overwritten with
     * `b`'s contents, the only copy of `a`'s original is in memory - so a failure on the second
     * write has to put it back.
     */
    @Test
    fun `a swap that fails halfway rolls the first write back`() = runTest {
        val fake = fake()
        fake.failWritesTo = setOf(1) // the second write will not take
        val pro800 = instrument(fake, backgroundScope)

        try {
            pro800.editor!!.swap(a(0), a(1))
            throw AssertionError("a half-completed swap reported success")
        } catch (expected: InstrumentException.ProtocolDesync) {
            // Both presets survive, in their original places.
            assertEquals("Organ I", fake.nameAt(0))
            assertEquals("Strings", fake.nameAt(1))
        }
    }

    // ---- Verification ----

    /**
     * An instrument that accepts a write and stores nothing is exactly the failure a status byte
     * would not reveal - which is why every write is proved by reading the address back.
     */
    @Test
    fun `a write that silently does not take is caught`() = runTest {
        val fake = fake()
        fake.ignoreWrites = true
        val pro800 = instrument(fake, backgroundScope)

        try {
            pro800.editor!!.rename(a(0), "Warm Pad")
            throw AssertionError("a dropped write reported success")
        } catch (expected: InstrumentException.ProtocolDesync) {
            assertTrue(expected.message!!.contains("A00"))
            assertEquals("Organ I", fake.nameAt(0))
        }
    }

    // ---- Undo buffer ----

    @Test
    fun `overwritten bytes are kept for the session`() = runTest {
        val fake = fake()
        val pro800 = instrument(fake, backgroundScope)
        val editor = pro800.editor as Pro800Editor

        pro800.editor!!.delete(a(0))
        val entry = editor.undoBuffer.first()
        assertEquals(a(0), entry.address)
        assertEquals("A00", entry.displayId)
        assertEquals("Organ I", Pro800Program.fromEncoded(entry.blob).name)
    }

    // ---- Declaration ----

    @Test
    fun `every edit this instrument can do is declared, and all are emulated`() {
        val editor = Pro800Editor(
            transfer = object : de.thewolfwalkexperience.software.patchpilot.core.PresetTransfer {
                override suspend fun read(address: SlotAddress) = ByteArray(0)
                override suspend fun write(address: SlotAddress, blob: ByteArray) = Unit
                override val fileExtension = "syx"
            },
            layout = Pro800Instrument(
                SysExExchange(FakePro800().transport, CoroutineScope(kotlinx.coroutines.Job())),
                config,
            ).layout,
        )
        // COPY joined the rest once it was implemented: the instrument has no copy command, but
        // it has the reads and writes to compose one, and RegressionTester gates its copy step on
        // this set - it used to skip with "this instrument cannot copy a preset", which was untrue.
        assertEquals(
            setOf(EditOp.RENAME, EditOp.MOVE, EditOp.SWAP, EditOp.DELETE, EditOp.COPY),
            editor.supported,
        )
        assertEquals("every EditOp there is", EditOp.entries.toSet(), editor.supported)
        EditOp.entries.forEach { assertTrue("$it should be emulated", editor.isEmulated(it)) }
        assertFalse(editor.supported.isEmpty())
        assertNull(null)
    }
}
