package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

/**
 * The one thing this app currently decodes out of a Motif XS voice: its name.
 *
 * A voice dump is roughly 1.9 kB and almost none of it is understood. That is deliberate for a
 * first iteration - a browser needs a name and nothing else.
 */
object MotifXsVoice {

    /**
     * Yamaha MSB packing: one byte carrying the high bits of the following seven, in groups of
     * eight. Bit *j* of the group's first byte is the high bit of the *j*-th byte after it.
     *
     * Structurally the same scheme the Pro-800 uses, and unpacked the same way and for the same
     * reason: once into a dense array, so field offsets are plain indices rather than a walk with
     * inline skips.
     */
    fun unpack(packed: ByteArray): ByteArray {
        val out = ArrayList<Byte>(packed.size)
        var i = 0
        while (i < packed.size) {
            val msb = packed[i].toInt()
            var j = 0
            while (j < 7 && i + 1 + j < packed.size) {
                val b = packed[i + 1 + j].toInt() and 0x7F
                out.add((b or (if (msb and (1 shl j) != 0) 0x80 else 0)).toByte())
                j++
            }
            i += 8
        }
        return out.toByteArray()
    }

    /**
     * Where the packed region starts inside a dump's payload.
     *
     * **Two bytes in, not zero.** Unpacking from the payload start yields garbage; from offset 2
     * it yields clean text. What those two bytes are is not decoded.
     */
    const val PACKED_OFFSET = 2

    /**
     * The ASCII figure pair the dense stream opens with: two decimal numbers, colon-separated and
     * colon-terminated - `256:256:`, `192:256:`, `83:146:`, `0:0:`. What they mean is not decoded.
     *
     * **They are variable width, so the name after them does not start at a fixed offset.**
     * Reading it at a constant position (assuming both figures are three digits) mis-decodes
     * names whose preceding figures are a different width, dropping the leading character - e.g.
     * `Dyno Straight MW+AS2` decodes as `yno Straight MW+AS2`.
     */
    val NAME_PREFIX = Regex("""\d{1,4}:\d{1,4}:""")

    /**
     * How far after the name it is repeated.
     *
     * The name appears twice: once as text, then a **6-byte trailer**, then again. That repeat
     * is what gives the field's true length, and it is needed because a greedy printable read
     * overshoots by one whenever the first trailer byte happens to be printable: `Kawala` decodes
     * as `KawalaS`, `Big Kit` as `Big Kitb`, if the run is trusted on its own.
     *
     * The rule is positional, not a guess about letters: the repeat sits 6 bytes after the *real*
     * end, so finding it at +5 instead means the read ran one byte long. This partitions every
     * named slot correctly, and it keeps `New Stab` - whose trailing `b` is genuine - intact.
     * That case is why no "strip a trailing capital" heuristic is used.
     *
     * What the six bytes mean, and why the repeat is sometimes high-bit-set, is not decoded.
     * Only its position is used.
     */
    const val NAME_TRAILER_BYTES = 6

    /**
     * A safety bound on the greedy scan, **not** a field width.
     *
     * The longest name this instrument produces is 20 characters, but that is an observation the
     * trailer rule yields rather than an assumption fed into it. Capping the scan at 20 directly
     * would get some names right for the wrong reason while silently truncating others whose
     * printable run legitimately extends further before the trailer settles the true length.
     */
    const val NAME_SCAN_LIMIT = 64

    /**
     * The name field's width, confirmed two independent ways.
     *
     * Reading: the trailer rule yields no name longer than 20 characters. Writing: the vendor
     * editor's rename sends exactly 20 parameter changes, at offsets `0x00`..`0x13`. Two
     * independent measurements of the same number, which is why this is stated as a width rather
     * than as an observed maximum.
     */
    const val NAME_LENGTH = 20

    /**
     * The voice's name, or null where the slot holds none.
     *
     * Three bounds combine: the name starts after the variable-width figure pair, runs while
     * printable, and ends where the trailer-and-repeat says it does rather than where the
     * printable run happens to stop.
     *
     * An empty slot has no printable run at all, which is how it presents - and the only
     * empty-slot signal available, since every address answers a request either way.
     */
    fun nameOf(dumpPayload: ByteArray): String? {
        if (dumpPayload.size <= PACKED_OFFSET) return null
        val dense = unpack(dumpPayload.copyOfRange(PACKED_OFFSET, dumpPayload.size))
        val start = nameStart(dense) ?: return null
        var run = 0
        while (run < NAME_SCAN_LIMIT && start + run < dense.size &&
            (dense[start + run].toInt() and 0xFF).let { it in 0x20..0x7E }
        ) {
            run++
        }
        if (run == 0) return null
        val length = trueNameLength(dense, start, run)

        // **A name has to be corroborated by its own repeat.** The printable run alone is not
        // enough: `0C`'s layout past the name field is not decoded, so whatever byte happens to
        // follow a *blank* name gets read as a one-character voice - e.g. a cleared name reading
        // back as `b`, the `b` being some undecoded byte of the serialisation and not a name at
        // all. An empty row is the whole signal the browser has for an empty slot, since there is
        // no emptiness flag on the wire.
        //
        // Requiring the repeat costs nothing on real data: the strict and greedy readings agree
        // everywhere except on a genuinely cleared slot.
        val at = start + length + NAME_TRAILER_BYTES
        if (at >= dense.size || (dense[at].toInt() and 0x7F) != (dense[start].toInt() and 0x7F)) {
            return null
        }

        val text = StringBuilder()
        for (i in start until start + length) text.append((dense[i].toInt() and 0xFF).toChar())
        return text.toString().trim().ifEmpty { null }
    }

    /**
     * [run] overshoots by one when the trailer's first byte is printable.
     *
     * Told apart by where the name's repeat sits: [NAME_TRAILER_BYTES] after the real end.
     * Finding it one byte early means the run swallowed a trailer byte. Where both positions
     * match, the longer reading wins - never strip on an ambiguity, because a wrongly stripped
     * name is silent corruption and a wrongly kept character is visible.
     */
    fun trueNameLength(dense: ByteArray, start: Int, run: Int): Int {
        val first = dense[start].toInt() and 0x7F
        val atReal = start + run + NAME_TRAILER_BYTES
        val atShort = atReal - 1
        val matchesShort = atShort < dense.size && (dense[atShort].toInt() and 0x7F) == first
        val matchesReal = atReal < dense.size && (dense[atReal].toInt() and 0x7F) == first
        return if (matchesShort && !matchesReal) run - 1 else run
    }

    /** Where the name begins: just past the figure pair, or null if there is no figure pair. */
    fun nameStart(dense: ByteArray): Int? {
        val head = String(dense, 0, minOf(dense.size, 12), Charsets.ISO_8859_1)
        val match = NAME_PREFIX.find(head) ?: return null
        return if (match.range.first == 0) match.range.last + 1 else null
    }
}
