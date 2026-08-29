package de.thewolfwalkexperience.software.patchpilot.catalog

import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordInstrument

/**
 * Which unrecognised USB devices may be opened on a guess, and which must be refused.
 *
 * The app can offer to try an *unrecognised* device against a family's protocol - a Nord model
 * newer than this catalog, say. That is only defensible where the guess has evidence behind it,
 * and this object is where "has evidence" is defined.
 *
 * ### Why Nord and nothing else
 *
 * **The asymmetry is structural, not an accident of what was built first.** Clavia's vendor id
 * covers *two* models that this project has verified share one protocol - so
 * "another Clavia device probably speaks this too" is an inference with evidence. Yamaha's and
 * Behringer's ids cover exactly *one* instrument each, and a Motif XS is no evidence about Yamaha
 * synths in general: its address map was hard-won, `0x08` is a hole the instrument rejects, and
 * USER DR sits detached at `0x28` for no reason anyone has established.
 *
 * The rule that falls out is re-derivable rather than a preference: **guess a family from a vendor
 * id only where two members have already agreed.** Revisit if a second Yamaha or Behringer
 * instrument is added and turns out to speak its sibling's protocol (decided 2026-08-21).
 *
 * ### Why this exists at all
 *
 * Before it, `confirmUnknownDevice` built a `NordInstrument` for **whatever the user picked**,
 * and the picker listed every attached USB device unfiltered. Selecting a keyboard, a hub or a
 * charger claimed its interface and sent Clavia's vendor bulk protocol at it - unsolicited and far
 * from read-only, since `NordDevice` carries destructive sub-opcodes. It survived
 * because the feature predates the multi-family work: when the app only spoke Nord, "unknown
 * device" could only mean "an unrecognised Nord", and the assumption was true.
 */
object UnknownDevicePolicy {

    /** Families whose protocol may be tried on an unrecognised device of the same vendor. */
    private val FAMILIES_ALLOWING_A_GUESS = setOf(NordInstrument.FAMILY)

    /**
     * Vendor ids for which [allows] returns true, read from [descriptors] rather than hardcoded.
     *
     * From the catalog so that adding a Nord model needs no change here, and so this cannot
     * drift from the ids the registry actually matches on.
     */
    fun vendorIdsAllowingAGuess(descriptors: List<InstrumentDescriptor>): Set<Int> =
        descriptors
            .filter { it.family in FAMILIES_ALLOWING_A_GUESS }
            .mapNotNull { (it.match as? DeviceMatch.Usb)?.vendorId }
            .toSet()

    /**
     * May an unrecognised device with [vendorId] be opened on a guess?
     *
     * **Fails closed.** An empty or unreadable catalog yields an empty set and refuses everything,
     * which is the safe direction for a decision whose entire job is to avoid talking to
     * strangers.
     */
    fun allows(descriptors: List<InstrumentDescriptor>, vendorId: Int): Boolean =
        vendorId in vendorIdsAllowingAGuess(descriptors)
}
