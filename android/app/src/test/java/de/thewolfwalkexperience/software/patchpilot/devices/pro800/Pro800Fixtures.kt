// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.pro800

/**
 * Sample program dumps and status messages covering the Pro-800 SysEx protocol's field-length
 * edge cases.
 *
 * Independently sourced rather than hand-written, because a fixture built to match what the code
 * already believes cannot expose a wrong belief: an empty-slot fixture built as "a short record"
 * only proves the code agrees with itself, a firmware fixture with its version bytes where the
 * code looks for them cannot catch an off-by-one, and a name fixture built from the production
 * offset constant cannot catch that the constant is wrong.
 *
 * The program records are the project's own. [DUMP_B00] is a preset saved on the instrument and
 * dumped with the Pro800 Manager Plugin. The other three are **derived** from it outside this
 * codebase, because the instrument writes only preset format 111 and every unnamed record on it
 * may be a copy of a factory one: [DUMP_111_UNNAMED] has the 16-byte name field zeroed;
 * [DUMP_109_SHORT_NAME] and [DUMP_109_LONG_NAME] have the fields format 110 and 111 append after
 * the name removed, the version byte set to 109, the name replaced, and the record ended one byte
 * after its last non-zero byte, which is where the instrument ends a record (measured: a
 * 4-character 109 name gives a 155-byte dense record, a 15-character one 166). The derived three
 * are the one place here where the bytes encode this project's own reading of the format rather
 * than the instrument's.
 *
 * The record shapes were chosen for what they disprove:
 *
 *  - [DUMP_B00] - the longest record modelled (210 bytes), preset format 111.
 *  - [DUMP_109_SHORT_NAME] - the shortest (190 bytes), format 109. **Records are variable
 *    length.** In format 109 the name is the last field and the record ends right after it, so the
 *    record length tracks the name: a 4-character name gives 190 bytes where a 15-character one
 *    gives 202.
 *  - [DUMP_111_UNNAMED] - a preset carrying **no name at all**, which must not read as an empty
 *    slot. Still full length, because format 111 adds fields *after* the name.
 *  - [DUMP_EMPTY_REPLY] - an address holding nothing: a bare `F0 F7`, and the only thing that
 *    actually means "empty".
 */
object Pro800Fixtures {

    /** Reply to the device-name query: `0x07` then ASCII "PRO-800". */
    const val DEVICE_NAME_REPLY = "f0002032000124000750524f2d38303000f7"

    /** Reply to the firmware query. The `00` at index 9 echoes the request parameter; the version
     * bytes `01 04 06` start at index 10, which is the off-by-one that reported 1.4.6 as garbage. */
    const val FIRMWARE_REPLY = "f0002032000124000900010406f7"

    /** Undocumented types that answer with a fixed payload nobody has decoded, recorded verbatim
     * in case that changes: `0x02` -> `03 00`, `0x04` -> `05` + ASCII "P0E9I". */
    const val TYPE_02_REPLY = "f0002032000124000300f7"
    const val TYPE_04_REPLY = "f00020320001240005503045394900f7"

    /** The settings block. Byte 10 of the decoded record held 4, i.e.
     * channel 3 - the setting whose being wrong made preset selection silently do
     * nothing. */
    const val SETTINGS_DUMP =
            "f000203200012400787e0301251661006f06004401047f04000030000401100103020006017f7f0c00000100" +
            "00010001003232000000000101f7"

    /** B00 "RandomTest", preset format 111, 210 bytes - the longest record on this instrument. */
    const val DUMP_B00 =
            "f00020320001240078640001251661006f00774b003433130031006923114900437f7f000056226a00000034" +
            "0000330025005a1500790066007300300000000017005f020021000000000100010001000000000000000101" +
            "00000100000001010202000400000000000000780100007f7f7f7f077f7f7f00000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000066" +
            "0000000000010052616e64006f6d54657374000000000000000000000003000060f7"

    /** Format 109 with the 4-character name "Rand", 190 bytes - the shortest. Derived from [DUMP_B00]; see the class note. */
    const val DUMP_109_SHORT_NAME =
            "f00020320001240078670001251661006d00774b003433130031006923114900437f7f000056226a00000034" +
            "0000330025005a1500790066007300300000000017005f020021000000000100010001000000000000000101" +
            "00000100000001010202000400000000000000780100007f7f7f7f077f7f7f00000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000066" +
            "0000000000010052616e640000f7"

    /**
     * Format 111 with **no name at all** - the 16-byte name field zeroed - and still full length,
     * because format 111 appends fields after the name. Derived from [DUMP_B00]; see the class note.
     */
    const val DUMP_111_UNNAMED =
            "f00020320001240078690001251661006f00774b003433130031006923114900437f7f000056226a00000034" +
            "0000330025005a1500790066007300300000000017005f020021000000000100010001000000000000000101" +
            "00000100000001010202000400000000000000780100007f7f7f7f077f7f7f00000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000066" +
            "000000000001000000000000000000000000000000000000000000000003000060f7"

    /** Format 109 with the 15-character name "RandomTest 109L", 202 bytes - a long name in the older format. Derived from [DUMP_B00]. */
    const val DUMP_109_LONG_NAME =
            "f00020320001240078680001251661006d00774b003433130031006923114900437f7f000056226a00000034" +
            "0000330025005a1500790066007300300000000017005f020021000000000100010001000000000000000101" +
            "00000100000001010202000400000000000000780100007f7f7f7f077f7f7f00000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000066" +
            "0000000000010052616e64006f6d5465737420003130394c00f7"


    /** The status a `0x78` write answers with - code 0, success. Measured for a store and an erase. */
    const val STATUS_OK = "f0002032000124000100" + "00f7"

    /** The status a read of an out-of-range address answers with - code 1, failure. */
    const val STATUS_FAILURE = "f0002032000124000100" + "01f7"

    /** An address holding nothing. */
    const val DUMP_EMPTY_REPLY = "f0f7"

    fun bytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
