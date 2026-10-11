// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

import de.thewolfwalkexperience.software.patchpilot.catalog.hexToBytes
import java.io.File

/**
 * Real Axe-Fx III traffic, every byte captured from Axe-Edit III talking to a Mark I on firmware
 * 32.06. Nothing here is produced by the code under test.
 */
object AxeFx3Fixtures {

    /** Axe-Edit III selecting preset 356. */
    val SELECT_356 = "f0000174100127000000000064020000000000000055f7".hexToBytes()

    /** Selecting 464 while deleting it, and the acknowledgement. */
    val SELECT_464 = "f0000174100127000000000050030000000000000060f7".hexToBytes()
    val SELECT_ACK = "f0000174100127000000000000000000000000000033f7".hexToBytes()

    /** Store requests: 510 from a Save after a rename, 509 and 511 from a Save after a swap. */
    val STORE_509 = "f000017410012600000000007d03000000000000004cf7".hexToBytes()
    val STORE_510 = "f000017410012600000000007e03000000000000004ff7".hexToBytes()
    val STORE_511 = "f000017410012600000000007f03000000000000004ef7".hexToBytes()

    /** Storing 464 at the end of its delete, acknowledged 594 ms later. */
    val STORE_464 = "f0000174100126000000000050030000000000000061f7".hexToBytes()
    val STORE_ACK = "f0000174100126000000000000000000000000000032f7".hexToBytes()

    /** A dump read of 511, its acknowledgement and its end marker. */
    val DUMP_READ_REQUEST_511 = "f00001741003037f006af7".hexToBytes()
    val DUMP_READ_ACK_511 = "f00001741077037f0000011ff7".hexToBytes()
    val DUMP_END_511 = "f000017410797f08001bf7".hexToBytes()

    /** The same for 509. */
    val DUMP_READ_REQUEST_509 = "f00001741003037d0068f7".hexToBytes()
    val DUMP_READ_ACK_509 = "f00001741077037d0000011df7".hexToBytes()

    /** A dump write's begin, and the step acknowledgements, from a delete. */
    val DUMP_WRITE_BEGIN = "f000017410777f7f00000163f7".hexToBytes()
    val BEGIN_ACK = "f00001741064770006f7".hexToBytes()
    val CHUNK_ACK = "f00001741064780009f7".hexToBytes()
    val END_ACK = "f00001741064790008f7".hexToBytes()

    /** The firmware and model queries Axe-Edit III sends at startup. */
    val FIRMWARE_REQUEST = "f000017410081df7".hexToBytes()
    val CAPABILITIES_REQUEST = "f0000174104752f7".hexToBytes()

    /** The model record of a Mark I (fifth value 512). */
    val CAPABILITIES_REPLY_MARK_I = "f000017410475002001000101000000414f7".hexToBytes()

    /** The broadcast "who is there" Axe-Edit III opens with, answered by model `10`. */
    val WHO_IS_THERE = "f00001747f007af7".hexToBytes()
    val WHO_IS_THERE_REPLY = "f00001741064000071f7".hexToBytes()

    /** A rename request (`Key Reamp Wolfwalk123`). */
    val RENAME_TO_WOLFWALK_123 = (
        "f0000174100128000000000000000000000000200025592f1202494a61365c04053b3d58663b582d4659446433100804" +
            "020100402010080402004df7"
        ).hexToBytes()

    /** The same request for `Key Reamp Wolfwalk`. */
    val RENAME_TO_WOLFWALK = (
        "f0000174100128000000000000000000000000200025592f1202494a61365c04053b3d58663b582d4659004020100804" +
            "020100402010080402003ef7"
        ).hexToBytes()

    /** The firmware reply: 32.06, built Jun 25 2026 10:11:56. */
    val FIRMWARE_REPLY =
        "f00001741008200610011100004a756e20323520323032362031303a31313a353600000000000000000000000049f7".hexToBytes()

    /**
     * A chunk as the instrument sends it, whole (3,082 bytes): the first of [NEVER_USED_IMAGE], a
     * slot that never held a preset, so it carries no preset's data.
     */
    val REAL_CHUNK: ByteArray by lazy {
        NEVER_USED_IMAGE.copyOf(NEVER_USED_IMAGE.indexOf(0xF7.toByte()) + 1)
    }

    /**
     * The whole dump of a slot that never held a preset, as the instrument sent it - the one real
     * full image these tests use.
     */
    val NEVER_USED_IMAGE: ByteArray by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("axefx3/never_used_preset.hex")!!
            .bufferedReader().use { it.readText() }
        text.trim().hexToBytes()
    }

    /** The shipped delete image, read from `devices/blanks/` as the app's assets are. */
    val EMPTY_PRESET_IMAGE: ByteArray by lazy {
        val dir = System.getProperty("deviceCatalogDir")
            ?: error("deviceCatalogDir is not set; see the Test task config in app/build.gradle.kts")
        File(dir, "blanks/axefx3_empty_preset.bin").readBytes()
    }
}
