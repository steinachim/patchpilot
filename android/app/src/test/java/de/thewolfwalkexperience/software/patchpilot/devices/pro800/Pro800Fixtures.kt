package de.thewolfwalkexperience.software.patchpilot.devices.pro800

/**
 * Sample program dumps and status messages covering the Pro-800 SysEx protocol's field-length
 * edge cases.
 *
 * **These exist because hand-written fixtures could not catch the bugs that mattered.** A fixture
 * built to match what the code already believes cannot expose a wrong belief: an empty-slot
 * fixture built as "a short record" only proves the code agrees with itself that short means
 * empty; a firmware fixture with its version bytes placed where the code looks for them cannot
 * catch an off-by-one in that placement; a name fixture built from the production offset constant
 * cannot catch that the constant is wrong. Independently-sourced byte sequences do not share the
 * code's assumptions, so they can actually fail when the code is wrong.
 *
 * The program records are the project's own presets. [DUMP_B00] is a preset saved on the
 * instrument and dumped with the Pro800 Manager Plugin; [DUMP_B01] and [DUMP_C11_UNNAMED] were
 * stored from the front panel without a name. The instrument writes only preset format 111, so the
 * two format-109 records, [DUMP_109_SHORT_NAME] and [DUMP_109_LONG_NAME], are **derived** from
 * [DUMP_B00] outside this codebase: the fields format 110 and 111 append after the name removed,
 * the version byte set to 109, the name replaced, and the record ended one byte after its last
 * non-zero byte, which is where the instrument ends a record. They are the one place here where
 * the bytes encode this project's own reading of the format rather than the instrument's.
 *
 * The record shapes were chosen for what they disprove:
 *
 *  - [DUMP_B00] - the longest record modelled (210 bytes), preset format 111.
 *  - [DUMP_109_SHORT_NAME] - the shortest (190 bytes), format 109. **Records are variable
 *    length.** In format 109 the name is the last field and the record ends right after it, so the
 *    record length tracks the name: a 4-character name gives 190 bytes where a 15-character one
 *    gives 202.
 *  - [DUMP_B01] - a preset carrying **no name at all**, which must not read as an empty slot.
 *    Still full length, because format 111 adds fields *after* the name.
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
     * in case that changes: `0x02` -> `03 00`, `0x04` -> `05` + ASCII "P0E9I". Both match what
     * `docs/Pro800SysExMessages.md` records. */
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

    /** B01, format 111, full length, and **no name**. */
    const val DUMP_B01 =
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

    /**
     * C11, format 111, 210 bytes - **saved from the front panel with no name entered**.
     *
     * The record that settles whether an unnamed preset can be shorter than the name field: it
     * cannot. The firmware writes format 111, which appends fields *after* the name, so the record
     * runs to full length with the name field simply left as zeros. Only factory presets are
     * format 109, and all of those are named. This is what makes
     * [Pro800Program.isSuspiciouslyShort]'s floor at the name field a safe threshold rather than
     * an arbitrary one.
     */
    const val DUMP_C11_UNNAMED =
            "f00020320001240078690001251661006f00774b003433130031006923114900437f7f000056226a00000034" +
            "0000330025005a1500790066007300300000000017005f020021000000000100010001000000000000000101" +
            "00000100000001010202000400000000000000780100007f7f7f7f077f7f7f00000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000066" +
            "000000000001000000000000000000000000000000000000000000000003000060f7"

    /** The status a `0x78` write answers with - code 0, success. Measured for a store and an erase. */
    const val STATUS_OK = "f0002032000124000100" + "00f7"

    /** The status a read of an out-of-range address answers with - code 1, failure. */
    const val STATUS_FAILURE = "f0002032000124000100" + "01f7"

    /** An address holding nothing. */
    const val DUMP_EMPTY_REPLY = "f0f7"

    fun bytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
