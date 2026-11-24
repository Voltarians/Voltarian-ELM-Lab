package org.voltarians.elmlab

import java.util.Locale
import kotlin.test.*

class Elm327EngineTest {
    private fun engine(p: VehicleProfile = VehicleProfile()) = Elm327Engine(p).also { it.execute("ATE0"); it.execute("ATSP6") }
    @Test fun identityAndPrompt() = assertEquals("ATI\rELM327 v1.5\r>", Elm327Engine().execute("ATI"))
    @Test fun echoTransitionUsesPreviousSetting() {
        val e = Elm327Engine()
        assertEquals("ATE0\rOK\r>", e.execute("ATE0"))
        assertEquals("OK\r>", e.execute("ATE1"))
        assertEquals("ATI\rELM327 v1.5\r>", e.execute("ATI"))
    }
    @Test fun remoteProfileControl() {
        val e = engine()
        assertEquals("OK\r>", e.execute("ATVSETSPD42"))
        assertEquals("41 0D 2A\r>", e.execute("010D"))
        assertTrue(e.execute("ATVPROFILE").contains("SPD=42"))
    }
    @Test fun inputValidationAndUnknownCommands() {
        val e = engine()
        for (command in listOf("ATMA", "ATSP9", "ATBOGUS", "ATVSETSPD256", "ATVSETVOLTNaN", "ATVSETTEMP-41", "GG", "010")) assertEquals("?\r>", e.execute(command), command)
        assertEquals("NO DATA\r>", e.execute("22F190"))
        assertEquals("NO DATA\r>", e.execute("0106"))
    }
    @Test fun numericEncoding() {
        val e = engine(VehicleProfile(rpm = 2200, speedKph = 100, coolantC = 90, controlModuleVoltage = 14.2))
        assertEquals("41 0C 22 60\r>", e.execute("010C"))
        assertEquals("41 0D 64\r>", e.execute("010D"))
        assertEquals("41 05 82\r>", e.execute("0105"))
        assertEquals("41 42 37 78\r>", e.execute("0142"))
    }
    @Test fun supportedBitmapsMatchImplementedPids() {
        val e = engine()
        for (base in listOf(0, 32, 64)) {
            val text = e.execute("01%02X".format(base)).substringBefore('\r').split(' ')
            val bits = text.drop(2).joinToString("").toLong(16)
            for (offset in 1..32) {
                val advertised = (bits and (1L shl (32 - offset))) != 0L
                val implemented = !e.execute("01%02X".format(base + offset)).startsWith("NO DATA")
                assertEquals(implemented, advertised, "PID ${base + offset}")
            }
        }
    }
    @Test fun clearCodesKeepsPermanentAndResetsReadiness() {
        val e = engine(VehicleProfile.presets().first { it.name == "Engine fault" })
        assertEquals("43 02 03 00 01 71\r>", e.execute("03"))
        assertEquals("47 01 03 01\r>", e.execute("07"))
        assertEquals("4A 01 03 00\r>", e.execute("0A"))
        assertTrue(e.execute("0101").startsWith("41 01 82"))
        assertEquals("42 0C 00 0E D8\r>", e.execute("020C00"))
        assertEquals("44\r>", e.execute("04"))
        assertEquals("43 00\r>", e.execute("03"))
        assertEquals("47 00\r>", e.execute("07"))
        assertEquals("4A 01 03 00\r>", e.execute("0A"))
        assertEquals("NO DATA\r>", e.execute("020C00"))
        assertEquals("41 01 00 77 65 65\r>", e.execute("0101"))
    }
    @Test fun dtcFamiliesEncodeCorrectly() {
        val e = engine(VehicleProfile(storedDtcs = listOf("P0300", "C1234", "B2345", "U0100")))
        e.execute("ATH1")
        assertEquals("7E8 10 0A 43 04 03 00 52 34\r7E8 21 A3 45 C1 00 00 00 00\r>", e.execute("03"))
    }
    @Test fun headerAndSpaceSettingsApplyToWholeFrame() {
        val e = engine(VehicleProfile(speedKph = 42))
        e.execute("ATH1")
        assertEquals("7E8 03 41 0D 2A 00 00 00 00\r>", e.execute("010D"))
        e.execute("ATS0")
        assertEquals("7E803410D2A00000000\r>", e.execute("010D"))
    }
    @Test fun vinUsesIsoTpFramesWhenHeadersEnabled() {
        val e = engine()
        e.execute("ATH1")
        val response = e.execute("0902")
        assertTrue(response.startsWith("7E8 10 14 49 02 01 "))
        val rows = response.substringBefore("\r>").split('\r')
        assertEquals(3, rows.size)
        assertTrue(rows[1].startsWith("7E8 21 "))
        assertTrue(rows[2].startsWith("7E8 22 "))
        val payload = rows[0].split(' ').drop(3) + rows.drop(1).flatMap { it.split(' ').drop(2) }
        assertEquals("49 02 01", payload.take(3).joinToString(" "))
        assertEquals(e.profile.vin, payload.drop(3).map { it.toInt(16).toChar() }.joinToString(""))
    }
    @Test fun eachSessionKeepsIndependentSettings() {
        val shared = SimulationState()
        val a = Elm327Engine(shared); val b = Elm327Engine(shared)
        a.execute("ATE0"); a.execute("ATSP6"); b.execute("ATSP6"); a.execute("ATH1"); a.execute("ATVSETSPD88")
        assertEquals("7E8 03 41 0D 58 00 00 00 00\r>", a.execute("010D"))
        assertEquals("010D\r41 0D 58\r>", b.execute("010D"))
        a.execute("ATZ")
        assertEquals(88, shared.snapshot().speedKph)
    }
    @Test fun repeatAndLinefeeds() {
        val e = engine()
        assertEquals(e.execute("010D"), e.execute(""))
        assertEquals("OK\r\n>", e.execute("ATL1"))
        assertEquals("41 0D 00\r\n>", e.execute("010D"))
    }
    @Test fun ecuFiltersAreHonored() {
        val e = engine()
        e.execute("ATSH7E1"); assertEquals("NO DATA\r>", e.execute("010D"))
        e.execute("ATSH7E0"); e.execute("ATCRA7E9"); assertEquals("NO DATA\r>", e.execute("010D"))
        e.execute("ATCRA"); assertEquals("41 0D 00\r>", e.execute("010D"))
    }
    @Test fun faultInjectionDoesNotPreventAdapterConfiguration() {
        for ((fault, expected) in listOf(ResponseFault.NO_DATA to "NO DATA", ResponseFault.BUS_ERROR to "CAN ERROR", ResponseFault.MALFORMED to "41 0C ZZ")) {
            val e = engine(VehicleProfile(fault = fault))
            assertEquals("$expected\r>", e.execute("010D"))
            assertEquals("ELM327 v1.5\r>", e.execute("ATI"))
        }
    }
    @Test fun voltageUsesDecimalPointInAnyLocale() {
        val previous = Locale.getDefault()
        try { Locale.setDefault(Locale.GERMANY); assertEquals("12.4V\r>", engine().execute("ATRV")) }
        finally { Locale.setDefault(previous) }
    }
    @Test fun profilesValidateAndCannotMutateSharedState() {
        VehicleProfile.presets().forEach { it.validate() }
        assertFailsWith<IllegalArgumentException> { SimulationState(VehicleProfile(vin = "SHORT")) }
        assertFailsWith<IllegalArgumentException> { SimulationState(VehicleProfile(supplyVoltage = Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { SimulationState(VehicleProfile(storedDtcs = listOf("P9999"))) }
        val state = SimulationState()
        state.snapshot().speedKph = 200
        assertEquals(0, state.snapshot().speedKph)
        assertFailsWith<IllegalArgumentException> { state.update { it.speedKph = 256 } }
        assertEquals(0, state.snapshot().speedKph)
    }
    @Test fun scriptedResponsesAdvancePerSessionAndResetOnNewProfile() {
        val p = VehicleProfile(responseScript = mapOf("010D" to listOf("41 0D 00", "41 0D 28")))
        val shared = SimulationState(p)
        val a = Elm327Engine(shared); val b = Elm327Engine(shared)
        a.execute("ATE0"); b.execute("ATE0"); a.execute("ATSP6"); b.execute("ATSP6")
        assertEquals("41 0D 00\r>", a.execute("010D"))
        assertEquals("41 0D 28\r>", a.execute("010D"))
        assertEquals("41 0D 28\r>", a.execute("010D"))
        assertEquals("41 0D 00\r>", b.execute("010D"))
        shared.replace(p)
        assertEquals("41 0D 00\r>", a.execute("010D"))
        a.execute("ATZ"); a.execute("ATE0")
        assertEquals("41 0D 00\r>", a.execute("010D"))
    }
    @Test fun scriptedResponsesHaveSizeAndFormatLimits() {
        for (script in listOf(mapOf("ATZ" to listOf("OK")), mapOf("010D" to emptyList()), mapOf("010D" to listOf("BAD>")))) {
            assertFailsWith<IllegalArgumentException> { SimulationState(VehicleProfile(responseScript = script)) }
        }
    }
    @Test fun freezeFrameDoesNotChangeWithLiveReadings() {
        val e = engine(VehicleProfile.presets().first { it.name == "Engine fault" })
        val before = e.execute("020C00")
        e.execute("ATVSETRPM2500")
        assertEquals(before, e.execute("020C00"))
        assertEquals("41 0C 27 10\r>", e.execute("010C"))
    }
    @Test fun unheadedVinUsesLengthAndNumberedRows() {
        val e = engine()
        val result = e.execute("0902")
        assertTrue(result.startsWith("014\r0: 49 02 01 "))
        val rows = result.substringBefore("\r>").split('\r').drop(1)
        assertEquals(3, rows.size)
        val payload = rows.flatMap { it.substringAfter(": ").split(' ') }.map { it.toInt(16) }
        assertEquals(e.profile.vin, payload.drop(3).map { it.toChar() }.joinToString(""))
    }

}
