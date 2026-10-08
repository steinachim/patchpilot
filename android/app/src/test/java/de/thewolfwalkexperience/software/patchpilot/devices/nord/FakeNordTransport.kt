// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport

/**
 * A synthetic Nord instrument: answers every request [NordDevice] can send with a
 * protocol-correct reply built from [catalog], instead of talking to real hardware.
 *
 * Being a *wire-level* fake is what makes it valuable in a test: the real [NordDevice] parses
 * these bytes, so its framing, its cursor walk and its lock discipline are all genuinely
 * exercised.
 *
 * Structurally this is [ReplayTransport] with a live instrument's state machine instead of a
 * fixed script: it tracks which category is currently SELECT_CATEGORY-locked (mirroring the lock
 * a real instrument holds - see [NordDevice]'s class doc) since the bank/item cursor's requests
 * (sub-opcode 32/33) and the per-item fetch (30/31) don't carry a category index of their own -
 * only [NordDevice.FileTransferSubOp.SELECT_CATEGORY]'s request does.
 *
 * [NordDevice] accepts a reply only for its request's protocol and `sub-op + 1`, so this always
 * replies with exactly that and never needs to track more than the pending message and, for the
 * content database, the current lock.
 */
class FakeNordTransport(private val catalog: FakeNordCatalog = FakeNordCatalog()) : UsbBulkTransport {

    private var selectedCategoryIndex: Int = -1

    /** The (bank, item) the last select-preset loaded; null before any. */
    var selectedPreset: Pair<Int, Int>? = null
        private set

    override fun bulkWrite(data: ByteArray) {
        pending = parseMessage(data)
    }

    override fun bulkRead(bufferSize: Int): ByteArray {
        val msg = pending ?: error("FakeNordTransport.bulkRead() called with no pending request")
        return buildMessage(msg.protocolId, msg.protocolVersion, msg.subOp + 1, respond(msg))
    }

    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, length: Int) =
        byteArrayOf(
            (FakeNordProfile.FIRMWARE_VERSION and 0xFF).toByte(),
            ((FakeNordProfile.FIRMWARE_VERSION shr 8) and 0xFF).toByte(),
        )

    override val rebuildOnResume = true

    override fun close() {}

    private var pending: NordMessage? = null

    private fun respond(msg: NordMessage): ByteArray = when (msg.protocolId) {
        NordDevice.PROTOCOL_CTRL -> deviceInfoReply()
        NordDevice.PROTOCOL_UI -> capsReply(msg.subOp)
        NordDevice.PROTOCOL_FILE_TRANSFER -> dbReply(msg.subOp, msg.payload)
        else -> error("FakeNordTransport can't answer cmd=${msg.protocolId} sub-op=${msg.subOp}")
    }

    /**
     * The protocol version table NordDevice.connect() reads protocolVersionFileTransfer from - the only
     * field of this reply anything here consults.
     */
    private fun deviceInfoReply(): ByteArray {
        val pairs = listOf(
            NordDevice.PROTOCOL_UI to NordDevice.PROTOCOL_VERSION_UI,
            NordDevice.PROTOCOL_FILE_TRANSFER to DEMO_PROTOCOL_VERSION_FILE_TRANSFER,
        )
        return byteArrayOf(pairs.size.toByte()) +
            pairs.flatMap { (cmd, target) -> listOf(cmd.toByte(), target.toByte()) }.toByteArray()
    }

    private fun capsReply(subOp: Int): ByteArray = when (subOp) {
        NordDevice.UiSubOp.CAPABILITY_QUERY.code -> ByteArray(8)

        // No other UI sub-op is sent.
        else -> ByteArray(0)
    }

    private fun dbReply(subOp: Int, payload: ByteArray): ByteArray = when (subOp) {
        NordDevice.FileTransferSubOp.ROOT_CATEGORY_LIST.code -> rootCategoryListReply()

        NordDevice.FileTransferSubOp.GET_CATEGORY_CHILD.code -> categoryChildReply(readU32(payload, 0))

        NordDevice.FileTransferSubOp.SELECT_CATEGORY.code -> {
            selectedCategoryIndex = readU32(payload, 0)
            ByteArray(0)
        }

        NordDevice.FileTransferSubOp.UNLOCK_CATEGORY_SELECTION.code -> {
            selectedCategoryIndex = -1
            ByteArray(0)
        }

        NordDevice.FileTransferSubOp.GET_ITEM_COUNT.code -> itemCountReply(readU32(payload, 0))

        NordDevice.FileTransferSubOp.MOVE_PROGRAM.code -> moveReply(payload)

        NordDevice.FileTransferSubOp.COPY_PROGRAM.code -> copyReply(payload)

        NordDevice.FileTransferSubOp.DELETE_ITEM.code -> deleteReply(payload)

        NordDevice.FileTransferSubOp.SWAP_PROGRAMS.code -> swapReply(payload)

        NordDevice.FileTransferSubOp.SET_NAME.code -> setNameReply(payload)

        NordDevice.FileTransferSubOp.FETCH_ITEM.code -> fetchItemReply(payload)

        NordDevice.FileTransferSubOp.CURSOR_NEXT_ITEM.code -> cursorNextItemReply(payload)

        NordDevice.FileTransferSubOp.SELECT_PRESET.code -> selectPresetReply(payload)

        // Anything else: a zero status word and nothing more.
        else -> ByteArray(4)
    }

    /**
     * Sub-opcode 0/1: 4 unused bytes, a count byte, then per category a
     * length-prefixed name and a fixed trailer - the same shape
     * NordDeviceProtocolTest.buildRootCategoryListPayload() builds and round-trips in tests, with
     * the same trailer length (29, proven to detect uniquely there too).
     */
    private fun rootCategoryListReply(): ByteArray {
        val out = mutableListOf<Byte>()
        out += List(4) { 0.toByte() }
        out += catalog.rootCategoryNames.size.toByte()
        catalog.rootCategoryNames.forEach { name ->
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            out += u32(nameBytes.size).toList()
            out += nameBytes.toList()
            out += List(ROOT_CATEGORY_TRAILER_LEN) { 0.toByte() }
        }
        return out.toByteArray()
    }

    /**
     * Sub-opcode 2/3: status, echoed category index, a child count byte, then
     * per bank a length-prefixed letter and its occupied-slot count.
     */
    private fun categoryChildReply(categoryIndex: Int): ByteArray {
        val banks = catalog.itemsInCategory(categoryIndex).keys.map { it.bank }.distinct().sorted()
        val out = mutableListOf<Byte>()
        out += List(8) { 0.toByte() }
        out += banks.size.toByte()
        banks.forEach { bank ->
            val label = ('A' + bank).toString().toByteArray(Charsets.US_ASCII)
            val capacity = catalog.itemsInCategory(categoryIndex).keys.count { it.bank == bank }
            out += u32(label.size).toList()
            out += label.toList()
            out += u32(capacity).toList()
        }
        return out.toByteArray()
    }

    /**
     * Sub-opcode 8/9: status, item count, then free/used/reclaimable/unitCode. Always
     * answers unitCode=0 ("counted in bytes"), which is what tells
     * [NordDevice.calibrateStorageUnits] there is nothing to calibrate for a fake category - there
     * is no real allocation scheme behind this data to simulate.
     */
    private fun itemCountReply(categoryIndex: Int): ByteArray {
        val count = catalog.itemsInCategory(categoryIndex).size
        return u32(0) + u32(count) + u32(DEMO_FREE_BYTES) + u32(DEMO_USED_BYTES) + u32(0) + u32(0)
    }

    /**
     * Sub-opcode 24/25. *Assumed*, as no refusal of a move is recorded: an occupied destination
     * answers status 4, as a copy's does, and an empty source status 1, as a fetch of an empty
     * slot does.
     */
    private fun moveReply(payload: ByteArray): ByteArray {
        val srcBank = readU32(payload, 0)
        val srcItem = readU32(payload, 4)
        val dstBank = readU32(payload, 8)
        val dstItem = readU32(payload, 12)
        val status = when {
            !catalog.isOccupied(srcBank, srcItem) -> NordDevice.STATUS_NOT_FOUND
            catalog.isOccupied(dstBank, dstItem) -> NordDevice.STATUS_FILE_EXISTS
            else -> 0.also { catalog.move(srcBank, srcItem, dstBank, dstItem) }
        }
        return u32(status) + u32(srcBank) + u32(srcItem) + u32(dstBank) + u32(dstItem)
    }

    /**
     * Sub-opcode 22/23: [moveReply]'s shape, but the source stays. An occupied destination
     * answers status 4 ("file exists"), the refusal PROTOCOLS records; an empty source *assumed*
     * status 1, as for a move.
     */
    private fun copyReply(payload: ByteArray): ByteArray {
        val srcBank = readU32(payload, 0)
        val srcItem = readU32(payload, 4)
        val dstBank = readU32(payload, 8)
        val dstItem = readU32(payload, 12)
        val status = when {
            catalog.isOccupied(dstBank, dstItem) -> NordDevice.STATUS_FILE_EXISTS
            !catalog.isOccupied(srcBank, srcItem) -> NordDevice.STATUS_NOT_FOUND
            else -> 0.also { catalog.copy(srcBank, srcItem, dstBank, dstItem) }
        }
        return u32(status) + u32(srcBank) + u32(srcItem) + u32(dstBank) + u32(dstItem)
    }

    /**
     * Sub-opcode 20/21: status plus an echo of the slot, which NordDevice checks
     * against the one it asked for.
     */
    private fun deleteReply(payload: ByteArray): ByteArray {
        val bank = readU32(payload, 0)
        val item = readU32(payload, 4)
        catalog.delete(bank, item)
        return u32(0) + u32(bank) + u32(item)
    }

    /** Sub-opcode 26/27: both slots must hold a program; an empty one answers status 1, as PROTOCOLS records. */
    private fun swapReply(payload: ByteArray): ByteArray {
        val (srcBank, srcItem) = readU32(payload, 0) to readU32(payload, 4)
        val (dstBank, dstItem) = readU32(payload, 8) to readU32(payload, 12)
        if (!catalog.isOccupied(srcBank, srcItem) || !catalog.isOccupied(dstBank, dstItem)) {
            return u32(NordDevice.STATUS_NOT_FOUND)
        }
        catalog.swap(srcBank, srcItem, dstBank, dstItem)
        return u32(0)
    }

    private fun setNameReply(payload: ByteArray): ByteArray {
        val bank = readU32(payload, 0)
        val item = readU32(payload, 4)
        val nameLen = readU32(payload, 8)
        catalog.rename(bank, item, String(payload, 12, nameLen, Charsets.US_ASCII))
        return u32(0) + u32(bank) + u32(item)
    }

    /**
     * Sub-opcode 30/31: the name field NordDevice.parseItemName reads always sits at a
     * fixed offset (32/36), regardless of the record's content tag - so this only needs to fill
     * that much, unlike a real item record's other fields (data size, content id), which nothing
     * here reads (calibration is short-circuited by [itemCountReply]'s unitCode=0).
     *
     * An empty slot answers status 1 with the previous record's bytes after it, name included,
     * as a Stage 2 EX does - so a reader that skips the status shows a stale name.
     */
    private fun fetchItemReply(payload: ByteArray): ByteArray {
        val bank = readU32(payload, 0)
        val item = readU32(payload, 4)
        val name = catalog.itemsInCategory(selectedCategoryIndex)[FakeNordCatalog.Slot(bank, item)]
            ?: return u32(1) + lastRecord.copyOfRange(4, lastRecord.size)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val record = ByteArray(36)
        u32(bank).copyInto(record, 4)
        u32(item).copyInto(record, 8)
        u32(nameBytes.size).copyInto(record, 32)
        return (record + nameBytes).also { lastRecord = it }
    }

    /** The last record [fetchItemReply] served, whose bytes an empty slot's reply carries. */
    private var lastRecord = ByteArray(36)

    /**
     * Sub-opcode 32/33: the bank/item cursor. [prevItem] is -1 (0xFFFFFFFF) before the
     * first call in a bank - the walk asks for the smallest occupied item strictly greater than
     * it, exactly as the real cursor's "give me the next one" contract works.
     */
    private fun cursorNextItemReply(payload: ByteArray): ByteArray {
        val bank = readU32(payload, 0)
        val prevItem = readU32(payload, 4)
        val nextItem = catalog.itemsInCategory(selectedCategoryIndex).keys
            .filter { it.bank == bank && it.item > prevItem }
            .minOfOrNull { it.item }
        return if (nextItem == null) {
            u32(1) + ByteArray(8) // bank exhausted
        } else {
            u32(0) + ByteArray(4) + u32(nextItem)
        }
    }

    /**
     * Sub-opcode 47/48 (select/load) and the reload-for-display call inside rename/move/swap's
     * lock both send (bank, item) and expect the same triple echoed back with a zero flag.
     */
    private fun selectPresetReply(payload: ByteArray): ByteArray {
        selectedPreset = readU32(payload, 0) to readU32(payload, 4)
        return u32(0) + u32(readU32(payload, 0)) + u32(readU32(payload, 4))
    }

    private fun readU32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)

    private fun u32(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    companion object {
        /**
         * **10, and not an arbitrary number.** It is the file-transfer protocol version, so it has
         * to be one this app accepts - outside 3-10 the connection is refused - and it has to
         * agree with what this fake actually serves: [ROOT_CATEGORY_TRAILER_LEN] below is 29,
         * which is the version-10 layout.
         */
        private const val DEMO_PROTOCOL_VERSION_FILE_TRANSFER = 10
        private const val ROOT_CATEGORY_TRAILER_LEN = 29
        private const val DEMO_FREE_BYTES = 1_000_000
        private const val DEMO_USED_BYTES = 200_000
    }
}
