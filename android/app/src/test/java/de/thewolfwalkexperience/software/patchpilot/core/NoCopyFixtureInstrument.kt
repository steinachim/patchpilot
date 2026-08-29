package de.thewolfwalkexperience.software.patchpilot.core

import de.thewolfwalkexperience.software.patchpilot.demo.DemoLibrary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import de.thewolfwalkexperience.software.patchpilot.core.Bus

/**
 * A fictitious instrument shaped like a family with no copy of its own - flat `A00` addressing, no
 * categories, no display, emulated edits, and a slow index that arrives in batches - kept as a
 * **test fixture only**, never reachable from the app.
 *
 * Two things exercise it, and both are the reason it exists rather than being deleted alongside
 * `DemoInstrument`'s old `Profile.ANALOG`:
 *
 * - `InstrumentFacetTest` needs an instrument shaped differently from a Nord to check the
 *   screens' facet-gating - `report`/`transfer` absent, edits emulated, a batched index -
 *   without real Pro-800 or Motif XS hardware or protocol code behind it.
 * - `RegressionTesterTest` needs an instrument that declares no [EditOp.COPY], to exercise the
 *   debug menu's regression test's fallback path for a family that has no sandbox to work in.
 *
 * **Not modeled on either real family today.** Both the real `Pro800Editor` and the real
 * `MotifXsInstrument` gained `copyProgram` after this shape was first written as
 * `DemoInstrument.Profile.ANALOG`; nothing this app currently talks to actually lacks copy. The
 * name says what it is for rather than which instrument it mirrors, so it stays meaningful even
 * if that stops being true of every family, and doesn't need renaming if a fourth family without
 * copy shows up.
 */
class NoCopyFixtureInstrument : Instrument, PresetBrowser, PresetSelector, PresetEditor {

    private val library = DemoLibrary(NAMES, slotsPerBank = SLOTS_PER_BANK)

    override val layout: SlotLayout = SlotLayout.uniform(
        bankCount = 4,
        slotsPerBank = SLOTS_PER_BANK,
        format = FlatBankAddressFormat(slotDigits = 2),
    )

    override val identity = InstrumentIdentity(
        descriptorId = "no-copy-fixture",
        family = "fixture",
        name = "No-Copy Fixture",
        firmwareVersion = "1.00",
        bus = Bus.NONE,
        stableKey = "no-copy-fixture",
    )

    override val browser: PresetBrowser get() = this
    override val selector: PresetSelector get() = this
    override val editor: PresetEditor get() = this

    /** No device report, same as the two real families this shape used to stand in for. */
    override val report: DeviceReporter? = null
    override val setup: InstrumentSetup? = null
    override val transfer: PresetTransfer? = null

    override suspend fun connect() = Unit
    override fun close() = Unit

    // ---- PresetBrowser ----

    /**
     * Batched with progress and a small delay, unlike a Nord's single-batch index - so the
     * partially-loaded list rendering this fixture is also meant to exercise has something to
     * render partially.
     */
    override fun index(): Flow<IndexUpdate> = flow {
        val all = library.slots(layout)
        all.chunked(BATCH).forEachIndexed { batchIndex, batch ->
            delay(BATCH_DELAY_MS)
            emit(IndexUpdate.Progress((batchIndex + 1) * BATCH, all.size, "Reading ${batch.last().displayId}"))
            emit(IndexUpdate.Slots(batch))
        }
        emit(IndexUpdate.Complete)
    }

    override suspend fun refresh(address: SlotAddress): PresetSlot = library.slot(address, layout)

    // ---- PresetSelector ----

    override suspend fun select(address: SlotAddress) {
        library.requireOccupied(address)
    }

    /** Mirrors what a fire-and-forget instrument can honestly claim - see
     * NordInstrument.confirmationFor for the contrast. */
    override fun confirmationFor(displayId: String) = "Sent $displayId."

    // ---- PresetEditor ----

    /** No [EditOp.COPY] - the entire reason this fixture exists. */
    override val supported = setOf(EditOp.RENAME, EditOp.MOVE, EditOp.SWAP, EditOp.DELETE)

    override fun isEmulated(op: EditOp) = true

    override val maxNameLength = 16

    override suspend fun rename(address: SlotAddress, newName: String) = library.rename(address, newName)
    override suspend fun move(from: SlotAddress, to: SlotAddress) = library.move(from, to)
    override suspend fun swap(a: SlotAddress, b: SlotAddress) = library.swap(a, b)
    override suspend fun delete(address: SlotAddress) = library.delete(address)

    private companion object {
        const val BATCH = 10
        const val BATCH_DELAY_MS = 1L
        const val SLOTS_PER_BANK = 20
        val NAMES = listOf(
            "Fat Saw Bass", "Poly Brass", "Glass Pad", "Sync Lead", "Pluck Keys",
            "Res Sweep", "Soft Strings", "Hollow Pad", "Acid Bass", "Octave Lead",
            "PWM Strings", "Noise Perc", "Bell Tone", "Deep Drone", "Chorus Pad",
            "Wide Brass", "Hard Sync", "Slow Filter", "Ring Mod", "Unison Stab",
            "Detune Saw", "Sub Pulse", "Air Pad", "Vox Humana",
        )
    }
}
