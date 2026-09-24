// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.pro800

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.midi.FakeMidiTransport
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import de.thewolfwalkexperience.software.patchpilot.core.Bus

/**
 * The Pro-800 driven end to end against a scripted MIDI device - the SysEx sibling of what
 * `DemoUsbTransport` does for the Nord: real message construction, real framing, real decoding,
 * with only the wire replaced.
 */
class Pro800InstrumentTest {

    /** A small instrument so a full index walk is quick; the shape is what matters, not the size. */
    private val config = Pro800Config(bankCount = 2, slotsPerBank = 5, slotDigits = 2)

    /** Builds a dump reply carrying [name], encoded exactly as the instrument would. */
    private fun dumpFor(programNumber: Int, name: String?): ByteArray {
        if (name == null) return byteArrayOf(0xF0.toByte(), 0xF7.toByte()) // empty slot
        val payload = run {
            val dense = ByteArray(Pro800ProgramFields.NAME_DENSE_END)
            Pro800ProgramCodec.writeValue(
                dense, Pro800ProgramFields.VERSION.denseOffset, Pro800ProgramFields.VERSION.byteCount, 111,
            )
            Pro800ProgramCodec.writeString(
                dense, Pro800ProgramFields.NAME_DENSE_OFFSET, Pro800ProgramFields.NAME_LENGTH, name,
            )
            Pro800ProgramCodec.encode(dense)
        }
        return Pro800SysEx.HEADER +
            byteArrayOf(Pro800SysEx.TYPE_DUMP.toByte()) +
            byteArrayOf((programNumber and 0x7F).toByte(), ((programNumber shr 7) and 0x7F).toByte()) +
            payload +
            Pro800SysEx.SYSEX_END
    }

    /** Slots 0, 2 and 7 hold presets; everything else is empty - real instruments are sparse. */
    private val names = mapOf(0 to "Fat Saw Bass", 2 to "Glass Pad", 7 to "Sync Lead")

    private fun instrument(scope: CoroutineScope): Pair<Pro800Instrument, FakeMidiTransport> {
        val transport = FakeMidiTransport { request ->
            when (Pro800SysEx.typeOf(request)) {
                Pro800SysEx.TYPE_DEVICE_NAME -> listOf(
                    Pro800SysEx.HEADER + byteArrayOf(Pro800SysEx.TYPE_DEVICE_NAME_REPLY.toByte()) +
                        "PRO-800".toByteArray(Charsets.US_ASCII) + Pro800SysEx.SYSEX_END,
                )
                Pro800SysEx.TYPE_FIRMWARE -> listOf(
                    // 0x00 at index 9 echoes the request's parameter; the version starts at 10.
                    Pro800SysEx.HEADER + byteArrayOf(Pro800SysEx.TYPE_FIRMWARE_REPLY.toByte(), 0x00, 1, 4, 6) +
                        Pro800SysEx.SYSEX_END,
                )
                Pro800SysEx.TYPE_REQUEST_DUMP -> {
                    val number = Pro800SysEx.addressOf(request)!!
                    listOf(dumpFor(number, names[number]))
                }
                else -> emptyList()
            }
        }
        return Pro800Instrument(SysExExchange(transport, scope), config) to transport
    }

    @Test
    fun `connect reads the instrument's own name and firmware version`() = runTest {
        val (pro800, _) = instrument(backgroundScope)
        pro800.connect()
        assertEquals("PRO-800", pro800.identity.name)
        assertEquals("1.4.6", pro800.identity.firmwareVersion)
        assertEquals(Bus.MIDI, pro800.identity.bus)
    }

    /**
     * A silent instrument must still yield a usable session: the presets are what the user came
     * for, and an unanswered identity probe is not a reason to refuse - including the firmware
     * check, since a version we could not read is not evidence of one we do not support.
     */
    @Test
    fun `connect survives an instrument that answers no identity probe`() = runTest {
        val transport = FakeMidiTransport { emptyList() }
        val pro800 = Pro800Instrument(
            SysExExchange(transport, backgroundScope, defaultTimeout = kotlin.time.Duration.parse("20ms"), retries = 0),
            config,
        )
        pro800.connect()
        assertEquals("Behringer Pro-800", pro800.identity.name) // the catalog's name
        assertEquals("unknown", pro800.identity.firmwareVersion)
    }

    @Test
    fun `the index dumps every address and reports names for the occupied ones`() = runTest {
        val (pro800, transport) = instrument(backgroundScope)
        val updates = pro800.browser.index().toList()

        val slots = updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }
        assertEquals(10, slots.size) // 2 banks x 5
        assertEquals("Fat Saw Bass", slots.first { it.displayId == "A00" }.name)
        assertEquals("Glass Pad", slots.first { it.displayId == "A02" }.name)
        assertEquals("Sync Lead", slots.first { it.displayId == "B02" }.name)
        assertNull(slots.first { it.displayId == "A01" }.name)

        // Every address answered, so every address was asked - this is the 400-round-trip shape.
        assertEquals(10, transport.sent.count { Pro800SysEx.typeOf(it) == Pro800SysEx.TYPE_REQUEST_DUMP })
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    @Test
    fun `every row carries its own bank label and a version badge`() = runTest {
        val (pro800, _) = instrument(backgroundScope)
        val slots = pro800.browser.index().toList()
            .filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }

        assertEquals("A", slots.first { it.displayId == "A00" }.bankLabel)
        assertEquals("B", slots.first { it.displayId == "B02" }.bankLabel)
        // An empty slot has no version to show.
        assertTrue(slots.first { it.displayId == "A01" }.badges.isEmpty())
        // The preset format version is not shown at all - it describes the record layout, not
        // anything a player cares about.
        assertTrue(slots.all { it.badges.isEmpty() })
    }

    /**
     * **A corrupt reply is left unmatched, and the retry gets the real record.**
     *
     * A splice on a shared bus keeps our header, type and echoed address and gains another
     * record's tail, so the address check cannot see it. The length against its declared version
     * can. Failing the match rather than raising is what makes the recovery free: the exchange
     * keeps listening, then `SysExExchange` asks again.
     */
    @Test
    fun `a reply too long for its declared version is rejected and the retry recovers`() = runTest {
        var firstAnswer = true
        val transport = FakeMidiTransport { request ->
            if (Pro800SysEx.typeOf(request) != Pro800SysEx.TYPE_REQUEST_DUMP) {
                emptyList()
            } else {
                val number = Pro800SysEx.addressOf(request)!!
                if (number == 0 && firstAnswer) {
                    firstAnswer = false
                    listOf(oversizedDumpFor(number)) // the splice
                } else {
                    listOf(dumpFor(number, names[number]))
                }
            }
        }
        val pro800 = Pro800Instrument(
            SysExExchange(
                transport, backgroundScope,
                defaultTimeout = kotlin.time.Duration.parse("20ms"), retries = 1,
            ),
            config,
        )

        val slots = pro800.browser.index().toList()
            .filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }
        assertEquals(
            "the corrupt reply must not become the preset's name",
            "Fat Saw Bass",
            slots.single { it.address == SlotAddress(0, 0) }.name,
        )
        assertFalse("the splice was consumed, not left to the next read", firstAnswer)
    }

    /**
     * **A short record that reads the same twice is a preset, and is kept.**
     *
     * Truncation is deterministic. The confirming read exists so a genuinely short record - which
     * an unnamed format 109 preset would be - is never thrown away, which is the failure a fixed
     * length floor would have caused for almost every preset on a real instrument.
     */
    @Test
    fun `a short record that reproduces is accepted`() = runTest {
        var reads = 0
        val transport = FakeMidiTransport { request ->
            if (Pro800SysEx.typeOf(request) != Pro800SysEx.TYPE_REQUEST_DUMP) {
                emptyList()
            } else {
                val number = Pro800SysEx.addressOf(request)!!
                if (number == 0) { reads++; listOf(shortDumpFor(number, "Tiny")) }
                else listOf(dumpFor(number, names[number]))
            }
        }
        val pro800 = Pro800Instrument(SysExExchange(transport, backgroundScope), config)

        val updates = pro800.browser.index().toList()
        assertTrue(
            "a short record that reproduces is a preset, not a failure",
            updates.filterIsInstance<IndexUpdate.Failed>().isEmpty(),
        )
        val slots = updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }
        assertEquals("every slot is still reported", 10, slots.size)
        // It stops before the name field, so it has no name to show - but it is kept.
        assertEquals(Pro800Instrument.UNNAMED, slots.single { it.address == SlotAddress(0, 0) }.name)
        assertEquals("the short record was confirmed by a second read", 2, reads)
    }

    /** A short record that does *not* reproduce was a splice, and must not be handed back. */
    @Test
    fun `a short record that differs on the confirming read is refused`() = runTest {
        var reads = 0
        val transport = FakeMidiTransport { request ->
            if (Pro800SysEx.typeOf(request) != Pro800SysEx.TYPE_REQUEST_DUMP) {
                emptyList()
            } else {
                val number = Pro800SysEx.addressOf(request)!!
                if (number == 0) {
                    reads++
                    listOf(shortDumpFor(number, if (reads == 1) "Splice" else "Other"))
                } else {
                    listOf(dumpFor(number, names[number]))
                }
            }
        }
        val pro800 = Pro800Instrument(SysExExchange(transport, backgroundScope), config)

        val updates = pro800.browser.index().toList()
        val failed = updates.filterIsInstance<IndexUpdate.Failed>()
        assertEquals(1, failed.size)
        assertEquals(SlotAddress(0, 0), failed.single().address)
        assertTrue(
            "the message should point at the shared port, which is what causes this",
            failed.single().reason.contains("same MIDI port"),
        )
        // The walk continues: one bad slot must not cost the other nine.
        assertEquals(9, updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }.size)
    }

    /** A record that stops before the name field - what a truncated splice looks like. */
    private fun shortDumpFor(programNumber: Int, marker: String): ByteArray {
        val dense = ByteArray(Pro800ProgramFields.NAME_DENSE_OFFSET - 8)
        Pro800ProgramCodec.writeValue(
            dense, Pro800ProgramFields.VERSION.denseOffset, Pro800ProgramFields.VERSION.byteCount, 109,
        )
        // Something that differs between the two replies, standing in for a foreign tail.
        marker.forEachIndexed { i, c -> dense[dense.size - marker.length + i] = c.code.toByte() }
        return Pro800SysEx.HEADER +
            byteArrayOf(Pro800SysEx.TYPE_DUMP.toByte()) +
            byteArrayOf((programNumber and 0x7F).toByte(), ((programNumber shr 7) and 0x7F).toByte()) +
            Pro800ProgramCodec.encode(dense) +
            Pro800SysEx.SYSEX_END
    }

    /** A dump whose record runs past what its own version byte permits - i.e. a spliced reply. */
    private fun oversizedDumpFor(programNumber: Int): ByteArray {
        val tooLong = Pro800ProgramFields.maxDenseSizeFor(111) + 16
        val dense = ByteArray(tooLong)
        Pro800ProgramCodec.writeValue(
            dense, Pro800ProgramFields.VERSION.denseOffset, Pro800ProgramFields.VERSION.byteCount, 111,
        )
        Pro800ProgramCodec.writeString(
            dense, Pro800ProgramFields.NAME_DENSE_OFFSET, Pro800ProgramFields.NAME_LENGTH, "Wrong Record",
        )
        return Pro800SysEx.HEADER +
            byteArrayOf(Pro800SysEx.TYPE_DUMP.toByte()) +
            byteArrayOf((programNumber and 0x7F).toByte(), ((programNumber shr 7) and 0x7F).toByte()) +
            Pro800ProgramCodec.encode(dense) +
            Pro800SysEx.SYSEX_END
    }

    /** One unreadable address must not lose the rest of the index. */
    @Test
    fun `a single unreadable slot is reported and the walk continues`() = runTest {
        val transport = FakeMidiTransport { request ->
            val number = Pro800SysEx.addressOf(request)
            if (Pro800SysEx.typeOf(request) != Pro800SysEx.TYPE_REQUEST_DUMP || number == 3) {
                emptyList() // slot 3 never answers
            } else {
                listOf(dumpFor(number!!, names[number]))
            }
        }
        val pro800 = Pro800Instrument(
            SysExExchange(transport, backgroundScope, defaultTimeout = kotlin.time.Duration.parse("20ms"), retries = 0),
            config,
        )

        val updates = pro800.browser.index().toList()
        val failures = updates.filterIsInstance<IndexUpdate.Failed>()
        assertEquals(1, failures.size)
        assertEquals(SlotAddress(0, 3), failures.single().address)
        assertEquals(9, updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }.size)
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    // ---- Selection: the settings-block pointer, and no MIDI channel anywhere ----

    /**
     * The whole sequence, in order: read the settings block, write the pointer back, confirm it by
     * reading again, then reload.
     *
     * **Nothing channel-voice goes out at all.** That is the point of the change: a bank select and
     * a program change need a channel, nothing acknowledges either, and the channel is not always
     * knowable - so a mismatch was a Load button that silently did nothing.
     */
    @Test
    fun `select writes the settings pointer and then reloads`() = runTest {
        val fake = FakePro800()
        val pro800 = fullSizeInstrument(fake, backgroundScope)

        pro800.selector!!.select(SlotAddress(bank = 1, slot = 5))

        assertTrue(
            "selection must be pure SysEx - no channel-voice message may be sent",
            fake.requestLog.none { it.isNotEmpty() && it[0] != 0xF0.toByte() },
        )
        val settings = fake.currentSettings()
        assertEquals(105, settings.currentPresetNumber)
        assertEquals(1, settings.currentBank)
        assertArrayEquals(
            byteArrayOf(
                0xF0.toByte(), 0x00, 0x20, 0x32, 0x00, 0x01, 0x24, 0x00, 0x32, 0x00, 0xF7.toByte(),
            ),
            fake.requestLog.last(),
        )
    }

    /**
     * The pointer is the *flat* program number, not the slot within its bank.
     *
     * The firmware takes the slot digits from this modulo 100 and the bank letter from the separate
     * `Current Bank` field, so D05 is 305 here. Writing 5 would leave the instrument pointed at the
     * right slot digits in whatever bank it happened to already be in.
     */
    @Test
    fun `the pointer carries the flat program number and the bank separately`() = runTest {
        val fake = FakePro800()
        val pro800 = fullSizeInstrument(fake, backgroundScope)

        pro800.selector!!.select(SlotAddress(bank = 3, slot = 5))

        assertEquals(305, fake.currentSettings().currentPresetNumber)
        assertEquals(3, fake.currentSettings().currentBank)
    }

    /**
     * The write touches the two pointer bytes and nothing else.
     *
     * A settings write carries the whole 46-byte block back, including every global setting the
     * user has configured, so "what else changed" is the question that matters. It is also why the
     * payload is patched in place rather than decoded and re-encoded - see
     * [Pro800ProgramCodec.patchValue].
     */
    @Test
    fun `the settings write changes only the pointer bytes`() = runTest {
        val fake = FakePro800()
        val before = fake.settingsPayload().copyOf()
        val pro800 = fullSizeInstrument(fake, backgroundScope)

        pro800.selector!!.select(SlotAddress(bank = 1, slot = 5))

        val after = fake.settingsPayload()
        assertEquals("the block must keep its length", before.size, after.size)
        val changed = before.indices.filter { before[it] != after[it] }
        // Raw offset 6 is the preset number's low byte and raw 23 is the bank. The number's high
        // byte (raw 7) is 0 both before and after here, so it does not show up as a difference.
        assertEquals(listOf(6, 23), changed)
    }

    /**
     * **The reload waits for the pointer to actually land.**
     *
     * A settings write is not guaranteed to be visible on the next read - real hardware commits
     * some of them over a second later - and a reload sent before the pointer commits recalls the
     * preset that is on its way out. So the poll is not a formality: it is what makes the ordering
     * correct rather than usually correct.
     */
    @Test
    fun `the reload is sent only once the read-back matches`() = runTest {
        val fake = FakePro800(staleSettingsReads = 2)
        val pro800 = fullSizeInstrument(fake, backgroundScope)

        pro800.selector!!.select(SlotAddress(bank = 1, slot = 5))

        val types = fake.requestLog.map { Pro800SysEx.typeOf(it) }
        val reload = types.indexOf(Pro800SysEx.TYPE_RESET_MODE)
        assertTrue("a reload must have been sent", reload >= 0)
        assertEquals("the reload must come last", types.size - 1, reload)
        // One read before the write, then three after it: two that still report the old pointer
        // and the one that finally agrees.
        assertEquals(4, types.count { it == Pro800SysEx.TYPE_REQUEST_DUMP })
    }

    /**
     * A pointer that never lands is a failure, and **no reload is sent**.
     *
     * Reloading anyway would recall whatever the instrument is still pointed at - the preset the
     * user was moving away from - which looks like the app loading the wrong thing rather than
     * like the write having failed.
     */
    @Test
    fun `a pointer that never lands fails without reloading`() = runTest {
        val fake = FakePro800(ignoreWrites = true)
        val pro800 = fullSizeInstrument(fake, backgroundScope)

        try {
            pro800.selector!!.select(SlotAddress(bank = 1, slot = 5))
            throw AssertionError("a select that never landed reported success")
        } catch (expected: InstrumentException.ProtocolDesync) {
            assertTrue(expected.message!!.contains("B05"))
        }
        assertTrue(
            "no reload may be sent when the pointer never landed",
            fake.requestLog.none { Pro800SysEx.typeOf(it) == Pro800SysEx.TYPE_RESET_MODE },
        )
    }

    /**
     * "Selected", because the pointer was read back and agreed - which a bank-select and program
     * change could not claim, since nothing acknowledges those. What cannot be confirmed is the
     * audible recall, which the instrument reports nowhere; this claims only the pointer.
     */
    @Test
    fun `the selection confirmation says selected`() = runTest {
        val (pro800, _) = instrument(backgroundScope)
        assertEquals("Selected A00.", pro800.selector!!.confirmationFor("A00"))
    }

    @Test
    fun `the facets a Pro-800 has no equivalent for are absent`() = runTest {
        val (pro800, _) = instrument(backgroundScope)
        // The report facet *is* present: it is read-only, and it is where the test fixtures
        // come from (Pro800Reporter).
        assertTrue(pro800.report != null)
        // The write path exists now, with its read-back verification and undo buffer.
        assertTrue(pro800.editor != null)
        // Present from day one: its browser is built on this.
        assertTrue(pro800.transfer != null)
    }

    @Test
    fun `transfer hands back the raw encoded blob the browser was built on`() = runTest {
        val (pro800, _) = instrument(backgroundScope)
        val blob = pro800.transfer!!.read(SlotAddress(0, 0))
        assertEquals("Fat Saw Bass", Pro800Program.fromEncoded(blob).name)
    }

    /** The settings block shares this address space at 510; a browsable row that overwrites global
     * settings is not a preset. */
    @Test
    fun `an address beyond the program count is refused`() = runTest {
        val bigConfig = Pro800Config(bankCount = 6, slotsPerBank = 100)
        val transport = FakeMidiTransport { emptyList() }
        val pro800 = Pro800Instrument(SysExExchange(transport, backgroundScope), bigConfig)
        try {
            pro800.transfer!!.read(SlotAddress(bank = 5, slot = 99)) // 599, past 400
            throw AssertionError("an out-of-range address was accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("outside"))
        }
    }

    /**
     * The reply a real Pro-800 gives for an address that holds nothing: a bare `F0 F7`, with no
     * manufacturer header and no echoed address.
     *
     * Rejecting it costs two full timeouts per address, and on an instrument with three of four
     * banks empty that is about twenty minutes of a scan spent waiting for replies that have
     * already arrived.
     */
    @Test
    fun `a bare F0 F7 is an empty slot, not a timeout`() = runTest {
        val transport = FakeMidiTransport { request ->
            when {
                Pro800SysEx.typeOf(request) != Pro800SysEx.TYPE_REQUEST_DUMP -> emptyList()
                // Slots 0..4 hold presets; everything above answers the empty reply.
                Pro800SysEx.addressOf(request)!! < 5 -> // bank A
                    listOf(dumpFor(Pro800SysEx.addressOf(request)!!, "Preset"))
                else -> listOf(byteArrayOf(0xF0.toByte(), 0xF7.toByte()))
            }
        }
        val pro800 = Pro800Instrument(
            SysExExchange(transport, backgroundScope, defaultTimeout = kotlin.time.Duration.parse("20ms"), retries = 0),
            config,
        )

        val updates = pro800.browser.index().toList()
        // Nothing failed: an empty reply is an answer.
        assertTrue(updates.filterIsInstance<IndexUpdate.Failed>().isEmpty())

        val slots = updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }
        assertEquals(10, slots.size)
        assertEquals("Preset", slots.first { it.displayId == "A00" }.name)
        assertEquals("Preset", slots.first { it.displayId == "A04" }.name)
        // The whole of bank B answered F0 F7 - empty, and read in one round trip each.
        assertTrue(slots.filter { it.bankLabel == "B" }.all { it.name == null })

        // One request per address, with no retries: the reply was accepted first time.
        assertEquals(10, transport.sent.count { Pro800SysEx.typeOf(it) == Pro800SysEx.TYPE_REQUEST_DUMP })
    }

    // ---- Firmware check ----

    /** A real-sized instrument on a stateful fake - what the selection tests need. */
    private fun fullSizeInstrument(fake: FakePro800, scope: CoroutineScope) = Pro800Instrument(
        SysExExchange(fake.transport, scope),
        Pro800Config(bankCount = 4, slotsPerBank = 100, slotDigits = 2),
    )

    private fun instrumentWith(
        /** Null models a firmware query that goes unanswered - a transient, not a bad version. */
        firmware: ByteArray?,
        config: Pro800Config = this.config,
        scope: CoroutineScope,
    ): Pair<Pro800Instrument, FakeMidiTransport> {
        val transport = FakeMidiTransport { request ->
            when {
                Pro800SysEx.typeOf(request) == Pro800SysEx.TYPE_DEVICE_NAME -> listOf(
                    Pro800SysEx.HEADER + byteArrayOf(Pro800SysEx.TYPE_DEVICE_NAME_REPLY.toByte()) +
                        "PRO-800".toByteArray(Charsets.US_ASCII) + Pro800SysEx.SYSEX_END,
                )
                Pro800SysEx.typeOf(request) == Pro800SysEx.TYPE_FIRMWARE -> listOfNotNull(firmware)
                else -> listOf(byteArrayOf(0xF0.toByte(), 0xF7.toByte()))
            }
        }
        return Pro800Instrument(SysExExchange(transport, scope), config) to transport
    }

    private fun firmwareReply(a: Int, b: Int, c: Int) =
        Pro800SysEx.HEADER +
            byteArrayOf(Pro800SysEx.TYPE_FIRMWARE_REPLY.toByte(), 0x00, a.toByte(), b.toByte(), c.toByte()) +
            Pro800SysEx.SYSEX_END

    @Test
    fun `an untested firmware version warns instead of refusing`() = runTest {
        val (pro800, _) = instrumentWith(firmwareReply(1, 4, 5), scope = backgroundScope)

        // A warning, not a refusal: refusing would lock out the one person who could establish
        // what an untested firmware actually does, and who can send back the device report
        // saying so. The session is allowed; the warning rides along.
        pro800.connect()

        val advisory = pro800.advisory
        assertNotNull("an untested firmware must raise an advisory", advisory)
        assertTrue("it must name the version found", advisory!!.contains("1.4.5"))
        assertTrue("and the versions that were tested", advisory.contains("1.4.6"))
        assertTrue("and point at the report, which is the useful thing to send back",
            advisory.contains("device report"))

        // Usable, not merely connected: the facets have to work, or "continue anyway" is a lie.
        // This fixture answers every address with a bare F0 F7, so the presets are all empty -
        // what is being asserted is that the walk runs to completion and reports every slot.
        val updates = pro800.browser.index().toList()
        assertTrue(
            "an advisory must not turn into a failed scan",
            updates.filterIsInstance<IndexUpdate.Failed>().isEmpty(),
        )
        assertEquals(10, updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }.size)
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    /** The supported firmware raises nothing - the advisory must not become background noise. */
    @Test
    fun `a tested firmware version raises no advisory`() = runTest {
        val (pro800, _) = instrumentWith(firmwareReply(1, 4, 6), scope = backgroundScope)
        pro800.connect()
        assertNull(pro800.advisory)
    }

    /** A firmware query that goes unanswered is a transient, not an untested version. */
    @Test
    fun `an unreadable firmware version raises no advisory`() = runTest {
        val (pro800, _) = instrumentWith(null, scope = backgroundScope)
        pro800.connect()
        assertNull("silence is not evidence of an unsupported version", pro800.advisory)
    }

    /**
     * Closing the session must release the MIDI port.
     *
     * Android hands out one handle per device, so leaking it does not merely waste a handle: the
     * next connection cannot open the device to probe it, and the app reports finding no
     * instrument while the instrument is plugged in and working - indistinguishable from the
     * instrument being gone, when only the port is unavailable.
     */
    @Test
    fun `closing the instrument releases the MIDI port`() = runTest {
        val (pro800, transport) = instrument(backgroundScope)
        pro800.connect()
        assertFalse(transport.closed)

        pro800.close()
        assertTrue("the MIDI port must be released", transport.closed)
    }
}
