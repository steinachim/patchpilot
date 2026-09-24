// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.demo

import de.thewolfwalkexperience.software.patchpilot.core.DeviceReportResult
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.GroupedBankAddressFormat
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentIdentity
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetEditor
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSelector
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTagger
import de.thewolfwalkexperience.software.patchpilot.core.PresetTags
import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.PresetTransfer
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import de.thewolfwalkexperience.software.patchpilot.core.Bus

/**
 * A fictitious instrument, implemented directly against [Instrument] with an in-memory library
 * rather than a fake transport, so showing the UI needs no fake wire protocol per family. Mirrors
 * a Nord: grouped addressing, one category per preset, native edits, no favorites.
 *
 * `DemoUsbTransport` and `core.NoCopyFixtureInstrument` in the test source set are the fixtures
 * that do fabricate wire bytes and a second facet shape.
 */
class DemoInstrument : Instrument, PresetBrowser, PresetSelector, PresetEditor, DeviceReporter {

    private val library = DemoLibrary(
        NAMES,
        slotsPerBank = SLOTS_PER_BANK,
        categoryByName = DemoCategories.BY_PRESET,
    )

    override val layout: SlotLayout = SlotLayout.uniform(
        bankCount = BANK_COUNT,
        slotsPerBank = SLOTS_PER_BANK,
        format = GroupedBankAddressFormat(groupsPerBank = GROUPS_PER_BANK, slotsPerGroup = SLOTS_PER_GROUP),
    )

    override val identity = InstrumentIdentity(
        descriptorId = DEMO_DESCRIPTOR_ID,
        family = FAMILY,
        name = "Demo Instrument",
        firmwareVersion = "1.00",
        bus = Bus.NONE,
        stableKey = "demo",
    )

    override val browser: PresetBrowser get() = this
    override val selector: PresetSelector get() = this
    override val editor: PresetEditor get() = this
    /** Categories, shaped like a Nord's - see [DemoCategories]. No favorites: nothing here models the per-bank table one lives in. */
    override val tagger: PresetTagger = DemoTagger()

    override val report: DeviceReporter? = this

    /** Demo mode doesn't pretend to hand out preset blobs; there is nothing meaningful to hand. */
    override val transfer: PresetTransfer? = null

    /** There is no hardware to repair, so a resume must not tear this session down. */
    override val rebuildOnResume = false

    override suspend fun connect() = Unit
    override fun close() = Unit

    // ---- PresetBrowser ----

    /** One batch, like a real Nord. [scope] is ignored; the demo library declares only [PresetScope.USER]. */
    override fun index(scope: PresetScope): Flow<IndexUpdate> = flow {
        emit(IndexUpdate.Slots(library.slots(layout)))
        emit(IndexUpdate.Complete)
    }

    override suspend fun refresh(address: SlotAddress): PresetSlot = library.slot(address, layout)

    // ---- PresetSelector ----

    override suspend fun select(address: SlotAddress) {
        library.requireOccupied(address)
    }

    // ---- PresetEditor ----

    override val supported =
        setOf(EditOp.RENAME, EditOp.MOVE, EditOp.SWAP, EditOp.DELETE, EditOp.COPY)

    override fun isEmulated(op: EditOp) = false

    /** A limit, so the rename field behaves here as it does against a real instrument. 16, matching the Nord shape this mirrors. */
    override val maxNameLength = MAX_NAME_LENGTH

    override suspend fun rename(address: SlotAddress, newName: String) = library.rename(address, newName)
    override suspend fun move(from: SlotAddress, to: SlotAddress) = library.move(from, to)
    override suspend fun swap(a: SlotAddress, b: SlotAddress) = library.swap(a, b)
    override suspend fun delete(address: SlotAddress) = library.delete(address)
    override suspend fun copyProgram(src: SlotAddress, dst: SlotAddress): String = library.copy(src, dst)

    // ---- PresetTagger ----

    /** The demo's category editor, backed by the same in-memory library the browser reads. */
    private inner class DemoTagger : PresetTagger {
        override val taxonomy = DemoCategories.taxonomy

        /** One, like the Nord this mirrors. */
        override val assignmentCount = 1

        override val favorites = null

        /** `None` is a category here, not the absence of one - same as a Nord. */
        override val allowsUnassigned = false

        override fun canSetCategories(address: SlotAddress) = true

        override fun canSetFavorite(address: SlotAddress) = false

        override suspend fun read(address: SlotAddress) =
            PresetTags(categories = listOf(DemoCategories.refOf(library.categoryAt(address))))

        override suspend fun setCategories(address: SlotAddress, categories: List<CategoryRef?>) {
            val wanted = categories.firstOrNull()
                ?: throw IllegalArgumentException(
                    "This instrument always files a preset under a category; there is none to clear."
                )
            val name = DemoCategories.nameOf(wanted)
                ?: throw IllegalArgumentException("No category ${wanted.main} on this instrument.")
            library.setCategoryAt(address, name)
        }

        override suspend fun setFavorite(address: SlotAddress, under: Set<Int>): Unit =
            throw UnsupportedOperationException("Demo mode has no favorites.")
    }

    // ---- DeviceReporter ----

    override fun suggestedFilename() = "demo_instrument"

    override val description =
        "Demo mode has no instrument to read. This produces a placeholder file so the sharing " +
            "flow can be exercised without hardware."

    override suspend fun buildReport(progress: ((String) -> Unit)?): DeviceReportResult {
        progress?.invoke("Building report...")
        val json = """
            {
              "note": "This is demo mode. No instrument was read; nothing here describes real hardware.",
              "instrument": "${identity.name}"
            }
        """.trimIndent()
        // Nothing was read, so nothing can have failed to read.
        return DeviceReportResult(json, emptyMap())
    }

    companion object {
        const val DEMO_DESCRIPTOR_ID = "demo"

        /** The family key, matching the `FAMILY` constant every real family declares. */
        const val FAMILY = "demo"

        private const val BANK_COUNT = 4
        private const val GROUPS_PER_BANK = 5
        private const val SLOTS_PER_GROUP = 5

        /** Derived, so the two halves of the layout cannot disagree. */
        private const val SLOTS_PER_BANK = GROUPS_PER_BANK * SLOTS_PER_GROUP

        /** 16, matching the Nord shape this mirrors - see [maxNameLength]. */
        private const val MAX_NAME_LENGTH = 16
        private val NAMES = listOf(
            "Concert Grand", "Studio Upright", "Rhodes Mk I", "Wurli 200A", "Clavinet D6",
            "Harpsichord", "Church Organ", "Jazz Organ", "String Pad", "Warm Brass",
            "Nylon Guitar", "Fretless Bass", "Choir Aah", "Vibraphone", "Marimba",
            "Bright Piano", "Dark Piano", "Tine EP", "Reed EP", "Toy Piano",
            "Celesta", "Glockenspiel", "Mellotron Flute", "Pipe Organ", "Accordion",
            "Analog Strings", "Poly Brass", "Solo Lead", "Sub Bass", "Bell Pad",
        )
    }
}
