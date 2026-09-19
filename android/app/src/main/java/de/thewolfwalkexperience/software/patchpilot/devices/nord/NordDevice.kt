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
 * Byte offsets into a content-database reply payload, one object per reply *shape*.
 *
 * Grouped this way rather than as one flat list because the same offset means different things in
 * different replies: word 1 is the echoed bank in a write's acknowledgement, the item count in a
 * `GET_ITEM_COUNT` reply, and the total category count in a root-category listing. A single
 * `OFFSET_BANK = 4` would be right in one place and quietly wrong in the other two.
 *
 * Every field is a 4-byte big-endian word, and these tables are the ones in docs/PROTOCOLS.md -
 * keep the two in step.
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
 * Device-agnostic USB protocol logic shared by every supported Nord
 * instrument.
 *
 * [profile] supplies the handful of constants that differ per instrument -
 * USB ids, the instrument's bank-letter range, and its known-good firmware
 * versions - loaded from devices/nord_devices.json by [InstrumentRegistry]
 * (or synthesized by [DeviceProfile.unknown] for an unrecognized device).
 * Everything else here - transport framing (NordMessage/NordCrc), the
 * bank/item cursor's state machine, and the item-record layout - is
 * identical across instruments.
 *
 * Two constants that differ per device are read off the instrument itself
 * rather than configured:
 *
 *  - [protocolVersionFileTransfer] is *advertised*. The device-info reply (protocol 7,
 *    sub-op 2/3) is a protocol version table: a 1-byte entry count followed
 *    by that many (protocol id, version) byte pairs. Its pair for
 *    PROTOCOL_FILE_TRANSFER reads 10 on the Grand and 8 on the Stage 2 EX.
 *    [connect] reads it (see [parseProtocolVersions]); no profile declares one.
 *  - the root-category trailer length follows from that version, and is
 *    cross-checked against the reply itself: only one trailer length walks a
 *    root category list response and lands exactly on its end (29 on the
 *    Grand, 28 on the Stage 2 EX) - see [rootCategoryTrailerLen].
 *
 * The vendor's editor opens every session with a fixed handshake (device-info
 * query, capability query, ENTER_STATUS_MODE, content-database RESET), but
 * none of them is a prerequisite for anything below. [connect] therefore
 * sends exactly one of them - the device-info query, whose reply is the
 * protocol version table above - and none of the other three. Each operation
 * sends exactly the requests it needs, and cleans up whatever it locks (see
 * [selectPreset]/[moveProgram]/[fetchCategoryItems] and the status-mode
 * sub-opcodes).
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
     * The file-transfer protocol version, as read from the instrument by [connect].
     *
     * `internal` rather than private because tests construct a [NordDevice] against a canned
     * transport and drive individual operations without running [connect] - and every
     * content-database request needs this in its header. It is not a configurable value: no
     * profile declares one, and the instrument is the only authority. Production code
     * outside [connect] has no reason to write it.
     */
    internal var detectedProtocolVersionFileTransfer: Int? = null

    /**
     * Whether the UI protocol (6) is safe to speak. Set false by [checkProtocolVersions] when the
     * instrument reports a version this app was not written against - see [ui].
     */
    private var uiProtocolUsable = true
    private var uiProtocolWarned = false

    /**
     * The version if it is known, null otherwise - for the parse paths, which must not throw
     * merely because nobody has profiled this instrument yet. [protocolVersionFileTransfer]
     * is the one that insists.
     */
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

    /** The first message of every session. It isn't a prerequisite for anything else here, but
     * its reply is where [protocolVersionFileTransfer] comes from (see [parseProtocolVersions]),
     * so [connect] does send it. */
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
     * ROOT_CATEGORY_LIST/GET_CATEGORY_CHILD need no prerequisites at all, while
     * SELECT_CATEGORY locks the instrument into the same status-message/no-play mode as
     * the status-mode sub-opcode 0 - sub-opcode 2 does NOT clear this lock, only
     * UNLOCK_CATEGORY_SELECTION does. RESET is unnecessary for any operation (its
     * reply is always 4 zero bytes) - kept here for reference, not sent by anything below.
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
     * Fetches the firmware version, refuses to continue if it isn't in
     * [supportedFirmwareVersions], then reads the instrument's
     * protocol version table to learn [protocolVersionFileTransfer]. That one
     * device-info query is the only handshake message sent - see the class
     * doc - and this is the only thing that has to happen before every
     * other method here can be called freely.
     *
     * [validateFirmwareVersion] treats an empty [supportedFirmwareVersions] (see
     * [DeviceProfile.unknown]) as "skip the check" - there's nothing to validate against for a
     * device this app doesn't recognize at all.
     */
    suspend fun connect() {
        firmwareVersion = getFirmwareVersion()
        validateFirmwareVersion()
        detectedProtocolVersionFileTransfer = resolveProtocolVersionFileTransfer()
    }

    /**
     * The file-transfer protocol version, read from the instrument. No fallback: if this cannot be
     * read, [connect] fails.
     *
     * No catalog value stands in for it. The version selects the wire format and the instrument is
     * the only authority on which one it speaks, so a declared value could only agree with what
     * was read (adding nothing) or disagree with it (parsing the instrument's responses against the
     * wrong layout, on the protocol that carries the writes). The query that carries the version
     * is the first bulk message of every session, so an instrument that will not answer it is one
     * nothing else here would work against either.
     */
    private suspend fun resolveProtocolVersionFileTransfer(): Int =
        try {
            detectProtocolVersionFileTransfer()
        } catch (exc: UnsupportedProtocolVersionException) {
            throw exc
        } catch (exc: CancellationException) {
            // Backing out of a connect attempt is not a failure to report: rewrapped as an
            // IllegalStateException it would put an error screen in front of a user who has just
            // asked to leave - see InstrumentViewModel.launchConnect.
            throw exc
        } catch (exc: Exception) {
            throw IllegalStateException(
                "Couldn't read the file-transfer protocol version from $name (${exc.message}). " +
                    "That query is the first bulk message of every session and needs no " +
                    "prerequisite, so failing it means something more basic is wrong than this " +
                    "app can work around - refusing to continue rather than guessing at a " +
                    "version.",
                exc,
            )
        }

    // ---- Device info / command protocol version table ----

    /**
     * Asks the instrument which version each protocol runs at
     * ([parseProtocolVersions] decodes the reply). Needs no prerequisite - it's the first bulk
     * message of every session.
     */
    suspend fun getProtocolVersions(): Map<Int, Int> {
        val payload = request(PROTOCOL_CTRL, PROTOCOL_VERSION_CTRL, CtrlSubOp.DEVICE_INFO_QUERY.code).payload
        return parseProtocolVersions(payload)
    }

    /** Reads the file-transfer protocol version off the instrument itself. */
    suspend fun detectProtocolVersionFileTransfer(): Int {
        val versions = getProtocolVersions()
        checkProtocolVersions(versions)
        return versions[PROTOCOL_FILE_TRANSFER] ?: throw IllegalStateException(
            "device-info reply advertises no version for the file-transfer protocol " +
                "($PROTOCOL_FILE_TRANSFER); it lists $versions",
        )
    }

    /**
     * Log anything in the version table this app was not written against.
     *
     * Warns rather than refuses, and the three protocols differ in why:
     *
     *  - **UI (6)** - every instrument measured reports 1. This app sends one UI-protocol
     *    request, the capability query the device report records, so a mismatch is worth saying
     *    but is no reason to refuse to browse presets.
     *  - **Ctrl (7)** - the protocol that carries the version table, so a change in it could not
     *    be announced through the table itself. Checked here so a change is noticed rather than
     *    silently absorbed.
     *  - **12** - has its own rules for out-of-range versions; flagged here when it falls outside
     *    the range every reply layout was decoded in, which is exactly when those rules stop
     *    applying.
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
                // Stop speaking it rather than guess at a layout that may have moved.
                // Cosmetic only - see [ui].
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
        // An empty set (see DeviceProfile.unknown) means "skip the check" - there's no
        // known-good firmware to check an unrecognized device against.
        if (supportedFirmwareVersions.isNotEmpty() && firmwareVersion !in supportedFirmwareVersions) {
            val supported = supportedFirmwareVersions.sorted().joinToString(", ") { formatFirmwareVersion(it) }
            // **Warn, do not refuse** (see Instrument.advisory). Untested firmware may not behave
            // the way this app assumes - but refusing locks out the one person who could establish
            // what it actually does, and who can send back a device report saying so.
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

    /**
     * Raw USB teardown only. No protocol messages are sent here - every
     * operation that locks the instrument ([selectPreset], [moveProgram],
     * [fetchCategoryItems]) is responsible for
     * unlocking it again itself, so there's nothing left to do at this
     * point.
     */
    fun close() = transport.close()

    private suspend fun request(
        protocolId: Int,
        protocolVersion: Int,
        subOp: Int,
        payload: ByteArray = ByteArray(0),
    ): NordMessage = withContext(Dispatchers.IO) {
        // Anything still buffered cannot be an answer to a request that has not gone out yet -
        // this protocol answers one reply per request - so it is stale, and keeping it would
        // hand it to this call as if it were the answer. Dropped loudly rather than silently.
        if (readBuffer.isNotEmpty()) {
            Log.w(TAG, "discarding ${readBuffer.size} unread bytes left over from an earlier " +
                "exchange before sending protocol=$protocolId version=$protocolVersion sub-op=$subOp")
            readBuffer = ByteArray(0)
        }
        transport.bulkWrite(buildMessage(protocolId, protocolVersion, subOp, payload))
        readReply()
    }

    /**
     * A request on [PROTOCOL_FILE_TRANSFER] - the content database.
     *
     * Every content-database message repeats the same two leading header words: the protocol id,
     * and the version this instrument negotiated for that protocol
     * ([protocolVersionFileTransfer]). Filling them in here keeps them out of the twenty-odd
     * call sites below, where they carried no information - they are the same on every one.
     */
    private suspend fun fileTransfer(
        subOp: FileTransferSubOp,
        payload: ByteArray = ByteArray(0),
    ): NordMessage =
        request(PROTOCOL_FILE_TRANSFER, protocolVersionFileTransfer, subOp.code, payload)

    /**
     * A request on [PROTOCOL_UI] - the instrument's status display.
     *
     * **Sends nothing and returns null** when the instrument reports a UI protocol version this
     * app was not written against. Only *this protocol* is refused: the one request this app
     * sends on it is the capability query for the device report, and none of browsing, moving,
     * renaming or transferring presets depends on it.
     *
     * Skipping rather than guessing is the safe direction: the protocol's other sub-opcodes lock
     * the instrument's display and inhibit playing, and a version whose message layout may have
     * moved is not one to send anything to.
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
     * pieces and keeping any surplus for the next call.
     *
     * **A bulk read is not a message.** It carries whatever bytes have arrived, which may be less
     * than one message or more than one, and the message's own length field is the only thing
     * that says where it ends. Every instrument seen so far delivers exactly one whole message
     * per read almost always, but a reply can still arrive split across more than one read, so
     * a naive one-read-per-message assumption is not safe. A reply larger than [READ_BUFSIZE]
     * (8 KB) needs the reassembly regardless; the protocol allows up to [MAX_MESSAGE_LEN].
     */
    internal suspend fun readReply(): NordMessage {
        var emptyReads = 0
        while (true) {
            // **Cancellable.** Nothing else in this loop suspends, so without this neither job
            // cancellation nor a `withTimeout` around it could interrupt an iteration, and
            // disconnecting mid-read would strand the thread rather than end the read.
            currentCoroutineContext().ensureActive()

            takeBufferedMessage()?.let { return it }

            val chunk = transport.bulkRead(READ_BUFSIZE)
            if (chunk.isEmpty()) {
                // **A zero-length transfer is a legitimate USB reply, and it makes no progress.**
                // `bulkRead` guards `read >= 0`, so a ZLP passes that check and hands back an empty
                // array; unbounded, a device answering every request this way - hostile or merely
                // broken - would pin an IO thread for the life of the process. Bounded rather than
                // banned, because a stray ZLP between the pieces of a split reply is not itself an
                // error.
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
            // Refusing here rather than reading on: a garbage length would otherwise be waited
            // on forever, one timeout at a time. The buffer goes with it, since nothing in it
            // can be trusted to start a message either.
            readBuffer = ByteArray(0)
            error("reply declares an unusable length of $totalLen bytes; discarding the buffer")
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
     * Best-effort UNLOCK_CATEGORY_SELECTION (cmd 12, sub-op 6/7) - clears
     * the lock [FileTransferSubOp.SELECT_CATEGORY] leaves the instrument in. Meant
     * to be called from a `finally` block right after a
     * SELECT_CATEGORY-based operation, so a failure here must never mask
     * that operation's own exception - worst case the instrument is left
     * locked, exactly as if this call had never been made.
     *
     * Runs under [NonCancellable]: a plain suspend call here would be
     * aborted immediately if the caller's own coroutine is already being
     * cancelled (e.g. the screen that triggered it left composition) -
     * since this runs from a `finally` block specifically to avoid
     * leaving the instrument locked, skipping it on cancellation would
     * defeat the whole point.
     */
    private suspend fun unlockCategorySelection() {
        withContext(NonCancellable) {
            try {
                fileTransfer(FileTransferSubOp.UNLOCK_CATEGORY_SELECTION)
            } catch (e: Exception) {
                // best-effort, never mask the real error from the caller's own operation
                Log.w(TAG, "Unlocking the instrument after a category selection did not complete cleanly", e)
            }
        }
    }

    /**
     * Re-selects an already-selected preset purely to force the front-panel display to redraw its
     * name/category from the database. Must be called from inside the SELECT_CATEGORY lock a
     * mutating operation ([renamePreset]/[moveProgram]/[swapPrograms]) already holds, before that
     * lock is released.
     *
     * Required on the Nord Stage 2 EX: none of [renamePreset], [moveProgram] or [swapPrograms]
     * refresh the display on their own - the instrument keeps showing whatever name was on
     * screen at last load, even though the lock visibly engages and releases. Reselecting the
     * same slot fixes it, but only while still inside the lock; sending SELECT_PRESET unbracketed
     * does nothing there, unlike the Grand, where it changes the active preset on its own. This
     * holds for swap, move, rename and set-category alike.
     */
    private suspend fun reloadPresetForDisplay(bank: Int, item: Int) {
        val payload = fileTransfer(FileTransferSubOp.SELECT_PRESET, uint32BE(bank) + uint32BE(item)).payload
        requireEcho("display-refresh", payload, bank, item)
    }

    /**
     * Decodes the sub-opcode 0/1 response into a list of category names, using the trailer length
     * the response itself implies ([detectRootCategoryTrailerLen]).
     *
     * There is no declared fallback to reach for when detection declines, because there is nothing
     * a fallback could correctly do: detection only returns null for a list of zero categories -
     * where the loop below never touches a trailer - or for a payload no candidate length fits at
     * all, which a declared value would parse into garbage rather than rescue. So a zero-category
     * list is answered as the empty list it is, and anything else throws.
     */
    fun parseRootCategoryList(payload: ByteArray): List<String> =
        parseRootCategories(payload).map { it.name }

    /**
     * The trailer length to parse this response with, or null if neither route settles it.
     *
     * Two independent sources, in this order:
     *
     *  - version known and <= 10, and that length walks this response -> the version rule. The
     *    walk is the cross-check, and it is one check rather than [detectRootCategoryTrailerLen]'s
     *    0..128 search; since that search leaves exactly one candidate, a length that fits *is*
     *    the length.
     *  - version above the known range -> derive from the response, which adapts to a revision
     *    this build cannot know about.
     *  - version unknown, or the version's length does not fit -> derive, and warn. Not knowing
     *    the version is normal before `connect()`; the two disagreeing is not.
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
     * [parseItemContentId] with the protocol version as a cross-check.
     *
     * The field is a version-10 addition, not a Nord Grand one. The record's own length
     * still decides - it is self-describing, and stays right for a version this build has never
     * seen - but a contradiction between the two is worth surfacing.
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
            throw IllegalStateException(
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
     * A program record's category tag id, or null where this record does not carry one.
     *
     * Four bytes at offset 28, immediately before the name length [parseItemName] reads at 32 -
     * so it costs nothing: it is already in the record the listing fetches for every row. It is
     * the same field sub-opcode 51 writes ([setPresetCategory]), confirmed on one slot from both
     * directions: the recorded `A:1:1` "White Grand" record reads 21 here, and the capture of a
     * category change on that same preset carries `categoryId` 21 for "Grand".
     *
     * **Gated on the content tag, which is the whole point.** Offset 28 is the record's
     * *tag-specific* field and means something different per content type: on an `nsmp` sample
     * record it is two 2-byte halves, a (category, sub-category) pair, which read as a 4-byte
     * word gives 589826 for the sample fixture here. Decoding it blind would badge samples with
     * a nonsense id rather than fail, so a record that is not `ngp` answers null.
     */
    fun parseItemCategory(payload: ByteArray): Int? {
        if (payload.size < 32) return null
        val tag = String(payload, ItemRecord.CONTENT_TAG, 4, Charsets.US_ASCII)
        if (tag != PROGRAM_CONTENT_TAG) return null
        return readUInt32BE(payload, ItemRecord.CATEGORY_ID)
    }

    /**
     * Strips control characters from text the *instrument* chose.
     *
     * US-ASCII decoding already turns anything above 0x7F into U+FFFD, so bidi overrides and
     * zero-width characters cannot survive - but 0x00-0x1F would pass through, which includes NUL,
     * CR and LF. A preset name carrying a newline renders as two rows in the browser, and the user
     * picks delete targets off that list; one carrying NUL or ESC reaches the shared device-report
     * JSON and logcat.
     *
     * The same rule applies wherever device text is shown: [rootCategoryListFits] rejects any
     * category-name byte outside 0x20..0x7E, and `core.sanitizeDeviceText` strips control and bidi
     * characters from a USB or MIDI product string because that label sits beside a trust
     * decision. Outbound names are validated on every family too ([renamePreset], `Pro800Editor`,
     * the Motif XS editor).
     *
     * Strips rather than rejects: a real instrument with one odd byte in one slot should still show
     * the other thirty-one characters of its name, not fail the whole listing.
     */
    private fun sanitizeDeviceText(raw: String): String =
        raw.filter { it.code in 0x20..0x7E }

    fun collectItemNames(itemPayloads: List<ByteArray>): List<NamedItem> = itemPayloads.map { payload ->
        val bank = readUInt32BE(payload, ItemRecord.BANK)
        val item = readUInt32BE(payload, ItemRecord.ITEM)
        NamedItem(formatPresetId(bank, item), parseItemName(payload), parseItemCategory(payload))
    }

    /**
     * Checks the `(status, bank, item)` triple most write sub-opcodes answer with.
     *
     * Both halves matter: a non-zero status is a refusal, and an echo that does not match means
     * the instrument acted on a *different* slot than the one asked for - which on a delete is the
     * difference between losing the right preset and losing the wrong one.
     *
     * Every sub-opcode answering that triple checks it here rather than inline, the display
     * refresh in [reloadPresetForDisplay] included: its reply has the same shape, and a refresh
     * that silently landed on another slot is worth hearing about for the same reason.
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
     * Selects [categoryIndex], runs [block], and always unlocks afterwards.
     *
     * The one bracket every operation goes through: `SELECT_CATEGORY` locks the instrument, and
     * [unlockCategorySelection] has to run whether the operation succeeded or threw - a lock left
     * standing makes the instrument's own front panel unresponsive until the session ends.
     */
    private suspend fun <T> withCategory(categoryIndex: Int, block: suspend () -> T): T {
        fileTransfer(FileTransferSubOp.SELECT_CATEGORY, uint32BE(categoryIndex))
        return try {
            block()
        } finally {
            unlockCategorySelection()
        }
    }

    /**
     * [withCategory] against the `Program` category - what every preset operation wants.
     *
     * The index is resolved *before* anything is selected, which is why it is an argument rather
     * than a lookup inside the bracket: [getProgramCategoryIndex] itself talks to the instrument.
     */
    private suspend fun <T> withProgramCategory(block: suspend () -> T): T =
        withCategory(getProgramCategoryIndex(), block)

    /**
     * Selects a root-list category by index, then walks its full item list
     * using the bank/item cursor (sub-opcode 32/33) and per-item fetch
     * (sub-opcode 30/31), returning every item's raw payload in order.
     * Makes no assumption about bank count or bank size - it just keeps
     * asking the cursor for the next (bank, item) and trusts whatever it
     * gets back, incrementing bank only when told the current one is
     * exhausted.
     *
     * **The item count is a cross-check, not the loop condition.** The Nord
     * Stage 2 EX's `Live` and `Settings` categories report a count of 0
     * while holding items, so a walk bounded by the count would return
     * nothing before its first cursor request. The walk ends on whichever
     * comes first: the item count where it is non-zero (which stops without
     * probing the bank after the last populated one), an **empty bank** -
     * one that reports itself exhausted having yielded nothing - or
     * **running out of banks**, the bound for which comes from
     * [categoryBankCount] - the category's own child list, NOT
     * [maxBankLetter], which describes the `Program` area alone and has no
     * bearing on any other category. A non-zero count the cursor then
     * fails to deliver is an error.
     *
     * SELECT_CATEGORY locks the instrument (see [FileTransferSubOp]'s doc);
     * [unlockCategorySelection] always runs before returning, even on
     * error.
     */
    suspend fun fetchCategoryItems(categoryIndex: Int): List<ByteArray> {
        // How many banks this category has, asked of the category itself before
        // anything is selected (the child list needs no prerequisites). A
        // category's children ARE its banks - see [categoryBankCount].
        val bankCount = categoryBankCount(categoryIndex)

        return withCategory(categoryIndex) {
            val countPayload = fileTransfer(FileTransferSubOp.GET_ITEM_COUNT, uint32BE(categoryIndex)).payload
            if (countPayload.size < 8) {
                throw IllegalStateException(
                    "Item-count reply for category $categoryIndex is ${countPayload.size} bytes, " +
                        "too short for a count field at offset ${ItemCountReply.ITEM_COUNT}",
                )
            }
            val totalCount = readUInt32BE(countPayload, ItemCountReply.ITEM_COUNT)

            // If the child list could not be read the bank count is simply unknown -
            // nothing else on the wire gives it. Let the instrument end the walk (the
            // item count, or a bank reporting itself empty having yielded nothing) with
            // FALLBACK_MAX_BANKS purely as a runaway guard. That constant is not a
            // device property and must not be read as one.
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
                    throw IllegalStateException(
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
                    // Boolean "current bank has no more items" flag, NOT the next
                    // bank's number. The device expects the host to just try
                    // `bank + 1` next, restarting the cursor at that bank.
                    //
                    // An empty bank ends the category - unless a non-zero count says
                    // there is more to come, in which case keep advancing and let the
                    // count decide. A leading empty bank would otherwise cut the walk
                    // short.
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
                // A count of 0 is a known lie (see above) and is tolerated; a non-zero
                // one the cursor then fails to deliver is a desync.
                throw IllegalStateException(
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

    /**
     * Selects/loads a preset by (bank, item): selects the "Program"
     * category, then sub-opcode 47/48. The response echoes back (flag,
     * bank, item); flag is expected to be 0.
     *
     * The vendor's editor sends ENTER_STATUS_MODE first, but the instrument
     * doesn't need it for this to work - only SELECT_CATEGORY locks the
     * instrument, so only [unlockCategorySelection] is needed afterward
     * (always, even on a failed select - see [fetchCategoryItems]).
     */
    suspend fun selectPreset(bank: Int, item: Int) {
        withProgramCategory {
            val payload = fileTransfer(FileTransferSubOp.SELECT_PRESET, uint32BE(bank) + uint32BE(item)).payload
            requireEcho("select-preset", payload, bank, item)
        }
    }

    /**
     * Renames the preset at (bank, item) to [newName] - selects the "Program" category, then
     * sub-opcode 28/29. The response echoes back (flag, bank, item); flag is expected to be 0.
     *
     * Same lock/unlock pattern as [selectPreset]/[moveProgram]: SELECT_CATEGORY locks the
     * instrument, [unlockCategorySelection] always runs before returning, even on failure. The
     * leading ENTER_STATUS_MODE and trailing item-count refresh are omitted, same as select/move.
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
     * Sets the category tag of the preset at (bank, item) - selects the "Program" category, then
     * sub-opcode 51/52. The response echoes back (flag, bank, item); flag is expected to be 0.
     *
     * **Deliberately the same shape as [renamePreset]**, because on the wire it is the same
     * operation with a different sub-opcode and a three-word payload: same lock/unlock bracket,
     * same echo check, same trailing re-select so the instrument's own display catches up.
     *
     * The two writes are independent (measured on a Nord Stage 2 EX), so this writes the category
     * alone and leaves the name untouched, even though the vendor's own editor commits both
     * together.
     *
     * [categoryId] is not validated against the instrument's own set here: every value from 0 to
     * 31 is accepted and stored, and one the instrument cannot name simply displays as `No Cat`.
     * Choosing an id a user can actually see is [NordCategories]' job.
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
     * Swaps the programs at two *occupied* (bank, item) positions -
     * sub-opcode 26/27. See [selectPreset] for why there's no
     * ENTER_STATUS_MODE call and why [unlockCategorySelection] always
     * runs, even on failure. Also skips the per-category item-count
     * refresh the vendor's editor issues right after a move (presumably
     * to refresh its own listing) - the swap itself doesn't depend on it,
     * and this app has no listing open mid-move to refresh.
     *
     * This single request performs a full two-way swap, not a one-way
     * move. The destination must hold a program: the instrument answers
     * status 1 (rather than swapping) when it doesn't - use [moveProgram]
     * for an empty destination.
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
     * Moves a program to an *empty* (bank, item) - sub-opcode 24/25, the
     * one-way counterpart to [swapPrograms]'s two-way exchange. Same
     * request payload and same surrounding lock/unlock sequence; only the
     * sub-opcode and the reply's shape differ (24/25 answers with the
     * status word followed by a 16-byte echo of the four request fields,
     * where 26/27 answers with the status word alone).
     *
     * Sending this at an occupied destination, or [swapPrograms] at an
     * empty one, is what the instrument rejects with a non-zero status -
     * the caller picks the call that matches the destination.
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
                throw IllegalStateException(
                    "Unexpected move-program echo: $echo " +
                        "(requested [$srcBank, $srcItem, $dstBank, $dstItem])",
                )
            }
            reloadPresetForDisplay(srcBank, srcItem)
            reloadPresetForDisplay(dstBank, dstItem)
        }
    }

    /**
     * Copies the program at (srcBank, srcItem) to an *empty* (dstBank, dstItem), leaving the
     * source in place - sub-opcode 22/23: the instrument duplicates the source record in place
     * at the destination address.
     *
     * Takes [moveProgram]'s four-field payload and answers in its shape too: the status word plus
     * a 16-byte echo of the request. Same surrounding lock/unlock sequence.
     *
     * **The instrument names the copy itself**, appending a disambiguating number to the source's
     * name ("Synth Strings" -> "Synth Strings 2") - nothing in the request carries a name. The
     * resulting name is read back off the destination's item record and returned; [renamePreset]
     * is how a caller overrides it.
     *
     * Only an empty destination is handled here. Status 4 is the instrument's "file exists" and
     * is called out by name, since an occupied destination is the one failure a caller can act
     * on; any other non-zero status surfaces as the number the instrument gave.
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
                throw IllegalStateException(
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
     * Deletes the item at ([bank], [item]) of the selected category - sub-opcode 20/21.
     *
     * A device primitive, not a composed erase: the instrument empties the slot itself. Works
     * on a sample as well as a program, so it is not program-specific.
     *
     * **It frees no space on its own.** The units move from `used` to `reclaimable`,
     * and only a reclaim (34/35) returns them to `free` - so a caller that deletes to make room
     * for a write will not find any until the instrument's own "Cleaning..." step has run. That
     * is also what makes a delete undoable until then.
     *
     * The reply is a status word plus an echo of the request, and both are checked: an echo that
     * does not match means the instrument acted on a different slot than the one asked for, which
     * on a delete is the difference between losing the right preset and losing the wrong one.
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

    /**
     * The raw sub-opcode 0/1 reply behind [listRootCategories]. Exposed for the device report,
     * which ships the bytes as well as the parsed names: each category's trailer is still
     * undecoded beyond its native/factory flag, so the raw payload is worth more to whoever
     * receives a report than the names alone.
     */
    suspend fun rootCategoryListPayload(): ByteArray =
        fileTransfer(FileTransferSubOp.ROOT_CATEGORY_LIST).payload

    /**
     * The capability query's reply (protocol 6, sub-opcode 4/5) - a protocol/capability
     * version block that nothing here decodes. Read-only, needs no prerequisites, and part of
     * the vendor editor's own session handshake.
     */
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
     * **This, and not [maxBankLetter], is what bounds a bank walk.** The bank
     * letters are a property of the `Program` area alone - they are where preset
     * ids get their `A:`/`B:` - and a category that is not `Program` has no
     * reason to have the same number of banks. A Nord Stage 2 EX addresses
     * programs in four banks, A-D, and gives `Piano` six children (Grand,
     * Upright, EPiano1, EPiano2, Clavinet, Harps); bounding that walk at four
     * returned 21 of its 25 items. On that instrument every category's items
     * lie inside its own child count, and every bank past the child count
     * reports itself exhausted.
     */
    suspend fun categoryBankCount(categoryIndex: Int): Int? {
        categoryBankCounts[categoryIndex]?.let { return it }
        return try {
            fetchCategoryChildren(categoryIndex).size.also { categoryBankCounts[categoryIndex] = it }
        } catch (e: CancellationException) {
            // Not an unreadable child list - the walk itself is being torn down. Falling through
            // to the `null` below would have let it carry on against a scope that is already gone.
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
     * The bank layout this instrument reports for its `Program` category, or null if it can't be
     * read: how many banks it has, and how many slots each holds.
     *
     * This is the *derivable* half of what a [DeviceProfile] declares about addressing. What it
     * cannot say is how a bank's slots are grouped - 25 per bank is 5 groups of 5 on a Nord
     * Grand, but nothing on the wire distinguishes that from 25 groups of 1 - so a caller that
     * uses this has to pick a convention. See [applyDerivedBankLayout] for the one this app
     * picks, and why it only applies where it does.
     *
     * **Throws** if the instrument can't be asked; null means only that it answered with no
     * children. Both callers tolerate a failure, and each wants to say something different about
     * it - the device report records the reason in its failure map, the connect path logs and
     * carries on - so swallowing it here would rob them of the reason.
     */
    suspend fun deriveBankLayout(): BankLayout? {
        val children = fetchCategoryChildren(getProgramCategoryIndex())
        if (children.isEmpty()) return null
        // Banks of differing size are not something any instrument has shown; take the largest
        // rather than assume, so nothing addressable falls outside the range.
        val slotsPerBank = children.maxOf { it.capacity }
        // The one place a reported capacity turns into an addressing bound, and therefore the
        // one place it has to be plausible: this feeds InstrumentViewModel.allSlots. Only the
        // `Program` category is read here, and neither supported instrument reports anything
        // but a real figure for it (25 on a Grand's banks, 100 on a Stage 2 EX's) - so this is
        // defensive, against a hostile or malfunctioning device rather than an observed one.
        check(slotsPerBank in 1..MAX_PLAUSIBLE_CHILD_CAPACITY) {
            "the Program category reports $slotsPerBank slots per bank, which is not a usable " +
                "addressing bound - refusing to derive a bank layout from it"
        }
        return BankLayout(bankCount = children.size, slotsPerBank = slotsPerBank)
    }

    data class BankLayout(val bankCount: Int, val slotsPerBank: Int) {
        /** 'A' for one bank, 'P' for the Nord Grand's sixteen. */
        val maxBankLetter: Char get() = 'A' + (bankCount - 1)
    }

    /**
     * Replaces this device's guessed bank bounds with the ones the instrument reports - **for an
     * unrecognized device only**, where [DeviceProfile.unknown] otherwise supplies bounds
     * ('Z', 100 groups) chosen to be generous rather than correct.
     *
     * A device in devices/nord_devices.json keeps its catalog values untouched. That file is the
     * configuration, and a derived value has one thing it provably cannot recover: the split of a
     * bank's slots into groups. This method
     * therefore sets `maxGroup = 1` and `slotsPerGroup` to the whole bank capacity, which
     * addresses every slot correctly (a Nord Grand bank would read "A:1:1".."A:1:25" rather than
     * "A:1:1".."A:5:5") without inventing a grouping nothing announced.
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
        if (index < 0) throw IllegalStateException("Couldn't find a '$name' category in root list: $categories")
        return index
    }

    suspend fun getProgramCategoryIndex(): Int = getCategoryIndexByName("Program")

    // ---- Storage figures and read-only unit calibration ----

    /**
     * One category's sub-opcode 8/9 reply, decoded: its item count and its storage area's
     * free/used/reclaimable triple.
     *
     * The three figures are a closed system summing to the area's capacity, and each operation
     * moves between exactly two of them. [unitCode] is the reply's sixth field, which is a *code*
     * and not a size: a 0 means the area is counted in bytes rather than blocks, and that is the
     * one thing it reliably says.
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
     * Sub-opcode 8/9 for one category, with the SELECT_CATEGORY bracket around it. Read-only:
     * selects, queries, unlocks. Note [CategorySpace.itemCount] is the field
     * [fetchCategoryItems] refuses to trust as a loop condition - it reads 0 for categories that
     * demonstrably hold items - and is reported here exactly as the instrument gave it.
     */
    suspend fun fetchCategorySpace(categoryIndex: Int): CategorySpace {
        return withCategory(categoryIndex) {
            val payload = fileTransfer(FileTransferSubOp.GET_ITEM_COUNT, uint32BE(categoryIndex)).payload
            // Checked rather than assumed: an instrument that answers this sub-opcode with
            // something shorter would otherwise read off the end of the array below, which is a
            // worse error to debug than this one.
            if (payload.size < 24) {
                throw IllegalStateException(
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
         * The same overhead measured at the unit the *instrument* reports rather than at
         * the fitted one, which is the roundest value in the admissible range and not usually the
         * reported figure. The two differ, and the reported one is both the smaller and the one
         * every calculation actually uses: a Grand's piano area leaves 0 at the reported 130,816
         * and 33 at the fitted 131,072.
         */
        val reportedResidual: Int? = null,
    ) {
        val solved: Boolean get() = unitBytes != null && !space.countedInBytes
    }

    /**
     * Every storage area's allocation unit, worked out from the instrument's own item records.
     *
     * **Read-only, and a cross-check rather than a source**: the instrument states each area's
     * unit in the root category list, and this fits an independent figure from the area's
     * own contents - every item record carries its own blob's byte length, so the unit is the U
     * for which `sum(ceil(size / U))` lands on the `used` the area reports. A fit only narrows U
     * to a range, so the test is whether the reported figure falls inside it.
     *
     * Categories reporting an identical (free, used, reclaimable, unitCode) share one area and
     * are calibrated together. A category is then **dropped only if it lists exactly the same
     * records as one already kept**: one area's categories are either two views of the same
     * items (`Samp Lib` and `Samp Lib (Native)`, which a sum would double-count) or genuinely
     * different content in a shared area (`Program`/`Live`/`Settings`, which taking just one
     * would miss). Comparing whole listings separates the two.
     *
     * It has to be the whole listing, not item by item. On a Nord Grand every `Live` slot holds
     * a copy of the program last loaded into it, so all five are byte-identical to five programs
     * and a per-item merge loses them - undercounting the area by exactly five and reporting 16
     * units of overhead where the true figure is 11.
     *
     * Costs two round trips per item, so this is slow on a large instrument - [progress] is
     * called as `progress(name, index)` before each category walk.
     */
    suspend fun calibrateStorageUnits(
        progress: ((String, Int) -> Unit)? = null,
        onFailure: ((String, Exception) -> Unit)? = null,
    ): List<StorageArea> {
        // The root list twice over: the names to walk, and the allocation unit each category
        // states in its trailer, which is what [StorageArea.reportedResidual] measures
        // the overhead at. One request - rootCategoryListPayload() is the same call either way.
        val rootCategories = parseRootCategories(rootCategoryListPayload())
        val names = rootCategories.map { it.name }
        val reportedUnits = rootCategories.map { it.unitBytes }

        // Per category, because a storage query is exactly the kind of thing an instrument
        // nobody has profiled might answer differently or not at all - and one category that
        // does is no reason to abandon the eight that didn't.
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
                // Every category in an area shares its storage, so they state the same unit;
                // taking the first that states one at all tolerates a short trailer.
                indexes.firstNotNullOfOrNull { reportedUnits.getOrNull(it) },
                progress,
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
     * A key identifying an item record by *what it is*, for recognising the same item listed
     * under two categories: the record's content id where the instrument provides one, and
     * `(tag, name, size)` where it doesn't (the Stage 2 EX's records carry no content id).
     *
     * Deliberately not `(bank, item)`. On a real Nord Grand, its two `Samp Lib` views
     * list the same 235 samples at *different* addresses - agreeing on all 235 by content and on
     * only 212 by address.
     */
    fun itemIdentity(payload: ByteArray): Any =
        itemContentId(payload)
            ?: listOf(payload.copyOfRange(16, 20).toList(), parseItemName(payload), parseItemDataSize(payload))

    companion object {
        /**
         * One bulk read. Larger than every reply this app receives (the root category list is
         * the largest, at 408 bytes on the Grand and 436 on the Stage 2 EX; item records are
         * under 100), and below the 16 KB per-transfer limit `UsbDeviceConnection.bulkTransfer()`
         * has on API levels below 28. A reply larger than this is reassembled across reads by
         * [readReply].
         *
         * An item-data read (sub-opcodes 12/13, 14/15, 18/19, not implemented here) answers with
         * replies of up to 65,532 bytes; the device caps a larger request and reports what it
         * actually sent in the reply's own length field, so any such reader must slice by the
         * returned length, never by its own request.
         */
        private const val READ_BUFSIZE = 8192

        /**
         * Sanity bound on a reply's declared length, used by [readReply] to tell "the rest is
         * still coming" from "this framing is unusable". Deliberately independent of
         * [READ_BUFSIZE]: the largest reply the device can serve is 65,532 bytes, and such a
         * message spans several reads and is reassembled.
         */
        private const val MAX_MESSAGE_LEN = 65536

        /**
         * Consecutive zero-length replies before [readReply] declares the endpoint dead.
         *
         * A zero-length packet is a legal USB reply, so one is not an error and cannot be refused
         * outright - but it carries no bytes, so a run of them means the read will never complete.
         * Generous enough that a stray ZLP arriving between the pieces of a split reply costs
         * nothing, small enough that a device sending only ZLPs is caught in well under a second.
         */
        private const val MAX_EMPTY_READS = 64

        /**
         * Runaway guard for a bank walk whose category could not be asked how many
         * banks it has ([categoryBankCount] returning null). NOT a device property and
         * not a substitute for one: the walk is still ended by the item count or by an
         * empty bank, and this only stops an unanswerable instrument looping forever.
         * The most banks any category on either supported instrument has is 16.
         */
        internal const val FALLBACK_MAX_BANKS = 256

        // internal, not private: DemoUsbTransport (the demo-mode fake instrument) branches on
        // these same command ids to answer NordDevice's requests without duplicating them.
        internal const val PROTOCOL_CTRL = 7 // protocol version 0 - basic device info query
        // protocol version 1 - capability query / status-message display mode / status text push
        internal const val PROTOCOL_UI = 6
        // version is device-specific (10 on the Grand, 8 on the Stage 2 EX) - content database
        internal const val PROTOCOL_FILE_TRANSFER = 12

        internal const val PROTOCOL_VERSION_CTRL = 0
        internal const val PROTOCOL_VERSION_UI = 1

        // The one vendor control request that returns the firmware version:
        // bmRequestType 0xC0 (IN, vendor, device recipient), bRequest 4. These live here
        // rather than in the transport because they describe this protocol, not the bus.
        private const val FIRMWARE_CTRL_BM_REQUEST_TYPE = 0xC0
        private const val FIRMWARE_CTRL_BREQUEST = 4
        private const val FIRMWARE_CTRL_WLENGTH = 2

        // Upper bounds for detectRootCategoryTrailerLen's search. Both are far above anything
        // real (trailers are 28-29 bytes, the longest known category name is
        // "Samp Lib (Native)" at 17) - they exist to keep a corrupt payload from being
        // "explained" by an absurd candidate, not to express a real protocol limit.
        /** The instrument's "file exists" status - what a copy onto an occupied slot answers.
         * The only status code the protocol names. */
        const val STATUS_FILE_EXISTS = 4

        private const val MAX_ROOT_CATEGORY_TRAILER_LEN = 128
        private const val MAX_ROOT_CATEGORY_NAME_LEN = 64

        /**
         * The file-transfer protocol versions every reply layout here was decoded in. Above this
         * is a protocol revision this app has not seen.
         */
        const val MIN_FILE_TRANSFER_VERSION = 3
        const val MAX_KNOWN_FILE_TRANSFER_VERSION = 10

        /**
         * The versions the other two protocols this app speaks are expected to report. Every
         * instrument measured reports exactly these.
         */
        const val EXPECTED_PROTOCOL_VERSION_UI = 1
        const val EXPECTED_PROTOCOL_VERSION_CTRL = 0

        private const val ROOT_CATEGORY_TRAILER_NUMERIC_LEN = 16
        private const val ROOT_CATEGORY_TRAILER_BASE_FLAGS = 10

        /**
         * The per-entry trailer length a file-transfer protocol version implies, or null when
         * the version is unknown or newer than anything decoded here.
         *
         * Ten flag bytes unconditionally, two more (erase, dependencies) at version >= 5, one
         * more at version >= 10 - so 26 / 28 / 29, and 29 and 28 are what the Nord Grand (10) and
         * the Nord Stage 2 EX (8) measure.
         *
         * Null above [MAX_KNOWN_FILE_TRANSFER_VERSION] on purpose: a later revision could add a
         * fifteenth flag exactly as version 10 added the fourteenth, and answering 29 would parse
         * every entry after the first into garbage. Callers fall back to reading the length out
         * of the response, which adapts.
         */
        fun rootCategoryTrailerLenForVersion(version: Int?): Int? {
            if (version == null || version > MAX_KNOWN_FILE_TRANSFER_VERSION) return null
            var flags = ROOT_CATEGORY_TRAILER_BASE_FLAGS
            if (version >= 5) flags += 2
            if (version >= 10) flags += 1
            return ROOT_CATEGORY_TRAILER_NUMERIC_LEN + flags
        }

        /**
         * Whether a sub-opcode 31 item record carries the content id (CRC-32) - a version-10
         * addition, not a Nord Grand one. Null when the version is unknown or newer than
         * anything decoded here, where the record's own length is the better guide.
         */
        fun itemRecordHasContentId(version: Int?): Boolean? {
            if (version == null || version > MAX_KNOWN_FILE_TRANSFER_VERSION) return null
            return version >= 10
        }

        /** Same idea as [MAX_ROOT_CATEGORY_NAME_LEN], for an item record's own name field -
         * real preset/sample names are well under this. */
        private const val MAX_ITEM_NAME_LEN = 64

        /**
         * The 4-byte content tag of a *program* record.
         *
         * **NUL-padded, not space-padded**: a three-letter tag arrives as `6e 67 70 00`, so this
         * is `ngp` plus a NUL - written as an escape, because a raw NUL in source is invisible to
         * every reader and to most diffs. Matching `"ngp "` instead would never match anything,
         * and since [parseItemCategory] answers null on a mismatch, that would present as an
         * instrument whose programs simply carry no category rather than as a failure.
         *
         * The one tag whose record carries a program category at offset 28. The others (`npno`,
         * `nsmp`, `npdl`, ...) put something else entirely in that field.
         */
        private const val PROGRAM_CONTENT_TAG = "ngp\u0000"

        /** Upper bound on a category-child's `capacity` field (the sub-opcode 2/3 reply)
         * **where it is used as an addressing bound** - i.e. in [NordDevice.deriveBankLayout],
         * whose result multiplies out to an allocation in
         * [de.thewolfwalkexperience.software.patchpilot.ui.InstrumentViewModel.allSlots]. Real
         * instruments report two-digit or three-digit capacities there (25 on the Grand's
         * banks, 100 on a Stage 2 EX's); this is generous headroom, not a protocol limit, and
         * exists against a hostile or malfunctioning device.
         *
         * Deliberately **not** applied when parsing a child list. A Stage 2 EX legitimately
         * reports 0xFFFE for its "(Native)" categories; vetting the value at parse time would
         * throw away three whole categories' child lists - and with them their bank counts -
         * over a value that never reaches an allocation. */
        private const val MAX_PLAUSIBLE_CHILD_CAPACITY = 10_000

        /**
         * Decodes a device-info (sub-op 2/3) reply into the {command -> target} protocol version table it
         * actually is: a 1-byte entry count followed by that many (protocol id, version) byte pairs.
         *
         *     Grand:      05 | 06 01 | 07 00 | 0a 02 | 0c 0a | 0d 00
         *     Stage 2 EX: 05 | 06 01 | 07 00 | 0a 02 | 0c 08 | 0d 00
         *
         * Both decode to {6: 1, 7: 0, 10: 2, 12: 10-or-8, 13: 0} - i.e. PROTOCOL_UI at
         * PROTOCOL_VERSION_UI, PROTOCOL_CTRL at PROTOCOL_VERSION_CTRL and
         * PROTOCOL_FILE_TRANSFER at protocolVersionFileTransfer, plus protocols 10 and
         * 13, which the instruments advertise and this app never uses. The count field
         * accounts for the payload exactly (1 + 2*5 = 11 bytes), which is both the tell that this
         * is the whole structure and the consistency check enforced here.
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
         * Recovers the per-category trailer length from a sub-opcode 0/1 response, or null if the
         * payload doesn't pin it down.
         *
         * The response is self-describing: the outer header gives its exact length, so of all the
         * candidate trailer lengths only the real one walks the whole payload and lands precisely
         * on the end. Verified unique - a single surviving candidate out of 0..128 - across both
         * instruments, recovering 29 for the Nord Grand and 28 for the Nord Stage 2 EX.
         *
         * Returns null rather than guessing if the search doesn't leave exactly one candidate -
         * most plausibly an empty list (count 0, where every candidate trivially "fits"), which
         * carries no information about the trailer at all.
         */
        fun detectRootCategoryTrailerLen(payload: ByteArray): Int? {
            val fits = (0..MAX_ROOT_CATEGORY_TRAILER_LEN).filter { rootCategoryListFits(payload, it) }
            return fits.singleOrNull()
        }

        /**
         * Would walking the sub-opcode 0/1 payload with this trailer length land exactly on the
         * end, reading a plausible name every time?
         *
         * Deliberately stricter than [parseRootCategoryList]: this is the predicate
         * [detectRootCategoryTrailerLen] searches with, so it has to reject near-misses (a length
         * that runs off the end, or that lands the name-length field on bytes that decode to
         * garbage), not tolerate them the way the real parse does.
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
         * Reads a big-endian u32, **checking the buffer is long enough first**.
         *
         * The check lives here rather than at the twenty-odd call sites because every one of them
         * reads a status word or an echo at a fixed offset in a reply whose length the instrument
         * chose, and a short reply should be reported as a protocol error rather than as an
         * [ArrayIndexOutOfBoundsException] caught several layers up.
         *
         * A CRC is verified before any of this runs, so reaching here with a short payload means a
         * device that computes a valid checksum over a reply that is not the shape its sub-opcode
         * promises - malfunctioning firmware, or one pretending to be an instrument it is not.
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

        /**
         * A sub-opcode 31 item record's data-size field - the 4-byte BE word at payload offset
         * 12, right before the 4-byte content tag. The exact byte length of
         * the item's blob, which is what makes [calibrateStorageUnits] possible at all.
         */
        fun parseItemDataSize(payload: ByteArray): Int = readUInt32BE(payload, ItemRecord.DATA_SIZE)

        /**
         * A sub-opcode 31 item record's content id - the CRC-32 of the item's data blob - or
         * null on an instrument whose records don't carry one.
         *
         * **Located positionally, not from the end of the record.** The field sits two 4-byte
         * words after the name, and a Nord Grand's records end right after it, so reading the
         * last four bytes works there. Nord Stage 2 EX records stop one word earlier with no
         * content id, where the last four bytes are the zero word preceding it. The
         * record's own length settles which, so this is detected rather than configured.
         */
        fun parseItemContentId(payload: ByteArray): Int? {
            if (payload.size < ItemRecord.NAME_START) return null
            val nameLen = readUInt32BE(payload, ItemRecord.NAME_LEN)
            // Validated (not just clamped) before the arithmetic below: an unchecked nameLen near
            // Int.MAX_VALUE would silently wrap this offset calculation around to a small or
            // negative value, defeating the payload.size bounds check that follows it.
            if (nameLen !in 0..MAX_ITEM_NAME_LEN) return null
            val offset = ItemRecord.NAME_START + nameLen + 8
            return if (payload.size < offset + 4) null else readUInt32BE(payload, offset)
        }

        /**
         * Decode a sub-opcode 2/3 reply into a category's child list.
         *
         *     status(4) | categoryIndex(4) | childCount(1) | [ nameLen(4) | name | capacity(4) ] * childCount
         *
         * On a Nord Grand, all nine categories' replies walk to exactly their own end under this
         * layout: `Program` yields 16 children "Bank A" - "Bank P" of 25 each (that instrument's
         * 400 program slots), `Piano` six *named piano types* rather than banks - "Grand",
         * "Upright", "Electric", "Clav", "Digital", "Misc" - of 20 each, and `Live`/`Settings` one
         * child of 5 and 1, matching their item counts.
         *
         * The trailing per-child word is read as a **slot capacity** on that evidence. The Stage
         * 2 EX's `Live`/`Settings` figures don't line up under that reading, but on a Grand they
         * do exactly. Treat the reading as established for this instrument and provisional
         * elsewhere - which is why the device report ships the raw bytes beside this parse.
         *
         * Throws rather than guessing if the payload doesn't walk cleanly: a wrong parse here
         * would feed [deriveBankLayout] a wrong addressing range.
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
                // Recorded as reported, not vetted here. A Nord Stage 2 EX answers 0xFFFE
                // (65534) for every "(Native)" category - a sentinel meaning "no real figure",
                // not corruption - and rejecting the reply outright would lose three of that
                // instrument's ten categories, including their bank counts. The plausibility
                // bound belongs where this value would actually become an addressing bound -
                // see [deriveBankLayout].
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
         * How far `used` may exceed the computed sum and still count as a fit. The residual is
         * always positive - the instrument charges per-item overhead the records don't show -
         * and across the five hand-measured areas it ran under 0.3% each. 1% covers that and is
         * still far tighter than the competition, whose wrong candidates miss by 25-98%.
         */
        private const val CALIBRATION_TOLERANCE = 0.01
        private const val CALIBRATION_MIN_TOLERANCE = 2

        /**
         * Below this many units per item on average, nearly every item fits in one unit, so
         * every candidate at or above the largest item explains the figures equally well and a
         * fitted "block size" is an artefact. This is the Nord Grand's slot-counted `Program`
         * area (208 records against a `used` of 219, a ratio of 1.05); the five real
         * block-counted areas run from 9 to 159.
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
