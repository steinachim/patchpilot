// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

/**
 * What this app decodes out of a Motif XS voice dump: its name and its category assignments. A
 * voice dump is roughly 1.9 kB and the rest is not decoded.
 */
object MotifXsVoice {

    /**
     * Yamaha MSB packing: one byte carrying the high bits of the following seven, in groups of
     * eight. Unpacked once into a dense array, so field offsets are plain indices.
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

    /** Where the packed region starts inside a dump's payload: two bytes in. What those two bytes are is not decoded. */
    const val PACKED_OFFSET = 2

    /**
     * The ASCII figure pair the dense stream opens with: two decimal numbers, colon-separated and
     * colon-terminated (`256:256:`, `227:146:`, `0:0:`). They are the voice's two category
     * assignments - see [categoriesOf].
     *
     * Variable width, so the name after them does not start at a fixed offset, and a category can
     * never be written by patching the pair in place: changing `9` to `146` moves every byte
     * after it.
     */
    val NAME_PREFIX = Regex("""\d{1,4}:\d{1,4}:""")

    /**
     * How far after the name it is repeated: the name appears as text, then a 6-byte trailer,
     * then again. The repeat settles the field's true length, since a greedy printable read
     * overshoots by one whenever the first trailer byte is printable (`Kawala` reads as
     * `KawalaS`). The rule is positional: finding the repeat at +5 rather than +6 means the read
     * ran one byte long, which keeps a genuine trailing letter (`New Stab`) intact. What the six
     * bytes mean is not decoded.
     */
    const val NAME_TRAILER_BYTES = 6

    /**
     * A safety bound on the greedy scan, not a field width: the trailer rule is what settles a
     * name's length, and capping the scan at 20 would truncate a printable run that legitimately
     * extends further before the repeat is found.
     */
    const val NAME_SCAN_LIMIT = 64

    /**
     * The name field's width, confirmed two ways: the trailer rule yields no name longer than 20
     * characters, and the vendor editor's rename sends exactly 20 parameter changes at offsets
     * `0x00`..`0x13`.
     */
    const val NAME_LENGTH = 20

    /**
     * The voice's name, or null where the slot holds none: the name starts after the
     * variable-width figure pair, runs while printable, and ends where the trailer-and-repeat
     * says. An empty slot presents as no name, which is the only emptiness signal available,
     * since every address answers a request.
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

        // A name has to be corroborated by its own repeat: `0C`'s layout past the name field is
        // not decoded, so a byte following a blank name would otherwise read as a one-character
        // voice. The strict and greedy readings agree everywhere except on a cleared slot.
        val at = start + length + NAME_TRAILER_BYTES
        if (at >= dense.size || (dense[at].toInt() and 0x7F) != (dense[start].toInt() and 0x7F)) {
            return null
        }

        val text = StringBuilder()
        for (i in start until start + length) text.append((dense[i].toInt() and 0xFF).toChar())
        return text.toString().trim().ifEmpty { null }
    }

    /**
     * [run] overshoots by one when the trailer's first byte is printable, told apart by where the
     * repeat sits ([NAME_TRAILER_BYTES] after the real end). Where both positions match the
     * longer reading wins: a wrongly stripped name is silent, a wrongly kept character visible.
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

    /**
     * The voice's two category assignments, as `main * 16 + sub` figures, null where unassigned;
     * null altogether where the payload carries no figure pair, the same signal [nameOf] treats
     * as an unreadable slot.
     *
     * The `0C` dump carries the same information as the documented four bytes at `0x18`-`0x1B`,
     * packed one pair per number: `227:146:` is `227 = 14*16 + 3` (M.EFX / Hit) and
     * `146 = 9*16 + 2` (Pads / Brite); `256` is `NoAsg`. Validated against the shipped factory
     * table on 205 user voices whose names match factory voices, with no mismatches.
     *
     * Read-only: the figures are variable width, so a category is written through the documented
     * path (`MotifXsInstrument.editCommonBlock`) instead.
     */
    fun categoriesOf(dumpPayload: ByteArray): List<Int?>? {
        if (dumpPayload.size <= PACKED_OFFSET) return null
        return figuresOf(unpack(dumpPayload.copyOfRange(PACKED_OFFSET, dumpPayload.size)))
    }

    /** [categoriesOf] on an already-unpacked stream, so a caller that has one need not unpack twice. */
    fun figuresOf(dense: ByteArray): List<Int?>? {
        val head = String(dense, 0, minOf(dense.size, 12), Charsets.ISO_8859_1)
        val match = NAME_PREFIX.find(head) ?: return null
        if (match.range.first != 0) return null
        // The regex guarantees two runs of one to four digits; the range check is about the
        // values, since an unrecognised figure must read as unassigned.
        return match.value.trimEnd(':').split(':').map { figure ->
            figure.toIntOrNull()?.takeIf { it in 0 until MotifXsCategories.NO_ASSIGNMENT_FIGURE }
        }
    }
}
