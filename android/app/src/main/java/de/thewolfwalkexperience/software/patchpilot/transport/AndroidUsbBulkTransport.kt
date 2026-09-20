// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log

private const val TAG = "AndroidUsbBulkTransport"

/**
 * Real [UsbBulkTransport], wrapping an already-permitted, already-opened `UsbDeviceConnection`.
 *
 * Endpoint addresses default to 0x03 OUT / 0x82 IN (the Nord devices' endpoints), but are
 * parameters rather than constants: a Yamaha Motif XS puts its bulk OUT on **0x01**, and which
 * endpoints an interface exposes is a property of the device, not of this class. They come from
 * the catalog entry (`DeviceMatch.Usb`) for the same reason the bank table does - a wrong one
 * should be a data edit.
 *
 * Nothing protocol-specific lives here: the Nord firmware read's `bmRequestType`/`bRequest` are
 * in `NordDevice`, because they describe that vendor protocol rather than this bus.
 */
class AndroidUsbBulkTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val timeoutMs: Int = 3000,
    endpointOutAddress: Int = EP_OUT_ADDRESS,
    endpointInAddress: Int = EP_IN_ADDRESS,
) : UsbBulkTransport {

    private val epOut: UsbEndpoint
    private val epIn: UsbEndpoint

    /** USB host mode has no add/remove callback this app can rely on mid-session, and unrelated
     * bus activity while backgrounded (e.g. a USB keyboard being unplugged and replugged) can
     * leave these endpoints permanently erroring - so a resume rebuilds rather than hopes. */
    override val rebuildOnResume = true

    init {
        // The connection was already opened by the caller (UsbConnectionManager.openTransport)
        // before this constructor runs, so on any failure below it's this class's job to close
        // it again - otherwise a claim failure (e.g. the interface is already held by another
        // app), or this interface not exposing an expected endpoint, would leak an open
        // UsbDeviceConnection.
        try {
            check(connection.claimInterface(usbInterface, true)) {
                "Failed to claim USB interface ${usbInterface.id}"
            }
            epOut = usbInterface.findEndpointByAddress(endpointOutAddress)
            epIn = usbInterface.findEndpointByAddress(endpointInAddress)
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    override fun bulkWrite(data: ByteArray) {
        val sent = connection.bulkTransfer(epOut, data, data.size, timeoutMs)
        check(sent == data.size) { "USB bulk write failed: sent $sent of ${data.size} bytes" }
    }

    override fun bulkRead(bufferSize: Int): ByteArray {
        val buffer = ByteArray(bufferSize)
        val read = connection.bulkTransfer(epIn, buffer, buffer.size, timeoutMs)
        check(read >= 0) { "USB bulk read failed (result=$read)" }
        return buffer.copyOf(read)
    }

    /**
     * A timeout here returns -1 and is not an error - see [UsbBulkTransport.bulkReadOrEmpty].
     *
     * `bulkTransfer` answers a timeout and a failure with the same -1, so the two are told apart
     * by the clock: a timeout has waited out [timeoutMs], while a detached device or a closed
     * connection fails at once. A negative result that arrives in under half the timeout is
     * therefore a failure and is thrown, which is what lets a reader loop count failures and
     * give up rather than spin on an endpoint that answers instantly with nothing.
     */
    override fun bulkReadOrEmpty(bufferSize: Int, timeoutMs: Int): ByteArray {
        val buffer = ByteArray(bufferSize)
        val started = System.nanoTime()
        val read = connection.bulkTransfer(epIn, buffer, buffer.size, timeoutMs)
        if (read > 0) return buffer.copyOf(read)
        if (read < 0) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            check(elapsedMs >= timeoutMs / 2) {
                "USB bulk read failed after ${elapsedMs} ms (result=$read)"
            }
        }
        return ByteArray(0)
    }

    /**
     * Reads with a short timeout until the device has nothing more to give. A negative result is
     * either that timeout or a dead endpoint, and both mean stop: the handshake that follows is
     * the place to find out which. Bounded, so a device that never stops talking cannot hold the
     * connect here.
     */
    override fun drainInput() {
        val buffer = ByteArray(DRAIN_BUFSIZE)
        var dropped = 0
        var reads = 0
        while (reads < MAX_DRAIN_READS) {
            val read = connection.bulkTransfer(epIn, buffer, buffer.size, DRAIN_TIMEOUT_MS)
            if (read <= 0) break
            dropped += read
            reads++
        }
        if (dropped > 0) {
            Log.w(TAG, "Discarded $dropped bytes the device had queued from an earlier session")
        }
    }

    override fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        length: Int,
    ): ByteArray {
        val buffer = ByteArray(length)
        val read = connection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)
        check(read == length) { "USB control transfer failed: read $read of $length bytes" }
        return buffer
    }

    override fun close() {
        connection.releaseInterface(usbInterface)
        connection.close()
    }

    private fun UsbInterface.findEndpointByAddress(address: Int): UsbEndpoint {
        for (i in 0 until endpointCount) {
            val ep = getEndpoint(i)
            if (ep.address == address) return ep
        }
        error("USB endpoint 0x%02x not found on interface %d".format(address, id))
    }

    companion object {
        const val EP_OUT_ADDRESS = 0x03
        const val EP_IN_ADDRESS = 0x82

        /** Per read while draining: short, because an idle device answers every one with silence. */
        private const val DRAIN_TIMEOUT_MS = 100
        private const val DRAIN_BUFSIZE = 8192
        private const val MAX_DRAIN_READS = 32
    }
}
