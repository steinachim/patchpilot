package de.thewolfwalkexperience.software.patchpilot.midi

import de.thewolfwalkexperience.software.patchpilot.transport.MidiTransport
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A scripted MIDI device: whatever [respond] returns for a request is emitted back, in whatever
 * chunks the test wants.
 *
 * The MIDI sibling of `ReplayTransport`. Returning a *list* of chunks rather than one array is
 * deliberate - it lets a test reproduce the split deliveries a real port produces, which is where
 * the framing bugs live.
 */
class FakeMidiTransport(
    private val respond: (request: ByteArray) -> List<ByteArray>,
) : MidiTransport {

    val sent = mutableListOf<ByteArray>()

    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()
    override val rebuildOnResume = false

    override fun send(bytes: ByteArray) {
        sent += bytes
        respond(bytes).forEach { _incoming.tryEmit(it) }
    }

    /** Pushes bytes nobody asked for - CC echo, another device on a shared bus. */
    fun inject(chunk: ByteArray) {
        _incoming.tryEmit(chunk)
    }

    var closed = false
        private set

    override fun close() {
        closed = true
    }
}
