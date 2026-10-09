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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection state machine against fake buses: what a USB detach does in each state a session
 * can be in, the unknown-device and advisory gates, leaving a session, and the resume rebuild.
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

        /** The user's answer to a permission request: granted unless a test says otherwise. */
        var permission: suspend () -> Boolean = { true }
        override fun attachedDevices() = attached
        override suspend fun requestPermission(device: AttachedUsbDevice) = permission()
        override fun openBulk(device: AttachedUsbDevice): UsbBulkTransport = error("not opened in these tests")
        override fun detachEvents(): Flow<AttachedUsbDevice> = detaches
    }

    private class FakeTransport : Transport {
        override val rebuildOnResume = true
        override fun close() = Unit
    }

    /** One bus whose scan finds [candidates], each opened by [open]. */
    private class FakeDiscovery(
        var candidates: List<Candidate>,
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

    private val nordGrand = InstrumentDescriptor(
        id = "nord_grand",
        family = "nord",
        name = "Nord Grand",
        match = DeviceMatch.Usb(vendorId = 0x0FFC, productId = 0x2E),
    )
    private val grandKey = "usb:4092:46"
    private val grandCandidate = Candidate(nordGrand, "Nord Grand", Bus.USB, grandKey, handle = Any())

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
        onUsbDetached = { handle -> events += "detached $handle" },
        onSessionLost = { events += "lost" },
        onLeavingSession = { events += "leaving" },
    ).also { it.start() }

    /**
     * A device plugged in while the scan runs may enumerate after the scan's USB phase. The plug-in
     * is remembered, and the empty result is followed by a second scan that finds it.
     */
    @Test
    fun `a device plugged in during a scan is found by a second scan`() = runSession { session ->
        val usb = FakeUsbHost()
        val scanned = CompletableDeferred<Unit>()
        var scans = 0
        val discovery = object : DeviceDiscovery {
            var candidates: List<Candidate> = emptyList()
            override val bus = Bus.USB
            override suspend fun scan(catalog: List<InstrumentDescriptor>): List<Candidate> {
                scans++
                val found = candidates
                scanned.await()
                return found
            }
            override suspend fun open(candidate: Candidate): Transport = FakeTransport()
        }
        val coordinator = coordinator(session, usb, discovery)
        coordinator.connect()
        runCurrent()
        assertEquals(ConnectionState.Searching, coordinator.state.value)

        val stage = FakeDevice(stageKey)
        usb.attached = listOf(stage)
        discovery.candidates = listOf(stageCandidate)
        coordinator.onUsbDeviceAttached(stage)
        scanned.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, scans)
        assertTrue("got ${coordinator.state.value}", coordinator.state.value is ConnectionState.Connected)
    }

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
    fun `a failed open reports the failure in the words it failed with`() = runSession { session ->
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
        assertEquals(listOf("started", "detached usb:/dev/bus/usb/001/002", "lost", "ending"), events)
    }

    /**
     * The session shows as lost before its teardown, which waits for whatever holds the operation
     * mutex; a replug in that window reconnects once the teardown has closed the old instrument.
     */
    @Test
    fun `an unplug shows at once, and a replug reconnects once the operation holding the session ends`() =
        runSession { session ->
            val usb = FakeUsbHost()
            val events = mutableListOf<String>()
            val coordinator = coordinator(
                session,
                usb,
                FakeDiscovery(listOf(stageCandidate)) { FakeTransport() },
                events = events,
                create = { closeRecorded(events) },
            )
            coordinator.connect()
            advanceUntilIdle()
            val first = coordinator.instrument
            val release = CompletableDeferred<Unit>()
            session.holdMutex(coordinator, release)
            runCurrent()

            usb.detaches.emit(FakeDevice(stageKey))
            runCurrent()
            assertTrue("got ${coordinator.state.value}", coordinator.state.value is ConnectionState.DeviceLost)
            assertTrue("lost" in events)
            assertFalse("closed only once the operation lets go", "closed" in events)

            coordinator.onUsbDeviceAttached(FakeDevice(stageKey))
            advanceUntilIdle()
            assertTrue("waits for the teardown", coordinator.state.value is ConnectionState.DeviceLost)

            release.complete(Unit)
            advanceUntilIdle()
            assertTrue("got ${coordinator.state.value}", coordinator.state.value is ConnectionState.Connected)
            assertNotSame(first, coordinator.instrument)
            assertEquals(listOf("ending", "closed", "started"), events.takeLast(3))
        }

    @Test
    fun `a permission refused when opening an instrument lands on the permission screen`() = runSession { session ->
        val coordinator = coordinator(
            session,
            FakeUsbHost(),
            FakeDiscovery(listOf(stageCandidate)) { throw SecurityException("USB permission was denied") },
        )
        coordinator.connect()
        advanceUntilIdle()
        assertEquals(ConnectionState.PermissionDenied("Nord Stage 2 EX"), coordinator.state.value)
    }

    @Test
    fun `a permission refused for an unrecognised device lands on the permission screen`() = runSession { session ->
        val usb = FakeUsbHost().apply { permission = { false } }
        val coordinator = coordinator(session, usb, FakeDiscovery(emptyList()) { FakeTransport() })
        val newNord = FakeDevice("usb:4092:99", productId = 99)
        coordinator.confirmUnknownDevice(newNord)
        advanceUntilIdle()
        assertEquals(ConnectionState.PermissionDenied(newNord.label), coordinator.state.value)
    }

    /** What `awaitPermission` bounds on a phone; here, Back is the way out. */
    @Test
    fun `a permission request nobody answers holds the attempt on Opening until Back`() = runSession { session ->
        val usb = FakeUsbHost().apply { permission = { awaitCancellation() } }
        val coordinator = coordinator(session, usb, FakeDiscovery(emptyList()) { FakeTransport() })
        coordinator.confirmUnknownDevice(FakeDevice("usb:4092:99", productId = 99))
        advanceUntilIdle()
        assertTrue(coordinator.state.value is ConnectionState.Opening)

        coordinator.disconnect()
        advanceUntilIdle()
        assertTrue("got ${coordinator.state.value}", coordinator.state.value !is ConnectionState.Opening)
    }

    /** Retry asks about the first instrument a scan finds, so another one needs the picker. */
    @Test
    fun `after a refusal, the picker offers every instrument even with auto-connect on`() = runSession { session ->
        val coordinator = coordinator(
            session,
            FakeUsbHost(),
            FakeDiscovery(listOf(stageCandidate, grandCandidate)) { candidate ->
                if (candidate == stageCandidate) throw SecurityException("denied") else FakeTransport()
            },
        )
        coordinator.connect()
        advanceUntilIdle()
        assertTrue(coordinator.state.value is ConnectionState.PermissionDenied)

        coordinator.chooseDevice()
        advanceUntilIdle()
        val picker = coordinator.state.value as ConnectionState.DeviceSelection
        assertEquals(
            listOf(stageKey, grandKey),
            picker.entries.map { (it as PickerEntry.Known).candidate.physicalKey },
        )
    }

    /** One unplug that fails to be handled must not end detection for the rest of the session. */
    @Test
    fun `an unplug whose handling fails does not stop the next from being heard`() = runSession { session ->
        val usb = FakeUsbHost()
        val coordinator = ConnectionCoordinator(
            scope = session,
            usb = usb,
            discoveries = listOf(FakeDiscovery(listOf(stageCandidate)) { FakeTransport() }),
            catalog = { listOf(nordStage) },
            create = { _, _ -> DemoInstrument() },
            createForUnknown = { _, _, _ -> DemoInstrument() },
            autoConnect = { true },
            strings = StringResolver { id, _ -> "string $id" },
            onUsbDetached = { handle -> check(handle != "usb:/dev/bus/usb/001/007") { "broken hook" } },
        ).also { it.start() }
        coordinator.connect()
        advanceUntilIdle()

        usb.detaches.emit(FakeDevice(grandKey, handle = "usb:/dev/bus/usb/001/007"))
        advanceUntilIdle()
        usb.detaches.emit(FakeDevice(stageKey))
        advanceUntilIdle()

        assertTrue("got ${coordinator.state.value}", coordinator.state.value is ConnectionState.DeviceLost)
    }

    /** Another unit of the same model may be plugged into the same port next, under the same handle. */
    @Test
    fun `unplugging a device no session uses still drops its cache`() = runSession { session ->
        val usb = FakeUsbHost()
        val events = mutableListOf<String>()
        val coordinator = coordinator(session, usb, FakeDiscovery(emptyList()) { FakeTransport() }, events = events)
        coordinator.connect()
        advanceUntilIdle()

        usb.detaches.emit(FakeDevice(grandKey, handle = "usb:/dev/bus/usb/001/007"))
        advanceUntilIdle()

        assertEquals(listOf("detached usb:/dev/bus/usb/001/007"), events)
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

    /** An instrument that raises an advisory once connected, and records its close. */
    private fun advisoryRaising(events: MutableList<String>): Instrument {
        val demo = DemoInstrument()
        return object : Instrument by demo {
            override val advisory = "Untested firmware"

            override fun close() {
                events += "closed"
            }
        }
    }

    /** Holds the operation mutex the way an edit or a device report does, until [release] completes. */
    private fun CoroutineScope.holdMutex(coordinator: ConnectionCoordinator, release: CompletableDeferred<Unit>) =
        launch { coordinator.exclusive { release.await() } }

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

    @Test
    fun `back leaves the session at once and ends on the picker once the operation holding it is done`() =
        runSession { session ->
            val events = mutableListOf<String>()
            val coordinator =
                coordinator(
                    session,
                    FakeUsbHost(),
                    FakeDiscovery(listOf(stageCandidate)) {
                        FakeTransport()
                    },
                    events = events,
                )
            coordinator.connect()
            advanceUntilIdle()
            val release = CompletableDeferred<Unit>()
            session.holdMutex(coordinator, release)
            runCurrent()

            coordinator.disconnect()
            // Before the teardown gets the mutex: no screen may still see the session as connected.
            assertEquals(ConnectionState.Searching, coordinator.state.value)
            assertTrue("leaving" in events)
            advanceUntilIdle()
            assertEquals(ConnectionState.Searching, coordinator.state.value)

            release.complete(Unit)
            advanceUntilIdle()
            // The picker even for a lone instrument with auto-connect on: the user asked to choose.
            val picker = coordinator.state.value as ConnectionState.DeviceSelection
            assertEquals(listOf(stageKey), picker.entries.map { (it as PickerEntry.Known).candidate.physicalKey })
            assertEquals(null, coordinator.instrument)
        }

    @Test
    fun `back during a resume's reconnect replaces it rather than being undone by it`() = runSession { session ->
        var opening: CompletableDeferred<Unit>? = null
        val coordinator = coordinator(
            session,
            FakeUsbHost(),
            FakeDiscovery(listOf(stageCandidate)) {
                opening?.await()
                FakeTransport()
            },
        )
        coordinator.connect()
        advanceUntilIdle()

        opening = CompletableDeferred()
        coordinator.forceReconnect()
        advanceUntilIdle()
        assertTrue(coordinator.state.value is ConnectionState.Opening)

        coordinator.disconnect()
        opening.complete(Unit)
        advanceUntilIdle()
        assertTrue(coordinator.state.value is ConnectionState.DeviceSelection)
        assertEquals(null, coordinator.instrument)
    }

    @Test
    fun `a resume reopens the session's own instrument, not the first one a scan finds`() = runSession { session ->
        val opened = mutableListOf<String>()
        val coordinator = coordinator(
            session,
            FakeUsbHost(),
            FakeDiscovery(listOf(stageCandidate, grandCandidate)) {
                opened += it.physicalKey
                FakeTransport()
            },
        )
        coordinator.selectEntry(PickerEntry.Known(grandCandidate))
        advanceUntilIdle()

        coordinator.forceReconnect()
        advanceUntilIdle()
        assertTrue(coordinator.state.value is ConnectionState.Connected)
        assertEquals(listOf(grandKey, grandKey), opened)
    }

    @Test
    fun `with auto-connect off, a resume reconnects the chosen instrument rather than asking again`() =
        runSession { session ->
            val coordinator = coordinator(
                session,
                FakeUsbHost(),
                FakeDiscovery(listOf(stageCandidate)) { FakeTransport() },
                autoConnect = false,
            )
            coordinator.connect()
            advanceUntilIdle()
            coordinator.selectEntry((coordinator.state.value as ConnectionState.DeviceSelection).entries.single())
            advanceUntilIdle()

            coordinator.forceReconnect()
            advanceUntilIdle()
            assertTrue(coordinator.state.value is ConnectionState.Connected)
        }

    @Test
    fun `a resume whose instrument is gone offers the picker rather than switching to another`() =
        runSession { session ->
            val discovery = FakeDiscovery(listOf(stageCandidate, grandCandidate)) { FakeTransport() }
            val coordinator = coordinator(session, FakeUsbHost(), discovery)
            coordinator.selectEntry(PickerEntry.Known(grandCandidate))
            advanceUntilIdle()

            discovery.candidates = listOf(stageCandidate)
            coordinator.forceReconnect()
            advanceUntilIdle()
            val picker = coordinator.state.value as ConnectionState.DeviceSelection
            assertEquals(listOf(stageKey), picker.entries.map { (it as PickerEntry.Known).candidate.physicalKey })
            assertEquals(null, coordinator.instrument)
        }

    @Test
    fun `a choice the user has not made yet is not rebuilt on resume`() = runSession { session ->
        val coordinator = coordinator(
            session,
            FakeUsbHost(),
            FakeDiscovery(listOf(stageCandidate)) { FakeTransport() },
            autoConnect = false,
        )
        coordinator.connect()
        advanceUntilIdle()
        assertTrue(coordinator.state.value is ConnectionState.DeviceSelection)
        assertFalse(coordinator.shouldRebuildOnResume)

        coordinator.selectUnknownDevice(FakeDevice("usb:4092:99", productId = 99))
        assertTrue(coordinator.state.value is ConnectionState.UnknownDeviceWarning)
        assertFalse(coordinator.shouldRebuildOnResume)
    }

    @Test
    fun `demo mode from the advisory gate closes the instrument it replaces`() = runSession { session ->
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            session,
            FakeUsbHost(),
            FakeDiscovery(listOf(stageCandidate)) { FakeTransport() },
            create = { advisoryRaising(events) },
        )
        coordinator.connect()
        advanceUntilIdle()
        val gated = (coordinator.state.value as ConnectionState.AdvisoryWarning).instrument

        coordinator.connectDemo()
        advanceUntilIdle()
        assertEquals(listOf("closed"), events)
        val demo = (coordinator.state.value as ConnectionState.Connected).instrument
        assertNotSame(gated, demo)
        assertSame(demo, coordinator.instrument)
    }

    /**
     * Nothing is read behind the advisory gate: a Subsequent 37's listing would hold the
     * instrument for minutes and refuse the gate's own device report.
     */
    @Test
    fun `the listing starts only once the advisory is accepted`() = runSession { session ->
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            session,
            FakeUsbHost(),
            FakeDiscovery(listOf(stageCandidate)) { FakeTransport() },
            events = events,
            create = { advisoryRaising(mutableListOf()) },
        )
        coordinator.connect()
        advanceUntilIdle()
        assertTrue(coordinator.state.value is ConnectionState.AdvisoryWarning)
        assertTrue("the listing started behind the gate: $events", "started" !in events)

        coordinator.confirmAdvisory()
        assertEquals(listOf("started"), events.filter { it == "started" })
    }

    @Test
    fun `an accepted advisory is not raised again by a resume, but is after the user backs out`() =
        runSession { session ->
            val coordinator = coordinator(
                session,
                FakeUsbHost(),
                FakeDiscovery(listOf(stageCandidate)) { FakeTransport() },
                create = { advisoryRaising(mutableListOf()) },
            )
            coordinator.connect()
            advanceUntilIdle()
            assertTrue(coordinator.state.value is ConnectionState.AdvisoryWarning)
            coordinator.confirmAdvisory()

            coordinator.forceReconnect()
            advanceUntilIdle()
            assertTrue(coordinator.state.value is ConnectionState.Connected)

            coordinator.disconnect()
            advanceUntilIdle()
            coordinator.selectEntry((coordinator.state.value as ConnectionState.DeviceSelection).entries.single())
            advanceUntilIdle()
            assertTrue(coordinator.state.value is ConnectionState.AdvisoryWarning)
        }

    @Test
    fun `unplugging the device the unknown-device warning is about returns to the picker`() = runSession { session ->
        val newNord = FakeDevice("usb:4092:99", productId = 99)
        val keyboard = FakeDevice("usb:1234:1", vendorId = 0x1234, label = "Keyboard")
        val usb = FakeUsbHost(attached = listOf(newNord, keyboard))
        val coordinator = coordinator(session, usb, FakeDiscovery(emptyList()) { FakeTransport() })
        advanceUntilIdle()
        coordinator.selectUnknownDevice(newNord)
        assertTrue(coordinator.state.value is ConnectionState.UnknownDeviceWarning)

        usb.attached = listOf(keyboard)
        usb.detaches.emit(newNord)
        advanceUntilIdle()
        val picker = coordinator.state.value as ConnectionState.DeviceSelection
        assertEquals(
            listOf(keyboard.physicalKey),
            picker.entries.map {
                (it as PickerEntry.Unknown).device.physicalKey
            },
        )
    }
}
