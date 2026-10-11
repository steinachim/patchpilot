// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import java.io.File

/**
 * Real Subsequent 37 traffic, firmware 1.2.0, in `src/test/resources/subsequent37/`. Nothing
 * here is produced by the code under test.
 *
 * The programs are scrubbed, in every file alike. Each program not named `Init Preset` is renamed
 * `Preset NNN` (NNN its program number), carries the panel's Initialize
 * (`devices/blanks/subsequent37_panel_initialized.bin`) in place of its sound bytes, keeps its
 * category and byte 51, and has its checksum closed again. The framing, the categories, byte 51,
 * the settings blocks and every `Init Preset` program are the instrument's own.
 */
object Subsequent37Fixtures {

    /** A complete bulk dump: 203,800 bytes, 256 programs, 89 of them named `Init Preset`. */
    val BULK_DUMP: ByteArray by lazy { load("subsequent37_bulk_dump_scrubbed.syx") }

    const val EMPTY_COUNT = 89

    /** The two replies to `06 01` for program 1, with program 0 selected: `04 07`, then `02 07`. */
    val READ_P001: List<ByteArray> by lazy { split(load("subsequent37_read_p001_scrubbed.syx")) }

    /** The same for program 255. */
    val READ_P255: List<ByteArray> by lazy { split(load("subsequent37_read_p255_scrubbed.syx")) }

    /** The vendor editor's rename of program 0 (`Preset 000` to `Preset 000  Test`), a `05 01`. */
    val WRITE_RENAME: ByteArray by lazy { load("subsequent37_write_rename_scrubbed.syx") }

    /** The rename back, byte-identical to program 0 in [BULK_DUMP]. */
    val WRITE_RENAME_BACK: ByteArray by lazy { load("subsequent37_write_rename_back_scrubbed.syx") }

    /** The Identity Reply: software revision `00 01 02 00`. */
    val IDENTITY_REPLY: ByteArray by lazy { load("subsequent37_identity_reply.syx") }

    /** Settings blocks with IN 1 / OUT 16 and with IN 3 / OUT 14, program 153 selected in both. */
    val SETTINGS_IN01_OUT16: ByteArray by lazy { load("subsequent37_settings_in01_out16.syx") }
    val SETTINGS_IN03_OUT14: ByteArray by lazy { load("subsequent37_settings_in03_out14.syx") }

    /** The panel's Initialize, as shipped in `devices/blanks/`. */
    val BLANK: ByteArray by lazy {
        val dir = System.getProperty("deviceCatalogDir")
            ?: error("deviceCatalogDir is not set; see the Test task config in app/build.gradle.kts")
        File(dir, "blanks/subsequent37_panel_initialized.bin").readBytes()
    }

    /** Program [number]'s 732 packed bytes in [BULK_DUMP]. */
    fun packedBlock(number: Int): ByteArray {
        val at = 23 + number * 796
        return BULK_DUMP.copyOfRange(at, at + 732)
    }

    /** The first [count] program numbers named `Init Preset` in [BULK_DUMP]. */
    fun emptyPrograms(count: Int): List<Int> =
        (0 until 256).filter { Subsequent37Program(unpackBlock(it)).isEmpty }.take(count)

    fun unpackBlock(number: Int): ByteArray = Subsequent37SysEx.unpack(packedBlock(number), 0, 244)!!

    /** Messages back to back in one file, split after each `F7`. */
    fun split(data: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var start = 0
        data.forEachIndexed { i, b ->
            if ((b.toInt() and 0xFF) == 0xF7) {
                out += data.copyOfRange(start, i + 1)
                start = i + 1
            }
        }
        return out
    }

    private fun load(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("subsequent37/$name")?.use { it.readBytes() }
            ?: error("Missing fixture subsequent37/$name")
}
