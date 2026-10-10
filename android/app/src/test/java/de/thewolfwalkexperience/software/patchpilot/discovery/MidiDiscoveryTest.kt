// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.discovery

import de.thewolfwalkexperience.software.patchpilot.catalog.DeviceMatch
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentDescriptor
import de.thewolfwalkexperience.software.patchpilot.catalog.UsbIds
import de.thewolfwalkexperience.software.patchpilot.catalog.hexToBytes
import de.thewolfwalkexperience.software.patchpilot.core.Bus
import de.thewolfwalkexperience.software.patchpilot.midi.FakeMidiTransport
import de.thewolfwalkexperience.software.patchpilot.transport.MidiTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a MIDI scan decides, against fake ports: which port is which instrument, which ports are
 * opened at all, and that every port it opens is closed again.
 */
class MidiDiscoveryTest {

    private class FakePort(
        override val label: String,
        override val usbIds: UsbIds? = null,
        override val virtual: Boolean = false,
    ) : MidiPort {
        override val physicalKey get() = usbIds?.let { "usb:${it.vendorId}:${it.productId}" } ?: "midi:$label"
        override val handle get() = "midi:$label"
    }

    /** [ports], each opened through [transportFor]; null there is a port that cannot be opened. */
    private class FakePorts(
        private val ports: List<FakePort>,
        private val transportFor: (port: FakePort, portIndex: Int) -> FakeMidiTransport?,
    ) : MidiPorts {
        val opened = mutableListOf<Pair<String, Int>>()
        val transports = mutableListOf<FakeMidiTransport>()

        override fun list(): List<MidiPort> = ports

        override suspend fun open(port: MidiPort, portIndex: Int): MidiTransport {
            opened += port.label to portIndex
            val transport = transportFor(port as FakePort, portIndex) ?: error("Couldn't open ${port.label}.")
            return transport.also { transports += it }
        }
    }

    private fun descriptor(
        id: String,
        probe: String,
        prefix: String,
        usbHint: DeviceMatch.Usb? = null,
        portIndex: Int = 0,
        launchOnUsbAttach: UsbIds? = null,
    ) = InstrumentDescriptor(
        id = id,
        family = id,
        name = id,
        match = DeviceMatch.MidiIdentity(
            probeHex = probe,
            replyPrefixHex = prefix,
            usbHint = usbHint,
            launchOnUsbAttach = launchOnUsbAttach,
            portIndex = portIndex,
        ),
    )

    // As devices/behringer_pro800.json and devices/fractal_axefx3.json declare them.
    private val pro800 = descriptor("pro800", "f0 00 20 32 00 01 24 00 06 f7", "f0 00 20 32 00 01 24 00 07")
    private val axeFx3 = descriptor("axefx3", "f0 00 01 74 10 08 1d f7", "f0 00 01 74 10 08")

    private val pro800Reply = "f0 00 20 32 00 01 24 00 07 50 52 4f 2d 38 30 30 f7".hexToBytes()

    private fun answering(vararg replies: ByteArray) = FakeMidiTransport { replies.toList() }

    @Test
    fun `a port that answers its descriptor's probe is identified`() = runTest {
        val port = FakePort("PRO 800", UsbIds(0x1397, 0x125F))
        val ports = FakePorts(listOf(port)) { _, _ -> answering(pro800Reply) }

        val candidate = MidiDiscovery(ports).scan(listOf(pro800)).single()

        assertSame(pro800, candidate.descriptor)
        assertEquals(Bus.MIDI, candidate.bus)
        assertEquals("usb:${0x1397}:${0x125F}", candidate.physicalKey)
        assertSame(port, candidate.handle)
        assertTrue(ports.transports.all { it.closed })
    }

    @Test
    fun `a silent port is not identified, and is closed`() = runTest {
        val ports = FakePorts(listOf(FakePort("Silent"))) { _, _ -> answering() }
        assertTrue(MidiDiscovery(ports).scan(listOf(pro800)).isEmpty())
        assertTrue(ports.transports.single().closed)
    }

    /** A loopback port sends the probe straight back, and the Axe-Fx III's prefix is the start of its own probe. */
    @Test
    fun `an echo of the probe is not an answer`() = runTest {
        val ports = FakePorts(listOf(FakePort("MS-1"))) { _, _ -> FakeMidiTransport { request -> listOf(request) } }
        assertTrue(MidiDiscovery(ports).scan(listOf(axeFx3)).isEmpty())
    }

    @Test
    fun `a reply in pieces after unrelated traffic is still an answer`() = runTest {
        val stray = "f0 7e 7f 06 02 00 f7".hexToBytes() // another device's identity reply
        val clock = "f8".hexToBytes()
        val ports = FakePorts(listOf(FakePort("PRO 800"))) { _, _ ->
            answering(stray, clock, pro800Reply.copyOfRange(0, 5), pro800Reply.copyOfRange(5, pro800Reply.size))
        }
        assertEquals(1, MidiDiscovery(ports).scan(listOf(pro800)).size)
    }

    @Test
    fun `a usb hint keeps the probe off ports backed by other devices`() = runTest {
        val hinted = descriptor(
            "hinted",
            "f0 00 20 32 00 01 24 00 06 f7",
            "f0 00 20 32 00 01 24 00 07",
            usbHint = DeviceMatch.Usb(vendorId = 0x1397, productId = 0x125F),
        )
        val match = FakePort("PRO 800", UsbIds(0x1397, 0x125F))
        val other = FakePort("Keyboard", UsbIds(0x1234, 0x0001))
        val virtual = FakePort("Network MIDI")
        val ports = FakePorts(listOf(other, match, virtual)) { _, _ -> answering(pro800Reply) }

        val candidates = MidiDiscovery(ports).scan(listOf(hinted))

        assertEquals(listOf("PRO 800" to 0), ports.opened)
        assertSame(match, candidates.single().handle)
    }

    @Test
    fun `a port that cannot be opened is skipped and the scan goes on`() = runTest {
        val broken = FakePort("Busy")
        val working = FakePort("PRO 800")
        val ports = FakePorts(listOf(broken, working)) { port, _ ->
            if (port ===
                broken
            ) {
                null
            } else {
                answering(pro800Reply)
            }
        }

        assertSame(working, MidiDiscovery(ports).scan(listOf(pro800)).single().handle)
    }

    @Test
    fun `each descriptor is probed on its own port, and opened on it again`() = runTest {
        val onSecondPort =
            descriptor("second", "f0 00 20 32 00 01 24 00 06 f7", "f0 00 20 32 00 01 24 00 07", portIndex = 1)
        val ports = FakePorts(listOf(FakePort("Interface"))) { _, index ->
            if (index ==
                1
            ) {
                answering(pro800Reply)
            } else {
                answering()
            }
        }
        val discovery = MidiDiscovery(ports)

        val candidate = discovery.scan(listOf(onSecondPort)).single()
        assertEquals(listOf("Interface" to 1), ports.opened)

        discovery.open(candidate)
        assertEquals("Interface" to 1, ports.opened.last())
    }

    /** Catalog order decides between two descriptors that would both accept the reply. */
    @Test
    fun `the first descriptor that answers wins, and the port is opened once for both`() = runTest {
        val broad = descriptor("broad", "f0 00 20 32 00 01 24 00 06 f7", "f0 00 20 32")
        val ports = FakePorts(listOf(FakePort("PRO 800"))) { _, _ -> answering(pro800Reply) }

        assertSame(broad, MidiDiscovery(ports).scan(listOf(broad, pro800)).single().descriptor)
        assertEquals(1, ports.opened.size)
    }

    /** Another app's port: each one would cost every probe's timeout, and no instrument is reached through one. */
    @Test
    fun `a virtual port is never opened, even one that would answer`() = runTest {
        val ports = FakePorts(listOf(FakePort("MS-1", virtual = true))) { _, _ -> answering(pro800Reply) }
        assertTrue(MidiDiscovery(ports).scan(listOf(pro800)).isEmpty())
        assertTrue(ports.opened.isEmpty())
    }

    /** A Nord's class-compliant interface: the USB scan claims the Nord through its vendor interface. */
    @Test
    fun `the MIDI interface of an instrument matched over USB is never opened`() = runTest {
        val nord =
            InstrumentDescriptor(id = "nord", family = "nord", name = "Nord", match = DeviceMatch.Usb(0x0FFC, 0x2B))
        val ports = FakePorts(listOf(FakePort("Nord Grand", UsbIds(0x0FFC, 0x2B)))) { _, _ -> answering(pro800Reply) }
        assertTrue(MidiDiscovery(ports).scan(listOf(nord, pro800)).isEmpty())
        assertTrue(ports.opened.isEmpty())
    }

    @Test
    fun `an instrument plugged in directly is probed first, with its own probe first`() = runTest {
        val pro800Direct = descriptor(
            "pro800",
            "f0 00 20 32 00 01 24 00 06 f7",
            "f0 00 20 32 00 01 24 00 07",
            launchOnUsbAttach = UsbIds(0x1397, 0x125F),
        )
        val interfacePort = FakePort("USB MIDI Interface", UsbIds(0x1234, 0x0001))
        val direct = FakePort("PRO 800", UsbIds(0x1397, 0x125F))
        val ports = FakePorts(listOf(interfacePort, direct)) { port, _ ->
            if (port === direct) answering(pro800Reply) else answering()
        }

        val candidates = MidiDiscovery(ports).scan(listOf(axeFx3, pro800Direct))

        assertEquals(listOf("PRO 800" to 0, "USB MIDI Interface" to 0), ports.opened)
        assertSame(direct, candidates.first().handle)
        val probe = (pro800Direct.match as DeviceMatch.MidiIdentity).probe
        assertTrue(ports.transports.first().sent.single().contentEquals(probe))
    }

    @Test
    fun `a catalog with no MIDI instruments opens nothing`() = runTest {
        val usbOnly =
            InstrumentDescriptor(id = "nord", family = "nord", name = "Nord", match = DeviceMatch.Usb(0x0FFC, 0x21))
        val ports = FakePorts(listOf(FakePort("PRO 800"))) { _, _ -> answering(pro800Reply) }
        assertTrue(MidiDiscovery(ports).scan(listOf(usbOnly)).isEmpty())
        assertTrue(ports.opened.isEmpty())
    }
}
