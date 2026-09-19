// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

/**
 * Recorded bulk-dump byte sequences: what the instrument actually sends.
 *
 * **Sampled instrument data, not written to match the decoder.** A fixture built by encoding the
 * same belief as the code under test proves only that the two agree with each other, which is not
 * the same as either being right. These bytes are independent of the decoder, so a test that
 * agrees with them is testing the decoder rather than a shared assumption.
 *
 * Every voice here is one of the project's own user voices (`TWE2 ...`), read off a Motif XS6 over
 * the `0C` extension. The one drum dump is the initialized Drum Voice the app ships as a blank
 * (`devices/blanks/motifxs_blank_drum.bin`), framed as the `0C 28 00` reply the instrument
 * answers with; the framing was produced outside this codebase.
 *
 * They live in `src/test/resources/motifxs/` because they are ~1.9 kB each. Inlined as hex they
 * would be 130 lines of Kotlin that nobody can read and a reviewer cannot check, which would
 * disguise their nature: these are recordings, not source.
 */
object MotifXsFixtures {

    /** `TWE2 Wolf Walk`, USER 1 slot 16 (`0C 0A 0F`, 1919 bytes) - an ordinary named voice. */
    val namedVoice: ByteArray by lazy { load("voice_usr1_016.hex") }

    /**
     * `TWE2 Dreadnought 2.0`, USER 2 slot 45 (`0C 0B 2C`, 1925 bytes) - a name that fills all 20
     * bytes and is followed by a printable `S`, which a terminator-only read reports as 21
     * characters.
     */
    val longNameVoice: ByteArray by lazy { load("voice_usr2_045.hex") }

    /**
     * Figure-pair widths narrower than `256:256:`.
     *
     * [shortPrefixVoice] (`TWE2 Ashes`, USER 2 slot 2) opens `81:256:` and [zeroPrefixVoice]
     * (`TWE2 Capital Of Low`, USER 2 slot 8) opens `0:0:`, so their names start at offsets 7 and 4
     * rather than 8. [fullWidthNameVoice] (`TWE2 Blue Matter 2.0`, USER 2 slot 6) is a second
     * 20-character name, behind a two-digit figure.
     */
    val shortPrefixVoice: ByteArray by lazy { load("voice_usr2_002.hex") }
    val zeroPrefixVoice: ByteArray by lazy { load("voice_usr2_008.hex") }
    val fullWidthNameVoice: ByteArray by lazy { load("voice_usr2_006.hex") }

    /**
     * The trailing-character edge case.
     *
     * [overshootVoice] and [secondOvershootVoice] name `TWE2 Stranger` (USER 1 slot 13) and
     * `TWE2 Addicted` (USER 1 slot 34); a naive printable-run read returns `TWE2 StrangerS` and
     * `TWE2 AddictedS` instead. [genuineTrailingLetterVoice] is the counter-example that forbids
     * any content-based fix: `TWE2 Growing Pains`' final `s` really is part of the name.
     */
    val overshootVoice: ByteArray by lazy { load("voice_usr1_013.hex") }
    val secondOvershootVoice: ByteArray by lazy { load("voice_usr1_034.hex") }
    val genuineTrailingLetterVoice: ByteArray by lazy { load("voice_usr1_045.hex") }

    /** `TWE2 Actor On Ledge`, USER 2 slot 21 - two category assignments, `145:226:`. */
    val twoAssignmentVoice: ByteArray by lazy { load("voice_usr2_021.hex") }

    /** USER 1 slot 21, holding no name. */
    val emptyVoice: ByteArray by lazy { load("voice_usr1_021.hex") }

    /**
     * The initialized Drum Voice, framed as a `0C 28 00` dump - **12,600 bytes**.
     *
     * Here because size is the thing it proves. Every drum voice is ~12.6 kB against a user
     * voice's ~1.9 kB, which is three times `SysExFramer.DEFAULT_MAX_MESSAGE_BYTES`; without a
     * larger framer all 32 of them are dropped as over-long and the last bank lists as empty. Its
     * cleared name is followed by a stray printable `b`, the case the name decoder's repeat rule
     * exists for.
     */
    val drumVoice: ByteArray by lazy { load("voice_drum_blank.hex") }

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
