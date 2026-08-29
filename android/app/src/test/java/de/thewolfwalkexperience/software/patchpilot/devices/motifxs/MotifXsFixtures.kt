package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

/**
 * Realistic bulk-dump byte sequences, representative of what the instrument actually sends.
 *
 * **Sampled instrument data, not written to match the decoder.** A fixture built by encoding the
 * same belief as the code under test proves only that the two agree with each other, which is not
 * the same as either being right. These bytes are independent of the decoder, so a test that
 * agrees with them is testing the decoder rather than a shared assumption.
 *
 * The three voices were chosen for what they can disprove:
 *
 *  - [namedVoice] (`0C 0A 0F`, 1919 bytes) - an ordinary named voice.
 *  - [longNameVoice] (`0C 0B 2C`, 1925 bytes) - a 21-character name, longer than the Motif XS
 *    voice-name field is usually documented to be, which is why the decoder reads to a terminator
 *    rather than to a fixed width.
 *  - [drumVoice] (`0C 28 00`, 12622 bytes) - the size outlier, and the one that decides whether
 *    the framer is configured correctly.
 *  - [emptyVoice] (`0C 0A 14`, 1903 bytes) - a slot with **no printable run where the name goes**.
 *    Note that it is a full-length, checksum-valid dump: on this instrument every address answers,
 *    so "shorter than usual" is not a reliable empty signal, and a decoder that assumes otherwise
 *    can silently mis-detect real presets as empty.
 *
 * They live in `src/test/resources/motifxs/` because they are ~1.9 kB each. Inlined as hex they
 * would be 130 lines of Kotlin that nobody can read and a reviewer cannot check, which would
 * disguise their nature: these are recordings, not source.
 */
object MotifXsFixtures {

    /** `TWE2 Wolf Walk`, User bank 1 slot 16. */
    val namedVoice: ByteArray by lazy { load("voice_usr1_016.hex") }

    /** `TWE2 Dreadnought 2.0`, User bank 2 slot 45 - a full-width 20-character name. */
    val longNameVoice: ByteArray by lazy { load("voice_usr2_045.hex") }

    /**
     * Real instrument data covering figure-pair widths the other fixtures do not.
     *
     * [shortPrefixVoice] and [zeroPrefixVoice] have figure pairs narrower than `256:256:`
     * (`16:256:` and `0:0:`), so their names start at offsets 7 and 4 rather than 8 - the case
     * that made a third of the instrument decode wrong. [fullWidthNameVoice] is a name that fills
     * all 20 bytes and is followed by a printable byte, which a terminator-only read reports as
     * 21 characters.
     */
    val shortPrefixVoice: ByteArray by lazy { load("voice_usr3_105.hex") }
    val zeroPrefixVoice: ByteArray by lazy { load("voice_usr2_008.hex") }
    val fullWidthNameVoice: ByteArray by lazy { load("voice_usr3_010.hex") }

    /**
     * The three slots covering the trailing-character edge case.
     *
     * [drumKitVoice] and [kawalaVoice] name `Big Kit` (USER DR - B:15) and `Kawala`
     * (USER 3 - D:05); a naive printable-run read returns `Big Kitb` and `KawalaS` instead.
     * [genuineTrailingBVoice] is the counter-example that forbids any content-based fix:
     * `New Stab`'s `b` really is part of the name.
     */
    val drumKitVoice: ByteArray by lazy { load("voice_drum_031.hex") }
    val kawalaVoice: ByteArray by lazy { load("voice_usr3_053.hex") }
    val genuineTrailingBVoice: ByteArray by lazy { load("voice_usr2_117.hex") }

    /** User bank 1 slot 21, holding no name. */
    val emptyVoice: ByteArray by lazy { load("voice_usr1_021.hex") }

    /**
     * `Power Standard Kit 1`, drum slot 1 - **12,622 bytes**.
     *
     * Here because size is the thing it proves. Every drum voice is ~12.6 kB against a user
     * voice's ~1.9 kB, which is three times `SysExFramer.DEFAULT_MAX_MESSAGE_BYTES`; without a
     * larger framer all 32 of them are dropped as over-long and the last bank lists as empty.
     */
    val drumVoice: ByteArray by lazy { load("voice_drum_001.hex") }

    /** The Universal Device Inquiry reply. */
    const val IDENTITY_REPLY_HEX = "f07e7f060243004135060600007ff7"

    val identityReply: ByteArray get() = IDENTITY_REPLY_HEX.fromHex()

    private fun load(name: String): ByteArray {
        val text = javaClass.classLoader!!.getResourceAsStream("motifxs/$name")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("Missing fixture motifxs/$name")
        return text.fromHex()
    }
}

internal fun String.fromHex(): ByteArray {
    val cleaned = filterNot { it.isWhitespace() }
    require(cleaned.length % 2 == 0) { "hex has an odd number of digits" }
    return ByteArray(cleaned.length / 2) { cleaned.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
