// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import kotlinx.serialization.Serializable

/** Who answered, as a device report records it. */
@Serializable
data class ReportIdentity(
    val name: String,
    val firmwareVersion: String,
    val descriptorId: String,
) {
    companion object {
        fun of(identity: InstrumentIdentity) =
            ReportIdentity(identity.name, identity.firmwareVersion, identity.descriptorId)
    }
}
