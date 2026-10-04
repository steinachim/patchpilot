// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.catalog.DeviceMatch
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentDescriptor
import de.thewolfwalkexperience.software.patchpilot.core.Bus
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.demo.DemoInstrument
import de.thewolfwalkexperience.software.patchpilot.discovery.Candidate
import de.thewolfwalkexperience.software.patchpilot.discovery.DeviceDiscovery
import de.thewolfwalkexperience.software.patchpilot.transport.Transport
import de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport
import de.thewolfwalkexperience.software.patchpilot.usb.AttachedUsbDevice
import de.thewolfwalkexperience.software.patchpilot.usb.UsbHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection state machine against fake buses: what a USB detach does in each state a session
 * can be in, and the unknown-device gate.
 */
class ConnectionCoordinatorTest {

    private data class FakeDevice(
        override val physicalKey: String,
        override val vendorId: Int = 0x0FFC,
        override val productId: Int = 0x21,
        override val handle: String = "usb:/dev/bus/usb/001/002",
        override val label: String = "Nord Stage 2 (0x0FFC:0x0021)",
    ) : AttachedUsbDevice

    private class FakeUsbHost(var attached: List<AttachedUsbDevice> = emptyList()) : UsbHost {
        val detaches = MutableSharedFlow<AttachedUsbDevice>()
        override fun attachedDevices() = attached
        override suspend fun requestPermission(device: AttachedUsbDevice) = true
        override fun openBulk(device: AttachedUsbDevice): UsbBulkTransport = error("not opened in these tests")
        override fun detachEvents(): Flow<AttachedUsbDevice> = detaches
    }

    private class FakeTransport : Transport {
        override val rebuildOnResume = true
        override fun close() = Unit
    }

    /** One bus whose scan finds [candidates], each opened by [open]. */
    private class FakeDiscovery(
        private val candidates: List<Candidate>,
        private val open: suspend (Candidate) -> Transport,
    ) : DeviceDiscovery {
        override val bus = Bus.USB
        override suspend fun scan(catalog: List<InstrumentDescriptor>) = candidates
        override suspend fun open(candidate: Candidate) = open.invoke(candidate)
    }

    private val nordStage = InstrumentDescriptor(
        id = "nord_stage_2_ex",
        family = "nord",
        name = "Nord Stage 2 EX",
        match = DeviceMatch.Usb(vendorId = 0x0FFC, productId = 0x21),
    )
    private val stageKey = "usb:4092:33"
    private val stageCandidate = Candidate(nordStage, "Nord Stage 2 EX", Bus.USB, stageKey, handle = Any())

    /**
     * [test] with a scope for the coordinator's own work, cancelled when [test] ends: a child of
     * the test, so `advanceUntilIdle` runs it - which it does not do for `backgroundScope` - and
     * cancelled, so its detach listener does not keep the test from finishing.
     */
    private fun runSession(test: suspend TestScope.(CoroutineScope) -> Unit) = runTest {
        val session = CoroutineScope(coroutineContext + Job(coroutineContext.job))
        try {
            test(session)
        } finally {
            session.cancel()
        }
    }

    private fun coordinator(
        scope: CoroutineScope,
        usb: UsbHost,
        discovery: DeviceDiscovery,
        autoConnect: Boolean = true,
        events: MutableList<String> = mutableListOf(),
        create: () -> Instrument = { DemoInstrument() },
        onSessionEnding: suspend () -> Unit = { events += "ending" },
    ) = ConnectionCoordinator(
        scope = scope,
        usb = usb,
        discoveries = listOf(discovery),
        catalog = { listOf(nordStage) },
        create = { _, _ -> create() },
        createForUnknown = { _, _, _ -> DemoInstrument() },
        autoConnect = { autoConnect },
        strings = StringResolver { id, _ -> "string $id" },
        onSessionStarted = { events += "started" },
        onSessionEnding = onSessionEnding,
        onDeviceLost = { events += "lost" },
    ).also { it.start() }

    /**
     * An open that fails must not leave its device marked as opening: unplugging that device
     * later would otherwise fail whatever session is current by then.
     */
    @Test
    fun `unplugging a device whose open failed leaves a later session alone`() = runSession { session ->
        val usb = FakeUsbHost()
        val coordinator = coordinator(
            session,
            usb,
            FakeDiscovery(listOf(stageCandidate)) { error("Failed to open USB device") },
        )

        coordinator.connect()
        advanceUntilIdle()
        assertTrue(coordinator.state.value is ConnectionState.Error)

        coordinator.connectDemo()
        advanceUntilIdle()
        val demo = (coordinator.state.value as ConnectionState.Connected).instrument

        usb.detaches.emit(FakeDevice(stageKey))
        advanceUntilIdle()
        assertSame(demo, (coordinator.state.value as ConnectionState.Connected).instrument)
        assertSame(demo, coordinator.instrument)
    }

    @Test
    fun `a failed open closes nothing it did not open and reports the failure`() = runSession { session ->
        val coordinator = coordinator(
            session,
            FakeUsbHost(),
            FakeDiscovery(listOf(stageCandidate)) { error("Failed to open USB device") },
        )
        coordinator.connect()
        advanceUntilIdle()
        assertEquals(ConnectionState.Error("Failed to open USB device"), coordinator.state.value)
    }

    @Test
    fun `unplugging the connected device ends the session as lost, cache first`() = runSession { session ->
        val usb = FakeUsbHost()
        val transport = FakeTransport()
        val events = mutableListOf<String>()
        val coordinator =
            coordinator(session, usb, FakeDiscovery(listOf(stageCandidate)) { transport }, events = events)

        coordinator.connect()
        advanceUntilIdle()
        assertTrue(coordinator.state.value is ConnectionState.Connected)

        usb.detaches.emit(FakeDevice(stageKey))
        advanceUntilIdle()
        val lost = coordinator.state.value as ConnectionState.DeviceLost
        assertEquals(stageKey, lost.physicalKey)
        assertEquals(null, coordinator.instrument)
        // The cache is dropped while the handle it is keyed on still exists.
        assertEquals(listOf("started", "lost", "ending"), events)
    }

    /**
     * The listing's cleanup - on a Nord, the unlock that ends a category selection - goes out
     * before the connection is closed, on a teardown and on [ConnectionCoordinator.release].
     */
    @Test
    fun `the connection is closed only once the listing has stopped`() = runSession { session ->
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            session,
            FakeUsbHost(),
            FakeDiscovery(listOf(stageCandidate)) { FakeTransport() },
            create = { closeRecorded(events) },
            onSessionEnding = {
                delay(1_000)
                events += "listing stopped"
            },
        )
        coordinator.connect()
        advanceUntilIdle()
        coordinator.disconnect()
        advanceUntilIdle()
        assertEquals(listOf("listing stopped", "closed"), events)

        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            events.clear()
            coordinator.connect()
            advanceUntilIdle()
            coordinator.release()
            advanceUntilIdle()
            assertEquals(listOf("listing stopped", "closed"), events)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun closeRecorded(events: MutableList<String>): Instrument {
        val demo = DemoInstrument()
        return object : Instrument by demo {
            override fun close() {
                events += "closed"
            }
        }
    }

    @Test
    fun `unplugging a device while it is being opened fails the attempt`() = runSession { session ->
        val usb = FakeUsbHost()
        val coordinator = coordinator(session, usb, FakeDiscovery(listOf(stageCandidate)) { awaitCancellation() })

        coordinator.connect()
        advanceUntilIdle()
        assertTrue(coordinator.state.value is ConnectionState.Opening)

        usb.detaches.emit(FakeDevice(stageKey))
        advanceUntilIdle()
        assertTrue(coordinator.state.value is ConnectionState.Error)
    }

    @Test
    fun `unplugging a listed device drops its picker row`() = runSession { session ->
        val other = FakeDevice("usb:1234:1", vendorId = 0x1234, label = "Keyboard")
        val usb = FakeUsbHost(attached = listOf(other))
        val coordinator =
            coordinator(session, usb, FakeDiscovery(listOf(stageCandidate)) { FakeTransport() }, autoConnect = false)

        coordinator.connect()
        advanceUntilIdle()
        assertEquals(2, (coordinator.state.value as ConnectionState.DeviceSelection).entries.size)

        usb.detaches.emit(other)
        advanceUntilIdle()
        val left = (coordinator.state.value as ConnectionState.DeviceSelection).entries
        assertEquals(listOf(stageKey), left.map { (it as PickerEntry.Known).candidate.physicalKey })
    }

    @Test
    fun `only a device of a guessable vendor reaches the unknown-device warning`() = runSession { session ->
        val coordinator = coordinator(session, FakeUsbHost(), FakeDiscovery(emptyList()) { FakeTransport() })
        val keyboard = FakeDevice("usb:1234:1", vendorId = 0x1234, label = "Keyboard")
        coordinator.selectUnknownDevice(keyboard)
        assertTrue((coordinator.state.value as ConnectionState.DeviceSelection).message != null)

        val newNord = FakeDevice("usb:4092:99", productId = 99)
        coordinator.selectUnknownDevice(newNord)
        assertEquals(ConnectionState.UnknownDeviceWarning(newNord), coordinator.state.value)
    }
}
