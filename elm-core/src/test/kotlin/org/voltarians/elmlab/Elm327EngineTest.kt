package org.voltarians.elmlab

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class Elm327EngineTest {
    @Test fun identityAndPrompt() = assertTrue(Elm327Engine().execute("ATI").endsWith("ELM327 v1.5\r>"))
    @Test fun voltage() = assertTrue(Elm327Engine(VehicleProfile(supplyVoltage = 12.1)).execute("ATRV").contains("12.1V"))
    @Test fun speedPid() = assertTrue(Elm327Engine(VehicleProfile(speedKph = 42)).execute("010D").contains("41 0D 2A"))
    @Test fun remoteProfileControl() {
        val engine = Elm327Engine()
        assertTrue(engine.execute("ATVSETSPD42").contains("OK"))
        assertTrue(engine.execute("010D").contains("41 0D 2A"))
        assertTrue(engine.execute("ATVPROFILE").contains("SPD=42"))
    }
    @Test fun reliabilityStreamIsDeterministicAndNumbered() {
        assertEquals(3000, ReliabilityTestStream.expectedFrames)
        assertEquals("7E8 00 00 00 00 56 45 4C 4D", ReliabilityTestStream.frame(0))
        assertEquals("7E8 00 00 0B B7 56 45 4C 4D", ReliabilityTestStream.frame(2999))
    }
}
