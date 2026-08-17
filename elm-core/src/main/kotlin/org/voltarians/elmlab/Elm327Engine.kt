package org.voltarians.elmlab

/** Stateful, transport-independent ELM327 command emulator. */
class Elm327Engine(
    var profile: VehicleProfile = VehicleProfile()
) {
    private var echo = true
    private var headers = false
    private var spaces = true
    private var protocol = "0"

    fun execute(rawInput: String): String {
        val command = rawInput.trim().replace(" ", "").uppercase()
        if (command.isEmpty()) return prompt("")
        val response = when {
            command == "ATZ" -> reset()
            command == "ATI" -> "ELM327 v1.5"
            command == "AT@1" -> "Voltarian ELM Lab"
            command == "AT@2" -> "VELM-ANDROID"
            command == "ATE0" -> setEcho(false)
            command == "ATE1" -> setEcho(true)
            command == "ATH0" -> setHeaders(false)
            command == "ATH1" -> setHeaders(true)
            command == "ATS0" -> setSpaces(false)
            command == "ATS1" -> setSpaces(true)
            command == "ATL0" || command == "ATL1" || command == "ATM0" || command == "ATM1" -> "OK"
            command == "ATD" -> resetDefaults()
            command == "ATRV" -> "%.1fV".format(profile.supplyVoltage)
            command == "ATDP" -> if (protocol == "0") "AUTO, ISO 15765-4 (CAN 11/500)" else "ISO 15765-4 (CAN 11/500)"
            command == "ATDPN" -> if (protocol == "0") "A6" else "6"
            command == "ATVPROFILE" -> profileSummary()
            command.startsWith("ATVSETSPD") -> setInt(command.removePrefix("ATVSETSPD"), 0, 255) { profile.speedKph = it }
            command.startsWith("ATVSETRPM") -> setInt(command.removePrefix("ATVSETRPM"), 0, 16_383) { profile.rpm = it }
            command.startsWith("ATVSETTEMP") -> setInt(command.removePrefix("ATVSETTEMP"), -40, 215) { profile.coolantC = it }
            command.startsWith("ATVSETVOLT") -> setDouble(command.removePrefix("ATVSETVOLT"), 0.0, 30.0) { profile.supplyVoltage = it }
            command.startsWith("ATSP") -> setProtocol(command.removePrefix("ATSP"))
            command.startsWith("AT") -> "OK"
            else -> obd(command)
        }
        val body = if (echo) "$command\r$response" else response
        return prompt(body)
    }

    private fun obd(command: String): String = when (command) {
        "0100" -> frame("41 00 BE 3E B8 13")
        "0105" -> frame("41 05 %02X".format((profile.coolantC + 40).coerceIn(0, 255)))
        "010C" -> {
            val value = (profile.rpm * 4).coerceIn(0, 65535)
            frame("41 0C %02X %02X".format(value shr 8, value and 0xff))
        }
        "010D" -> frame("41 0D %02X".format(profile.speedKph.coerceIn(0, 255)))
        "0142" -> {
            val mv = (profile.controlModuleVoltage * 1000).toInt().coerceIn(0, 65535)
            frame("41 42 %02X %02X".format(mv shr 8, mv and 0xff))
        }
        "0902" -> vinFrames(profile.vin)
        else -> "NO DATA"
    }

    private fun frame(payload: String): String {
        val formatted = if (spaces) payload else payload.replace(" ", "")
        return if (headers) "7E8 $formatted" else formatted
    }

    private fun vinFrames(vin: String): String {
        val bytes = vin.padEnd(17).take(17).map { "%02X".format(it.code) }
        val rows = listOf(
            listOf("49", "02", "01") + bytes.take(4),
            listOf("49", "02", "02") + bytes.drop(4).take(7),
            listOf("49", "02", "03") + bytes.drop(11)
        )
        return rows.joinToString("\r") { frame(it.joinToString(" ")) }
    }

    private fun reset(): String { resetDefaults(); return "ELM327 v1.5" }
    private fun resetDefaults(): String { echo = true; headers = false; spaces = true; protocol = "0"; return "OK" }
    private fun setEcho(value: Boolean): String { echo = value; return "OK" }
    private fun setHeaders(value: Boolean): String { headers = value; return "OK" }
    private fun setSpaces(value: Boolean): String { spaces = value; return "OK" }
    private fun setProtocol(value: String): String { protocol = value.ifEmpty { "0" }; return "OK" }
    private fun profileSummary() = "SPD=${profile.speedKph},RPM=${profile.rpm},TEMP=${profile.coolantC},VOLT=%.1f".format(profile.supplyVoltage)
    private fun setInt(raw: String, min: Int, max: Int, update: (Int) -> Unit): String {
        val value = raw.toIntOrNull() ?: return "?"
        if (value !in min..max) return "?"
        update(value)
        return "OK"
    }
    private fun setDouble(raw: String, min: Double, max: Double, update: (Double) -> Unit): String {
        val value = raw.toDoubleOrNull() ?: return "?"
        if (value !in min..max) return "?"
        update(value)
        return "OK"
    }
    private fun prompt(text: String) = "$text\r>"
}

data class VehicleProfile(
    var vin: String = "1G1RA6E40DU100001",
    var supplyVoltage: Double = 12.4,
    var controlModuleVoltage: Double = 13.8,
    var coolantC: Int = 82,
    var rpm: Int = 0,
    var speedKph: Int = 0
)
