package de.thewolfwalkexperience.software.patchpilot.demo

import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.GroupedBankAddressFormat
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentIdentity
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetEditor
import de.thewolfwalkexperience.software.patchpilot.core.PresetSelector
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTransfer
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import de.thewolfwalkexperience.software.patchpilot.core.Bus

/**
 * A fictitious instrument, implemented directly against [Instrument] with an in-memory library -
 * no fake wire underneath it. Mirrors a Nord: grouped addressing, categories, a display, native
 * edits - the shape "Try demo mode" on the connect screen shows, and the only one this class is
 * responsible for being faithful to.
 *
 * **This is demo mode's new home, one layer up from where it used to live.**
 * `DemoUsbTransport` fabricates *Nord wire bytes* so that the real `NordDevice` parses them, which
 * is genuinely valuable and stays - as a **test fixture**. It was the wrong basis for demo mode
 * once the app grew a second family, because it would have demanded a second fake wire (a fake
 * Pro-800 answering SysEx) purely to show the UI.
 *
 * A second, Pro-800-shaped profile used to live here too, kept only so the screens' facet-gating
 * could be exercised without hardware - never reachable from the UI itself. It has moved to
 * `core.NoCopyFixtureInstrument` in the test source set, which is what it always was: a test
 * fixture, not a second demo mode.
 */
class DemoInstrument : Instrument, PresetBrowser, PresetSelector, PresetEditor, DeviceReporter {

    private val library = DemoLibrary(NAMES, slotsPerBank = SLOTS_PER_BANK)

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
    override val report: DeviceReporter? = this

    /** Demo mode has no unknowable settings - there is no instrument to be configured. */

    /** Demo mode doesn't pretend to hand out preset blobs; there is nothing meaningful to hand. */
    override val transfer: PresetTransfer? = null

    /** There is no hardware to repair, so a resume must not tear this session down - which is
     * what the `isDemoMode` special case in `MainActivity` used to be for. */
    override val rebuildOnResume = false

    override suspend fun connect() = Unit
    override fun close() = Unit

    // ---- PresetBrowser ----

    /** One batch, like a real Nord - no artificial progress/delay to simulate here. */
    override fun index(): Flow<IndexUpdate> = flow {
        emit(IndexUpdate.Slots(library.slots(layout)))
        emit(IndexUpdate.Complete)
    }

    override suspend fun refresh(address: SlotAddress): PresetSlot = library.slot(address, layout)

    // ---- PresetSelector ----

    override suspend fun select(address: SlotAddress) {
        library.requireOccupied(address)
    }

    override fun confirmationFor(displayId: String) = "Selected $displayId."

    // ---- PresetEditor ----

    override val supported =
        setOf(EditOp.RENAME, EditOp.MOVE, EditOp.SWAP, EditOp.DELETE, EditOp.COPY)

    override fun isEmulated(op: EditOp) = false

    /**
     * A limit, because every real family has one and demo mode exists to exercise the same UI
     * paths. Without it the rename field behaves differently here than anywhere else -
     * uncapped, and with no character counter - which is exactly the divergence demo mode is
     * supposed to catch rather than introduce. 16 to match the Nord shape this mirrors.
     */
    override val maxNameLength = MAX_NAME_LENGTH

    override suspend fun rename(address: SlotAddress, newName: String) = library.rename(address, newName)
    override suspend fun move(from: SlotAddress, to: SlotAddress) = library.move(from, to)
    override suspend fun swap(a: SlotAddress, b: SlotAddress) = library.swap(a, b)
    override suspend fun delete(address: SlotAddress) = library.delete(address)
    override suspend fun copyProgram(src: SlotAddress, dst: SlotAddress): String = library.copy(src, dst)

    // ---- DeviceReporter ----

    override fun suggestedFilename() = "demo_instrument"

    override val description =
        "Demo mode has no instrument to read. This produces a placeholder file so the sharing " +
            "flow can be exercised without hardware."

    override suspend fun buildReport(progress: ((String) -> Unit)?): String {
        progress?.invoke("Building report...")
        return """
            {
              "note": "This is demo mode. No instrument was read; nothing here describes real hardware.",
              "instrument": "${identity.name}"
            }
        """.trimIndent()
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

        /** 16, matching the Nord shape this mirrors - see [maxNameLength]'s own note on why demo
         * mode declares a limit at all. */
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
