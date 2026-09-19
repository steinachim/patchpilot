package de.thewolfwalkexperience.software.patchpilot.devices.pro800

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.devices.pro800.Pro800Fixtures.bytes
import de.thewolfwalkexperience.software.patchpilot.midi.FakeMidiTransport
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes the sample Pro-800 messages in [Pro800Fixtures] end to end.
 *
 * These fixtures cover record shapes - variable length, no name, format-dependent field layout -
 * that a hand-written fixture can easily miss if it happens to encode the same assumption as the
 * code being tested. Exercising the decoder against realistic, representative byte sequences
 * catches exactly the class of bug a self-consistent fixture cannot.
 */
class Pro800DecodingTest {

    // ---- Identity and firmware ----

    @Test
    fun `the device-name reply decodes to PRO-800`() {
        assertEquals("PRO-800", Pro800SysEx.deviceName(bytes(Pro800Fixtures.DEVICE_NAME_REPLY)))
    }

    /**
     * The version bytes start at index 10, not 9 - index 9 echoes the request's `0x00` parameter.
     * Reading from 9 would turn a firmware version like 1.4.6 into nonsense, and the fixture that
     * was supposed to cover it had been built to match the wrong offset.
     */
    @Test
    fun `the firmware reply decodes to 1_4_6`() {
        assertEquals("1.4.6", Pro800SysEx.firmwareVersion(bytes(Pro800Fixtures.FIRMWARE_REPLY)))
    }

    @Test
    fun `the undocumented replies match what the protocol notes record`() {
        // 0x02 -> "03 00"
        val two = bytes(Pro800Fixtures.TYPE_02_REPLY)
        assertEquals(0x03, Pro800SysEx.typeOf(two))
        // 0x04 -> 0x05 followed by ASCII "P0E9I"
        val four = bytes(Pro800Fixtures.TYPE_04_REPLY)
        assertEquals(0x05, Pro800SysEx.typeOf(four))
        assertTrue(String(four, Charsets.US_ASCII).contains("P0E9I"))
    }

    // ---- Settings ----

    /**
     * A value of 4 here is front-panel channel 3, which is wire channel 2.
     *
     * Nothing depends on this any more - selection needs no channel - but it is still read and
     * still reported in the device report, so it still has to decode correctly.
     */
    @Test
    fun `the settings block yields the instrument's MIDI receive channel`() {
        val settings = Pro800Settings.fromEncoded(
            Pro800SysEx.dumpPayload(bytes(Pro800Fixtures.SETTINGS_DUMP)),
        )
        assertEquals(4, settings.midiRxChannelSetting)
        assertEquals("channel 3", settings.midiRxDescription)
    }

    /** The same block's selection pointer: the two fields `select` writes. */
    @Test
    fun `the settings block yields the selection pointer`() {
        val settings = Pro800Settings.fromEncoded(
            Pro800SysEx.dumpPayload(bytes(Pro800Fixtures.SETTINGS_DUMP)),
        )
        // A06 - the preset this instrument was sitting on when the capture was taken.
        assertEquals(6, settings.currentPresetNumber)
        assertEquals(0, settings.currentBank)
    }

    /**
     * Moving the pointer changes exactly two bytes of the block and nothing else.
     *
     * This is the real 46-byte block, which is the shape that matters: it ends mid-group, so its
     * last overflow byte governs five value bytes rather than seven and two of its bits belong to
     * nothing. A decode/re-encode round trip would zero those; patching in place cannot.
     */
    @Test
    fun `moving the pointer leaves the rest of the settings block untouched`() {
        val before = Pro800SysEx.dumpPayload(bytes(Pro800Fixtures.SETTINGS_DUMP))
        val after = Pro800Settings.withSelection(before, programNumber = 305, bank = 3)

        assertEquals(before.size, after.size)
        assertEquals(listOf(6, 7, 23), before.indices.filter { before[it] != after[it] })

        val settings = Pro800Settings.fromEncoded(after)
        assertEquals(305, settings.currentPresetNumber)
        assertEquals(3, settings.currentBank)
        // Everything else still reads as it did - the MIDI channel is the one to check, since it
        // sits between the two patched fields.
        assertEquals(4, settings.midiRxChannelSetting)
    }

    // ---- Program records ----

    @Test
    fun `a preset decodes to the name the instrument shows`() {
        val program = Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(bytes(Pro800Fixtures.DUMP_B00)))
        assertFalse(program.isEmpty)
        assertEquals(111, program.version)
        assertEquals("RandomTest", program.name)
    }

    /**
     * **Records are variable length.** This one is 190 bytes where B00 is 210; a decoder that
     * called anything under 166 dense bytes an empty slot would report most of a bank as empty.
     */
    @Test
    fun `the shortest record is a preset, not an empty slot`() {
        val message = bytes(Pro800Fixtures.DUMP_109_SHORT_NAME)
        assertEquals(190, message.size)
        val program = Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(message))
        assertFalse("a 190-byte record is still a preset", program.isEmpty)
        assertEquals(109, program.version)
        assertEquals("Rand", program.name)
    }

    /** In format 109 the name is the last field, so a longer name means a longer record. */
    @Test
    fun `record length tracks name length in the older preset format`() {
        val harp = bytes(Pro800Fixtures.DUMP_109_SHORT_NAME)
        val brass = bytes(Pro800Fixtures.DUMP_109_LONG_NAME)
        assertEquals("Rand", Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(harp)).name)
        assertEquals("RandomTest 109L", Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(brass)).name)
        assertTrue("the longer name should give the longer record", brass.size > harp.size)
    }

    /** A preset may carry no name; that must not read as "no preset". */
    @Test
    fun `a preset with no name is still a preset`() {
        val program = Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(bytes(Pro800Fixtures.DUMP_111_UNNAMED)))
        assertFalse("an unnamed record holds a preset", program.isEmpty)
        assertNull("...and it has no name", program.name)
        assertEquals(111, program.version)
    }

    @Test
    fun `an empty address is a bare F0 F7`() {
        val message = bytes(Pro800Fixtures.DUMP_EMPTY_REPLY)
        assertArrayEquals(byteArrayOf(0xF0.toByte(), 0xF7.toByte()), message)
        assertTrue(Pro800SysEx.isEmptyReply(message))
        assertTrue(Pro800Program.fromEncoded(ByteArray(0)).isEmpty)
    }

    /** Whatever the instrument sent, the codec must be able to hand back unchanged - the
     * precondition for a write path that does not corrupt a preset it merely renamed. */
    @Test
    fun `every record survives a decode-encode round trip`() {
        listOf(
            Pro800Fixtures.DUMP_B00,
            Pro800Fixtures.DUMP_109_LONG_NAME,
            Pro800Fixtures.DUMP_109_SHORT_NAME,
            Pro800Fixtures.DUMP_111_UNNAMED,
            Pro800Fixtures.SETTINGS_DUMP,
        ).forEach { hex ->
            val payload = Pro800SysEx.dumpPayload(bytes(hex))
            assertArrayEquals(
                payload,
                Pro800ProgramCodec.encode(Pro800ProgramCodec.decode(payload)),
            )
        }
    }

    // ---- End to end, against sample bytes ----

    /**
     * The browser driven end to end against these sample replies: two populated addresses of
     * different lengths and formats, one unnamed preset, one empty address.
     */
    @Test
    fun `the browser reads sample replies correctly end to end`() = runTest {
        val byNumber = mapOf(
            0 to Pro800Fixtures.DUMP_B00,
            1 to Pro800Fixtures.DUMP_109_LONG_NAME,
            2 to Pro800Fixtures.DUMP_109_SHORT_NAME,
            3 to Pro800Fixtures.DUMP_111_UNNAMED,
        )
        // The settings block is kept, not re-served from the fixture, because selection writes it
        // and then reads it back: a transport that always answers with the capture would report the
        // pointer never moving. This starts as the real captured block, which is the point - the
        // patch below is applied to genuine hardware bytes, not a synthetic 46 bytes.
        var settings = bytes(Pro800Fixtures.SETTINGS_DUMP)
        val transport = FakeMidiTransport { request ->
            when (Pro800SysEx.typeOf(request)) {
                Pro800SysEx.TYPE_DEVICE_NAME -> listOf(bytes(Pro800Fixtures.DEVICE_NAME_REPLY))
                Pro800SysEx.TYPE_FIRMWARE -> listOf(bytes(Pro800Fixtures.FIRMWARE_REPLY))
                Pro800SysEx.TYPE_REQUEST_DUMP -> {
                    val number = Pro800SysEx.addressOf(request)!!
                    if (number == Pro800SysEx.SETTINGS_ADDRESS) {
                        listOf(settings)
                    } else {
                        // The sample dumps echo their own original addresses, so re-address
                        // them to whatever this small fixture instrument is being asked for.
                        byNumber[number]?.let { listOf(readdress(bytes(it), number)) }
                            ?: listOf(bytes(Pro800Fixtures.DUMP_EMPTY_REPLY))
                    }
                }
                Pro800SysEx.TYPE_DUMP -> {
                    if (Pro800SysEx.addressOf(request) == Pro800SysEx.SETTINGS_ADDRESS) {
                        settings = request
                    }
                    emptyList()
                }
                Pro800SysEx.TYPE_RESET_MODE -> listOf(
                    Pro800SysEx.HEADER +
                        byteArrayOf(Pro800SysEx.TYPE_STATUS.toByte(), 0x00, Pro800SysEx.STATUS_OK.toByte()) +
                        Pro800SysEx.SYSEX_END,
                )
                else -> emptyList()
            }
        }
        val pro800 = Pro800Instrument(
            SysExExchange(transport, backgroundScope),
            Pro800Config(bankCount = 1, slotsPerBank = 5, slotDigits = 2),
        )
        pro800.connect()
        assertEquals("1.4.6", pro800.identity.firmwareVersion)

        val slots = pro800.browser.index().toList()
            .filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }
        assertEquals(5, slots.size)
        assertEquals("RandomTest", slots[0].name)
        assertEquals("RandomTest 109L", slots[1].name)
        assertEquals("Rand", slots[2].name)
        assertEquals(Pro800Instrument.UNNAMED, slots[3].name)
        assertNull(slots[4].name)

        // And selection moves the pointer in the real captured settings block. The capture was
        // taken with the instrument on A06; A01 is where this asks it to go.
        assertEquals(6, Pro800Settings.fromEncoded(Pro800SysEx.dumpPayload(settings)).currentPresetNumber)
        pro800.selector!!.select(SlotAddress(0, 1))

        val after = Pro800Settings.fromEncoded(Pro800SysEx.dumpPayload(settings))
        assertEquals(1, after.currentPresetNumber)
        assertEquals(0, after.currentBank)
        // The MIDI channel the capture declares is untouched - and never consulted.
        assertEquals(4, after.midiRxChannelSetting)
        assertTrue(
            "selection must send no channel-voice message",
            transport.sent.none { it.isNotEmpty() && it[0] != 0xF0.toByte() },
        )
    }

    private fun readdress(message: ByteArray, programNumber: Int): ByteArray {
        val copy = message.copyOf()
        copy[Pro800SysEx.ADDRESS_LSB_INDEX] = (programNumber and 0x7F).toByte()
        copy[Pro800SysEx.ADDRESS_MSB_INDEX] = ((programNumber shr 7) and 0x7F).toByte()
        return copy
    }

    /**
     * Renaming a record that was truncated for a *shorter* name.
     *
     * The 109 record "Rand" is 155 dense bytes long - the instrument stops after the last meaningful
     * byte, eleven short of where the 16-wide name field ends. Writing "PatchPilot" into that
     * without growing the record first fails outright ("string at 150..165 runs past a 155-byte
     * record"). A fake built from full-length records could never catch this: it always has room.
     */
    @Test
    fun `a longer name fits a record that was truncated for a shorter one`() {
        val original = Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(bytes(Pro800Fixtures.DUMP_109_SHORT_NAME)))
        assertEquals("Rand", original.name)
        assertTrue("the record really is short", original.dense.size < Pro800ProgramFields.NAME_DENSE_END)

        val renamed = original.withName("PatchPilot")
        assertEquals("PatchPilot", renamed.name)
        // Growing must not disturb what came before the name.
        assertEquals(original.version, renamed.version)
        assertArrayEquals(
            original.dense.copyOf(Pro800ProgramFields.NAME_DENSE_OFFSET),
            renamed.dense.copyOf(Pro800ProgramFields.NAME_DENSE_OFFSET),
        )
    }

    /** And the reverse: a format-111 record has fields *after* the name, which must survive. */
    @Test
    fun `renaming a newer-format record preserves what follows the name`() {
        val original = Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(bytes(Pro800Fixtures.DUMP_B00)))
        val renamed = original.withName("Hi")
        assertEquals("Hi", renamed.name)
        assertEquals(original.dense.size, renamed.dense.size)
        assertArrayEquals(
            original.dense.copyOfRange(Pro800ProgramFields.NAME_DENSE_END, original.dense.size),
            renamed.dense.copyOfRange(Pro800ProgramFields.NAME_DENSE_END, renamed.dense.size),
        )
    }

    // ---- Status replies ----

    /**
     * **Both real codes, because reading the wrong byte passes with only one of them.**
     *
     * The status message has two parameter bytes and the code is the *second*, at offset 10.
     * Offset 9 is a constant `00` in both replies, so a check reading from 9 reports every status
     * as a success - including every refusal. A write answers `01 00 00`; a read of an
     * out-of-range address answers `01 00 01` - `isStatusOk` must tell these two apart, not just
     * recognize the first. A test carrying only the success case would still pass with the bug.
     */
    @Test
    fun `the status code is read from offset ten, not offset nine`() {
        val ok = bytes(Pro800Fixtures.STATUS_OK)
        val failure = bytes(Pro800Fixtures.STATUS_FAILURE)

        assertEquals(0, Pro800SysEx.statusCodeOf(ok))
        assertEquals(1, Pro800SysEx.statusCodeOf(failure))

        assertTrue(Pro800SysEx.isStatusOk(ok))
        assertFalse("a refusal must not read as success", Pro800SysEx.isStatusOk(failure))
        assertFalse(Pro800SysEx.isStatusFailure(ok))
        assertTrue(Pro800SysEx.isStatusFailure(failure))

        // The bug, stated so it cannot come back: the two differ *only* at offset 10.
        assertEquals(
            "offset 9 is identical in both, which is why reading it there sees nothing",
            ok[9], failure[9],
        )
        assertTrue("they differ at offset 10", ok[Pro800SysEx.STATUS_CODE_INDEX] != failure[Pro800SysEx.STATUS_CODE_INDEX])
    }

    /** Anything that is not a status reply has no status code - a dump must not look like one. */
    @Test
    fun `a dump is not mistaken for a status reply`() {
        assertNull(Pro800SysEx.statusCodeOf(bytes(Pro800Fixtures.DUMP_B00)))
        assertNull(Pro800SysEx.statusCodeOf(bytes(Pro800Fixtures.DUMP_EMPTY_REPLY)))
        assertFalse(Pro800SysEx.isStatusOk(bytes(Pro800Fixtures.DUMP_B00)))
        assertFalse(Pro800SysEx.isStatusFailure(bytes(Pro800Fixtures.DUMP_B00)))
    }

    // ---- Preset format version vs record length ----

    /**
     * **The version byte is a schema version, and growing a record must not outrun it.**
     *
     * Format 110 appends LFO Aftertouch Amount and 111 appends four more fields, all *after* the
     * name. A record carrying those bytes while still declaring 109 is one the instrument reads by
     * the wrong schema. The name field happens to end exactly one byte before format 110's first
     * addition, so renaming is safe - but that margin is one byte wide and worth pinning.
     */
    @Test
    fun `renaming a format 109 record never reaches a field only newer formats declare`() {
        val original = Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(bytes(Pro800Fixtures.DUMP_109_SHORT_NAME)))
        assertEquals(109, original.version)

        val renamed = original.withName("ABCDEFGHIJKLMNOP") // the full 16-character field
        assertEquals(109, renamed.version)
        assertEquals(
            "a format 109 record ends at the name field",
            Pro800ProgramFields.NAME_DENSE_END,
            renamed.dense.size,
        )
        // One byte short of where format 110's first appended field begins.
        assertEquals(
            Pro800ProgramCodec.denseIndexOf(190),
            renamed.dense.size,
        )
    }

    /**
     * **A spliced reply passes every check except its own length.**
     *
     * On a shared MIDI bus replies merge: under load one loses its `F7` and runs together with the
     * next, so the message carries our header, our type and our echoed address - its head genuinely
     * *is* our reply - with another record's tail. Roughly 1% of reads return a reply spliced from
     * two adjacent records rather than one clean record.
     *
     * This builds one from two sample dumps the same way a real splice forms, and pins both
     * halves of the point: the header, type and address checks all pass, and only the record's
     * length against its declared version gives it away.
     */
    @Test
    fun `a reply spliced from two real dumps passes the address check and fails on length`() {
        val a48 = bytes(Pro800Fixtures.DUMP_109_SHORT_NAME) // format 109
        val a00 = bytes(Pro800Fixtures.DUMP_B00) // format 111
        // The short 109 record with its terminator lost, running straight into B00's payload - the shape a splice
        // actually takes.
        val spliced = a48.copyOfRange(0, a48.size - 1) +
            a00.copyOfRange(Pro800SysEx.DATA_START_INDEX, a00.size)

        // Header, type and address all still say this is our reply; only the length betrays it.
        assertTrue("the manufacturer header survives a splice", Pro800SysEx.isOurs(spliced))
        assertEquals(Pro800SysEx.TYPE_DUMP, Pro800SysEx.typeOf(spliced))
        assertEquals(
            "the echoed address is the one we asked for - it arrives before the splice does",
            Pro800SysEx.addressOf(a48),
            Pro800SysEx.addressOf(spliced),
        )

        val corrupt = Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(spliced))
        assertEquals("the version byte comes from the genuine head", 109, corrupt.version)
        assertTrue(
            "a 109 record carrying a 111 record's tail outruns its own declared format",
            corrupt.outrunsDeclaredVersion,
        )
    }

    /**
     * **A short splice is the other half, and no length test can settle it.**
     *
     * Corrupt replies as short as 4, 7, 9 or 18 dense bytes are possible, some carrying a
     * plausible version byte of 109 when the splice happens to fall after the version field.
     * Neither the upper bound nor a version check sees those, and there is no floor to reject on:
     * across a sample of 102 records, an unnamed variant of 90 of them would end at dense 149 and
     * one at 85. So the record is *flagged* here and confirmed by a second read in
     * [Pro800Instrument].
     */
    @Test
    fun `a truncated record is flagged for confirmation rather than rejected`() {
        val a48 = Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(bytes(Pro800Fixtures.DUMP_109_SHORT_NAME)))
        val truncated = Pro800Program(a48.dense.copyOfRange(0, 7))
        assertEquals("the version byte survives a short splice", 109, truncated.version)
        assertFalse("nothing about its length outruns 109", truncated.outrunsDeclaredVersion)
        assertTrue("so only its shortness is suspicious", truncated.isSuspiciouslyShort)
    }

    /**
     * An unnamed format-111 record is full length, so it needs no confirming read.
     *
     * Not a safety proof - format 109 can arrive from a SysEx file or an older firmware, and an
     * unnamed 109 record *would* be short. What it shows is that the confirmation path stays cheap
     * in practice: records the instrument itself writes are format 111, which appends fields after
     * the name and so runs to full length whether or not a name was typed (measured on presets
     * stored from the panel without a name).
     */
    @Test
    fun `a preset saved unnamed is still full length and needs no confirming read`() {
        val unnamed = Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(bytes(Pro800Fixtures.DUMP_111_UNNAMED)))
        assertEquals(111, unnamed.version)
        assertNull("the name field is all zeros - this preset has no name", unnamed.name)
        assertEquals(
            "an unnamed 111 record is full length, not truncated",
            Pro800ProgramFields.maxDenseSizeFor(111),
            unnamed.dense.size,
        )
        assertFalse(unnamed.isSuspiciouslyShort)
        assertFalse(unnamed.outrunsDeclaredVersion)
    }

    /** Where the confirming read starts being asked for. */
    @Test
    fun `suspicion begins exactly below the start of the name field`() {
        val filler = ByteArray(Pro800ProgramFields.NAME_DENSE_OFFSET)
        Pro800ProgramCodec.writeValue(
            filler, Pro800ProgramFields.VERSION.denseOffset, Pro800ProgramFields.VERSION.byteCount, 109,
        )
        assertFalse(
            "a record reaching the name field is taken at face value",
            Pro800Program(filler).isSuspiciouslyShort,
        )
        assertTrue(
            "one byte shorter is confirmed by a second read",
            Pro800Program(filler.copyOfRange(0, filler.size - 1)).isSuspiciouslyShort,
        )
    }

    /** No false positives: a record within its own version's declared limit must never be flagged
     * as corrupt. */
    @Test
    fun `every record is within the length its version declares`() {
        listOf(
            "B00" to Pro800Fixtures.DUMP_B00,
            "109 long" to Pro800Fixtures.DUMP_109_LONG_NAME,
            "109 short" to Pro800Fixtures.DUMP_109_SHORT_NAME,
            "111 unnamed" to Pro800Fixtures.DUMP_111_UNNAMED,
        ).forEach { (label, hex) ->
            val program = Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(bytes(hex)))
            assertFalse(
                "$label is a real record and must not be read as corrupt",
                program.outrunsDeclaredVersion,
            )
        }
    }

    /** An empty slot is not a corrupt one - a zero-length record has no version to outrun. */
    @Test
    fun `an empty slot is never reported as outrunning its version`() {
        assertFalse(Pro800Program(ByteArray(0)).outrunsDeclaredVersion)
    }

    /** The declared boundaries match what the instrument actually returns. */
    @Test
    fun `the per-version record lengths match real records`() {
        val v109 = Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(bytes(Pro800Fixtures.DUMP_109_LONG_NAME)))
        val v111 = Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(bytes(Pro800Fixtures.DUMP_B00)))
        assertEquals(109, v109.version)
        assertEquals(111, v111.version)

        // The long-name record carries a 15-character name, so it is a full-length 109 record.
        assertEquals(Pro800ProgramFields.maxDenseSizeFor(109), v109.dense.size)
        assertEquals(Pro800ProgramFields.maxDenseSizeFor(111), v111.dense.size)
        assertTrue(
            "a newer format must allow a longer record",
            Pro800ProgramFields.maxDenseSizeFor(111) > Pro800ProgramFields.maxDenseSizeFor(109),
        )
    }

    /** A rename of a newer-format record must not shorten it either - its appended fields stay. */
    @Test
    fun `renaming a format 111 record keeps it at its own full length`() {
        val original = Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(bytes(Pro800Fixtures.DUMP_B00)))
        val renamed = original.withName("Hi")
        assertEquals(111, renamed.version)
        assertEquals(Pro800ProgramFields.maxDenseSizeFor(111), renamed.dense.size)
    }
}
