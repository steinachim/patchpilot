// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetTagger

/**
 * What the session offers, derived once per [ConnectionState] rather than read off the instrument
 * by each screen: facets and layout do not change within a session, so a screen collects this
 * instead of re-asking on every recomposition. The defaults are "nothing connected", which is
 * also what a screen sees across the reconnect a resume causes.
 *
 * @property instrumentName the connected instrument's own name, for the app bar.
 * @property advisory the connected instrument's advisory: the banner that keeps the warning
 *   visible once the gate has been passed.
 * @property canSelect whether tapping a preset can load it: a family may browse and nothing else.
 * @property supportedEdits which edit operations exist at all - the drag handle and the row menu.
 * @property emulatedEdits edits composed host-side rather than one device command, which the
 *   delete dialog words its warning from.
 * @property maxNameLength the longest name the instrument stores, or null where unlimited or
 *   unknown - caps the rename field rather than letting the instrument truncate silently.
 * @property browsingScopes the listings offered; a single entry means no selector is shown.
 * @property readOnlyBanks bank indices the instrument refuses writes to.
 * @property bankRailLabels bank label -> what the index rail draws for it, keyed by the label the
 *   rows carry so the rail can render something narrow and still jump by the header's value.
 * @property virtualBankLabels banks the app made up to split a flat numbering, whose headers show
 *   the range alone.
 * @property hasReport whether the instrument can describe itself. True behind the advisory gate
 *   too, where the connect screen offers the report.
 * @property isUnknownDevice connected to something the catalog does not list - what "Share device
 *   details" on the preset screen is gated on.
 * @property factoryBanks the banks whose shipped names can be checked against the instrument.
 */
data class SessionCapabilities(
    val instrumentName: String? = null,
    val advisory: String? = null,
    val canSelect: Boolean = false,
    val supportedEdits: Set<EditOp> = emptySet(),
    val emulatedEdits: Set<EditOp> = emptySet(),
    val maxNameLength: Int? = null,
    val browsingScopes: List<PresetScope> = listOf(PresetScope.USER),
    val readOnlyBanks: Set<Int> = emptySet(),
    val bankRailLabels: Map<String, String> = emptyMap(),
    val virtualBankLabels: Set<String> = emptySet(),
    val tagger: PresetTagger? = null,
    val hasReport: Boolean = false,
    val isUnknownDevice: Boolean = false,
    val factoryBanks: List<Pair<Int, String>> = emptyList(),
) {
    val canVerifyFactoryNames: Boolean get() = factoryBanks.isNotEmpty()

    companion object {
        fun of(state: ConnectionState): SessionCapabilities {
            val pending = (state as? ConnectionState.AdvisoryWarning)?.instrument
            val connected = (state as? ConnectionState.Connected)?.instrument
                ?: return SessionCapabilities(hasReport = pending?.report != null)
            return of(connected)
        }

        private fun of(instrument: Instrument): SessionCapabilities {
            val banks = instrument.layout.banks
            val editor = instrument.editor
            return SessionCapabilities(
                instrumentName = instrument.identity.name,
                advisory = instrument.advisory,
                canSelect = instrument.selector != null,
                supportedEdits = editor?.supported.orEmpty(),
                emulatedEdits = editor?.supported.orEmpty().filter { editor?.isEmulated(it) == true }.toSet(),
                maxNameLength = editor?.maxNameLength,
                browsingScopes = instrument.browser.scopes,
                readOnlyBanks = banks.withIndex().filter { it.value.readOnly }.map { it.index }.toSet(),
                bankRailLabels = banks.associate { it.label to it.shortLabel },
                virtualBankLabels = banks.filter { it.virtual }.map { it.label }.toSet(),
                tagger = instrument.tagger,
                hasReport = instrument.report != null,
                isUnknownDevice = instrument.identity.isGuessed,
                factoryBanks = instrument.factoryNames?.banks.orEmpty(),
            )
        }
    }
}
