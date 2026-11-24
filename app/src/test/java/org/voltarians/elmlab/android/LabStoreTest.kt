package org.voltarians.elmlab.android

import org.junit.Assert.*
import org.junit.Test
import org.voltarians.elmlab.*

class LabStoreTest {
    @Test fun presetsRoundTripWithoutLosingFields() {
        VehicleProfile.presets().forEach { assertEquals(it, LabStore.decode(LabStore.encode(it))) }
    }
    @Test fun scriptedMultilineResponsesRoundTrip() {
        val p = VehicleProfile(responseScript = mapOf("22F190" to listOf("NO DATA", "7E8 03 62 F1 90\r7E8 21 AA")))
        assertEquals(p, LabStore.decode(LabStore.encode(p)))
    }
    @Test fun invalidOrFutureProfilesAreRejected() {
        for (text in listOf("{}", LabStore.encode(VehicleProfile()).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"), LabStore.encode(VehicleProfile()).replace("TESTCAR0000000001", "SHORT"))) {
            try { LabStore.decode(text); fail("Expected invalid profile to fail") } catch (_: Exception) { }
        }
    }
}
