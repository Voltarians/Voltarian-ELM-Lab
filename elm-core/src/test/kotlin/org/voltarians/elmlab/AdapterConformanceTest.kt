package org.voltarians.elmlab

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

/** Independent byte expectations, anchored to ELM327DSK pp. 14, 17, 35, 42–43, 53. */
class AdapterConformanceTest {
    private fun adapter(p: VehicleProfile = VehicleProfile()) = Elm327Engine(p).also { it.execute("ATE0"); it.execute("ATSP6") }

    @Test fun commonClientInitializationTranscript() {
        val e = Elm327Engine()
        val transcript = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATAT1", "ATST64", "ATCAF1", "ATCFC1", "ATAL", "ATSP6", "01001", "ATDPN")
        val replies = transcript.map(e::execute)
        assertEquals("ATZ\rELM327 v1.5\r>", replies[0])
        assertEquals("ATE0\rOK\r>", replies[1])
        for (reply in replies.subList(2, 11)) assertEquals("OK\r>", reply)
        assertTrue(replies[11].startsWith("7E8064100"))
        assertTrue(replies[11].endsWith("00\r>"))
        assertEquals("6\r>", replies.last())
    }
    @Test fun canDtcCountIsSeparateFromFirstCode() {
        val e = adapter(VehicleProfile(storedDtcs = listOf("P0133")))
        assertEquals("43 01 01 33\r>", e.execute("03"))
        e.execute("ATH1")
        assertEquals("7E8 04 43 01 01 33 00 00 00\r>", e.execute("03"))
        e.profile = VehicleProfile()
        assertEquals("7E8 02 43 00 00 00 00 00 00\r>", e.execute("03"))
    }
    @Test fun rawCanRequestsNeedPciAndReceivePadding() {
        val e = adapter(VehicleProfile(coolantC = 30))
        e.execute("ATCAF0")
        assertEquals("03 41 05 46 00 00 00 00\r>", e.execute("020105"))
        assertEquals("NO DATA\r>", e.execute("0105")) // raw 01 05 is mode 05, not PID 0105
        assertEquals("?\r>", e.execute("0201"))
        e.execute("ATH1")
        assertEquals("7E8 03 41 05 46 00 00 00 00\r>", e.execute("0201050000000000"))
        e.execute("ATD1")
        assertEquals("7E8 8 03 41 05 46 00 00 00 00\r>", e.execute("020105"))
        e.execute("ATS0")
        assertEquals("7E880341054600000000\r>", e.execute("020105"))
    }
    @Test fun receiveMaskAndWildcardFilterControlResponses() {
        val e = adapter()
        e.execute("ATCRA7EX"); assertEquals("41 0D 00\r>", e.execute("010D"))
        e.execute("ATCRA7E9"); assertEquals("NO DATA\r>", e.execute("010D"))
        e.execute("ATCRA"); assertEquals("41 0D 00\r>", e.execute("010D"))
        e.execute("ATCF700"); e.execute("ATCM700"); assertEquals("41 0D 00\r>", e.execute("010D"))
        e.execute("ATCM7FF"); assertEquals("NO DATA\r>", e.execute("010D"))
        e.execute("ATAR"); assertEquals("41 0D 00\r>", e.execute("010D"))
        for (bad in listOf("ATCF800", "ATCM800", "ATCRA8FF")) assertEquals("?\r>", e.execute(bad))
    }
    @Test fun rawManualFlowControlHonorsBlockSizeAndCancellation() {
        val e = adapter()
        e.execute("ATCAF0"); e.execute("ATCFC0"); e.execute("ATH1")
        assertTrue(e.execute("020902").startsWith("7E8 10 14 49 02 01 "))
        assertEquals("NO DATA\r>", e.execute("300000")) // FC must target physical ECU
        e.execute("ATSH7E0")
        val first = e.execute("300100")
        assertTrue(first.startsWith("7E8 21 ")); assertEquals(1, first.count { it == '\r' })
        assertEquals("?\r>", e.execute("30000A")) // STmin not implemented; never falsely acknowledge it
        assertEquals("\r>", e.execute("310000"))
        assertTrue(e.execute("300000").startsWith("7E8 22 "))
        assertEquals("NO DATA\r>", e.execute("300000"))
        e.execute("020902"); e.execute("320000")
        assertEquals("NO DATA\r>", e.execute("300000"))
    }
    @Test fun automaticFlowControlAndExpectedFrameCount() {
        val e = adapter()
        e.execute("ATH1")
        assertEquals(3, e.execute("0902").count { it == '\r' })
        assertEquals(1, e.execute("09021").count { it == '\r' })
        assertEquals(3, e.execute("09023").count { it == '\r' })
        e.execute("ATCFC0")
        assertEquals(1, e.execute("0902").count { it == '\r' })
        e.execute("ATCFC1")
        assertEquals(3, e.execute("0902").count { it == '\r' })
    }
    @Test fun multiplePidRequestsAndUnsupportedPids() {
        val e = adapter(VehicleProfile(rpm = 2200, speedKph = 100))
        assertEquals("41 0C 22 60 0D 64\r>", e.execute("010C0D"))
        assertEquals("41 0D 64\r>", e.execute("01FF0D"))
        assertEquals("NO DATA\r>", e.execute("01FFFF"))
        assertEquals("?\r>", e.execute("0100010203040506"))
        e.execute("ATAL")
        assertEquals("?\r>", e.execute("0100010203040506")) // AL cannot fit seven PIDs plus PCI into CAN
        e.execute("ATNL")
        assertEquals("?\r>", e.execute("0100010203040506"))
    }
    @Test fun protocolMemoryAndTryProtocolHaveDifferentResetBehavior() {
        val e = adapter()
        assertEquals("6\r>", e.execute("ATDPN"))
        e.execute("ATZ"); e.execute("ATE0")
        assertEquals("6\r>", e.execute("ATDPN"))
        e.execute("ATSP00"); e.execute("ATM0"); e.execute("ATSP6")
        e.execute("ATZ"); e.execute("ATE0")
        assertEquals("0\r>", e.execute("ATDPN"))
        e.execute("ATTP6"); e.execute("010D"); e.execute("ATZ"); e.execute("ATE0")
        assertEquals("0\r>", e.execute("ATDPN"))
        assertEquals("SEARCHING...\r41 0D 00\r>", e.execute("010D"))
        assertEquals("A6\r>", e.execute("ATDPN"))
        e.execute("ATPC")
        assertTrue(e.execute("010D").startsWith("SEARCHING..."))
    }
    @Test fun responseSuppressionStillAllowsAdapterCommandsAndClear() {
        val e = adapter(VehicleProfile(storedDtcs = listOf("P0300"), responseDelayMs = 5000))
        e.execute("ATR0")
        assertEquals(AdapterReply("\r>", 0), e.exchange("010D"))
        assertEquals(AdapterReply("12.4V\r>", 0), e.exchange("ATRV"))
        e.execute("04")
        assertTrue(e.profile.storedDtcs.isEmpty())
        e.execute("ATR1")
        assertEquals("NO DATA\r>", e.execute("010D"))
    }
    @Test fun timingPlanHonorsTimeoutAndResponseCount() {
        val e = adapter(VehicleProfile(responseDelayMs = 30))
        e.execute("ATAT0"); e.execute("ATST0A")
        assertEquals(70, e.exchange("010D").delayMs)
        assertEquals(30, e.exchange("010D1").delayMs)
        e.profile = VehicleProfile(responseDelayMs = 50)
        assertEquals(AdapterReply("NO DATA\r>", 40), e.exchange("010D"))
        e.execute("ATST00")
        assertEquals(250, e.exchange("010D").delayMs)
        e.execute("ATAT1")
        assertEquals(120, e.exchange("010D").delayMs)
        e.execute("ATAT2")
        assertEquals(110, e.exchange("010D").delayMs)
        assertEquals(0, e.exchange("ATI").delayMs)
    }
    @Test fun streamHandlesCrLfFragmentationRepeatAndBackspace() {
        val output = ByteArrayOutputStream()
        val input = ByteArrayInputStream("ATE0\r\nATSP6\r\n010X\bD\r\n\r".toByteArray())
        val commands = mutableListOf<String>()
        ElmStreamSession(Elm327Engine()).serve(input, output, observe = { command, _ -> commands += command })
        assertEquals(listOf("ATE0", "ATSP6", "010D", ""), commands)
        assertEquals("ATE0\rOK\r>OK\r>41 0D 00\r>41 0D 00\r>", output.toString("US-ASCII"))
    }
    @Test fun streamRejectsOversizedAndControlCharacterInput() {
        for (input in listOf("A".repeat(513) + "\r", "ATI\u0000\r")) {
            val out = ByteArrayOutputStream()
            ElmStreamSession(Elm327Engine()).serve(ByteArrayInputStream(input.toByteArray()), out)
            assertEquals(0, out.size())
        }
    }
    @Test fun aDeliveredClearStillHappensWithSuppressedFilteredOrLateReply() {
        for (configuration in listOf("ATR0", "ATCRA7E9", "ATST01")) {
            val e = adapter(VehicleProfile(storedDtcs = listOf("P0300"), responseDelayMs = 10))
            e.execute(configuration)
            e.execute("04")
            assertTrue(e.profile.storedDtcs.isEmpty(), configuration)
        }
        for (fault in listOf(ResponseFault.NO_DATA, ResponseFault.BUS_ERROR)) {
            val e = adapter(VehicleProfile(storedDtcs = listOf("P0300"), fault = fault))
            e.execute("04")
            assertEquals(listOf("P0300"), e.profile.storedDtcs)
        }
    }
    @Test fun adaptiveTimeoutBacksOffAfterSlowReply() {
        val e = adapter()
        e.execute("010D") // learn a short simulated response window
        e.profile = VehicleProfile(responseDelayMs = 70)
        assertEquals(AdapterReply("NO DATA\r>", 20), e.exchange("010D"))
        assertEquals(AdapterReply("NO DATA\r>", 40), e.exchange("010D"))
        assertTrue(e.exchange("010D").text.startsWith("41 0D"))
    }
    @Test fun profileReplacementInvalidatesPendingRawFrames() {
        val e = adapter()
        e.execute("ATCAF0"); e.execute("ATCFC0"); e.execute("ATSH7E0"); e.execute("020902")
        e.profile = VehicleProfile(vin = "TESTCAR0000000009")
        assertEquals("NO DATA\r>", e.execute("300000"))
    }
    @Test fun customCrLfResponsesDoNotDuplicateLinefeeds() {
        val e = adapter(VehicleProfile(responseScript = mapOf("010D" to listOf("41 0D 00\r\n41 0D 01"))))
        assertEquals("41 0D 00\r41 0D 01\r>", e.execute("010D"))
        e.execute("ATL1")
        assertEquals("41 0D 00\r\n41 0D 01\r\n>", e.execute("010D"))
    }

    @Test fun recordingKeysNormalizeCountsRepeatsAndRawRequests() {
        val e = adapter()
        e.execute("010D1")
        assertEquals("010D", e.lastDiagnosticRequest)
        assertEquals("010D1", e.lastExecutedCommand)
        e.execute("")
        assertEquals("010D", e.lastDiagnosticRequest)
        e.execute("ATCAF0")
        assertNull(e.lastDiagnosticRequest)
        e.execute("02010D")
        assertEquals("010D", e.lastDiagnosticRequest)
        e.execute("300000")
        assertNull(e.lastDiagnosticRequest)
    }

}
