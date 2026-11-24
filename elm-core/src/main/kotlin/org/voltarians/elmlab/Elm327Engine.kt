package org.voltarians.elmlab

import java.util.Locale
import kotlin.math.roundToInt

/** An 11-bit / 500 kbit/s CAN ELM-style adapter. Other protocols are explicitly rejected. */
class Elm327Engine(val state: SimulationState = SimulationState()) {
    constructor(profile: VehicleProfile) : this(SimulationState(profile))
    var profile: VehicleProfile
        get() = state.snapshot()
        set(value) = state.replace(value)
    private var echo = true
    private var headers = false
    private var spaces = true
    private var linefeeds = false
    private var autoFormatting = true
    private var autoFlowControl = true
    private var showDlc = false
    private var responsesEnabled = true
    // Retained for reset compatibility; CAN raw frames always allow eight bytes.
    private var allowLong = false
    private var memoryEnabled = true
    private var savedProtocol = "0"
    private var protocol = "0"
    private var temporaryProtocol = false
    private var connected = false
    private var timeoutMs = 200
    private var adaptiveMode = 1
    private var learnedTimeoutMs = 200
    private var requestAddress = "7DF"
    private var receiveFilter = 0x7E8
    private var receiveMask = 0x7F8
    private var previous = ""
    val lastExecutedCommand: String get() = previous
    var lastDiagnosticRequest: String? = null
        private set
    private var expectedFrames: Int? = null
    private var pendingFrames: List<List<Int>> = emptyList()
    private var pendingRevision = 0L
    private var replyDelayMs = 0
    private val scriptPositions = mutableMapOf<String, Int>()
    private var scriptRevision = state.revision()

    fun execute(rawInput: String): String = exchange(rawInput).text

    /** Timing is returned separately so tests never need to sleep; all transports use this plan. */
    @Synchronized fun exchange(rawInput: String): AdapterReply {
        lastDiagnosticRequest = null
        var command = rawInput.trim().replace(" ", "").uppercase(Locale.ROOT)
        if (command.isEmpty()) command = previous
        if (command.isEmpty()) return AdapterReply(">", 0)
        val echoBefore = echo
        previous = command
        replyDelayMs = 0
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
            command == "ATCAF0" -> { autoFormatting = false; "OK" }
            command == "ATCAF1" -> { autoFormatting = true; "OK" }
            command == "ATCFC0" -> { autoFlowControl = false; "OK" }
            command == "ATCFC1" -> { autoFlowControl = true; "OK" }
            command == "ATD0" -> { showDlc = false; "OK" }
            command == "ATD1" -> { showDlc = true; "OK" }
            command == "ATR0" -> { responsesEnabled = false; "OK" }
            command == "ATR1" -> { responsesEnabled = true; "OK" }
            command == "ATAL" -> { allowLong = true; "OK" }
            command == "ATNL" -> { allowLong = false; "OK" }
            command == "ATM0" -> { memoryEnabled = false; "OK" }
            command == "ATM1" -> { memoryEnabled = true; "OK" }
            command == "ATPC" -> { connected = false; pendingFrames = emptyList(); "OK" }
            command == "ATAR" || command == "ATCRA" -> { resetFilters(); "OK" }
            command.matches(Regex("ATAT[012]")) -> { adaptiveMode = command.last().digitToInt(); learnedTimeoutMs = timeoutMs; "OK" }
            command.matches(Regex("ATST[0-9A-F]{2}")) -> {
                timeoutMs = command.takeLast(2).toInt(16).let { if (it == 0) 200 else it * 4 }
                learnedTimeoutMs = timeoutMs
                "OK"
            }
            command == "ATRV" -> String.format(Locale.US, "%.1fV", profile.supplyVoltage)
            command == "ATDP" -> if (!connected && protocol != "6") "AUTO" else (if (protocol != "6") "AUTO, " else "") + "ISO 15765-4 (CAN 11/500)"
            command == "ATDPN" -> if (protocol == "6") "6" else if (connected) "A6" else "0"
            command.startsWith("ATSP") || command.startsWith("ATTP") -> selectProtocol(command)
            command.matches(Regex("ATSH[0-7][0-9A-F]{2}")) -> { requestAddress = command.drop(4); "OK" }
            command.matches(Regex("ATCRA[0-7X][0-9A-FX]{2}")) -> {
                val pattern = command.drop(5)
                receiveFilter = pattern.replace('X', '0').toInt(16)
                receiveMask = pattern.map { if (it == 'X') '0' else 'F' }.joinToString("").toInt(16) and 0x7FF
                "OK"
            }
            command.matches(Regex("ATCF[0-7][0-9A-F]{2}")) -> { receiveFilter = command.drop(4).toInt(16); "OK" }
            command.matches(Regex("ATCM[0-7][0-9A-F]{2}")) -> { receiveMask = command.drop(4).toInt(16); "OK" }
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
        val canonicalResponse = response.replace("\r\n", "\r").replace('\n', '\r')
        return AdapterReply(((if (echoBefore) "$command\r" else "") + canonicalResponse + "\r>").replace("\r", newline), replyDelayMs)
    }

    private fun selectProtocol(command: String): String {
        val value = command.drop(4)
        if (value !in listOf("0", "00", "6", "A6")) return "?"
        temporaryProtocol = command.startsWith("ATTP")
        protocol = if (value == "00") "0" else value
        if (value == "00") savedProtocol = "0"
        else if (memoryEnabled && !temporaryProtocol) savedProtocol = protocol
        connected = false
        pendingFrames = emptyList()
        requestAddress = "7DF"
        resetFilters()
        return "OK"
    }

    private fun obd(rawCommand: String): String {
        if (!rawCommand.matches(Regex("[0-9A-F]+"))) return "?"
        expectedFrames = null
        var command = rawCommand
        if (command.length % 2 != 0) {
            val count = command.last().digitToInt(16)
            if (count == 0 || command.length < 3) return "?"
            expectedFrames = count
            command = command.dropLast(1)
        }
        val bytes = command.chunked(2).map { it.toInt(16) }
        if (!autoFormatting) {
            if (bytes.size !in 1..8) return "?"
            if (bytes.first() in 0x30..0x32) return flowControl(bytes)
            if (bytes.first() !in 1..7 || bytes.size < bytes.first() + 1) return "?"
            command = bytes.drop(1).take(bytes.first()).joinToString("") { "%02X".format(it) }
        } else if (bytes.size > 7) return "?" // single-frame CAF1 reserves one byte for PCI
        lastDiagnosticRequest = command
        pendingFrames = emptyList()
        val (revision, p) = state.versionedSnapshot()
        val searching = !connected && protocol != "6"
        val prefix = if (searching) "SEARCHING...\r" else ""
        connected = true
        if (memoryEnabled && !temporaryProtocol) savedProtocol = if (protocol == "6") "6" else "A6"
        val validAddress = requestAddress in listOf("7DF", "7E0")
        val visibleAddress = (0x7E8 and receiveMask) == (receiveFilter and receiveMask)
        val reachesEcu = validAddress && p.fault !in listOf(ResponseFault.NO_DATA, ResponseFault.BUS_ERROR)
        // Receiving a request and displaying its reply are separate events. A filtered,
        // suppressed, or late reply must not prevent a delivered clear-code command.
        if (command == "04" && reachesEcu && "04" !in p.responseScript) state.clearEmissions()
        if (p.fault == ResponseFault.BUS_ERROR) return prefix + "CAN ERROR"
        if (!responsesEnabled) return ""
        val wait = if (adaptiveMode == 0) timeoutMs else learnedTimeoutMs
        if (p.responseDelayMs > wait) {
            replyDelayMs = wait
            if (adaptiveMode != 0) learnedTimeoutMs = (wait * 2).coerceAtMost(timeoutMs)
            return prefix + "NO DATA"
        }
        replyDelayMs = p.responseDelayMs
        val result = when {
            !validAddress -> "NO DATA"
            p.fault == ResponseFault.NO_DATA -> "NO DATA"
            p.fault == ResponseFault.BUS_ERROR -> "CAN ERROR"
            !visibleAddress -> "NO DATA"
            p.fault == ResponseFault.MALFORMED -> "41 0C ZZ"
            else -> {
                if (revision != scriptRevision) { scriptPositions.clear(); scriptRevision = revision }
                p.responseScript[command]?.let { responses ->
                    val position = scriptPositions[command] ?: 0
                    scriptPositions[command] = (position + 1).coerceAtMost(responses.lastIndex)
                    responses[position.coerceAtMost(responses.lastIndex)]
                } ?: diagnostic(command, p)
            }
        }
        if (!responsesEnabled && result !in listOf("?", "CAN ERROR")) { replyDelayMs = 0; return "" }
        if (result == "NO DATA") replyDelayMs = wait
        else {
            if (adaptiveMode != 0) learnedTimeoutMs = (p.responseDelayMs + if (adaptiveMode == 1) 20 else 10).coerceIn(4, timeoutMs)
            if (result.isNotEmpty() && expectedFrames == null) replyDelayMs += if (adaptiveMode == 0) timeoutMs else learnedTimeoutMs
        }
        return prefix + result
    }

    private fun diagnostic(command: String, p: VehicleProfile): String = when {
        command.length >= 4 && command.startsWith("01") -> {
            val pids = command.drop(2).chunked(2).map { it.toInt(16) }
            val payload = mutableListOf(0x41)
            pids.forEach { pid -> pidValue(pid, p)?.let { payload.add(pid); payload.addAll(it) } }
            if (payload.size == 1) "NO DATA" else frame(payload)
        }
        command.length == 6 && command.startsWith("02") -> freezeFrame(command, p)
        command == "03" -> dtcFrame(0x43, p.storedDtcs)
        command == "07" -> dtcFrame(0x47, p.pendingDtcs)
        command == "0A" -> dtcFrame(0x4A, p.permanentDtcs)
        command == "04" -> frame(listOf(0x44))
        command == "0900" -> frame(listOf(0x49, 0, 0x40, 0, 0, 0))
        command == "0902" -> frame(listOf(0x49, 2, 1) + p.vin.map { it.code })
        else -> "NO DATA"
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

    private fun dtcFrame(mode: Int, codes: List<String>) = frame(listOf(mode, codes.size) + codes.flatMap(::encodeDtc))
    private fun encodeDtc(code: String): List<Int> {
        val value = ("PCBU".indexOf(code[0]) shl 14) or code.substring(1).toInt(16)
        return word(value)
    }
    private fun hex(bytes: List<Int>) = bytes.joinToString(if (spaces) " " else "") { "%02X".format(it) }
    private fun physicalFrame(bytes: List<Int>): String {
        val separator = if (spaces) " " else ""
        return (if (headers) "7E8$separator" + (if (showDlc) "8$separator" else "") else "") + hex(bytes + List(8 - bytes.size) { 0 })
    }

    private fun frame(payload: List<Int>): String {
        val frames = if (payload.size <= 7) listOf(listOf(payload.size) + payload)
        else listOf(listOf(0x10 or (payload.size shr 8), payload.size and 255) + payload.take(6)) + payload.drop(6).chunked(7).mapIndexed { i, row -> listOf(0x20 or ((i + 1) and 15)) + row }
        var visible = frames
        if (frames.size > 1 && !autoFlowControl) { pendingFrames = frames.drop(1); pendingRevision = state.revision(); visible = frames.take(1) }
        expectedFrames?.let { visible = visible.take(it) }
        if (headers || !autoFormatting) return visible.joinToString("\r", transform = ::physicalFrame)
        if (payload.size <= 7) return hex(payload)
        return "%03X".format(payload.size) + "\r" + visible.mapIndexed { i, row -> "%X:".format(i and 15) + (if (spaces) " " else "") + hex(row.drop(if (i == 0) 2 else 1)) }.joinToString("\r")
    }

    /** Basic raw-mode CTS/block-size handling. Nonzero STmin is explicitly unsupported. */
    private fun flowControl(bytes: List<Int>): String {
        if (bytes.size < 3 || bytes[2] != 0) return "?"
        if (pendingRevision != state.revision()) pendingFrames = emptyList()
        if (pendingFrames.isEmpty()) return "NO DATA"
        when (bytes[0]) {
            0x31 -> return ""
            0x32 -> { pendingFrames = emptyList(); return "" }
        }
        if (requestAddress != "7E0" || (0x7E8 and receiveMask) != (receiveFilter and receiveMask)) return "NO DATA"
        val count = if (bytes[1] == 0) pendingFrames.size else bytes[1]
        val frames = pendingFrames.take(count)
        pendingFrames = pendingFrames.drop(frames.size)
        return if (responsesEnabled) frames.joinToString("\r", transform = ::physicalFrame) else ""
    }

    private fun resetFilters() { receiveFilter = 0x7E8; receiveMask = 0x7F8 }
    private fun defaults() {
        scriptPositions.clear(); pendingFrames = emptyList(); expectedFrames = null
        echo = true; headers = false; spaces = true; linefeeds = false
        autoFormatting = true; autoFlowControl = true; showDlc = false; responsesEnabled = true; allowLong = false
        protocol = savedProtocol; memoryEnabled = true; temporaryProtocol = false; connected = false
        timeoutMs = 200; learnedTimeoutMs = 200; adaptiveMode = 1
        requestAddress = "7DF"; resetFilters()
    }
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

data class AdapterReply(val text: String, val delayMs: Int)
