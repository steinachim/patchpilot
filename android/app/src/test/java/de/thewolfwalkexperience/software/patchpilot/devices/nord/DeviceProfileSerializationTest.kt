package de.thewolfwalkexperience.software.patchpilot.devices.nord

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeviceProfile] is deliberately field-for-field devices/nord_devices.schema.json's `$defs/device`
 * shape, so NordInstrument.buildReport() can encode one straight into a paste-able
 * catalog entry (see that function's doc). These tests pin the two encoding details a plain
 * "it compiles" check wouldn't catch: [DeviceProfile.maxBankLetter] must serialize as a bare JSON
 * string (the schema's `^[A-Z]$` pattern, not a number), and the whole thing must round-trip
 * through the same [Json] config [InstrumentRegistry] uses to read the real catalog file.
 */
class DeviceProfileSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val profile = DeviceProfile(
        id = "some_new_device",
        name = "Some New Device",
        vendorId = 0x1234,
        productId = 0x5678,
        maxBankLetter = 'F',
        maxGroup = 10,
        slotsPerGroup = 5,
        maxDisplayTextLen = 16,
        supportedFirmwareVersions = setOf(142),
    )

    @Test
    fun `maxBankLetter encodes as a bare one-character JSON string, not a number`() {
        val encoded = json.encodeToString(profile)
        assertTrue("expected \"maxBankLetter\": \"F\" in $encoded", encoded.contains("\"maxBankLetter\": \"F\""))
    }

    @Test
    fun `programCategoryIds defaults to null, matching a real catalog entry`() {
        val encoded = json.encodeToString(profile)
        assertTrue("expected \"programCategoryIds\": null in $encoded", encoded.contains("\"programCategoryIds\": null"))
    }

    @Test
    fun `an encoded profile round-trips back through the catalog's own Json config`() {
        val encoded = json.encodeToString(profile)
        val decoded = json.decodeFromString(DeviceProfile.serializer(), encoded)
        assertEquals(profile, decoded)
    }

    /**
     * The catalog's `maxProgramNameLen` must actually reach the profile.
     *
     * It has a default, which is what makes this worth asserting: if the property name ever
     * stopped matching the JSON key, decoding would not fail - it would quietly fall back to the
     * display width and the rename cap would go on looking correct while no longer being read
     * from the catalog at all.
     */
    @Test
    fun `maxProgramNameLen is read from the catalog, not defaulted`() {
        val entry = """
            {"id":"nord_grand","name":"Nord Grand","vendorId":4092,"productId":43,
             "maxBankLetter":"P","maxGroup":5,"slotsPerGroup":5,
             "maxDisplayTextLen":16,"maxProgramNameLen":9,
             "supportedFirmwareVersions":[168]}
        """.trimIndent()
        val decoded = json.decodeFromString(DeviceProfile.serializer(), entry)
        assertEquals("the catalog's value must win over the default", 9, decoded.maxProgramNameLen)
    }

    /**
     * And an entry written before the field existed must still load, falling back to the display
     * width - which is what every instrument measured so far turns out to store.
     */
    @Test
    fun `an entry without maxProgramNameLen falls back to the display width`() {
        val entry = """
            {"id":"older","name":"Older","vendorId":4092,"productId":1,
             "maxBankLetter":"P","maxGroup":5,"slotsPerGroup":5,
             "maxDisplayTextLen":16,"supportedFirmwareVersions":[]}
        """.trimIndent()
        val decoded = json.decodeFromString(DeviceProfile.serializer(), entry)
        assertEquals(16, decoded.maxProgramNameLen)
    }

    @Test
    fun `an encoded profile parses as one entry of a real devices array`() {
        // Mirrors exactly how a user would paste NordInstrument.buildReport()'s
        // output into devices/nord_devices.json: as one more element of its "devices" array.
        val catalogJson = """
            {"schemaVersion": 1, "devices": [${json.encodeToString(profile)}]}
        """.trimIndent()
        val catalog = json.decodeFromString(DeviceCatalog.serializer(), catalogJson)
        assertEquals(listOf(profile), catalog.devices)
    }
}
