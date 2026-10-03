// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AxeFx3ReporterTest {

    @Test
    fun `the report counts presets and times reads, and carries no name or preset data`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(3, "Secret Lead")
        fake.put(4, "Private Pad")
        fake.put(5, "")
        val instrument = axeFx3(fake).apply { connect() }
        instrument.select(SlotAddress(0, 4))
        val start = fake.requests.size

        val result = instrument.report.buildReport()

        assertTrue(result.isComplete)
        for (secret in listOf("Secret", "Private", "Wolfwalk")) assertFalse(secret, result.json.contains(secret))
        val json = Json.parseToJsonElement(result.json).jsonObject
        assertEquals(512, json["presetCount"]!!.jsonPrimitive.int)
        assertEquals(3, json["occupiedCount"]!!.jsonPrimitive.int)
        assertEquals(509, json["emptyCount"]!!.jsonPrimitive.int)
        assertEquals(1, json["unnamedCount"]!!.jsonPrimitive.int)
        assertEquals(4, json["loadedPresetNumber"]!!.jsonPrimitive.int)
        assertEquals(2, json["dumpReads"]!!.jsonArray.size)
        assertTrue(json["dumpReadsIdentical"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf(336, 2048, 2048, 16, 512),
            json["capabilities"]!!.jsonArray.map { it.jsonPrimitive.int },
        )
        // Read-only: nothing selected, renamed, written or stored.
        val sent = fake.commands().drop(start)
        assertTrue(sent.none { it in setOf("01/27", "01/28", "01/26", "77", "78", "79") })
        assertEquals(4, fake.selected)
    }

    @Test
    fun `a query that fails is recorded and the report is still built`() = runTest {
        val fake = FakeAxeFx3()
        val instrument = axeFx3(fake).apply { connect() }
        fake.capabilitiesReply = null

        val result = instrument.report.buildReport()

        assertFalse(result.isComplete)
        assertTrue(result.failures.containsKey("capabilities"))
        assertTrue(result.json.contains("\"presetCount\": 512"))
    }
}
