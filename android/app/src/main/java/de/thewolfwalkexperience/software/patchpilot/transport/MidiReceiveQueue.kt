// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.transport

import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Takes the bytes `android.media.midi` delivers on its reader thread and hands them on from a
 * coroutine, so that thread does no more than copy and queue.
 *
 * The platform's socket to this app holds only a few packets. A reader that is late by a few
 * milliseconds makes the service's write fail with EAGAIN, and the service then drops this
 * receiver for good: the port goes silent, mid-reply, for every later request too (measured with
 * an Axe-Fx III preset dump, 49 KB at about 2,000 callbacks of 25 bytes). Anything that can block
 * or wake other threads per callback, a flow's collectors included, has to stay off that thread.
 *
 * What queued up while the consumer was away goes out as one array, in arrival order.
 *
 * @param emit receives each batch, from [scope] only.
 */
internal class MidiReceiveQueue(scope: CoroutineScope, private val emit: (ByteArray) -> Unit) {

    private val pending = ConcurrentLinkedQueue<ByteArray>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (signal in wake) drain()
        }
    }

    /** Copies [count] bytes of [data] from [offset]: the platform may reuse its buffer on return. */
    fun push(data: ByteArray, offset: Int, count: Int) {
        // Clamped rather than trusted: virtual ports published by other apps share this callback,
        // and an out-of-range pair would throw on a platform thread.
        val start = offset.coerceIn(0, data.size)
        val end = (offset + count).coerceIn(start, data.size)
        if (end <= start) return
        pending.add(data.copyOfRange(start, end))
        wake.trySend(Unit)
    }

    private fun drain() {
        var single: ByteArray? = null
        var joined: ByteArrayOutputStream? = null
        while (true) {
            val chunk = pending.poll() ?: break
            when {
                single == null -> single = chunk

                else -> {
                    val out = joined ?: ByteArrayOutputStream().also { it.write(single!!) }
                    joined = out
                    out.write(chunk)
                }
            }
        }
        val batch = joined?.toByteArray() ?: single ?: return
        emit(batch)
    }
}
