// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

private const val TAG = "NordDevice"

/**
 * Byte offsets into a content-database reply payload, one object per reply shape, since the same
 * offset means different things in different replies. Every field is a 4-byte big-endian word;
 * these tables are the ones in docs/PROTOCOLS.md.
 */
private object StatusEcho {
    /** Non-zero is a refusal; the instrument's own code, verbatim. */
    const val STATUS = 0
    const val BANK = 4
    const val ITEM = 8
}

/** The sub-opcode 30/31 reply: an item's metadata record, not its data. */
private object ItemRecord {
    const val BANK = 4
    const val ITEM = 8

    /** The exact byte length of the item's underlying blob. */
    const val DATA_SIZE = 12

    /** ASCII, e.g. `ngp `, `npno`, `nsmp` - what makes [CATEGORY_ID] meaningful or not. */
    const val CONTENT_TAG = 16

    /**
     * Tag-specific. A category id on an `ngp ` program record; two 2-byte halves on an `nsmp`
     * sample record, which is why it is only read once the tag has been checked.
     */
    const val CATEGORY_ID = 28
    const val NAME_LEN = 32

    /** Where the ASCII name itself starts - immediately after the [NAME_LEN] word. */
    const val NAME_START = 36
}

/** The sub-opcode 8/9 reply: six words, of which only the storage figures are trusted. */
private object ItemCountReply {
    const val STATUS = 0

    /**
     * Not the authority on how many items a category holds - the cursor is, and this can read 0
     * for a category that is not empty (observed on a Stage 2 EX). Used as an *upper* bound that
     * ends a walk early and as one term of its runaway guard, never as the count itself.
     */
    const val ITEM_COUNT = 4
    const val FREE = 8
    const val USED = 12
    const val RECLAIMABLE = 16
    const val UNIT_CODE = 20
}

/** The sub-opcode 32/33 reply: one step of a bank walk. */
private object CursorReply {
    const val BANK_EXHAUSTED = 0
    const val NEXT_ITEM = 8
}

/**
 * The instrument speaks a file-transfer protocol version this app does not.
 *
 * Its own type because it must not be confused with *failing to read* the version: the
 * instrument answered clearly, and the answer was a protocol revision this app has not decoded.
 */
class UnsupportedProtocolVersionException(message: String) : IllegalStateException(message)

/**
 * The USB protocol logic shared by every supported Nord instrument. [profile] supplies what
 * differs per model (USB ids, bank layout, name length, known firmware versions), from
 * devices/nord_devices.json or from [DeviceProfile.unknown] for an unrecognized device.
 *
 * Two values are read off the instrument rather than configured: [protocolVersionFileTransfer],
 * advertised in the device-info reply (10 on a Grand, 8 on a Stage 2 EX), and the root-category
 * trailer length, which follows from that version and is cross-checked against the reply (see
 * [rootCategoryTrailerLen]).
 *
 * [connect] sends only the device-info query. The vendor's editor also sends a capability query,
 * ENTER_STATUS_MODE and a content-database RESET, none of which is a prerequisite for anything
 * here. Each operation sends the requests it needs and unlocks whatever it locked.
 */
class NordDevice(private val transport: UsbBulkTransport, initialProfile: DeviceProfile) {

    /** The transport's own resume policy - see [de.thewolfwalkexperience.software.patchpilot
     * .core.Instrument.rebuildOnResume], which is what actually consults it. */
    val rebuildOnResume: Boolean get() = transport.rebuildOnResume

    /**
     * The per-instrument constants in force. Normally exactly what
     * devices/nord_devices.json declared - the catalog is the configuration, and this class does
     * not second-guess it. The one exception is [applyDerivedBankLayout], which an *unknown*
     * device uses to replace [DeviceProfile.unknown]'s guessed bank bounds with the
     * instrument's own; see there.
     */
    var profile: DeviceProfile = initialProfile
        private set

    val name: String get() = profile.name
    val vendorId: Int get() = profile.vendorId
    val productId: Int get() = profile.productId
    val maxBankLetter: Char get() = profile.maxBankLetter
    val maxGroup: Int get() = profile.maxGroup
    val slotsPerGroup: Int get() = profile.slotsPerGroup
    val supportedFirmwareVersions: Set<Int> get() = profile.supportedFirmwareVersions

    /**
     * The file-transfer protocol version, as read from the instrument by [connect]. `internal`
     * so tests can drive individual operations without running [connect].
     */
    internal var detectedProtocolVersionFileTransfer: Int? = null

    /**
     * Whether the UI protocol (6) is safe to speak. Set false by [checkProtocolVersions] when the
     * instrument reports a version this app was not written against - see [ui].
     */
    private var uiProtocolUsable = true
    private var uiProtocolWarned = false

    /** The version if known, for the parse paths, which must not throw before [connect] has run. */
    private val protocolVersionFileTransferOrNull: Int?
        get() = detectedProtocolVersionFileTransfer

    /** The protocol version every content-database request below carries in its header. */
    val protocolVersionFileTransfer: Int
        get() = detectedProtocolVersionFileTransfer ?: error(
            "$name's file-transfer protocol version is unknown - connect() hasn't read the " +
                "device-info protocol version table yet, and no profile declares one.",
        )

    var firmwareVersion: Int = 0
        private set

    /** The first message of every session; its reply is where [protocolVersionFileTransfer] comes from. */
    enum class CtrlSubOp(val code: Int) {
        DEVICE_INFO_QUERY(2), // bare request -> protocol version table (2/3)
    }

    /**
     * The one UI-protocol request this app sends. The protocol's other sub-opcodes toggle the
     * instrument's "status message" display mode, which also inhibits playing, and are not used.
     */
    enum class UiSubOp(val code: Int) {
        CAPABILITY_QUERY(4), // protocol/capability version block (4/5)
    }

    /**
     * ROOT_CATEGORY_LIST and GET_CATEGORY_CHILD need no prerequisites. SELECT_CATEGORY locks the
     * instrument's panel, and only UNLOCK_CATEGORY_SELECTION releases it. RESET is kept for
     * reference and never sent.
     */
    enum class FileTransferSubOp(val code: Int) {
        ROOT_CATEGORY_LIST(0), // get root category list (0/1)
        GET_CATEGORY_CHILD(2), // get bank/child name by index (2/3)
        SELECT_CATEGORY(4), // select a category by root-list index; locks the instrument (4/5)
        UNLOCK_CATEGORY_SELECTION(6), // unlocks the instrument after SELECT_CATEGORY (6/7)
        GET_ITEM_COUNT(8), // item count for the selected category (8/9)
        DELETE_ITEM(20), // delete the item at (bank, item), leaving the slot empty (20/21)
        COPY_PROGRAM(22), // copy a program to an *empty* (bank, item), source kept (22/23)
        MOVE_PROGRAM(24), // move a program to an *empty* (bank, item) (24/25)
        SWAP_PROGRAMS(26), // swap the programs at two *occupied* (bank, item) positions (26/27)
        SET_NAME(28), // rename a preset by (bank, item) (28/29)
        FETCH_ITEM(30), // fetch the item record for (bank, item) (30/31)
        CURSOR_NEXT_ITEM(32), // nearest occupied item past (bank, item); stateless, third field is a direction (32/33)
        GET_DEPENDENCY(40), // an item's dependency list: the piano and sample library it needs (40/41)
        ENABLE_INVALIDATION(45), // enable invalidation (45/46); the reply is an unexamined ack
        SELECT_PRESET(47), // select/load a preset by (bank, item) (47/48)
        SET_CATEGORY(51), // set a preset's category tag by (bank, item) (51/52)
        RESET(57), // reset (57/58); reply is always 4 zero bytes, nothing needs it
        QUERY_CONTENT_VERSION(61), // query content version (61/62); reply is 4 zero bytes
    }

    data class BankItem(val bank: Int, val item: Int)
    /** [categoryId] is null for a record carrying no program category - see [parseItemCategory]. */
    data class NamedItem(val presetId: String, val name: String, val categoryId: Int? = null)

    /**
     * Reads the firmware version, raises [firmwareAdvisory] if it is not in
     * [supportedFirmwareVersions] (an empty set skips the check - see [DeviceProfile.unknown]),
     * then reads the protocol version table to learn [protocolVersionFileTransfer]. The only
     * thing that has to happen before every other method can be called.
     */
    suspend fun connect() {
        // See [UsbBulkTransport.drainInput], and [request] for the tail the drain can miss.
        withContext(Dispatchers.IO) { transport.drainInput() }
        firmwareVersion = getFirmwareVersion()
        validateFirmwareVersion()
        detectedProtocolVersionFileTransfer = resolveProtocolVersionFileTransfer()
    }

    /**
     * The file-transfer protocol version, read from the instrument. No catalog value stands in
     * for it: the version selects the wire format, and a declared value could only agree with
     * what is read or parse the writes against the wrong layout. If it cannot be read, [connect]
     * fails.
     */
    private suspend fun resolveProtocolVersionFileTransfer(): Int =
        try {
            detectProtocolVersionFileTransfer()
        } catch (exc: UnsupportedProtocolVersionException) {
            throw exc
        } catch (exc: CancellationException) {
            // Backing out of a connect is not a failure to report.
            throw exc
        } catch (exc: Exception) {
            throw IllegalStateException(
                "Couldn't read the file-transfer protocol version from $name (${exc.message}). " +
                    "That query is the first bulk message of every session and needs no " +
                    "prerequisite, so failing it means something more basic is wrong than this " +
                    "app can work around - refusing to continue rather than guessing at a " +
                    "version. If the app was closed or killed while it was talking to the " +
                    "instrument, unplug the USB cable, plug it back in and try again.",
                exc,
            )
        }

    // ---- Device info / command protocol version table ----

    /** Asks the instrument which version each protocol runs at; [parseProtocolVersions] decodes the reply. */
    suspend fun getProtocolVersions(): Map<Int, Int> {
        val payload = request(PROTOCOL_CTRL, PROTOCOL_VERSION_CTRL, CtrlSubOp.DEVICE_INFO_QUERY.code).payload
        return parseProtocolVersions(payload)
    }

    /** Reads the file-transfer protocol version off the instrument itself. */
    suspend fun detectProtocolVersionFileTransfer(): Int {
        val versions = getProtocolVersions()
        checkProtocolVersions(versions)
        return versions[PROTOCOL_FILE_TRANSFER] ?: throw NordProtocolException(
            "device-info reply advertises no version for the file-transfer protocol " +
                "($PROTOCOL_FILE_TRANSFER); it lists $versions",
        )
    }

    /**
     * Logs anything in the version table this app was not written against, and refuses a
     * file-transfer version outside the range every reply layout was decoded in. UI (6) and
     * Ctrl (7) only warn: this app sends one UI request (the device report's capability query),
     * and Ctrl carries the version table itself.
     */
    fun checkProtocolVersions(versions: Map<Int, Int>) {
        listOf(
            Triple(PROTOCOL_UI, EXPECTED_PROTOCOL_VERSION_UI, "UI"),
            Triple(PROTOCOL_CTRL, EXPECTED_PROTOCOL_VERSION_CTRL, "Ctrl"),
        ).forEach { (protocolId, expected, label) ->
            val actual = versions[protocolId]
            if (actual != null && actual != expected) {
                Log.w(
                    TAG,
                    "$name reports $label protocol (id $protocolId) at version $actual; this app " +
                        "was written against version $expected. Anything using it may be wrong.",
                )
                // Stop speaking it rather than guess at a layout that may have moved - see [ui].
                if (protocolId == PROTOCOL_UI) uiProtocolUsable = false
            }
        }
        val fileTransfer = versions[PROTOCOL_FILE_TRANSFER]
        if (fileTransfer != null &&
            fileTransfer !in MIN_FILE_TRANSFER_VERSION..MAX_KNOWN_FILE_TRANSFER_VERSION
        ) {
            throw UnsupportedProtocolVersionException(
                "$name reports file-transfer protocol version $fileTransfer, outside the " +
                    "$MIN_FILE_TRANSFER_VERSION-$MAX_KNOWN_FILE_TRANSFER_VERSION range this app accepts. Refusing to continue: every response layout this app " +
                    "knows was decoded from that range, and a protocol revision outside it can " +
                    "differ in ways nothing here would detect - including on the writes that " +
                    "move, rename and delete presets.",
            )
        }
    }

    /** Set by [validateFirmwareVersion] where the firmware is untested; surfaced as
     * [de.thewolfwalkexperience.software.patchpilot.core.Instrument.advisory]. */
    var firmwareAdvisory: String? = null
        private set

    private fun validateFirmwareVersion() {
        // An empty set (DeviceProfile.unknown) skips the check.
        if (supportedFirmwareVersions.isNotEmpty() && firmwareVersion !in supportedFirmwareVersions) {
            val supported = supportedFirmwareVersions.sorted().joinToString(", ") { formatFirmwareVersion(it) }
            // Warn, do not refuse - see Instrument.advisory.
            val advisory =
                "$name reports firmware version ${formatFirmwareVersion(firmwareVersion)}, which " +
                    "this app has not been tested against (tested: $supported). The app might " +
                    "still work, but it is not guaranteed that programs will be read or written " +
                    "correctly. Please consider sending in a device report, so support for this " +
                    "firmware can be added in future."
            firmwareAdvisory = advisory
            Log.w(TAG, advisory)
        }
    }

    /** Raw USB teardown only; every operation that locks the instrument unlocks it itself. */
    fun close() = transport.close()

    private suspend fun request(
        protocolId: Int,
        protocolVersion: Int,
        subOp: Int,
        payload: ByteArray = ByteArray(0),
    ): NordMessage = withContext(Dispatchers.IO) {
        // This protocol answers one reply per request, so anything still buffered is stale.
        if (readBuffer.isNotEmpty()) {
            Log.w(TAG, "discarding ${readBuffer.size} unread bytes left over from an earlier " +
                "exchange before sending protocol=$protocolId version=$protocolVersion sub-op=$subOp")
            readBuffer = ByteArray(0)
        }
        transport.bulkWrite(buildMessage(protocolId, protocolVersion, subOp, payload))
        // A reply answers its request's sub-opcode with the next number up. A mismatch is the
        // tail of a reply a killed session left queued, which [connect]'s drain can miss; the
        // real answer is still to come. Bounded, so a device that is out of step fails rather
        // than loops.
        var reply = readReply()
        var stale = 0
        while (reply.protocolId != protocolId || reply.subOp != subOp + 1) {
            if (++stale > MAX_STALE_REPLIES) {
                throw NordProtocolException(
                    "the instrument answered protocol=$protocolId sub-op=$subOp with a reply for " +
                        "protocol=${reply.protocolId} sub-op=${reply.subOp} $stale times in a row; " +
                        "its replies are out of step with this session's requests",
                )
            }
            Log.w(TAG, "discarding a stale reply (protocol=${reply.protocolId} " +
                "sub-op=${reply.subOp}, ${reply.payload.size} bytes) while waiting for the answer " +
                "to protocol=$protocolId sub-op=$subOp")
            reply = readReply()
        }
        reply
    }

    /** A request on [PROTOCOL_FILE_TRANSFER], the content database, at the version the instrument negotiated. */
    private suspend fun fileTransfer(
        subOp: FileTransferSubOp,
        payload: ByteArray = ByteArray(0),
    ): NordMessage =
        request(PROTOCOL_FILE_TRANSFER, protocolVersionFileTransfer, subOp.code, payload)

    /**
     * A request on [PROTOCOL_UI], the instrument's status display. Sends nothing and returns null
     * when the instrument reports a UI protocol version this app was not written against: the
     * protocol's other sub-opcodes lock the display and inhibit playing, and the one request this
     * app sends on it (the device report's capability query) is not worth guessing at a layout.
     */
    private suspend fun ui(
        subOp: UiSubOp,
        payload: ByteArray = ByteArray(0),
    ): NordMessage? {
        if (!uiProtocolUsable) {
            if (!uiProtocolWarned) {
                uiProtocolWarned = true
                Log.w(
                    TAG,
                    "Not using $name's status display - it reports a UI protocol version this app " +
                        "was not written against. Everything else works normally.",
                )
            }
            return null
        }
        return request(PROTOCOL_UI, PROTOCOL_VERSION_UI, subOp.code, payload)
    }

    /** Bytes read off the IN endpoint but not yet consumed as a whole message - see [readReply]. */
    private var readBuffer = ByteArray(0)

    /**
     * Reads and decodes one reply message, reassembling it across bulk reads if it arrives in
     * pieces and keeping any surplus for the next call. A bulk read carries whatever bytes have
     * arrived, less or more than one message; the message's own length field says where it ends.
     */
    internal suspend fun readReply(): NordMessage {
        var emptyReads = 0
        while (true) {
            // Nothing else in this loop suspends, so this is what lets cancellation end a read.
            currentCoroutineContext().ensureActive()

            takeBufferedMessage()?.let { return it }

            val chunk = transport.bulkRead(READ_BUFSIZE)
            if (chunk.isEmpty()) {
                // A zero-length transfer is a legitimate USB reply that makes no progress. Bounded
                // rather than refused, since a stray one between the pieces of a split reply is
                // not an error.
                if (++emptyReads >= MAX_EMPTY_READS) {
                    val buffered = readBuffer.size
                    readBuffer = ByteArray(0)
                    error(
                        "the instrument returned $emptyReads empty replies in a row with " +
                            "$buffered bytes buffered; treating the endpoint as dead",
                    )
                }
                continue
            }
            emptyReads = 0
            readBuffer += chunk
        }
    }

    /**
     * One whole message off the front of [readBuffer], or null while it holds less than the
     * leading length field promises.
     */
    private fun takeBufferedMessage(): NordMessage? {
        if (readBuffer.size < MESSAGE_HEADER_LEN) return null
        val totalLen = ByteBuffer.wrap(readBuffer, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        if (totalLen !in (MESSAGE_HEADER_LEN + MESSAGE_CRC_LEN)..MAX_MESSAGE_LEN) {
            // A garbage length would otherwise be waited on forever, one timeout at a time.
            readBuffer = ByteArray(0)
            throw NordProtocolException(
                "reply declares an unusable length of $totalLen bytes; discarding the buffer",
            )
        }
        if (readBuffer.size < totalLen) return null
        val message = readBuffer.copyOfRange(0, totalLen)
        readBuffer = readBuffer.copyOfRange(totalLen, readBuffer.size)
        return parseMessage(message)
    }

    // ---- Firmware version ----

    suspend fun getFirmwareVersion(): Int = withContext(Dispatchers.IO) {
        val raw = transport.controlTransfer(
            FIRMWARE_CTRL_BM_REQUEST_TYPE, FIRMWARE_CTRL_BREQUEST, 0, 0, FIRMWARE_CTRL_WLENGTH,
        )
        (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8) // little-endian u16
    }

    fun formatFirmwareVersion(code: Int): String {
        val major = code / 100
        val minor = code % 100
        return "$major.${minor.toString().padStart(2, '0')}"
    }

    // ---- Content database ----

    /**
     * Best-effort UNLOCK_CATEGORY_SELECTION (sub-op 6/7), clearing the lock SELECT_CATEGORY
     * leaves the instrument in. Called from [withCategory]'s `finally`, so a failure here must
     * never mask the operation's own exception, and under [NonCancellable], so a cancelled caller
     * still unlocks.
     */
    private suspend fun unlockCategorySelection() {
        withContext(NonCancellable) {
            try {
                fileTransfer(FileTransferSubOp.UNLOCK_CATEGORY_SELECTION)
            } catch (e: Exception) {
                Log.w(TAG, "Unlocking the instrument after a category selection did not complete cleanly", e)
            }
        }
    }

    /**
     * Re-selects a preset purely to make the front-panel display redraw its name and category.
     * Called inside the SELECT_CATEGORY lock a write already holds: on a Nord Stage 2 EX no
     * write refreshes the display on its own, and a SELECT_PRESET outside the lock does nothing
     * there (on a Grand it changes the active preset).
     */
    private suspend fun reloadPresetForDisplay(bank: Int, item: Int) {
        val payload = fileTransfer(FileTransferSubOp.SELECT_PRESET, uint32BE(bank) + uint32BE(item)).payload
        requireEcho("display-refresh", payload, bank, item)
    }

    /**
     * Decodes the sub-opcode 0/1 response into a list of category names. A zero-category list is
     * answered as empty; a payload no trailer length fits throws.
     */
    fun parseRootCategoryList(payload: ByteArray): List<String> =
        parseRootCategories(payload).map { it.name }

    /**
     * The trailer length to parse this response with, or null if nothing settles it: the length
     * the protocol version implies, if it walks the response; otherwise the single length in
     * 0..128 that does ([detectRootCategoryTrailerLen]), with a warning where the version's
     * length did not fit.
     */
    fun rootCategoryTrailerLen(payload: ByteArray): Int? {
        val version = protocolVersionFileTransferOrNull
        val expected = rootCategoryTrailerLenForVersion(version)
        if (expected != null && rootCategoryListFits(payload, expected)) return expected
        val detected = detectRootCategoryTrailerLen(payload)
        if (expected != null && detected != null) {
            Log.w(
                TAG,
                "$name reports file-transfer protocol version $version, which implies a " +
                    "$expected-byte root-category trailer, but this response only parses with " +
                    "$detected. Using $detected - the version-implied length does not hold for " +
                    "this instrument.",
            )
        }
        return detected
    }

    /**
     * [parseItemContentId], with the protocol version as a cross-check: the record's own length
     * decides, and a contradiction with the version is logged.
     */
    fun itemContentId(payload: ByteArray): Int? {
        val contentId = parseItemContentId(payload)
        val expected = itemRecordHasContentId(protocolVersionFileTransferOrNull)
        if (expected != null && expected != (contentId != null)) {
            Log.w(
                TAG,
                "$name reports file-transfer protocol version " +
                    "$protocolVersionFileTransferOrNull, so its item records " +
                    (if (expected) "should" else "should not") + " carry a content id, " +
                    "but this ${payload.size}-byte record " +
                    (if (expected) "has none." else "has one."),
            )
        }
        return contentId
    }

    /**
     * The same sub-opcode 0/1 response, decoded into [RootCategory] entries that keep each
     * category's **allocation unit** as well as its name - the first word of its trailer.
     *
     * This is where the storage unit comes from: the instrument states it outright and exactly,
     * so no catalog value carries one. A unit fitted from an area's item sizes instead can land on
     * a rounder but wrong figure - 131,072 for a Nord Grand's `Piano` where the instrument says
     * 130,816, leaving 33 of its 15,427 units unexplained - which is why the fit in
     * [calibrateStorageUnits] is a cross-check on this and not a source.
     *
     * A byte-counted area reports 1, so callers need no special case.
     */
    fun parseRootCategories(payload: ByteArray): List<RootCategory> {
        val count = if (payload.size >= 5) payload[4].toInt() and 0xFF else 0
        val trailerLen = rootCategoryTrailerLen(payload) ?: if (count == 0) {
            return emptyList()
        } else {
            throw NordProtocolException(
                "Couldn't determine the root-category trailer length from this " +
                    "${payload.size}-byte response, which claims $count categories - no trailer " +
                    "length walks it and lands on its end.",
            )
        }

        val categories = mutableListOf<RootCategory>()
        var off = 5
        repeat(count) {
            val nameLen = readUInt32BE(payload, off)
            off += 4
            val name = String(payload, off, nameLen, Charsets.US_ASCII)
            off += nameLen
            val unit = if (trailerLen >= 4) readUInt32BE(payload, off) else null
            off += trailerLen
            categories += RootCategory(name, unit)
        }
        return categories
    }

    /**
     * Decodes a sub-opcode 31 item record's name field. The
     * length-prefixed name always sits at the same offset regardless of
     * the record's 4-byte content tag (`ngp`, `npno`, `nsmp`, ...).
     */
    fun parseItemName(payload: ByteArray): String {
        require(payload.size >= ItemRecord.NAME_START) {
            "item record is only ${payload.size} bytes, too short for a name length field at offset ${ItemRecord.NAME_LEN}"
        }
        val nameLen = readUInt32BE(payload, ItemRecord.NAME_LEN)
        require(nameLen in 0..MAX_ITEM_NAME_LEN && ItemRecord.NAME_START + nameLen <= payload.size) {
            "implausible item name length $nameLen in a ${payload.size}-byte record"
        }
        return sanitizeDeviceText(String(payload, ItemRecord.NAME_START, nameLen, Charsets.US_ASCII))
    }

    /**
     * A program record's category tag id, or null where this record does not carry one: the
     * field sub-opcode 51 writes ([setPresetCategory]), already in the record the listing
     * fetches for every row.
     *
     * Gated on the content tag. Offset 28 is tag-specific: on an `nsmp` sample record it is two
     * 2-byte halves, a (category, sub-category) pair, so a record that is not `ngp` answers null.
     */
    fun parseItemCategory(payload: ByteArray): Int? {
        if (payload.size < 32) return null
        val tag = String(payload, ItemRecord.CONTENT_TAG, 4, Charsets.US_ASCII)
        if (tag != PROGRAM_CONTENT_TAG) return null
        return readUInt32BE(payload, ItemRecord.CATEGORY_ID)
    }

    /**
     * Strips control characters from a name the instrument chose: US-ASCII decoding already
     * replaces anything above 0x7F, but NUL, CR and LF would pass through into the browser, the
     * report JSON and logcat. Strips rather than rejects, so one odd byte does not fail a listing.
     */
    private fun sanitizeDeviceText(raw: String): String =
        raw.filter { it.code in 0x20..0x7E }

    fun collectItemNames(itemPayloads: List<ByteArray>): List<NamedItem> = itemPayloads.map { payload ->
        val bank = readUInt32BE(payload, ItemRecord.BANK)
        val item = readUInt32BE(payload, ItemRecord.ITEM)
        NamedItem(formatPresetId(bank, item), parseItemName(payload), parseItemCategory(payload))
    }

    /**
     * Checks the `(status, bank, item)` triple most write sub-opcodes answer with: a non-zero
     * status is a refusal, and an echo that does not match means the instrument acted on a
     * different slot than the one asked for.
     */
    private fun requireEcho(what: String, payload: ByteArray, bank: Int, item: Int) {
        val status = readUInt32BE(payload, StatusEcho.STATUS)
        val echoBank = readUInt32BE(payload, StatusEcho.BANK)
        val echoItem = readUInt32BE(payload, StatusEcho.ITEM)
        if (status != 0 || echoBank != bank || echoItem != item) {
            throw NordStatusException(
                what, status,
                "Unexpected $what response: status=$status bank=$echoBank item=$echoItem " +
                    "(requested bank=$bank item=$item)",
            )
        }
    }

    /**
     * Selects [categoryIndex], runs [block], and always unlocks afterwards: a lock left standing
     * makes the instrument's own front panel unresponsive until the session ends.
     */
    private suspend fun <T> withCategory(categoryIndex: Int, block: suspend () -> T): T {
        fileTransfer(FileTransferSubOp.SELECT_CATEGORY, uint32BE(categoryIndex))
        return try {
            block()
        } finally {
            unlockCategorySelection()
        }
    }

    /** [withCategory] against the `Program` category; the index is looked up before the lock is taken. */
    private suspend fun <T> withProgramCategory(block: suspend () -> T): T =
        withCategory(getProgramCategoryIndex(), block)

    /**
     * Selects a root-list category by index, then walks its full item list with the bank/item
     * cursor (sub-opcode 32/33) and a per-item fetch (30/31), returning every item's raw payload
     * in order.
     *
     * The item count is an upper bound, not the loop condition: a Nord Stage 2 EX's `Live` and
     * `Settings` categories report 0 while holding items. The walk ends on whichever comes first
     * - a non-zero count reached, a bank that reports itself exhausted having yielded nothing, or
     * running out of banks, bounded by [categoryBankCount] (the category's own child list, not
     * [maxBankLetter], which describes the `Program` area alone). A non-zero count the cursor
     * then fails to deliver is an error.
     */
    suspend fun fetchCategoryItems(categoryIndex: Int): List<ByteArray> {
        // Asked before anything is selected; the child list needs no prerequisites.
        val bankCount = categoryBankCount(categoryIndex)

        return withCategory(categoryIndex) {
            val countPayload = fileTransfer(FileTransferSubOp.GET_ITEM_COUNT, uint32BE(categoryIndex)).payload
            if (countPayload.size < 8) {
                throw NordProtocolException(
                    "Item-count reply for category $categoryIndex is ${countPayload.size} bytes, " +
                        "too short for a count field at offset ${ItemCountReply.ITEM_COUNT}",
                )
            }
            val totalCount = readUInt32BE(countPayload, ItemCountReply.ITEM_COUNT)

            // With no child list the bank count is unknown; the instrument ends the walk, with
            // FALLBACK_MAX_BANKS as a runaway guard only.
            val maxBanks = bankCount ?: FALLBACK_MAX_BANKS

            val items = mutableListOf<ByteArray>()
            var bank = 0
            var prevItem = -1 // 0xFFFFFFFF on the wire: the cursor's "start of bank" value
            var itemsInBank = 0
            val safetyLimit = maxOf(totalCount, 1) * 4 + maxBanks * 8 + 32
            var steps = 0

            while (bank < maxBanks) {
                if (totalCount != 0 && items.size >= totalCount) break

                steps++
                if (steps > safetyLimit) {
                    throw NordProtocolException(
                        "Giving up after $steps cursor steps with ${items.size} items " +
                            "(count field said $totalCount) - protocol desync?",
                    )
                }

                val cursorPayload = fileTransfer(
                                        FileTransferSubOp.CURSOR_NEXT_ITEM,
                                        uint32BE(bank) + uint32BE(prevItem) + uint32BE(0),
                                    ).payload
                val bankExhausted = readUInt32BE(cursorPayload, CursorReply.BANK_EXHAUSTED)
                val nextVal = readUInt32BE(cursorPayload, CursorReply.NEXT_ITEM)

                if (bankExhausted != 0) {
                    // A "no more items in this bank" flag, not the next bank's number: the host
                    // tries `bank + 1` next. An empty bank ends the category unless a non-zero
                    // count says more is to come (a leading empty bank would otherwise cut the
                    // walk short).
                    if (itemsInBank == 0 && !(totalCount != 0 && items.size < totalCount)) break
                    bank += 1
                    prevItem = -1
                    itemsInBank = 0
                    continue
                }

                val itemIndex = nextVal
                val itemPayload = fileTransfer(FileTransferSubOp.FETCH_ITEM, uint32BE(bank) + uint32BE(itemIndex)).payload
                items += itemPayload
                prevItem = itemIndex
                itemsInBank++
            }

            if (totalCount != 0 && items.size != totalCount) {
                throw NordProtocolException(
                    "category $categoryIndex reported $totalCount items but its cursor " +
                        "yielded ${items.size} - protocol desync?",
                )
            }
            items
        }
    }

    /**
     * Parses a preset id like "A:1:1" (bank letter, then group and slot,
     * both 1-based, leading zeros accepted) into a (bank, item) pair as
     * used by sub-opcode 30/32/47.
     */
    fun parsePresetId(presetId: String): BankItem {
        val m = PRESET_ID_RE.matchEntire(presetId.trim())
            ?: throw IllegalArgumentException(
                "Invalid preset id '$presetId'; expected format like 'A:1:1' (bank letter " +
                    "A-$maxBankLetter, group 1-$maxGroup, slot 1-$slotsPerGroup)",
            )
        val (bankLetterStr, groupStr, slotStr) = m.destructured
        val bankLetter = bankLetterStr.uppercase()[0]
        val group = groupStr.toInt()
        val slot = slotStr.toInt()
        if (bankLetter > maxBankLetter) {
            throw IllegalArgumentException(
                "Invalid preset id '$presetId': bank '$bankLetter' is beyond the $name's last bank, '$maxBankLetter'",
            )
        }
        if (group !in 1..maxGroup) {
            throw IllegalArgumentException(
                "Invalid preset id '$presetId': group $group is beyond the $name's last group, $maxGroup",
            )
        }
        if (slot !in 1..slotsPerGroup) {
            throw IllegalArgumentException(
                "Invalid preset id '$presetId': slot $slot is beyond the $name's last slot, $slotsPerGroup",
            )
        }
        val bank = bankLetter - 'A'
        val item = (group - 1) * slotsPerGroup + (slot - 1)
        return BankItem(bank, item)
    }

    fun formatPresetId(bank: Int, item: Int): String {
        val bankLetter = 'A' + bank
        val group = item / slotsPerGroup
        val slot = item % slotsPerGroup
        return "$bankLetter:${group + 1}:${slot + 1}"
    }

    /** Loads a preset: sub-opcode 47/48 inside the `Program` category lock. The reply echoes (status, bank, item). */
    suspend fun selectPreset(bank: Int, item: Int) {
        withProgramCategory {
            val payload = fileTransfer(FileTransferSubOp.SELECT_PRESET, uint32BE(bank) + uint32BE(item)).payload
            requireEcho("select-preset", payload, bank, item)
        }
    }

    /**
     * Renames the preset at (bank, item): sub-opcode 28/29 inside the `Program` category lock,
     * then a re-select so the panel catches up. An oversized name is accepted and silently
     * truncated by the instrument; [DeviceProfile.maxProgramNameLen] caps it earlier.
     */
    suspend fun renamePreset(bank: Int, item: Int, newName: String) {
        require(newName.isNotBlank()) { "New preset name must not be blank" }
        require(newName.all { it.code in 0..127 }) { "New preset name '$newName' must be ASCII" }
        val nameBytes = newName.toByteArray(Charsets.US_ASCII)

        withProgramCategory {
            val payload = uint32BE(bank) + uint32BE(item) + uint32BE(nameBytes.size) + nameBytes
            val respPayload = fileTransfer(FileTransferSubOp.SET_NAME, payload).payload
            requireEcho("rename", respPayload, bank, item)
            reloadPresetForDisplay(bank, item)
        }
    }

    /**
     * Sets the category tag of the preset at (bank, item): sub-opcode 51/52, the same shape as
     * [renamePreset]. Writes the category alone and leaves the name untouched (the two are
     * independent; measured on a Nord Stage 2 EX). [categoryId] is not validated here: every
     * value 0..31 is accepted and stored, and one the instrument cannot name displays as
     * `No Cat`; choosing a visible id is [NordCategories]' job.
     */
    suspend fun setPresetCategory(bank: Int, item: Int, categoryId: Int) {
        withProgramCategory {
            val payload = uint32BE(bank) + uint32BE(item) + uint32BE(categoryId)
            val respPayload = fileTransfer(FileTransferSubOp.SET_CATEGORY, payload).payload
            requireEcho("set-category", respPayload, bank, item)
            reloadPresetForDisplay(bank, item)
        }
    }

    /**
     * Swaps the programs at two occupied (bank, item) positions: sub-opcode 26/27, a two-way
     * exchange in one request. An empty destination answers status 1; use [moveProgram] there.
     */
    suspend fun swapPrograms(srcBank: Int, srcItem: Int, dstBank: Int, dstItem: Int) {
        withProgramCategory {
            val payload = fileTransfer(
                              FileTransferSubOp.SWAP_PROGRAMS,
                              uint32BE(srcBank) + uint32BE(srcItem) + uint32BE(dstBank) + uint32BE(dstItem),
                          ).payload
            val status = readUInt32BE(payload, StatusEcho.STATUS)
            if (status != 0) {
                throw NordStatusException(
                    "swap-programs", status,
                    "Unexpected swap-programs response status: $status",
                )
            }
            reloadPresetForDisplay(srcBank, srcItem)
            reloadPresetForDisplay(dstBank, dstItem)
        }
    }

    /**
     * Moves a program to an empty (bank, item): sub-opcode 24/25, answered by the status word and
     * a 16-byte echo of the request. An occupied destination is refused with a non-zero status;
     * the caller picks this or [swapPrograms] to match the destination.
     */
    suspend fun moveProgram(srcBank: Int, srcItem: Int, dstBank: Int, dstItem: Int) {
        withProgramCategory {
            val payload = fileTransfer(
                              FileTransferSubOp.MOVE_PROGRAM,
                              uint32BE(srcBank) + uint32BE(srcItem) + uint32BE(dstBank) + uint32BE(dstItem),
                          ).payload
            val status = readUInt32BE(payload, StatusEcho.STATUS)
            if (status != 0) {
                throw NordStatusException(
                    "move-program", status,
                    "Unexpected move-program response status: $status",
                )
            }
            val echo = listOf(4, 8, 12, 16).map { readUInt32BE(payload, it) }
            if (echo != listOf(srcBank, srcItem, dstBank, dstItem)) {
                throw NordProtocolException(
                    "Unexpected move-program echo: $echo " +
                        "(requested [$srcBank, $srcItem, $dstBank, $dstItem])",
                )
            }
            reloadPresetForDisplay(srcBank, srcItem)
            reloadPresetForDisplay(dstBank, dstItem)
        }
    }

    /**
     * Copies the program at (srcBank, srcItem) to an empty (dstBank, dstItem): sub-opcode 22/23,
     * [moveProgram]'s payload and reply shape. The instrument names the copy itself ("Synth
     * Strings" -> "Synth Strings 2"), so the name is read back off the destination's record and
     * returned. Status 4 ("file exists") is the occupied-destination refusal.
     */
    suspend fun copyProgram(srcBank: Int, srcItem: Int, dstBank: Int, dstItem: Int): String {
        return withProgramCategory {
            val payload = fileTransfer(
                              FileTransferSubOp.COPY_PROGRAM,
                              uint32BE(srcBank) + uint32BE(srcItem) + uint32BE(dstBank) + uint32BE(dstItem),
                          ).payload
            val status = readUInt32BE(payload, StatusEcho.STATUS)
            if (status == STATUS_FILE_EXISTS) {
                throw NordStatusException(
                    "copy-program", status,
                    "Cannot copy onto an occupied slot: the instrument answered status 4 (file " +
                        "exists). A copy needs an empty destination.",
                )
            }
            if (status != 0) {
                throw NordStatusException(
                    "copy-program", status,
                    "Unexpected copy-program response status: $status",
                )
            }
            val echo = listOf(4, 8, 12, 16).map { readUInt32BE(payload, it) }
            if (echo != listOf(srcBank, srcItem, dstBank, dstItem)) {
                throw NordProtocolException(
                    "Unexpected copy-program echo: $echo " +
                        "(requested [$srcBank, $srcItem, $dstBank, $dstItem])",
                )
            }
            val record = fileTransfer(FileTransferSubOp.FETCH_ITEM, uint32BE(dstBank) + uint32BE(dstItem)).payload
            val newName = parseItemName(record)
            reloadPresetForDisplay(dstBank, dstItem)
            newName
        }
    }

    /**
     * Deletes the item at ([bank], [item]) of the selected category: sub-opcode 20/21, a device
     * primitive that works on samples as well as programs. It frees no space on its own - the
     * units move from `used` to `reclaimable` until the instrument's own reclaim runs.
     */
    suspend fun deleteItem(categoryIndex: Int, bank: Int, item: Int) {
        withCategory(categoryIndex) {
            val payload = fileTransfer(FileTransferSubOp.DELETE_ITEM, uint32BE(bank) + uint32BE(item)).payload
            requireEcho("delete-item", payload, bank, item)
            reloadPresetForDisplay(bank, item)
        }
    }

    /** [deleteItem] against the `Program` category - the common case. */
    suspend fun deleteProgram(bank: Int, item: Int) =
        deleteItem(getProgramCategoryIndex(), bank, item)

    suspend fun listRootCategories(): List<String> = parseRootCategoryList(rootCategoryListPayload())

    /** The raw sub-opcode 0/1 reply behind [listRootCategories], for the device report; each category's trailer is mostly undecoded. */
    suspend fun rootCategoryListPayload(): ByteArray =
        fileTransfer(FileTransferSubOp.ROOT_CATEGORY_LIST).payload

    /** The capability query's reply (protocol 6, sub-opcode 4/5), which nothing here decodes. Read-only. */
    suspend fun capabilityQueryPayload(): ByteArray =
        ui(UiSubOp.CAPABILITY_QUERY)?.payload ?: throw IllegalStateException(
            "skipped: $name reports a UI protocol version this app was not written against",
        )

    /**
     * One category's child list reply (sub-opcode 2/3), raw. The report ships these bytes
     * alongside [fetchCategoryChildren]'s parse, since the parse was derived from one instrument.
     */
    suspend fun categoryChildPayload(categoryIndex: Int): ByteArray =
        fileTransfer(FileTransferSubOp.GET_CATEGORY_CHILD, uint32BE(categoryIndex)).payload

    /** One entry of a category's child list - a bank, or a piano type. See [parseCategoryChildren]. */
    data class CategoryChild(val name: String, val capacity: Int)

    /**
     * One entry of the root category list (sub-opcode 0/1): a category's
     * name and the **allocation unit of its storage area, in bytes**, which is the first word
     * of its trailer. 1 for a byte-counted area; null only for a trailer too short to hold it.
     */
    data class RootCategory(val name: String, val unitBytes: Int?)

    /** Cache for [categoryBankCount] - a child list is a fixed firmware table. */
    private val categoryBankCounts = mutableMapOf<Int, Int>()

    /**
     * How many banks a category has: the length of its own child list
     * (sub-opcode 2/3). Null if the instrument cannot be asked.
     *
     * This, not [maxBankLetter], bounds a bank walk: the bank letters describe the `Program`
     * area alone, and a Nord Stage 2 EX gives `Piano` six children while addressing programs in
     * four banks.
     */
    suspend fun categoryBankCount(categoryIndex: Int): Int? {
        categoryBankCounts[categoryIndex]?.let { return it }
        return try {
            fetchCategoryChildren(categoryIndex).size.also { categoryBankCounts[categoryIndex] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Category $categoryIndex's child list could not be read, so its bank " +
                "count is unknown; the walk will rely on the item count and the empty-bank rule", e)
            null
        }
    }

    /**
     * A category's child list, decoded (sub-opcode 2/3). Needs no prerequisites.
     */
    suspend fun fetchCategoryChildren(categoryIndex: Int): List<CategoryChild> =
        parseCategoryChildren(categoryChildPayload(categoryIndex))

    /**
     * The bank layout this instrument reports for its `Program` category - how many banks, and
     * how many slots each holds - or null if it answers with no children. Throws if it cannot be
     * asked, so each caller can say why. What the wire does not announce is how a bank's slots
     * are grouped; see [applyDerivedBankLayout].
     */
    suspend fun deriveBankLayout(): BankLayout? {
        val children = fetchCategoryChildren(getProgramCategoryIndex())
        if (children.isEmpty()) return null
        // The largest, so nothing addressable falls outside the range.
        val slotsPerBank = children.maxOf { it.capacity }
        // This feeds InstrumentViewModel.allSlots, so it has to be plausible - a guard against a
        // malfunctioning device, not an observed one.
        if (slotsPerBank !in 1..MAX_PLAUSIBLE_CHILD_CAPACITY) {
            throw NordProtocolException(
                "the Program category reports $slotsPerBank slots per bank, which is not a usable " +
                    "addressing bound - refusing to derive a bank layout from it",
            )
        }
        return BankLayout(bankCount = children.size, slotsPerBank = slotsPerBank)
    }

    data class BankLayout(val bankCount: Int, val slotsPerBank: Int) {
        /** 'A' for one bank, 'P' for the Nord Grand's sixteen. */
        val maxBankLetter: Char get() = 'A' + (bankCount - 1)
    }

    /**
     * Replaces an unrecognized device's guessed bank bounds ([DeviceProfile.unknown]'s generous
     * 'Z' and 100 groups) with the ones the instrument reports. A catalog device keeps its
     * configured values. The wire does not announce how a bank's slots are grouped, so this sets
     * `maxGroup = 1` and `slotsPerGroup` to the whole bank: a Nord Grand bank reads
     * "A:1:1".."A:1:25" rather than "A:1:1".."A:5:5".
     */
    suspend fun applyDerivedBankLayout(): BankLayout? {
        val layout = deriveBankLayout() ?: return null
        profile = profile.copy(
            maxBankLetter = layout.maxBankLetter,
            maxGroup = 1,
            slotsPerGroup = layout.slotsPerBank,
        )
        Log.i(TAG, "Derived bank layout for $name: ${layout.bankCount} banks of ${layout.slotsPerBank}")
        return layout
    }

    suspend fun getCategoryIndexByName(name: String): Int {
        val categories = listRootCategories()
        val index = categories.indexOfFirst { it.equals(name, ignoreCase = true) }
        if (index < 0) throw NordProtocolException("Couldn't find a '$name' category in root list: $categories")
        return index
    }

    suspend fun getProgramCategoryIndex(): Int = getCategoryIndexByName("Program")

    // ---- Storage figures and read-only unit calibration ----

    /**
     * One category's sub-opcode 8/9 reply, decoded: its item count and its storage area's
     * free/used/reclaimable triple, which sums to the area's capacity. [unitCode] is a code, not
     * a size; 0 means the area is counted in bytes.
     */
    data class CategorySpace(
        val itemCount: Int,
        val free: Int,
        val used: Int,
        val reclaimable: Int,
        val unitCode: Int,
    ) {
        val countedInBytes: Boolean get() = unitCode == 0
        val capacity: Int get() = free + used + reclaimable
    }

    /**
     * Sub-opcode 8/9 for one category, inside the SELECT_CATEGORY bracket. Read-only.
     * [CategorySpace.itemCount] is reported as the instrument gave it, 0 for a non-empty
     * category included.
     */
    suspend fun fetchCategorySpace(categoryIndex: Int): CategorySpace {
        return withCategory(categoryIndex) {
            val payload = fileTransfer(FileTransferSubOp.GET_ITEM_COUNT, uint32BE(categoryIndex)).payload
            if (payload.size < 24) {
                throw NordProtocolException(
                    "Item-count reply for category $categoryIndex is ${payload.size} bytes, " +
                        "too short for the six fields this reply is expected to carry",
                )
            }
            val status = readUInt32BE(payload, ItemCountReply.STATUS)
            if (status != 0) {
                throw NordStatusException(
                    "item-count query", status,
                    "Item-count query for category $categoryIndex answered status $status",
                )
            }
            CategorySpace(
                itemCount = readUInt32BE(payload, ItemCountReply.ITEM_COUNT),
                free = readUInt32BE(payload, ItemCountReply.FREE),
                used = readUInt32BE(payload, ItemCountReply.USED),
                reclaimable = readUInt32BE(payload, ItemCountReply.RECLAIMABLE),
                unitCode = readUInt32BE(payload, ItemCountReply.UNIT_CODE),
            )
        }
    }

    /** One storage area and everything measured about it - see [calibrateStorageUnits]. */
    data class StorageArea(
        val names: List<String>,
        val indexes: List<Int>,
        /** The shared storage figures - identical across [names], which is what groups them. */
        val space: CategorySpace,
        /** Each category's own item count, aligned to [names]. These *do* differ within an area. */
        val itemCounts: List<Int>,
        val recordCount: Int,
        val totalBytes: Long,
        val unitBytes: Int?,
        val residual: Int?,
        val candidateRange: Pair<Int, Int>?,
        val reason: String,
        /**
         * The same overhead measured at the unit the instrument reports rather than at the fitted
         * one (the roundest value in the admissible range): a Grand's piano area leaves 0 at the
         * reported 130,816 and 33 at the fitted 131,072.
         */
        val reportedResidual: Int? = null,
    ) {
        val solved: Boolean get() = unitBytes != null && !space.countedInBytes
    }

    /**
     * Every storage area's allocation unit, fitted from the instrument's own item records as a
     * read-only cross-check on the unit the root category list states: every record carries its
     * blob's byte length, so the unit is the U for which `sum(ceil(size / U))` lands on the
     * area's `used`. A fit only narrows U to a range; the test is whether the reported figure
     * falls inside it.
     *
     * Categories reporting an identical (free, used, reclaimable, unitCode) share one area. A
     * category is dropped only if it lists exactly the same records as one already kept: `Samp
     * Lib` and `Samp Lib (Native)` are two views of the same items, while `Program`, `Live` and
     * `Settings` are different content in one area. Whole listings are compared, not items: on a
     * Nord Grand every `Live` slot holds a byte-identical copy of a program.
     *
     * Two round trips per item, so slow on a large instrument; [progress] is called as
     * `progress(name, index)` before each category walk.
     */
    suspend fun calibrateStorageUnits(
        progress: ((String, Int) -> Unit)? = null,
        onFailure: ((String, Exception) -> Unit)? = null,
    ): List<StorageArea> {
        // The names to walk, and the allocation unit each category states in its trailer.
        val rootCategories = parseRootCategories(rootCategoryListPayload())
        val names = rootCategories.map { it.name }
        val reportedUnits = rootCategories.map { it.unitBytes }

        // Per category, so one that will not answer does not abandon the rest.
        val spaces = LinkedHashMap<Int, CategorySpace>()
        names.indices.forEach { index ->
            try {
                spaces[index] = fetchCategorySpace(index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read storage figures for '${names[index]}'", e)
                onFailure?.invoke(names[index], e)
            }
        }

        // Group by the storage triple: identical figures mean one shared area.
        val grouped = LinkedHashMap<List<Int>, MutableList<Int>>()
        spaces.forEach { (index, s) ->
            grouped.getOrPut(listOf(s.free, s.used, s.reclaimable, s.unitCode)) { mutableListOf() }.add(index)
        }

        return grouped.values.map { indexes ->
            calibrateArea(
                indexes.map { names[it] }, indexes, spaces.getValue(indexes.first()),
                indexes.map { spaces.getValue(it).itemCount },
                // Every category in an area states the same unit; the first tolerates a short trailer.
                indexes.firstNotNullOfOrNull { reportedUnits.getOrNull(it) },
                progress,
                onFailure,
            )
        }
    }

    private suspend fun calibrateArea(
        names: List<String>,
        indexes: List<Int>,
        space: CategorySpace,
        itemCounts: List<Int>,
        reportedUnitBytes: Int?,
        progress: ((String, Int) -> Unit)?,
        onFailure: ((String, Exception) -> Unit)?,
    ): StorageArea {
        if (space.countedInBytes) {
            return StorageArea(
                names, indexes, space, itemCounts, space.itemCount, 0L, 1, null, null,
                "counted in bytes, not blocks (unit code 0) - nothing to calibrate",
            )
        }

        val listings = ArrayList<Map<Any, Int>>()     // per category: identity -> times listed
        val sizeOf = HashMap<Any, Int>()
        for ((name, index) in names.zip(indexes)) {
            progress?.invoke(name, index)
            val payloads = try {
                fetchCategoryItems(index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Both the area's note and the report's top-level failure map, so an interrupted
                // report does not read as complete.
                onFailure?.invoke(name, e)
                return StorageArea(
                    names, indexes, space, itemCounts, 0, 0L, null, null, null,
                    "couldn't walk '$name', so the records are incomplete (${e.message})",
                )
            }
            val here = HashMap<Any, Int>()
            for (payload in payloads) {
                val identity = itemIdentity(payload)
                here[identity] = (here[identity] ?: 0) + 1
                sizeOf[identity] = parseItemDataSize(payload)
            }
            if (listings.none { it == here }) listings.add(here)
        }

        val sizes = listings.flatMap { here ->
            here.flatMap { (identity, count) -> List(count) { sizeOf.getValue(identity) } }
        }
        val solved = solveStorageUnit(sizes, space.used)
        val reportedResidual = if (sizes.isNotEmpty() && reportedUnitBytes != null && reportedUnitBytes > 0) {
            (space.used - storageUnitsAt(sizes, reportedUnitBytes)).toInt()
        } else {
            null
        }
        return StorageArea(
            names, indexes, space, itemCounts, sizes.size, sizes.sumOf { it.toLong() },
            solved.unitBytes, solved.residual, solved.candidateRange, solved.reason,
            reportedResidual,
        )
    }

    /**
     * A key identifying an item record by what it is, for recognising the same item listed under
     * two categories: the content id where the instrument provides one, `(tag, name, size)` where
     * it does not (a Stage 2 EX). Not `(bank, item)`: a Nord Grand's two `Samp Lib` views list the
     * same 235 samples at different addresses.
     */
    fun itemIdentity(payload: ByteArray): Any =
        itemContentId(payload)
            ?: listOf(payload.copyOfRange(16, 20).toList(), parseItemName(payload), parseItemDataSize(payload))

    companion object {
        /**
         * One bulk read: larger than every reply this app receives (the root category list is the
         * largest, 436 bytes on a Stage 2 EX) and below the 16 KB per-transfer limit
         * `UsbDeviceConnection.bulkTransfer()` has below API 28. A larger reply is reassembled by
         * [readReply]. An item-data read (sub-opcodes 12/13, 14/15, 18/19, not implemented)
         * answers with up to 65,532 bytes and must be sliced by the reply's own length field.
         */
        private const val READ_BUFSIZE = 8192

        /** Sanity bound on a reply's declared length; the largest the device can serve is 65,532 bytes. */
        private const val MAX_MESSAGE_LEN = 65536

        /**
         * Consecutive zero-length replies before [readReply] declares the endpoint dead: generous
         * enough for a stray ZLP between the pieces of a split reply, small enough to give up in
         * well under a second.
         */
        private const val MAX_EMPTY_READS = 64

        /**
         * Replies answering some other request that [request] discards before giving up. A killed
         * session leaves at most a reply or two queued; a device genuinely out of step answers
         * every read wrongly.
         */
        internal const val MAX_STALE_REPLIES = 4

        /**
         * Runaway guard for a bank walk whose category could not be asked how many banks it has.
         * Not a device property: the walk is still ended by the item count or an empty bank. The
         * most banks any category on a supported instrument has is 16.
         */
        internal const val FALLBACK_MAX_BANKS = 256

        // internal: DemoUsbTransport in the test sources answers on these ids.
        internal const val PROTOCOL_CTRL = 7 // version 0 - device info query
        internal const val PROTOCOL_UI = 6 // version 1 - capability query, status display
        internal const val PROTOCOL_FILE_TRANSFER = 12 // version per device - content database

        internal const val PROTOCOL_VERSION_CTRL = 0
        internal const val PROTOCOL_VERSION_UI = 1

        // The vendor control request that returns the firmware version: bmRequestType 0xC0 (IN,
        // vendor, device recipient), bRequest 4.
        private const val FIRMWARE_CTRL_BM_REQUEST_TYPE = 0xC0
        private const val FIRMWARE_CTRL_BREQUEST = 4
        private const val FIRMWARE_CTRL_WLENGTH = 2

        /** The instrument's "file exists" status, answered by a copy onto an occupied slot. The only status code the protocol names. */
        const val STATUS_FILE_EXISTS = 4

        // Upper bounds for detectRootCategoryTrailerLen's search, far above anything real
        // (trailers are 28-29 bytes, the longest known category name 17), so a corrupt payload
        // is not "explained" by an absurd candidate.
        private const val MAX_ROOT_CATEGORY_TRAILER_LEN = 128
        private const val MAX_ROOT_CATEGORY_NAME_LEN = 64

        /** The file-transfer protocol versions every reply layout here was decoded in. */
        const val MIN_FILE_TRANSFER_VERSION = 3
        const val MAX_KNOWN_FILE_TRANSFER_VERSION = 10

        /** The versions the other two protocols are expected to report; every instrument measured reports these. */
        const val EXPECTED_PROTOCOL_VERSION_UI = 1
        const val EXPECTED_PROTOCOL_VERSION_CTRL = 0

        private const val ROOT_CATEGORY_TRAILER_NUMERIC_LEN = 16
        private const val ROOT_CATEGORY_TRAILER_BASE_FLAGS = 10

        /**
         * The per-entry trailer length a file-transfer protocol version implies: ten flag bytes,
         * two more at version >= 5, one more at version >= 10 - so 26 / 28 / 29, matching a Nord
         * Stage 2 EX (8) and a Nord Grand (10). Null above [MAX_KNOWN_FILE_TRANSFER_VERSION], where
         * a later revision could add another flag; callers then derive the length from the
         * response.
         */
        fun rootCategoryTrailerLenForVersion(version: Int?): Int? {
            if (version == null || version > MAX_KNOWN_FILE_TRANSFER_VERSION) return null
            var flags = ROOT_CATEGORY_TRAILER_BASE_FLAGS
            if (version >= 5) flags += 2
            if (version >= 10) flags += 1
            return ROOT_CATEGORY_TRAILER_NUMERIC_LEN + flags
        }

        /** Whether a sub-opcode 31 item record carries the content id (CRC-32), a version-10 addition. Null where the version is unknown or newer than decoded here. */
        fun itemRecordHasContentId(version: Int?): Boolean? {
            if (version == null || version > MAX_KNOWN_FILE_TRANSFER_VERSION) return null
            return version >= 10
        }

        /** Bound on an item record's name field; real preset and sample names are well under this. */
        private const val MAX_ITEM_NAME_LEN = 64

        /**
         * The 4-byte content tag of a program record, NUL-padded: `6e 67 70 00`. The one tag
         * whose record carries a program category at offset 28; `npno`, `nsmp`, `npdl` put
         * something else there.
         */
        private const val PROGRAM_CONTENT_TAG = "ngp\u0000"

        /**
         * Upper bound on a category-child's `capacity` where it becomes an addressing bound
         * ([deriveBankLayout], which feeds `InstrumentViewModel.allSlots`). Real instruments
         * report 25 or 100; this guards against a malfunctioning device. Not applied when parsing
         * a child list, where a Stage 2 EX legitimately reports 0xFFFE for its "(Native)"
         * categories.
         */
        private const val MAX_PLAUSIBLE_CHILD_CAPACITY = 10_000

        /**
         * Decodes a device-info (sub-op 2/3) reply into the protocol version table it is: a
         * 1-byte entry count followed by that many (protocol id, version) byte pairs.
         *
         *     Grand:      05 | 06 01 | 07 00 | 0a 02 | 0c 0a | 0d 00
         *     Stage 2 EX: 05 | 06 01 | 07 00 | 0a 02 | 0c 08 | 0d 00
         *
         * Protocols 10 and 13 are advertised and never used. The count must account for the
         * payload exactly.
         */
        fun parseProtocolVersions(payload: ByteArray): Map<Int, Int> {
            require(payload.isNotEmpty()) { "device-info reply is empty" }
            val count = payload[0].toInt() and 0xFF
            val expectedLen = 1 + 2 * count
            require(payload.size == expectedLen) {
                "device-info reply claims $count (protocol id, version) pairs, which needs " +
                    "$expectedLen bytes, but the payload is ${payload.size}"
            }
            return (0 until count).associate { i ->
                (payload[1 + 2 * i].toInt() and 0xFF) to (payload[2 + 2 * i].toInt() and 0xFF)
            }
        }

        /**
         * Recovers the per-category trailer length from a sub-opcode 0/1 response: of all
         * candidate lengths in 0..128 only the real one walks the whole payload and lands on its
         * end (29 on a Nord Grand, 28 on a Nord Stage 2 EX). Null where the search does not leave
         * exactly one candidate, such as an empty list.
         */
        fun detectRootCategoryTrailerLen(payload: ByteArray): Int? {
            val fits = (0..MAX_ROOT_CATEGORY_TRAILER_LEN).filter { rootCategoryListFits(payload, it) }
            return fits.singleOrNull()
        }

        /**
         * Would walking the sub-opcode 0/1 payload with this trailer length land exactly on the
         * end, reading a plausible name every time? Stricter than the real parse, since
         * [detectRootCategoryTrailerLen] searches with it and has to reject near-misses.
         */
        private fun rootCategoryListFits(payload: ByteArray, trailerLen: Int): Boolean {
            if (payload.size < 5) return false
            val count = payload[4].toInt() and 0xFF
            var off = 5
            repeat(count) {
                if (off + 4 > payload.size) return false
                val nameLen = readUInt32BE(payload, off)
                off += 4
                if (nameLen !in 1..MAX_ROOT_CATEGORY_NAME_LEN) return false
                if (off + nameLen > payload.size) return false
                for (i in off until off + nameLen) {
                    val byte = payload[i].toInt() and 0xFF
                    if (byte < 0x20 || byte >= 0x7F) return false
                }
                off += nameLen + trailerLen
            }
            return off == payload.size
        }

        private val PRESET_ID_RE = Regex("^([A-Za-z]):(\\d+):(\\d+)$")

        /**
         * Reads a big-endian u32, checking the buffer is long enough first, so a short reply is a
         * protocol error rather than an [ArrayIndexOutOfBoundsException].
         */
        private fun readUInt32BE(data: ByteArray, offset: Int): Int {
            require(offset >= 0 && offset + 4 <= data.size) {
                "reply is ${data.size} bytes, too short for the 4-byte field at offset $offset"
            }
            return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
        }

        private fun uint32BE(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
        )

        /** A sub-opcode 31 item record's data-size field: the exact byte length of the item's blob. */
        fun parseItemDataSize(payload: ByteArray): Int = readUInt32BE(payload, ItemRecord.DATA_SIZE)

        /**
         * A sub-opcode 31 item record's content id (the CRC-32 of its data blob), or null on an
         * instrument whose records do not carry one. Located positionally, two words after the
         * name: a Nord Grand's records end right after it, a Stage 2 EX's one word earlier.
         */
        fun parseItemContentId(payload: ByteArray): Int? {
            if (payload.size < ItemRecord.NAME_START) return null
            val nameLen = readUInt32BE(payload, ItemRecord.NAME_LEN)
            // Validated before the arithmetic: a nameLen near Int.MAX_VALUE would wrap the offset.
            if (nameLen !in 0..MAX_ITEM_NAME_LEN) return null
            val offset = ItemRecord.NAME_START + nameLen + 8
            return if (payload.size < offset + 4) null else readUInt32BE(payload, offset)
        }

        /**
         * Decodes a sub-opcode 2/3 reply into a category's child list:
         *
         *     status(4) | categoryIndex(4) | childCount(1) | [ nameLen(4) | name | capacity(4) ] * childCount
         *
         * On a Nord Grand every category's reply walks to exactly its own end under this layout,
         * and the trailing word reads as a slot capacity (16 banks of 25 for `Program`). A Stage 2
         * EX's `Live`/`Settings` figures do not line up under that reading, so the device report
         * ships the raw bytes beside this parse. Throws if the payload does not walk cleanly,
         * since a wrong parse would feed [deriveBankLayout] a wrong addressing range.
         */
        fun parseCategoryChildren(payload: ByteArray): List<CategoryChild> {
            require(payload.size >= 9) { "child list reply is only ${payload.size} bytes" }
            val count = payload[8].toInt() and 0xFF
            val children = mutableListOf<CategoryChild>()
            var off = 9
            repeat(count) {
                require(off + 4 <= payload.size) { "child list ran off the end at $off" }
                val nameLen = readUInt32BE(payload, off)
                off += 4
                require(nameLen in 1..MAX_ROOT_CATEGORY_NAME_LEN && off + nameLen + 4 <= payload.size) {
                    "implausible child name length $nameLen at $off"
                }
                val name = String(payload, off, nameLen, Charsets.US_ASCII)
                off += nameLen
                // Recorded as reported: a Stage 2 EX answers 0xFFFE for every "(Native)" category.
                // The plausibility bound is applied in [deriveBankLayout].
                val capacity = readUInt32BE(payload, off)
                children += CategoryChild(name, capacity)
                off += 4
            }
            require(off == payload.size) {
                "child list left ${payload.size - off} bytes unconsumed - layout is not as assumed"
            }
            return children
        }

        /** Total allocation units `sizes` occupy at a candidate unit size. */
        fun storageUnitsAt(sizes: List<Int>, unitBytes: Int): Long =
            sizes.sumOf { ((it + unitBytes - 1) / unitBytes).toLong() }

        /**
         * How far `used` may exceed the computed sum and still count as a fit. The residual (the
         * instrument's per-item overhead) ran under 0.3% on every area measured; wrong candidates
         * miss by 25-98%.
         */
        private const val CALIBRATION_TOLERANCE = 0.01
        private const val CALIBRATION_MIN_TOLERANCE = 2

        /**
         * Below this many units per item on average, nearly every item fits in one unit and every
         * candidate at or above the largest item fits equally well: a Nord Grand's slot-counted
         * `Program` area (ratio 1.05), where the block-counted areas run from 9 to 159.
         */
        private const val CALIBRATION_MIN_UNITS_PER_ITEM = 1.5

        data class SolvedUnit(
            val unitBytes: Int?,
            val residual: Int?,
            val candidateRange: Pair<Int, Int>?,
            val reason: String,
        )

        /**
         * Solve for the allocation unit that explains `used` given every item's byte size.
         *
         * `sum(ceil(size / U))` is non-increasing in U, so the units that fit form one
         * contiguous range: its lower edge is the smallest U whose sum has fallen to `used` or
         * below, its upper edge the largest still within tolerance. Both are found by binary
         * search, so this is a few dozen sums however large the area is. The value reported out
         * of that range is the one divisible by the largest power of two: real units are round
         * numbers (64/128/192/256 KB across the instruments measured so far) while the range's
         * edges are wherever the arithmetic happened to cross.
         */
        fun solveStorageUnit(rawSizes: List<Int>, used: Int): SolvedUnit {
            val sizes = rawSizes.filter { it > 0 }
            val count = sizes.size
            if (count == 0) return SolvedUnit(null, null, null, "no item records to measure against")
            if (used <= 0) {
                return SolvedUnit(null, null, null, "the area reports used=$used, which nothing can be fitted to")
            }
            if (used < count) {
                return SolvedUnit(
                    null, null, null,
                    "$count records against a reported used of $used - fewer units than items, so " +
                        "these records are not (only) what this area is accounting for",
                )
            }
            if (used < count * CALIBRATION_MIN_UNITS_PER_ITEM) {
                // Locale.ROOT, not the device's locale: this string is machine-readable output
                // that goes into a shared JSON report, and a German phone renders "1.05" as "1,05".
                val perItem = String.format(Locale.ROOT, "%.2f", used.toDouble() / count)
                return SolvedUnit(
                    null, null, null,
                    "$used units over $count items is $perItem per item - at barely one unit each, " +
                        "every candidate at or above the largest item explains the figures equally " +
                        "well, so these records cannot separate a block size from a per-item slot " +
                        "count",
                )
            }

            val tolerance = maxOf(CALIBRATION_MIN_TOLERANCE, (used * CALIBRATION_TOLERANCE).toInt())
            val largest = sizes.max()

            // Smallest unit whose total has come down to `used` - the range's lower edge.
            var lo = 1
            var hi = largest
            while (lo < hi) {
                val mid = lo + (hi - lo) / 2
                if (storageUnitsAt(sizes, mid) <= used) hi = mid else lo = mid + 1
            }
            val unitLo = lo
            if (storageUnitsAt(sizes, unitLo) < used - tolerance) {
                return SolvedUnit(
                    null, null, null,
                    "no unit size reproduces a used of $used from these $count records " +
                        "(the closest total is ${storageUnitsAt(sizes, unitLo)})",
                )
            }

            // ...and the largest still within tolerance, the upper edge.
            lo = unitLo
            hi = largest
            while (lo < hi) {
                val mid = lo + (hi - lo + 1) / 2
                if (used - storageUnitsAt(sizes, mid) <= tolerance) lo = mid else hi = mid - 1
            }
            val unitHi = lo

            val unit = roundestInRange(unitLo, unitHi)
            val residual = (used - storageUnitsAt(sizes, unit)).toInt()
            return SolvedUnit(
                unit, residual, unitLo to unitHi,
                "$count records, $used units used, $residual unaccounted for; any unit from " +
                    "$unitLo to $unitHi bytes fits as well",
            )
        }

        /**
         * The value in [lo]..[hi] divisible by the largest power of two, smallest first on a tie
         * - see [solveStorageUnit] for why that is the one worth reporting. Falls back to [lo]
         * for a range holding nothing rounder.
         */
        fun roundestInRange(lo: Int, hi: Int): Int {
            for (shift in (31 - Integer.numberOfLeadingZeros(hi)) downTo 1) {
                val step = 1 shl shift
                val first = ((lo + step - 1) / step) * step
                if (first <= hi) return first
            }
            return lo
        }
    }
}
