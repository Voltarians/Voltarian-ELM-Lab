package org.voltarians.elmlab

import kotlin.test.Test
import kotlin.test.assertTrue

class Elm327EngineTest {
    @Test fun identityAndPrompt() = assertTrue(Elm327Engine().execute("ATI").endsWith("ELM327 v1.5\r>"))
    @Test fun voltage() = assertTrue(Elm327Engine(VehicleProfile(supplyVoltage = 12.1)).execute("ATRV").contains("12.1V"))
    @Test fun speedPid() = assertTrue(Elm327Engine(VehicleProfile(speedKph = 42)).execute("010D").contains("41 0D 2A"))
}

