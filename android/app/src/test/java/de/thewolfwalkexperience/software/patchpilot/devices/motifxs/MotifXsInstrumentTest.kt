// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import de.thewolfwalkexperience.software.patchpilot.core.Bus
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.PresetEditor
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.UNKNOWN_FIRMWARE
import de.thewolfwalkexperience.software.patchpilot.core.assertEveryDeclaredEditCompletes
import de.thewolfwalkexperience.software.patchpilot.midi.FakeMidiTransport
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import de.thewolfwalkexperience.software.patchpilot.thrownBy
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Motif XS browser end to end against a scripted device that replays sample dumps.
 *
 * The instrument is deliberately tiny here - two banks of three - because the shape under test is
 * the walk, not its length. The real one is 416 voices and over a minute, which is why the walk is
 * a flow in the first place.
 */
class MotifXsInstrumentTest {

    private val config = MotifXsConfig(
        banks = listOf(
            // selectLsb is the real instrument's: USER 1 selects with 0x08 and dumps at 0x0A,
            // which is exactly why bank-select LSB is not derivable from the dump address.
            MotifXsBank(
                label = "USR1",
                slotCount = 3,
                addressHi = 0x0C,
                addressMid = 0x0A,
                displayLabel = "USER 1",
                selectLsb = 0x08,
            ),
            MotifXsBank(
                label = "USR2",
                slotCount = 3,
                addressHi = 0x0C,
                addressMid = 0x0B,
                displayLabel = "USER 2",
                selectLsb = 0x09,
            ),
        ),
    )

    /** A stand-in for the app's shipped blanks - real sample data, not fabricated bytes. */
    private val testBlanks
        get() = MotifXsBlanks(
            normal = MotifXsSysEx.dumpPayload(MotifXsFixtures.emptyVoice),
            drum = MotifXsSysEx.dumpPayload(MotifXsFixtures.emptyVoice),
        )

    private fun instrument(
        scope: CoroutineScope,
        withConfig: MotifXsConfig = config,
        /** The names the factory listing draws on; empty means no factory scope is offered. */
        factoryVoices: MotifXsFactoryVoices = MotifXsFactoryVoices(),
        fake: FakeMotifXs = FakeMotifXs(),
    ): Pair<MotifXsInstrument, FakeMidiTransport> = MotifXsInstrument(
        SysExExchange(fake.transport, scope),
        withConfig,
        testBlanks,
        factoryVoices = factoryVoices,
    ) to fake.transport

    /** The contract every family is held to - see `assertEveryDeclaredEditCompletes`. */
    @Test
    fun `every edit the Motif XS declares completes`() = runTest {
        assertEveryDeclaredEditCompletes("the Motif XS", instrument(backgroundScope).first)
    }

    @Test
    fun `the index walks every address and names the occupied ones`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        val updates = motif.browser.index().toList()
        val slots = updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }

        assertEquals(6, slots.size)
        assertEquals("TWE2 Wolf Walk", slots.first { it.displayId == "USER 1 - A:01" }.name)
        assertNull(slots.first { it.displayId == "USER 1 - A:02" }.name)
        assertEquals("TWE2 Dreadnought 2.0", slots.first { it.displayId == "USER 2 - A:03" }.name)
        assertEquals(6, transport.sent.count { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_DUMP_REQUEST })
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    /**
     * A dump that arrives truncated is **retried**, not reported as a permanent failure.
     *
     * A live 416-voice scan shows a handful of damaged dumps. The well-formedness check belongs
     * inside the exchange's matcher: checked after the exchange returned, a corrupted dump would
     * satisfy the matcher, become "the reply", and never reach the retry for a fault that is
     * transient by nature.
     */
    @Test
    fun `a damaged dump is retried rather than failing the slot`() = runTest {
        var attempts = 0
        val transport = FakeMidiTransport { request ->
            val address = MotifXsSysEx.addressOf(request) ?: return@FakeMidiTransport emptyList()
            val good = dumpAt(MotifXsFixtures.namedVoice, address.first, address.second, address.third)
            if (address.third == 0 && attempts++ == 0) {
                // Truncated in flight: the framer still emits F0..F7, so this *looks* like a
                // reply and is exactly what a dropped USB chunk produces.
                listOf(good.copyOfRange(0, good.size - 40) + 0xF7.toByte())
            } else {
                listOf(good)
            }
        }
        val motif = MotifXsInstrument(
            SysExExchange(transport, backgroundScope, defaultTimeout = 200.milliseconds, retries = 2),
            MotifXsConfig(
                banks = listOf(
                    MotifXsBank(
                        label = "USR1",
                        slotCount = 1,
                        addressHi = 0x0C,
                        addressMid = 0x0A,
                        displayLabel = "USER 1",
                        selectLsb = 0x08,
                    ),
                ),
            ),
            testBlanks,
        )
        val updates = motif.browser.index().toList()

        assertTrue("a damaged dump must not fail the slot", updates.filterIsInstance<IndexUpdate.Failed>().isEmpty())
        assertEquals(
            "TWE2 Wolf Walk",
            updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }.single().name,
        )
        assertTrue("the damaged reply should have forced a second attempt", attempts >= 2)
    }

    @Test
    fun `every row carries its own bank label`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        val slots = motif.browser.index().toList()
            .filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }
        // The panel's label, not the catalog shorthand: the header above these rows would
        // otherwise read "Bank USR1" over ids reading "USER 1 - A:01".
        assertEquals("USER 1", slots.first { it.displayId == "USER 1 - A:01" }.bankLabel)
        assertEquals("USER 2", slots.first { it.displayId == "USER 2 - A:01" }.bankLabel)
    }

    /**
     * A dump for the wrong address must not be accepted for this one.
     *
     * Not a hypothetical: at ~155 ms a voice, a late reply to the previous request is ordinary
     * traffic, and taking it would put one voice's name on another's row - a wrong answer that
     * looks entirely plausible in the list.
     */
    @Test
    fun `a reply for a different address is not accepted`() = runTest {
        val transport = FakeMidiTransport { request ->
            val address = MotifXsSysEx.addressOf(request) ?: return@FakeMidiTransport emptyList()
            // Always answers with slot 0's dump, whatever was asked for.
            listOf(dumpAt(MotifXsFixtures.namedVoice, address.first, address.second, 0))
        }
        val motif = MotifXsInstrument(
            SysExExchange(transport, backgroundScope, defaultTimeout = 20.milliseconds, retries = 0),
            config,
            testBlanks,
        )
        val updates = motif.browser.index().toList()
        val named = updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }
        val failed = updates.filterIsInstance<IndexUpdate.Failed>()

        // Only the two slot-0 addresses are satisfied; the other four time out rather than
        // silently inheriting slot 0's name.
        assertEquals(2, named.size)
        assertEquals(4, failed.size)
        assertTrue(named.all { it.name == "TWE2 Wolf Walk" })
    }

    /**
     * The same rule for the read every edit is built on, where it matters more: its bytes are
     * written back to the instrument, so accepting another slot's dump would copy, move or swap
     * the wrong voice, and the read-back would then confirm it.
     */
    @Test
    fun `the read an edit writes back refuses a reply for a different address`() = runTest {
        val transport = FakeMidiTransport { request ->
            val address = MotifXsSysEx.addressOf(request) ?: return@FakeMidiTransport emptyList()
            // Always answers with slot 0's dump, whatever was asked for.
            listOf(dumpAt(MotifXsFixtures.namedVoice, address.first, address.second, 0))
        }
        val motif = MotifXsInstrument(
            SysExExchange(transport, backgroundScope, defaultTimeout = 20.milliseconds, retries = 0),
            config,
            testBlanks,
        )

        assertEquals("TWE2 Wolf Walk", MotifXsVoice.nameOf(motif.readVoicePayload(SlotAddress(0, 0))))
        val refusal = runCatching { motif.readVoicePayload(SlotAddress(0, 2)) }.exceptionOrNull()
        assertTrue("expected a timeout, got $refusal", refusal is InstrumentException.Timeout)
    }

    /**
     * A damaged dump costs its own voice and nothing else - a large scan is likely to hit at
     * least one corrupted dump along the way, so a walk that gives up on the first one would
     * never finish.
     */
    @Test
    fun `a damaged dump fails one slot and the walk continues`() = runTest {
        val transport = FakeMidiTransport { request ->
            val (hi, mid, lo) = MotifXsSysEx.addressOf(request)
                ?: return@FakeMidiTransport emptyList()
            val dump = dumpAt(MotifXsFixtures.namedVoice, hi, mid, lo)
            if (lo == 1) dump[600] = (dump[600] + 1).toByte() // one flipped byte in slot 1
            listOf(dump)
        }
        val motif = MotifXsInstrument(
            SysExExchange(transport, backgroundScope, defaultTimeout = 20.milliseconds, retries = 0),
            config,
            testBlanks,
        )
        val updates = motif.browser.index().toList()

        val failed = updates.filterIsInstance<IndexUpdate.Failed>()
        assertEquals(2, failed.size)
        assertTrue(failed.first().reason, failed.first().reason.contains("arrived damaged"))
        assertEquals(4, updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }.size)
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    @Test
    fun `connect reads the firmware version out of the inquiry reply`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        motif.connect()
        assertEquals("Yamaha Motif XS", motif.identity.name)
        // USB, not MIDI: this instrument is matched by USB ids and has no MIDI port at all.
        assertEquals(Bus.USB, motif.identity.bus)
        assertEquals("6.0.0.127", motif.identity.firmwareVersion)
    }

    /** Only what was measured on the instrument is offered, and the nulls say so - see MotifXsInstrument's class doc. */
    @Test
    fun `only the facets something has actually observed are offered`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        // Select, move and swap are all supported.
        assertNotNull(motif.selector)
        assertNotNull(motif.editor)
        // Four of these are one stored-slot `0C` write plus a `11 00 00` commit. RENAME is the
        // fifth and is not built on that path at all - it uses Yamaha's documented Bulk
        // Header/Footer sequence.
        assertEquals(
            setOf(EditOp.RENAME, EditOp.MOVE, EditOp.SWAP, EditOp.DELETE, EditOp.COPY),
            motif.editor!!.supported,
        )
        // Still nothing anywhere about these.
        assertNull(motif.transfer)
        assertNull(motif.report)
    }

    // ---- Connecting to an instrument that is not listening on USB ----

    /**
     * **Total silence is refused, with the fix spelled out.**
     *
     * A Motif XS routes MIDI to one destination - DIN, USB or mLAN - and set to any but USB it
     * still enumerates, still opens, and then ignores everything. A session built anyway would be
     * a working-looking app in which each operation times out separately and none can say why.
     */
    @Test
    fun `an instrument answering nothing is refused, with the setting to change`() = runTest {
        val (motif, _) = instrument(backgroundScope, fake = FakeMotifXs(mode = null, answersIdentity = false))
        try {
            motif.connect()
            throw AssertionError("a silent instrument was accepted as a working session")
        } catch (expected: InstrumentException.NeedsManualSetting) {
            assertTrue(expected.message!!.contains("MIDI In/Out"))
            // The button sequence has to be usable while standing at the instrument.
            assertTrue(expected.steps.any { it.contains("UTILITY") })
            assertTrue(expected.steps.any { it.contains("[F5]") && it.contains("[SF2]") })
            assertTrue(expected.steps.any { it.contains("USB") })
            // Silence has other causes, and the app is inferring from an absence.
            assertTrue(expected.alsoCheck!!.contains("TO HOST"))
        }
    }

    /**
     * **One silent probe is not enough to refuse a session.**
     *
     * The identity reply is cosmetic and allowed to be missing on an instrument that otherwise
     * works, so the refusal above requires the Yamaha-specific mode request to be unanswered too.
     * Gating on the identity inquiry alone would lock out a working instrument.
     */
    @Test
    fun `an unanswered identity inquiry alone still connects`() = runTest {
        val (motif, _) = instrument(
            backgroundScope,
            fake = FakeMotifXs(mode = MotifXsMode.VOICE, answersIdentity = false),
        )
        motif.connect()
        assertEquals(UNKNOWN_FIRMWARE, motif.identity.firmwareVersion)
    }

    // ---- Mode gating ----

    /**
     * A selection outside Voice mode is refused, and the message says what to press.
     *
     * In Performance and Song mode the three parameter sets draw **no echo at all** and change
     * nothing - not the Performance, not any part's voice assignment. Without this check the app
     * would wait out its timeout and report that the instrument had not answered, which is true
     * and tells a player nothing.
     */
    @Test
    fun `selection is refused outside voice mode`() = runTest {
        for (mode in listOf(MotifXsMode.PERFORMANCE, MotifXsMode.SONG, MotifXsMode.MASTER)) {
            val (motif, transport) = instrument(backgroundScope, fake = FakeMotifXs(mode = mode))
            // Called directly rather than through `runBlocking`, which would block the test
            // scheduler and stop the fake's mode reply from ever being delivered - the check
            // would then "pass" by timing out, which is the failure it exists to replace.
            val failure = try {
                motif.selector!!.select(SlotAddress(0, 0))
                null
            } catch (e: InstrumentException.BlockedByDeviceState) {
                e
            }
            assertNotNull("$mode should refuse a selection", failure)
            assertTrue(
                "the message should name the mode, was: ${failure!!.message}",
                failure.message!!.contains(mode.label),
            )
            // A fix is offered rather than only an obstacle reported.
            assertNotNull("a remedy should be offered", failure.remedy)
            assertNotNull(failure.remedyLabel)
            assertNotNull(failure.remedyDetail)
            // Refused before the selection goes out, not after it is ignored - and the mode is
            // *not* changed by merely being asked for one.
            assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_SELECT })
            assertTrue(
                "offering a remedy must not already have applied it",
                transport.sent.none { MotifXsSysEx.modeOf(it) != null },
            )
        }
    }

    /**
     * Taking the remedy sends the documented mode change, and then the selection works.
     *
     * The fake flips to Voice mode when it receives the parameter change, which is what lets the
     * retry be tested rather than only the send.
     */
    @Test
    fun `the offered remedy switches the instrument to voice mode`() = runTest {
        val (motif, transport) = instrument(
            backgroundScope,
            fake = FakeMotifXs(
                mode = MotifXsMode.PERFORMANCE,
                modeIsSettable = true,
            ),
        )
        val blocked = try {
            motif.selector!!.select(SlotAddress(0, 0))
            null
        } catch (e: InstrumentException.BlockedByDeviceState) {
            e
        }
        assertNotNull(blocked)

        blocked!!.remedy!!.invoke()
        val sentModeChange = transport.sent.single {
            MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_PARAM_CHANGE &&
                MotifXsSysEx.addressOf(it) == Triple(0x0A, 0x00, 0x01)
        }
        assertEquals(MotifXsMode.VOICE.value, sentModeChange[8].toInt())

        // And the operation the block interrupted now succeeds.
        motif.selector!!.select(SlotAddress(0, 0))
        assertEquals(3, transport.sent.count { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_SELECT })
    }

    /**
     * A switch that takes a moment is **waited for**, not declared failed.
     *
     * Reading the mode immediately after sending the change would see the *old* value, since
     * changing mode is not instant, and would wrongly report the switch as having failed even
     * though it goes on to succeed.
     */
    @Test
    fun `a mode change that takes time still succeeds`() = runTest {
        val (motif, _) = instrument(
            backgroundScope,
            fake = FakeMotifXs(
                mode = MotifXsMode.PERFORMANCE,
                modeIsSettable = true,
                // Three reads still report Performance before the switch shows up.
                modeSettlesAfterReads = 3,
            ),
        )
        val blocked = try {
            motif.selector!!.select(SlotAddress(0, 0))
            null
        } catch (e: InstrumentException.BlockedByDeviceState) {
            e
        }
        assertNotNull(blocked)

        // Must not throw: the instrument gets there, just not on the first read.
        blocked!!.remedy!!.invoke()
        // And the operation it was blocking now works.
        motif.selector!!.select(SlotAddress(0, 0))
    }

    /** A switch the instrument ignores is reported, not assumed. */
    @Test
    fun `a mode change that does not take is reported`() = runTest {
        val (motif, _) = instrument(
            backgroundScope,
            fake = FakeMotifXs(
                mode = MotifXsMode.PERFORMANCE,
                modeIsSettable = false,
            ),
        )
        val blocked = try {
            motif.selector!!.select(SlotAddress(0, 0))
            null
        } catch (e: InstrumentException.BlockedByDeviceState) {
            e
        }
        val second = try {
            blocked!!.remedy!!.invoke()
            null
        } catch (e: InstrumentException.BlockedByDeviceState) {
            e
        }
        assertNotNull("a stuck mode should be reported, not assumed fixed", second)
        assertTrue(
            "the message should say it was still in the old mode, was: ${second!!.message}",
            second.message!!.contains("still in"),
        )
    }

    @Test
    fun `selection proceeds in voice mode`() = runTest {
        val (motif, transport) = instrument(backgroundScope, fake = FakeMotifXs(mode = MotifXsMode.VOICE))
        motif.selector!!.select(SlotAddress(0, 0))
        assertEquals(3, transport.sent.count { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_SELECT })
    }

    /**
     * An instrument that will not report its mode is not blocked from selecting.
     *
     * The check exists to explain a silent failure, not to invent a new one. An older firmware
     * that ignores the mode request must still be usable, so a missing answer falls through to
     * the selection, which then succeeds or times out on its own.
     */
    @Test
    fun `an unreadable mode does not block a selection`() = runTest {
        val (motif, transport) = instrument(backgroundScope, fake = FakeMotifXs(mode = null))
        motif.selector!!.select(SlotAddress(0, 0))
        assertEquals(3, transport.sent.count { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_SELECT })
    }

    /**
     * Rename goes out as a documented sequence and the new name reads back.
     *
     * The vendor editor's own rename mechanism reaches only the edit buffer, not the stored slot.
     * Yamaha's own documented write path stores the name directly and survives a power cycle,
     * which is the path this method uses instead.
     */
    @Test
    fun `rename writes a documented sequence and the new name reads back`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        motif.editor!!.rename(SlotAddress(0, 0), "Renamed In Place")

        val documented = transport.sent.filter {
            MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP &&
                MotifXsSysEx.addressOf(it)?.first in DOCUMENTED_ADDRESS_HI
        }
        // Header, 24 blocks, footer - and nothing else on this path.
        assertEquals(26, documented.size)
        assertEquals(MotifXsSysEx.BULK_HEADER_HI, MotifXsSysEx.addressOf(documented.first())!!.first)
        assertEquals(MotifXsSysEx.BULK_FOOTER_HI, MotifXsSysEx.addressOf(documented.last())!!.first)

        // The header and footer carry the destination; the blocks do not.
        assertEquals(Triple(MotifXsSysEx.BULK_HEADER_HI, 0x0A, 0), MotifXsSysEx.addressOf(documented.first()))
        assertEquals(Triple(MotifXsSysEx.BULK_FOOTER_HI, 0x0A, 0), MotifXsSysEx.addressOf(documented.last()))

        // Every block goes out in *host* form. Echoing the instrument's own bytes back would be
        // wrong in the model byte and in the checksum that covers it.
        assertTrue(documented.all { it[4].toInt() == MotifXsSysEx.MODEL_HOST })
        assertTrue(documented.all { MotifXsSysEx.isWellFormedBulkDump(it) })

        // No store marker: the footer is this path's commit.
        assertTrue(
            "the documented path needs no 11 00 00",
            transport.sent.none {
                MotifXsSysEx.addressOf(it)?.first == MotifXsSysEx.STORE_HI
            },
        )
        assertEquals("Renamed In Place", motif.browser.refresh(SlotAddress(0, 0)).name)
    }

    /**
     * Renaming the voice the panel is showing reloads it, so the panel stops showing the old name.
     *
     * The documented write stores to Flash and does **not** touch the edit buffer, so without
     * this the instrument goes on displaying the previous name until the player reselects.
     */
    @Test
    fun `renaming the loaded voice reloads it so the panel updates`() = runTest {
        val (motif, transport) = instrument(
            backgroundScope,
            fake = FakeMotifXs(
                loadedVoiceName = "TWE2 Wolf Walk",
            ),
        )
        motif.editor!!.rename(SlotAddress(0, 0), "Renamed In Place")

        // A selection went out *after* the write, to reload the slot that was just renamed.
        val selects = transport.sent.filter { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_SELECT }
        assertEquals(3, selects.size)
        val lastWrite = transport.sent.indexOfLast { MotifXsSysEx.isBulkFooter(it) }
        val firstSelect = transport.sent.indexOfFirst { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_SELECT }
        assertTrue("the reload must follow the write", firstSelect > lastWrite)
    }

    /**
     * Renaming a voice that is *not* loaded leaves the player where they are.
     *
     * The reload is a fix for a stale display, not a licence to change what the instrument is
     * playing. Getting this wrong would yank a player onto a voice they never chose.
     */
    @Test
    fun `renaming an unloaded voice does not reload anything`() = runTest {
        val (motif, transport) = instrument(
            backgroundScope,
            fake = FakeMotifXs(
                loadedVoiceName = "Something Else",
            ),
        )
        motif.editor!!.rename(SlotAddress(0, 0), "Renamed In Place")
        assertTrue(
            "no selection should be sent for a voice the panel is not showing",
            transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_SELECT },
        )
    }

    /** Only the Common block changes; the other 23 are returned exactly as they arrived. */
    @Test
    fun `rename alters the name bytes and nothing else`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        motif.editor!!.rename(SlotAddress(0, 0), "Short")

        val common = transport.sent.single {
            MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP && MotifXsSysEx.isCommonBlock(it)
        }
        val payload = MotifXsSysEx.dumpPayload(common)
        assertEquals("Short", String(payload, 0, 5, Charsets.ISO_8859_1))
        // NUL padded, not space padded - what the instrument does, and what revision C0 of the
        // Data List permits where B0 does not.
        assertTrue((5 until 20).all { payload[it].toInt() == 0 })
        // Everything past the name is the filler the fake sent, untouched.
        assertTrue((20 until payload.size).all { payload[it].toInt() == (it and 0x3F) })
    }

    /** A name the field cannot hold is refused before anything is sent. */
    @Test
    fun `rename refuses a name the instrument cannot store`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        val editor = motif.editor!!
        thrownBy<IllegalArgumentException> { editor.rename(SlotAddress(0, 0), "x".repeat(21)) }
        thrownBy<IllegalArgumentException> { editor.rename(SlotAddress(0, 0), "   ") }
        thrownBy<IllegalArgumentException> { editor.rename(SlotAddress(0, 0), "Grüße") }
        // Nothing left the building: there is no safe place to abort once a header has gone out,
        // so every one of these has to fail before the first byte.
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    /**
     * An empty slot is refused - it would otherwise succeed and leave a named, empty voice.
     *
     * The fixture's slot 1 reads empty in both banks. This is the same care `copyProgram` takes
     * over an empty *source*, and it matters more here because the documented read answers for an
     * empty slot exactly as it does for a full one: there is no natural failure to rely on.
     */
    @Test
    fun `rename refuses an empty slot`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        val failure = try {
            motif.editor!!.rename(SlotAddress(0, 1), "Not A Voice")
            null
        } catch (e: InstrumentException.SlotStateChanged) {
            e
        }
        assertNotNull("an empty slot should be refused", failure)
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    /**
     * A **drum** voice renames too, and its sequence is a different shape entirely.
     *
     * Eight `46 xx` Common blocks and 73 `47 ee` elements, against a Normal Voice's eight
     * `40 xx` plus eight `41`/`42` pairs - 81 blocks against 24. Requiring the Normal Voice shape
     * would refuse every drum row after reading it, which is safe and useless. The name is the
     * first 20 bytes of `46 00 00` exactly as it is of `40 00 00`.
     */
    @Test
    fun `a drum voice renames, despite a completely different block sequence`() = runTest {
        val drumConfig = MotifXsConfig(
            banks = listOf(
                MotifXsBank(
                    label = "DRUM",
                    slotCount = 1,
                    addressHi = 0x0C,
                    addressMid = 0x28,
                    displayLabel = "USER DR",
                    selectLsb = 0x28,
                ),
            ),
        )
        val (motif, transport) = instrument(
            backgroundScope,
            withConfig = drumConfig,
            fake = FakeMotifXs(drumShape = true),
        )
        motif.editor!!.rename(SlotAddress(0, 0), "Renamed Kit")

        val sent = transport.sent.filter {
            MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP &&
                MotifXsSysEx.addressOf(it)?.first in DOCUMENTED_ADDRESS_HI
        }
        // Header, 81 blocks, footer - the instrument's shape, not a number this app knows.
        assertEquals(83, sent.size)
        assertEquals(MotifXsSysEx.BULK_HEADER_HI, MotifXsSysEx.addressOf(sent.first())!!.first)
        assertEquals(MotifXsSysEx.BULK_FOOTER_HI, MotifXsSysEx.addressOf(sent.last())!!.first)

        // The drum Common went back to 46 00 00, not to a Normal Voice's 40 00 00.
        val common = sent.single { MotifXsSysEx.isCommonBlock(it) }
        assertEquals(
            Triple(MotifXsSysEx.DRUM_COMMON_HI, 0x00, 0x00),
            MotifXsSysEx.addressOf(common),
        )
        assertEquals("Renamed Kit", String(MotifXsSysEx.dumpPayload(common), 0, 11, Charsets.ISO_8859_1))
    }

    /**
     * Copy, move and swap refuse to cross the drum boundary - because nothing else will.
     *
     * A drum kit and a normal voice are different objects, ~12.6 kB against ~1.9 kB with entirely
     * different block sequences, and the instrument **acknowledges a payload of the wrong kind
     * without complaint**: a normal voice offered to `0C 28 00` is acknowledged, and a drum kit
     * to `0C 0A 7F` likewise, without either being committed. Since the acknowledgement carries no
     * warning, the app cannot discover this by trying, and the rule has to live in the app.
     */
    @Test
    fun `copy, move and swap refuse to cross the drum boundary`() = runTest {
        val mixed = MotifXsConfig(
            banks = listOf(
                MotifXsBank(
                    label = "USR1",
                    slotCount = 3,
                    addressHi = 0x0C,
                    addressMid = 0x0A,
                    displayLabel = "USER 1",
                    selectLsb = 0x08,
                ),
                MotifXsBank(
                    label = "DRUM",
                    slotCount = 3,
                    addressHi = 0x0C,
                    addressMid = 0x28,
                    displayLabel = "USER DR",
                    selectLsb = 0x28,
                ),
            ),
        )
        assertFalse("USR1 is not a drum bank", mixed.banks[0].isDrum)
        assertTrue("USER DR is", mixed.banks[1].isDrum)

        val normal = SlotAddress(0, 0)
        val drum = SlotAddress(1, 1) // slot 1 reads empty in the fixture, so copy has a target
        for (op in listOf<Pair<String, suspend (PresetEditor) -> Unit>>(
            "copy" to { e -> e.copyProgram(normal, drum) },
            "move" to { e -> e.move(normal, drum) },
            "swap" to { e -> e.swap(normal, drum) },
        )) {
            val (name, run) = op
            val (motif, transport) = instrument(backgroundScope, withConfig = mixed)
            val failure = try {
                run(motif.editor!!)
                null
            } catch (e: InstrumentException.NotSupported) {
                e
            }
            assertNotNull("$name across the drum boundary should be refused", failure)
            // Worded by bank: the slot may be empty, so "a slot that holds a drum kit" would be wrong.
            assertTrue(failure!!.message!!, failure.message!!.contains("USER DR, a bank of drum kits"))
            // Refused before anything is offered to the instrument, which matters here more than
            // usual: an accepted-but-uncommitted write stays armed until something commits it.
            assertTrue(
                "$name must send nothing",
                transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP },
            )
        }
    }

    /** What a destination pick and a drag offer: the same rule the edits enforce, asked up front. */
    @Test
    fun `fits tells drum banks and normal ones apart`() = runTest {
        val mixed = MotifXsConfig(
            banks = listOf(
                MotifXsBank(
                    label = "USR1",
                    slotCount = 3,
                    addressHi = 0x0C,
                    addressMid = 0x0A,
                    displayLabel = "USER 1",
                ),
                MotifXsBank(
                    label = "DRUM",
                    slotCount = 3,
                    addressHi = 0x0C,
                    addressMid = 0x28,
                    displayLabel = "USER DR",
                ),
            ),
        )
        val editor = instrument(backgroundScope, withConfig = mixed).first.editor!!
        assertTrue(editor.fits(SlotAddress(0, 0), SlotAddress(0, 2)))
        assertTrue(editor.fits(SlotAddress(1, 0), SlotAddress(1, 2)))
        assertFalse(editor.fits(SlotAddress(0, 0), SlotAddress(1, 1)))
        assertFalse(editor.fits(SlotAddress(1, 0), SlotAddress(0, 1)))
    }

    /** Same-kind edits are unaffected. */
    @Test
    fun `copy within a bank still works`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        val name = motif.editor!!.copyProgram(SlotAddress(0, 0), SlotAddress(0, 1))
        assertEquals("TWE2 Wolf Walk", name)
    }

    /**
     * Delete picks the blank matching the bank's kind - the drum blank for a drum bank, not the
     * normal-voice one - and works even in a fully populated bank, such as a USER DR with all 32
     * kits occupied and nothing empty to fall back on.
     */
    @Test
    fun `delete writes the drum blank in a drum bank`() = runTest {
        val fullBank = MotifXsConfig(
            banks = listOf(
                // Every slot named, and none of it matters: there is no fallback to avoid.
                MotifXsBank(
                    label = "DRUM",
                    slotCount = 2,
                    addressHi = 0x0C,
                    addressMid = 0x28,
                    displayLabel = "USER DR",
                    selectLsb = 0x28,
                ),
            ),
        )
        val shipped = MotifXsBlanks(normal = ByteArray(64) { 1 }, drum = ByteArray(64) { 2 })
        val slots = FakeMotifXsSlots()
        val transport = FakeMidiTransport { request ->
            val address = MotifXsSysEx.addressOf(request)
            when {
                MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_BULK_DUMP -> slots.write(request)

                // Both slots always read as a named voice - no empty one anywhere.
                else -> listOfNotNull(
                    address?.let {
                        slots.read(it, dumpAt(MotifXsFixtures.namedVoice, it.first, it.second, it.third))
                    },
                )
            }
        }
        val motif = MotifXsInstrument(
            SysExExchange(transport, backgroundScope),
            fullBank,
            shipped,
        )
        motif.editor!!.delete(SlotAddress(0, 0))

        // The drum blank went out, not the normal one, and not a payload scavenged from a slot.
        val written = transport.sent.single {
            MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP &&
                MotifXsSysEx.addressOf(it)?.first == 0x0C
        }
        assertTrue(
            "the shipped drum blank should have been written",
            MotifXsSysEx.dumpPayload(written).contentEquals(shipped.drum),
        )
    }

    /** A factory bank is refused, as it is for every other edit. */
    @Test
    fun `rename refuses a read-only bank`() = runTest {
        val readOnly = MotifXsConfig(
            banks = listOf(
                MotifXsBank(
                    label = "PRE1",
                    slotCount = 3,
                    addressHi = 0x0C,
                    addressMid = 0x00,
                    displayLabel = "PRE 1",
                    readOnly = true,
                ),
            ),
        )
        val (motif, transport) = instrument(backgroundScope, withConfig = readOnly)
        thrownBy<IllegalArgumentException> { motif.editor!!.rename(SlotAddress(0, 0), "Nope") }
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    /** Delete erases the stored slot, and does it the way move clears its source. */
    @Test
    fun `delete writes an initialised voice over the slot`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        motif.editor!!.delete(SlotAddress(0, 0))

        val dumps = transport.sent.filter {
            MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP
        }
        val marker = Triple(MotifXsSysEx.STORE_HI, MotifXsSysEx.STORE_MID, MotifXsSysEx.STORE_LO)
        val writes = dumps.filter { MotifXsSysEx.addressOf(it) != marker }

        assertEquals(1, writes.size)
        // Written to the slot asked for...
        assertEquals(Triple(0x0C, 0x0A, 0), MotifXsSysEx.addressOf(writes.single()))
        // ...carrying the shipped blank for a normal voice, not a payload scavenged from a slot.
        assertTrue(
            MotifXsSysEx.dumpPayload(writes.single()).contentEquals(testBlanks.normal),
        )
        // ...and committed - without this the instrument acknowledges the write and keeps
        // holding the old voice.
        assertEquals(marker, MotifXsSysEx.addressOf(dumps.last()))
    }

    /**
     * An uncommitted write is not visible, and that is the instrument's behaviour rather than the
     * fake's convenience.
     *
     * Worth its own test because the failure it guards is silent: an edit could acknowledge every
     * write and report success while changing nothing on the instrument if the commit were
     * missing. This is the test that would catch that.
     */
    @Test
    fun `a write is not stored until the marker commits it`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        val before = motif.browser.refresh(SlotAddress(0, 0)).name
        assertNotNull(before)

        // The write on its own changes nothing.
        transport.send(
            MotifXsSysEx.bulkDump(
                0,
                0x0C,
                0x0A,
                0,
                MotifXsSysEx.dumpPayload(MotifXsFixtures.emptyVoice),
            ),
        )
        assertEquals(before, motif.browser.refresh(SlotAddress(0, 0)).name)

        // The marker is what applies it.
        transport.send(MotifXsSysEx.storeMarker(0))
        assertNull(motif.browser.refresh(SlotAddress(0, 0)).name)
    }

    /**
     * Swap really exchanges the two slots' contents.
     *
     * Asserted by reading both back afterwards rather than by counting messages: the point of a
     * swap is the end state, and a test that checked only "four writes happened" would pass on an
     * implementation that wrote each voice back over itself.
     */
    @Test
    fun `swap exchanges the two slots`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        val a = SlotAddress(0, 0) // named
        val b = SlotAddress(0, 2) // long-named
        val before = motif.browser.refresh(a).name to motif.browser.refresh(b).name
        assertNotEquals(before.first, before.second)

        motif.editor!!.swap(a, b)

        assertEquals(before.second, motif.browser.refresh(a).name)
        assertEquals(before.first, motif.browser.refresh(b).name)
    }

    /**
     * Move puts the voice at the destination **and clears the source**.
     *
     * The second half is the destructive one, and it is what makes move and delete the same
     * operation underneath - see the editor's own note on why delete is offered.
     */
    @Test
    fun `move carries the voice across and empties the slot it came from`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        val from = SlotAddress(0, 0) // named
        val to = SlotAddress(1, 1) // empty, in the other bank
        val moving = motif.browser.refresh(from).name
        assertNotNull(moving)

        motif.editor!!.move(from, to)

        assertEquals(moving, motif.browser.refresh(to).name)
        assertNull("the source slot must read empty after a move", motif.browser.refresh(from).name)
    }

    /**
     * Copy duplicates the voice and **leaves the source alone** - the one thing that separates it
     * from move, which is otherwise the same write and the same commit.
     *
     * This works in both directions, within a bank and across two, and the destination matches
     * the source byte for byte each time.
     */
    @Test
    fun `copy duplicates the voice and leaves the source where it was`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        val from = SlotAddress(0, 0) // named
        val to = SlotAddress(1, 1) // empty, in the other bank
        val source = motif.browser.refresh(from).name
        assertNotNull(source)

        val stored = motif.editor!!.copyProgram(from, to)

        assertEquals(source, stored)
        assertEquals(source, motif.browser.refresh(to).name)
        assertEquals("the source must survive a copy", source, motif.browser.refresh(from).name)
    }

    /**
     * An occupied destination is refused **before anything is written**.
     *
     * Nothing at the other end enforces this: a well-formed dump aimed at an occupied writable
     * slot is simply accepted, so the instrument would overwrite it without complaint. The
     * contract says "into the empty slot dst", and this is the only thing keeping it.
     */
    @Test
    fun `copy onto an occupied slot is refused without writing`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        // Not `runBlocking` inside `assertThrows`: this refusal has to read both slots first, and
        // blocking the test thread stops the fake device's replies from ever being delivered - the
        // call then fails on a timeout, which would pass an `assertThrows` for the wrong reason.
        val refusal = runCatching {
            motif.editor!!.copyProgram(SlotAddress(0, 0), SlotAddress(1, 0))
        }.exceptionOrNull()

        assertTrue("expected a refusal, got $refusal", refusal is InstrumentException.SlotStateChanged)
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    /** Copying an empty slot is refused too - there is nothing to duplicate. */
    @Test
    fun `copy of an empty slot is refused without writing`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        val refusal = runCatching {
            motif.editor!!.copyProgram(SlotAddress(0, 1), SlotAddress(1, 1))
        }.exceptionOrNull()

        assertTrue("expected a refusal, got $refusal", refusal is InstrumentException.SlotStateChanged)
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    /**
     * A move onto an occupied slot is refused before anything is written, as a copy is: the
     * instrument would overwrite the voice there, and a listing out of date offers it as empty.
     */
    @Test
    fun `move onto an occupied slot is refused and leaves it untouched`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        val occupant = motif.browser.refresh(SlotAddress(1, 0)).name
        assertNotNull(occupant)
        val refusal = runCatching {
            motif.editor!!.move(SlotAddress(0, 0), SlotAddress(1, 0))
        }.exceptionOrNull()

        assertTrue("expected a refusal, got $refusal", refusal is InstrumentException.SlotStateChanged)
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
        assertEquals(occupant, motif.browser.refresh(SlotAddress(1, 0)).name)
    }

    @Test
    fun `move of an empty slot is refused without writing`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        val refusal = runCatching {
            motif.editor!!.move(SlotAddress(0, 1), SlotAddress(1, 1))
        }.exceptionOrNull()

        assertTrue("expected a refusal, got $refusal", refusal is InstrumentException.SlotStateChanged)
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    /** Moving or swapping a slot onto itself is a no-op, not two writes. */
    @Test
    fun `an operation onto the same slot writes nothing`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        motif.editor!!.move(SlotAddress(0, 0), SlotAddress(0, 0))
        motif.editor!!.swap(SlotAddress(0, 1), SlotAddress(0, 1))
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    /** A factory bank is refused before anything is sent, not after a failed write. */
    @Test
    fun `writing a read-only bank is refused before it is attempted`() = runTest {
        val withFactory = MotifXsConfig(
            banks = listOf(
                MotifXsBank(
                    label = "USR1",
                    slotCount = 3,
                    addressHi = 0x0C,
                    addressMid = 0x0A,
                    displayLabel = "USER 1",
                    selectLsb = 0x08,
                ),
                MotifXsBank(
                    label = "PRE1",
                    slotCount = 3,
                    addressHi = 0x0C,
                    addressMid = 0x00,
                    displayLabel = "PRE1",
                    readOnly = true,
                    indexByDefault = false,
                ),
            ),
        )
        val (motif, transport) = instrument(backgroundScope, withFactory)
        thrownBy<IllegalArgumentException> { motif.editor!!.swap(SlotAddress(0, 0), SlotAddress(1, 0)) }
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    /**
     * The two sources of browser rows must agree on a bank's label.
     *
     * `index()` builds rows from dumps; the view model builds placeholder rows for unread slots
     * from `SlotLayout`'s [BankSpec]s. The browser groups by `bankLabel`, so if those two ever
     * disagree - the panel label on one side and the catalog shorthand on the other, say - every
     * bank grows a second header and its rows split across both.
     */
    @Test
    fun `indexed rows and layout banks label a bank the same way`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        val slots = motif.browser.index().toList()
            .filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }

        assertTrue(slots.isNotEmpty())
        for (slot in slots) {
            assertEquals(motif.layout.banks[slot.address.bank].label, slot.bankLabel)
        }
    }

    /**
     * A full index skips the factory banks.
     *
     * Walking all 15 banks would take several minutes against 93 s for the user banks, and the
     * factory ones are read-only and never change. The saving is the point of the flag, so the
     * test asserts the *requests*, not just the rows: a bank that is skipped must cost nothing.
     */
    @Test
    fun `a full index walks only the banks marked indexByDefault`() = runTest {
        val scoped = MotifXsConfig(
            banks = listOf(
                MotifXsBank(
                    label = "USR1",
                    slotCount = 3,
                    addressHi = 0x0C,
                    addressMid = 0x0A,
                    displayLabel = "USER 1",
                ),
                MotifXsBank(
                    label = "PRE1",
                    slotCount = 3,
                    addressHi = 0x0C,
                    addressMid = 0x00,
                    displayLabel = "PRE1",
                    readOnly = true,
                    indexByDefault = false,
                ),
            ),
        )
        val (motif, transport) = instrument(backgroundScope, scoped)
        val updates = motif.browser.index().toList()
        val slots = updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }

        assertEquals(3, slots.size)
        assertTrue(slots.all { it.bankLabel == "USER 1" })
        assertEquals(3, transport.sent.count { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_DUMP_REQUEST })
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    /** ...and the skipped bank is still reachable, which is what makes skipping it acceptable. */
    @Test
    fun `a factory bank can be indexed on demand`() = runTest {
        val scoped = MotifXsConfig(
            banks = listOf(
                MotifXsBank(
                    label = "USR1",
                    slotCount = 3,
                    addressHi = 0x0C,
                    addressMid = 0x0A,
                    displayLabel = "USER 1",
                ),
                MotifXsBank(
                    label = "PRE1",
                    slotCount = 3,
                    addressHi = 0x0C,
                    addressMid = 0x00,
                    displayLabel = "PRE1",
                    readOnly = true,
                    indexByDefault = false,
                ),
            ),
        )
        val (motif, transport) = instrument(backgroundScope, scoped)
        val updates = (motif.browser as MotifXsInstrument).indexBank(1).toList()
        val slots = updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }

        assertEquals(3, slots.size)
        assertTrue(slots.all { it.bankLabel == "PRE1" })
        assertEquals(3, transport.sent.count { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_DUMP_REQUEST })
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    /**
     * The two worked examples are how the instrument's own display renders these addresses:
     * `DRUM:031` shows as `USER DR - B:15`, and `USR3:053` as `USER 3 - D:05`.
     */
    @Test
    fun `addresses render as the instrument displays them, in lettered groups of sixteen`() {
        val format = MotifXsAddressFormat(
            listOf("USR1", "USR2", "USR3", "DRUM"),
            listOf("USER 1", "USER 2", "USER 3", "USER DR"),
        )
        assertEquals("USER DR - B:15", format.format(SlotAddress(3, 30)))
        assertEquals("USER 3 - D:05", format.format(SlotAddress(2, 52)))
        assertEquals("USER 1 - A:01", format.format(SlotAddress(0, 0)))
        assertEquals("USER 3 - H:16", format.format(SlotAddress(2, 127)))
    }

    @Test
    fun `every voice address has its own displayed id`() {
        val format = MotifXsAddressFormat(
            listOf("USR1", "USR2", "USR3", "DRUM"),
            listOf("USER 1", "USER 2", "USER 3", "USER DR"),
        )
        val ids = (0 until 4).flatMap { bank -> (0 until 128).map { format.format(SlotAddress(bank, it)) } }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("USER DR - B:15", format.format(SlotAddress(3, 30)))
    }

    /**
     * **A write and its commit are one unit, and cancelling must not split them.**
     *
     * `commit()` applies everything offered since the last commit, so an acknowledged-but-
     * uncommitted write is not discarded when its coroutine dies - it is armed, and some later,
     * unrelated edit's commit applies it. `NonCancellable` on the send loop inside
     * `exchangeAfterAll` does not cover this: delete, copy, move and swap do not use that path,
     * they issue plain exchanges and then commit separately.
     *
     * Cancellation is delivered from inside the transport, the moment the write goes out, which
     * puts it exactly in the window this is about rather than relying on timing.
     */
    @Test
    fun `a cancelled delete still commits the write it already sent`() = runTest {
        val sent = mutableListOf<ByteArray>()
        var job: Job? = null
        val transport = FakeMidiTransport { request ->
            sent += request
            val address = MotifXsSysEx.addressOf(request)
            // The write has just been accepted; the commit has not gone out yet.
            if (address != null && address.first == 0x0C) job?.cancel()
            listOf(FakeMotifXsSlots.ACK)
        }
        val motif = MotifXsInstrument(
            SysExExchange(transport, backgroundScope, defaultTimeout = 200.milliseconds),
            MotifXsConfig(
                banks = listOf(
                    MotifXsBank(
                        label = "USR1",
                        slotCount = 1,
                        addressHi = 0x0C,
                        addressMid = 0x0A,
                        displayLabel = "USER 1",
                        selectLsb = 0x08,
                    ),
                ),
            ),
            testBlanks,
        )

        job = launch(start = CoroutineStart.LAZY) {
            runCatching { motif.editor.delete(SlotAddress(0, 0)) }
        }
        job!!.start()
        job!!.join()

        assertTrue("cancelled as its write went out", job!!.isCancelled)

        val committed = sent.any { MotifXsSysEx.addressOf(it)?.first == MotifXsSysEx.STORE_HI }
        assertTrue(
            "an accepted write left uncommitted is armed, not discarded - a later unrelated " +
                "commit would apply it",
            committed,
        )
    }

    /**
     * A move cancelled as its first write goes out still sends the second write and the commit:
     * the source is cleared and the destination holds the voice, never one without the other.
     */
    @Test
    fun `a cancelled move still completes both writes and the commit`() = runTest {
        var job: Job? = null
        val (motif, _) = instrument(
            backgroundScope,
            fake = FakeMotifXs(
                onRequest = { request ->
                    if (MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_BULK_DUMP &&
                        MotifXsSysEx.addressOf(request)?.first == 0x0C
                    ) {
                        job?.cancel()
                    }
                },
            ),
        )
        val from = SlotAddress(0, 0)
        val to = SlotAddress(1, 1)
        val moving = motif.browser.refresh(from).name
        assertNotNull(moving)

        job = launch(start = CoroutineStart.LAZY) { motif.editor!!.move(from, to) }
        job!!.start()
        job!!.join()

        assertTrue(job!!.isCancelled)
        assertEquals(moving, motif.browser.refresh(to).name)
        assertNull("the source must be cleared in the same commit", motif.browser.refresh(from).name)
    }

    /** As the move: a copy cancelled as its write goes out still commits it. */
    @Test
    fun `a cancelled copy still commits`() = runTest {
        var job: Job? = null
        val (motif, _) = instrument(backgroundScope, fake = FakeMotifXs(onRequest = cancelOnFirstWrite { job }))
        val from = SlotAddress(0, 0)
        val to = SlotAddress(1, 1)
        val copying = motif.browser.refresh(from).name

        job = launch(start = CoroutineStart.LAZY) { motif.editor!!.copyProgram(from, to) }
        job!!.start()
        job!!.join()

        assertTrue(job!!.isCancelled)
        assertEquals(copying, motif.browser.refresh(to).name)
    }

    /** A swap cancelled mid-way still writes both slots in one commit: never one voice in both. */
    @Test
    fun `a cancelled swap still completes both writes and the commit`() = runTest {
        var job: Job? = null
        val (motif, _) = instrument(backgroundScope, fake = FakeMotifXs(onRequest = cancelOnFirstWrite { job }))
        val a = SlotAddress(0, 0)
        val b = SlotAddress(1, 2)
        val nameA = motif.browser.refresh(a).name
        val nameB = motif.browser.refresh(b).name

        job = launch(start = CoroutineStart.LAZY) { motif.editor!!.swap(a, b) }
        job!!.start()
        job!!.join()

        assertTrue(job!!.isCancelled)
        assertEquals(nameB, motif.browser.refresh(a).name)
        assertEquals(nameA, motif.browser.refresh(b).name)
    }

    /** Cancels [job] the moment the first write to a voice address goes out. */
    private fun cancelOnFirstWrite(job: () -> Job?): (ByteArray) -> Unit = { request ->
        if (MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_BULK_DUMP &&
            MotifXsSysEx.addressOf(request)?.first == 0x0C
        ) {
            job()?.cancel()
        }
    }

    // ---- Writes with nothing to change ----

    @Test
    fun `a rename to the name already stored writes nothing`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        val name = motif.browser.refresh(SlotAddress(0, 0)).name!!
        motif.editor!!.rename(SlotAddress(0, 0), name)
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    @Test
    fun `swapping two empty slots writes nothing`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        motif.editor!!.swap(SlotAddress(0, 1), SlotAddress(1, 1))
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    // ---- When a write or its commit fails ----

    /**
     * A refused second write leaves the first armed, not stored. The move says so, with the
     * warning about the armed write, and no commit follows that would apply half of it.
     */
    @Test
    fun `a move whose second write is refused commits nothing and says why`() = runTest {
        // Bank 0 (USR1, address-mid 0x0A) refuses writes: the move's second write clears its source there.
        val (motif, transport) = instrument(backgroundScope, fake = FakeMotifXs(readOnlyBanks = setOf(0x0A)))
        val to = SlotAddress(1, 1)

        val failure = runCatching { motif.editor!!.move(SlotAddress(0, 0), to) }.exceptionOrNull()

        assertTrue("expected a timeout, got $failure", failure is InstrumentException.Timeout)
        assertTrue(failure!!.message!!, failure.message!!.contains("accepted-but-uncommitted"))
        assertTrue(transport.sent.none { MotifXsSysEx.addressOf(it)?.first == MotifXsSysEx.STORE_HI })
        assertNull("nothing was committed, so the destination is still empty", motif.browser.refresh(to).name)
    }

    /** As the move: the first write stays armed, so the swap says so and commits nothing. */
    @Test
    fun `a swap whose second write is refused commits nothing and says why`() = runTest {
        // USER 1 (address-mid 0x0A) refuses writes; the swap writes USER 2's slot first.
        val (motif, transport) = instrument(backgroundScope, fake = FakeMotifXs(readOnlyBanks = setOf(0x0A)))
        val a = SlotAddress(1, 0)
        val b = SlotAddress(0, 2)
        val before = motif.browser.refresh(a).name

        val failure = runCatching { motif.editor!!.swap(a, b) }.exceptionOrNull()

        assertTrue("expected a timeout, got $failure", failure is InstrumentException.Timeout)
        assertTrue(failure!!.message!!, failure.message!!.contains("accepted-but-uncommitted"))
        assertTrue(transport.sent.none { MotifXsSysEx.addressOf(it)?.first == MotifXsSysEx.STORE_HI })
        assertEquals("nothing was committed", before, motif.browser.refresh(a).name)
    }

    @Test
    fun `a commit the instrument does not confirm says to re-read the bank`() = runTest {
        val (motif, _) = instrument(backgroundScope, fake = FakeMotifXs(silentCommit = true))

        val failure = runCatching { motif.editor!!.delete(SlotAddress(0, 0)) }.exceptionOrNull()

        assertTrue("expected a timeout, got $failure", failure is InstrumentException.Timeout)
        assertTrue(failure!!.message!!, failure.message!!.contains("Re-read the bank"))
    }

    @Test
    fun `a read-back that differs from the write is reported`() = runTest {
        val (motif, _) = instrument(backgroundScope, fake = FakeMotifXs(misstore = true))

        val failure = runCatching { motif.editor!!.delete(SlotAddress(0, 0)) }.exceptionOrNull()

        assertTrue("expected a desync, got $failure", failure is InstrumentException.ProtocolDesync)
        assertTrue(failure!!.message!!, failure.message!!.contains("read back something different"))
    }

    /**
     * Only the echo of the program sent confirms a selection: one naming another program is some
     * other selection, so all three sets go out again, as often as the exchange retries anything.
     */
    @Test
    fun `an echo naming another program does not confirm a selection`() = runTest {
        val (motif, transport) = instrument(backgroundScope, fake = FakeMotifXs(echoesOtherProgram = true))

        val failure = runCatching { motif.selector.select(SlotAddress(0, 1)) }.exceptionOrNull()

        assertTrue("expected a timeout, got $failure", failure is InstrumentException.Timeout)
        assertEquals(9, transport.sent.count { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_SELECT })
    }
}
