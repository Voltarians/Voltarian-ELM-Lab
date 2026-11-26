package org.voltarians.elmlab

/** Generic spark-ignition OBD-II simulation. Values never come from a real vehicle. */
data class VehicleProfile(
    val name: String = "Generic vehicle",
    var vin: String = "TESTCAR0000000001",
    var supplyVoltage: Double = 12.4,
    var controlModuleVoltage: Double = 13.8,
    var coolantC: Int = 82,
    var rpm: Int = 0,
    var speedKph: Int = 0,
    var engineLoadPercent: Int = 20,
    var intakeC: Int = 25,
    var massAirflowGps: Double = 3.5,
    var throttlePercent: Int = 15,
    var runtimeSeconds: Int = 120,
    var fuelPercent: Int = 65,
    var ambientC: Int = 22,
    var storedDtcs: List<String> = emptyList(),
    var pendingDtcs: List<String> = emptyList(),
    var permanentDtcs: List<String> = emptyList(),
    var monitorsComplete: Boolean = true,
    var freezeFrame: FreezeFrame? = null,
    var responseDelayMs: Int = 0,
    var fault: ResponseFault = ResponseFault.NONE,
    var responseScript: Map<String, List<String>> = emptyMap(),
    var manifoldKpa: Int = 35,
    var barometricKpa: Int = 101,
    var oilC: Int = 85,
    var timingDegrees: Double = 10.0,
    var fuelRateLph: Double = 0.8,
    var shortFuelTrim: Double = 0.0,
    var longFuelTrim: Double = 0.0
) {
    fun validate() {
        require(manifoldKpa in 0..255 && barometricKpa in 0..255 && oilC in -40..215)
        require(timingDegrees in -64.0..63.5 && fuelRateLph in 0.0..3276.75)
        require(shortFuelTrim in -100.0..99.21875 && longFuelTrim in -100.0..99.21875)
        require(name.isNotBlank() && name.length <= 80) { "Profile name must contain 1–80 characters" }
        require(vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) { "VIN must be 17 uppercase VIN characters" }
        require(supplyVoltage.isFinite() && supplyVoltage in 0.0..30.0) { "Supply voltage must be 0–30 V" }
        require(controlModuleVoltage.isFinite() && controlModuleVoltage in 0.0..65.535) { "Module voltage must be 0–65.535 V" }
        require(coolantC in -40..215 && intakeC in -40..215 && ambientC in -40..215) { "Temperature must be -40–215 °C" }
        require(rpm in 0..16383 && speedKph in 0..255) { "RPM or speed is out of range" }
        require(engineLoadPercent in 0..100 && throttlePercent in 0..100 && fuelPercent in 0..100) { "Percentages must be 0–100" }
        require(massAirflowGps.isFinite() && massAirflowGps in 0.0..655.35) { "Airflow must be 0–655.35 g/s" }
        require(runtimeSeconds in 0..65535 && responseDelayMs in 0..5000) { "Runtime or response delay is out of range" }
        require(responseScript.size <= 128 && responseScript.values.sumOf { it.size } <= 500) { "Response script is too large" }
        responseScript.forEach { (command, responses) ->
            require(command.matches(Regex("(?:[0-9A-F]{2}){1,32}")) && responses.isNotEmpty()) { "Script keys must be even-length hex requests with at least one response" }
            require(responses.all { response -> response.length <= 4096 && response.all { it in ' '..'~' || it == '\r' || it == '\n' } && '>' !in response }) { "Script responses must contain printable adapter text without a prompt" }
        }
        freezeFrame?.let {
            require(it.dtc.matches(DTC_PATTERN) && it.rpm in 0..16383 && it.speedKph in 0..255 && it.coolantC in -40..215 && it.engineLoadPercent in 0..100) { "Invalid freeze frame" }
        }
        for (codes in listOf(storedDtcs, pendingDtcs, permanentDtcs)) {
            require(codes.size <= 32 && codes.all { it.matches(DTC_PATTERN) }) { "Use up to 32 DTCs such as P0300 or U0100" }
        }
    }

    fun snapshot() = copy(storedDtcs = storedDtcs.toList(), pendingDtcs = pendingDtcs.toList(), permanentDtcs = permanentDtcs.toList(), responseScript = responseScript.mapValues { it.value.toList() })

    companion object {
        val DTC_PATTERN = Regex("[PCBU][0-3][0-9A-F]{3}")
        fun presets(): List<VehicleProfile> = listOf(
            VehicleProfile(name = "Engine off", controlModuleVoltage = 12.4, coolantC = 22, engineLoadPercent = 0, massAirflowGps = 0.0, throttlePercent = 0, runtimeSeconds = 0),
            VehicleProfile(name = "Warm idle", supplyVoltage = 14.2, controlModuleVoltage = 14.2, rpm = 800),
            VehicleProfile(name = "Highway cruise", supplyVoltage = 14.2, controlModuleVoltage = 14.2, rpm = 2200, speedKph = 100, engineLoadPercent = 45, massAirflowGps = 24.0, throttlePercent = 30),
            VehicleProfile(name = "Engine fault", supplyVoltage = 14.0, rpm = 950, storedDtcs = listOf("P0300", "P0171"), pendingDtcs = listOf("P0301"), permanentDtcs = listOf("P0300"), freezeFrame = FreezeFrame("P0300", 950, 0, 88, 35)),
            VehicleProfile(name = "Overheating", coolantC = 120, rpm = 1800, speedKph = 40),
            VehicleProfile(name = "Low supply voltage", supplyVoltage = 10.5, controlModuleVoltage = 10.5),
            VehicleProfile(name = "No ECU response", fault = ResponseFault.NO_DATA)
        )
    }
}

data class FreezeFrame(val dtc: String, val rpm: Int, val speedKph: Int, val coolantC: Int, val engineLoadPercent: Int)
enum class ResponseFault { NONE, NO_DATA, BUS_ERROR, MALFORMED }

/** Connections have separate adapter settings but share the selected simulated vehicle. */
class SimulationState(initial: VehicleProfile = VehicleProfile()) {
    private var revision = 0L
    @Synchronized fun revision(): Long = revision
    private var current = initial.snapshot().also { it.validate() }
    @Synchronized fun snapshot(): VehicleProfile = current.snapshot()
    @Synchronized fun versionedSnapshot(): Pair<Long, VehicleProfile> = revision to current.snapshot()
    @Synchronized fun replace(profile: VehicleProfile) { profile.validate(); current = profile.snapshot(); revision++ }
    @Synchronized fun update(change: (VehicleProfile) -> Unit) {
        val next = current.snapshot()
        change(next)
        next.validate()
        current = next
    }
    @Synchronized fun clearEmissions() {
        current = current.copy(storedDtcs = emptyList(), pendingDtcs = emptyList(), freezeFrame = null, monitorsComplete = false)
    }
}
