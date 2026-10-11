// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Subsequent37ReporterTest {

    @Test
    fun `the report reads identity, settings and the selected program, and changes nothing`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val instrument = subsequent37(fake).apply { connect() }
        val result = instrument.report.buildReport()
        assertTrue(result.failures.toString(), result.isComplete)

        val json = Json.parseToJsonElement(result.json).jsonObject
        assertEquals("1.2.0", json["identityFirmware"]!!.jsonPrimitive.content)
        assertEquals(268, json["settingsWords"]!!.jsonArray.size)
        assertEquals(153, json["selectedProgram"]!!.jsonPrimitive.int)
        assertEquals("1", json["inChannel"]!!.jsonPrimitive.content)
        assertEquals(16, json["outChannel"]!!.jsonPrimitive.int)
        val reads = json["programReads"]!!.jsonArray
        assertEquals(2, reads.size)
        assertEquals(153, reads[0].jsonObject["program"]!!.jsonPrimitive.int)
        assertEquals("true", json["programReadsIdentical"]!!.jsonPrimitive.content)

        // No preset name, and nothing written or selected.
        assertFalse(result.json.contains("Preset "))
        assertTrue(fake.writes.isEmpty())
        assertNull(fake.selected)
    }

    @Test
    fun `a report survives an instrument that answers nothing but reads`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply { identityReply = null }
        val instrument = subsequent37(fake).apply { connect() }
        val result = instrument.report.buildReport()
        assertEquals(setOf("identity"), result.failures.keys)
        assertEquals(2, Json.parseToJsonElement(result.json).jsonObject["programReads"]!!.jsonArray.size)
    }
}
