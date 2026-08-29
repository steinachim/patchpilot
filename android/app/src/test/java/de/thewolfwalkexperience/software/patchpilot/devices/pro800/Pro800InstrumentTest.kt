package de.thewolfwalkexperience.software.patchpilot.devices.pro800

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
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

    /**
     * Selection is bank select then program change - and exactly the (bank, slot) pair the address
     * already carries, with no flattening arithmetic in between.
     */
    @Test
    fun `select sends a bank select then a program change`() = runTest {
        val (pro800, transport) = instrument(backgroundScope)
        pro800.selector!!.select(SlotAddress(bank = 1, slot = 37))

        val voice = transport.sent.filter { it.isNotEmpty() && it[0] != 0xF0.toByte() }
        assertEquals(2, voice.size)
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 0x00, 0x01), voice[0])
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0x25), voice[1])
    }

    /** The bank select goes out every time: tracking the instrument's current bank host-side is
     * wrong the moment the user touches the front panel. */
    @Test
    fun `the bank select is repeated even when the bank has not changed`() = runTest {
        val (pro800, transport) = instrument(backgroundScope)
        pro800.selector!!.select(SlotAddress(0, 1))
        pro800.selector!!.select(SlotAddress(0, 2))

        val bankSelects = transport.sent.count { it.size == 3 && it[0] == 0xB0.toByte() && it[1] == 0x00.toByte() }
        assertEquals(2, bankSelects)
    }

    /** Nothing acknowledges a program change, so the app must not claim it landed. */
    @Test
    fun `the selection confirmation says sent, not selected`() = runTest {
        val (pro800, _) = instrument(backgroundScope)
        assertEquals("Sent A00.", pro800.selector!!.confirmationFor("A00"))
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
     * Rejecting it costs two full timeouts per address. On the instrument this was found on,
     * three of four banks answered this way - about twenty minutes of a scan spent waiting for
     * replies that had already arrived.
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

    // ---- Firmware check and MIDI channel resolution ----

    /** A settings dump carrying one MIDI RX Channel setting, encoded as the instrument would. */
    private fun settingsDump(rxChannelSetting: Int): ByteArray {
        val dense = ByteArray(Pro800Settings.RX_CHANNEL_DENSE + 1)
        Pro800ProgramCodec.writeValue(dense, Pro800Settings.RX_CHANNEL_DENSE, 1, rxChannelSetting)
        return Pro800SysEx.HEADER +
            byteArrayOf(Pro800SysEx.TYPE_DUMP.toByte()) +
            byteArrayOf(
                (Pro800SysEx.SETTINGS_ADDRESS and 0x7F).toByte(),
                ((Pro800SysEx.SETTINGS_ADDRESS shr 7) and 0x7F).toByte(),
            ) +
            Pro800ProgramCodec.encode(dense) +
            Pro800SysEx.SYSEX_END
    }

    private fun instrumentWith(
        /** Null models a firmware query that goes unanswered - a transient, not a bad version. */
        firmware: ByteArray?,
        rxChannelSetting: Int,
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
                Pro800SysEx.addressOf(request) == Pro800SysEx.SETTINGS_ADDRESS ->
                    listOf(settingsDump(rxChannelSetting))
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
        val (pro800, _) = instrumentWith(firmwareReply(1, 4, 5), Pro800Settings.RX_ALL, scope = backgroundScope)

        // **It used to throw here, and that was the wrong call.** Refusing locked out the one
        // person who could establish what an untested firmware actually does - and who can send
        // back the device report saying so. The session is allowed; the warning rides along.
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
        val (pro800, _) = instrumentWith(firmwareReply(1, 4, 6), Pro800Settings.RX_ALL, scope = backgroundScope)
        pro800.connect()
        assertNull(pro800.advisory)
    }

    /** A firmware query that goes unanswered is a transient, not an untested version. */
    @Test
    fun `an unreadable firmware version raises no advisory`() = runTest {
        val (pro800, _) = instrumentWith(null, Pro800Settings.RX_ALL, scope = backgroundScope)
        pro800.connect()
        assertNull("silence is not evidence of an unsupported version", pro800.advisory)
    }

    /**
     * The instrument's own MIDI RX Channel setting decides where selection is sent.
     *
     * A program change on the wrong channel is ignored, and nothing acknowledges it, so getting
     * this wrong is completely silent: an instrument on channel 3 with an app configured for
     * channel 1 looks like a button that does nothing.
     */
    @Test
    fun `selection is sent on the channel the instrument says it listens to`() = runTest {
        // Setting 5 = channel 4 on the front panel = wire channel 3.
        val (pro800, transport) = instrumentWith(firmwareReply(1, 4, 6), 5, scope = backgroundScope)
        pro800.connect()
        pro800.selector!!.select(SlotAddress(bank = 1, slot = 7))

        val voice = transport.sent.filter { it.isNotEmpty() && it[0] != 0xF0.toByte() }
        assertArrayEquals(byteArrayOf(0xB3.toByte(), 0x00, 0x01), voice[0])
        assertArrayEquals(byteArrayOf(0xC3.toByte(), 0x07), voice[1])
    }

    @Test
    fun `an instrument set to receive on ALL is sent channel 1`() = runTest {
        val (pro800, transport) = instrumentWith(firmwareReply(1, 4, 6), Pro800Settings.RX_ALL, scope = backgroundScope)
        pro800.connect()
        pro800.selector!!.select(SlotAddress(0, 0))

        val voice = transport.sent.filter { it.isNotEmpty() && it[0] != 0xF0.toByte() }
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 0x00, 0x00), voice[0])
    }

    /** Better a clear refusal than a button that silently does nothing. */
    @Test
    fun `selection refuses outright when the instrument has MIDI receive off`() = runTest {
        val (pro800, transport) = instrumentWith(firmwareReply(1, 4, 6), Pro800Settings.RX_OFF, scope = backgroundScope)
        pro800.connect()
        try {
            pro800.selector!!.select(SlotAddress(0, 0))
            throw AssertionError("a select was sent to an instrument that cannot hear it")
        } catch (expected: de.thewolfwalkexperience.software.patchpilot.core.InstrumentException.NotSupported) {
            assertTrue(expected.message!!.contains("OFF"))
        }
        assertTrue(transport.sent.none { it.isNotEmpty() && it[0] == 0xB0.toByte() })
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

    // ---- DIP-switch mode: the one setting the instrument will not report ----

    /**
     * In DIP-switch mode the instrument says "the switches decide" and will not say what they are
     * set to. Falling back to a configured default here would silently send to the wrong channel
     * whenever the switches disagree with it, so the app asks instead.
     */
    @Test
    fun `dip-switch mode raises a question instead of guessing a channel`() = runTest {
        val (pro800, _) = instrumentWith(
            firmwareReply(1, 4, 6),
            Pro800Settings.RX_DIP_SWITCHES,
            scope = backgroundScope,
        )
        pro800.connect()

        val question = pro800.setup!!.question
        assertTrue("a question should be pending", question != null)
        assertEquals(16, question!!.options.size)
        assertEquals("Channel 1", question.options.first())
        assertEquals("Channel 16", question.options.last())
        assertTrue(question.explanation.contains("DIP"))
    }

    /** Better a refusal naming the reason than a Load button that silently does nothing. */
    @Test
    fun `selection refuses until the dip-switch channel is answered`() = runTest {
        val (pro800, transport) = instrumentWith(
            firmwareReply(1, 4, 6),
            Pro800Settings.RX_DIP_SWITCHES,
            scope = backgroundScope,
        )
        pro800.connect()

        try {
            pro800.selector!!.select(SlotAddress(0, 0))
            throw AssertionError("a select was sent before the channel was known")
        } catch (expected: de.thewolfwalkexperience.software.patchpilot.core.InstrumentException.NotSupported) {
            assertTrue(expected.message!!.contains("DIP"))
        }
        assertTrue(transport.sent.none { it.isNotEmpty() && (it[0].toInt() and 0xF0) == 0xC0 })
    }

    @Test
    fun `answering the question sends selection on the chosen channel`() = runTest {
        val (pro800, transport) = instrumentWith(
            firmwareReply(1, 4, 6),
            Pro800Settings.RX_DIP_SWITCHES,
            scope = backgroundScope,
        )
        pro800.connect()

        pro800.setup!!.answer(2) // "Channel 3" -> wire channel 2
        assertNull("nothing further should be pending", pro800.setup!!.question)

        pro800.selector!!.select(SlotAddress(bank = 1, slot = 7))
        val voice = transport.sent.filter { it.isNotEmpty() && it[0] != 0xF0.toByte() }
        assertArrayEquals(byteArrayOf(0xB2.toByte(), 0x00, 0x01), voice[0])
        assertArrayEquals(byteArrayOf(0xC2.toByte(), 0x07), voice[1])
    }

    /** An instrument that reports a usable channel must not be asked anything. */
    @Test
    fun `a reported channel raises no question`() = runTest {
        val (pro800, _) = instrumentWith(firmwareReply(1, 4, 6), 5, scope = backgroundScope)
        pro800.connect()
        assertNull(pro800.setup!!.question)
    }

    /** MIDI receive OFF is not a question either - no channel would help. */
    @Test
    fun `midi receive off raises no question, it just refuses`() = runTest {
        val (pro800, _) = instrumentWith(
            firmwareReply(1, 4, 6),
            Pro800Settings.RX_OFF,
            scope = backgroundScope,
        )
        pro800.connect()
        assertNull(pro800.setup!!.question)
    }
}
