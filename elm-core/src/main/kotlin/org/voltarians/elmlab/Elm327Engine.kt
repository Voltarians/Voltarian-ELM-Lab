package org.voltarians.elmlab

import java.util.Locale
import kotlin.math.roundToInt

/** ELM-style automatic formatting for an 11-bit, 500 kbit/s CAN ECU at 7E8. */
class Elm327Engine(val state: SimulationState = SimulationState()) {
    constructor(profile: VehicleProfile) : this(SimulationState(profile))
    var profile: VehicleProfile
        get() = state.snapshot()
        set(value) = state.replace(value)
    private var echo = true
    private var headers = false
    private var spaces = true
    private var linefeeds = false
    private var protocol = "0"
    private var receiveAddress: String? = null
    private var requestAddress = "7DF"
    private var previous = ""
    private val scriptPositions = mutableMapOf<String, Int>()
    private var scriptRevision = state.revision()

    @Synchronized fun execute(rawInput: String): String {
        var command = rawInput.trim().replace(" ", "").uppercase(Locale.ROOT)
        if (command.isEmpty()) command = previous
        if (command.isEmpty()) return ">"
        val echoBefore = echo
        previous = command
        val response = when {
            command == "ATZ" || command == "ATWS" -> { defaults(); "ELM327 v1.5" }
            command == "ATD" -> { defaults(); "OK" }
            command == "ATI" -> "ELM327 v1.5"
            command == "AT@1" -> "ELM Lab OBD Simulator"
            command == "AT@2" -> "ELMLAB-ANDROID"
            command == "ATE0" -> { echo = false; "OK" }
            command == "ATE1" -> { echo = true; "OK" }
            command == "ATH0" -> { headers = false; "OK" }
            command == "ATH1" -> { headers = true; "OK" }
            command == "ATS0" -> { spaces = false; "OK" }
            command == "ATS1" -> { spaces = true; "OK" }
            command == "ATL0" -> { linefeeds = false; "OK" }
            command == "ATL1" -> { linefeeds = true; "OK" }
            command == "ATRV" -> String.format(Locale.US, "%.1fV", profile.supplyVoltage)
            command == "ATDP" -> (if (protocol == "0") "AUTO, " else "") + "ISO 15765-4 (CAN 11/500)"
            command == "ATDPN" -> if (protocol == "0") "A6" else "6"
            command == "ATSP0" || command == "ATSP6" || command == "ATSPA6" -> { protocol = if (command == "ATSP6") "6" else "0"; "OK" }
            command.startsWith("ATSH") && command.drop(4).matches(Regex("[0-7][0-9A-F]{2}")) -> { requestAddress = command.drop(4); "OK" }
            command == "ATCRA" -> { receiveAddress = null; "OK" }
            command.startsWith("ATCRA") && command.drop(5).matches(Regex("[0-7][0-9A-F]{2}")) -> { receiveAddress = command.drop(5); "OK" }
            command == "ATVPROFILE" -> profile.let { String.format(Locale.US, "SPD=%d,RPM=%d,TEMP=%d,VOLT=%.1f", it.speedKph, it.rpm, it.coolantC, it.supplyVoltage) }
            command.startsWith("ATVSETSPD") -> setInt(command.drop(9), 0..255) { p, n -> p.speedKph = n }
            command.startsWith("ATVSETRPM") -> setInt(command.drop(9), 0..16383) { p, n -> p.rpm = n }
            command.startsWith("ATVSETTEMP") -> setInt(command.drop(10), -40..215) { p, n -> p.coolantC = n }
            command.startsWith("ATVSETVOLT") -> {
                val value = command.drop(10).toDoubleOrNull()
                if (value == null || !value.isFinite() || value !in 0.0..30.0) "?"
                else { state.update { it.supplyVoltage = value }; "OK" }
            }
            command.startsWith("AT") -> "?"
            else -> obd(command)
        }
        val newline = if (linefeeds) "\r\n" else "\r"
        return ((if (echoBefore) "$command\r" else "") + response + "\r>").replace("\r", newline)
    }

    private fun obd(command: String): String {
        if (!command.matches(Regex("[0-9A-F]+")) || command.length % 2 != 0) return "?"
        val (revision, p) = state.versionedSnapshot()
        when (p.fault) {
            ResponseFault.NO_DATA -> return "NO DATA"
            ResponseFault.BUS_ERROR -> return "CAN ERROR"
            ResponseFault.MALFORMED -> return "41 0C ZZ"
            ResponseFault.NONE -> Unit
        }
        if (requestAddress !in listOf("7DF", "7E0") || receiveAddress?.let { it != "7E8" } == true) return "NO DATA"
        if (revision != scriptRevision) { scriptPositions.clear(); scriptRevision = revision }
        p.responseScript[command]?.let { responses ->
            val position = scriptPositions[command] ?: 0
            scriptPositions[command] = (position + 1).coerceAtMost(responses.lastIndex)
            return responses[position.coerceAtMost(responses.lastIndex)]
        }
        return when {
            command.length == 4 && command.startsWith("01") -> {
                val pid = command.drop(2).toInt(16)
                pidValue(pid, p)?.let { frame(listOf(0x41, pid) + it) } ?: "NO DATA"
            }
            command.length == 6 && command.startsWith("02") -> freezeFrame(command, p)
            command == "03" -> dtcFrame(0x43, p.storedDtcs)
            command == "07" -> dtcFrame(0x47, p.pendingDtcs)
            command == "0A" -> dtcFrame(0x4A, p.permanentDtcs)
            command == "04" -> { state.clearEmissions(); frame(listOf(0x44)) }
            command == "0900" -> frame(listOf(0x49, 0, 0x40, 0, 0, 0))
            command == "0902" -> frame(listOf(0x49, 2, 1) + p.vin.map { it.code })
            else -> "NO DATA"
        }
    }

    private fun pidValue(pid: Int, p: VehicleProfile): List<Int>? = when (pid) {
        0x00, 0x20, 0x40 -> bitmap(pid)
        // Generic spark ignition: misfire, fuel, components + catalyst, evap and O2.
        0x01 -> listOf((if (p.storedDtcs.isNotEmpty()) 0x80 else 0) or p.storedDtcs.size.coerceAtMost(127), if (p.monitorsComplete) 0x07 else 0x77, 0x65, if (p.monitorsComplete) 0 else 0x65)
        0x04 -> listOf(percent(p.engineLoadPercent))
        0x05 -> listOf(p.coolantC + 40)
        0x0C -> word(p.rpm * 4)
        0x0D -> listOf(p.speedKph)
        0x0F -> listOf(p.intakeC + 40)
        0x10 -> word((p.massAirflowGps * 100).roundToInt())
        0x11 -> listOf(percent(p.throttlePercent))
        0x1F -> word(p.runtimeSeconds)
        0x2F -> listOf(percent(p.fuelPercent))
        0x42 -> word((p.controlModuleVoltage * 1000).roundToInt())
        0x46 -> listOf(p.ambientC + 40)
        else -> null
    }

    private fun freezeFrame(command: String, p: VehicleProfile): String {
        val frozen = p.freezeFrame ?: return "NO DATA"
        if (!command.endsWith("00")) return "NO DATA"
        val pid = command.substring(2, 4).toInt(16)
        val value = when (pid) {
            0 -> bitmap(0, setOf(2, 4, 5, 12, 13))
            2 -> encodeDtc(frozen.dtc)
            4 -> listOf(percent(frozen.engineLoadPercent))
            5 -> listOf(frozen.coolantC + 40)
            12 -> word(frozen.rpm * 4)
            13 -> listOf(frozen.speedKph)
            else -> return "NO DATA"
        }
        return frame(listOf(0x42, pid, 0) + value)
    }

    private fun bitmap(base: Int, supported: Set<Int> = SUPPORTED): List<Int> {
        var bits = 0L
        for (pid in supported) if (pid in (base + 1)..(base + 32)) bits = bits or (1L shl (32 - (pid - base)))
        return (3 downTo 0).map { ((bits shr (it * 8)) and 255).toInt() }
    }

    private fun dtcFrame(mode: Int, codes: List<String>) = frame(listOf(mode) + if (codes.isEmpty()) listOf(0, 0) else codes.flatMap(::encodeDtc))
    private fun encodeDtc(code: String): List<Int> {
        val value = ("PCBU".indexOf(code[0]) shl 14) or code.substring(1).toInt(16)
        return word(value)
    }

    /** CAF1 style: no headers yields decoded payloads; headers include ISO-TP PCI bytes. */
    private fun frame(payload: List<Int>): String {
        fun hex(bytes: List<Int>) = bytes.joinToString(if (spaces) " " else "") { "%02X".format(it) }
        fun headed(bytes: List<Int>) = "7E8" + (if (spaces) " " else "") + hex(bytes)
        if (payload.size <= 7) return if (headers) headed(listOf(payload.size) + payload) else hex(payload)
        if (!headers) {
            // ELM multiline autoformat: total length followed by numbered payload rows.
            return "%03X".format(payload.size) + "\r" + (listOf(payload.take(6)) + payload.drop(6).chunked(7)).mapIndexed { index, row -> "%X:".format(index) + (if (spaces) " " else "") + hex(row) }.joinToString("\r")
        }
        val rows = mutableListOf(headed(listOf(0x10 or (payload.size shr 8), payload.size and 255) + payload.take(6)))
        payload.drop(6).chunked(7).forEachIndexed { index, row -> rows += headed(listOf(0x20 or ((index + 1) and 15)) + row) }
        return rows.joinToString("\r")
    }

    private fun defaults() { scriptPositions.clear(); echo = true; headers = false; spaces = true; linefeeds = false; protocol = "0"; receiveAddress = null; requestAddress = "7DF" }
    private fun setInt(raw: String, range: IntRange, update: (VehicleProfile, Int) -> Unit): String {
        val number = raw.toIntOrNull() ?: return "?"
        if (number !in range) return "?"
        state.update { update(it, number) }
        return "OK"
    }
    private fun percent(value: Int) = (value * 255.0 / 100).roundToInt()
    private fun word(value: Int) = listOf((value shr 8) and 255, value and 255)
    companion object { private val SUPPORTED = setOf(1, 4, 5, 12, 13, 15, 16, 17, 31, 32, 47, 64, 66, 70) }
}
