package org.voltarians.elmlab.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.voltarians.elmlab.*
import java.net.NetworkInterface
import java.util.Locale

/** General-purpose simulator controls, independent of any diagnostic client or vehicle make. */
class MainActivity : AppCompatActivity() {
    private var service: EmulatorService? = null
    private var bound = false
    private var connectedOnce = false
    private var restoredDraft = false
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var start: Button
    private lateinit var profiles: Spinner
    private lateinit var faults: Spinner
    private lateinit var readiness: CheckBox
    private lateinit var freeze: CheckBox
    private lateinit var script: EditText
    private lateinit var content: LinearLayout
    private val fields = linkedMapOf<String, EditText>()
    private var choices = emptyList<VehicleProfile>()
    private var draft = VehicleProfile.presets().first()
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val s = service
            status.text = if (s?.running == true) "${s.status}\n${s.bluetoothStatus}\nClients: ${s.connectionCount()}" else "Stopped"
            start.text = if (s?.running == true) "Stop simulator" else "Start simulator"
            transcript.text = s?.transcript()?.takeLast(12000).orEmpty().ifEmpty { "Commands will appear here when a client connects." }
            handler.postDelayed(this, 1000)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as EmulatorService.LocalBinder).service
            if (!connectedOnce && !restoredDraft && service?.running == true) showProfile(service!!.simulation.snapshot())
            connectedOnce = true
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) enableBluetooth() else message("Nearby devices permission denied. TCP remains available.")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val discoverable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching { contentResolver.openOutputStream(uri)?.use { output -> java.io.File(cacheDir, "pending-export.txt").inputStream().use { it.copyTo(output) } } ?: error("Cannot open file") }
            .onSuccess { message("Export saved") }.onFailure { message(it.message ?: "Export failed") }
    }
    private val importProfile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = ByteArray(65537)
                var total = 0
                while (total < bytes.size) { val n = stream.read(bytes, total, bytes.size - total); if (n < 0) break; total += n }
                require(total <= 65536) { "Profile is too large" }
                String(bytes, 0, total, Charsets.UTF_8)
            } ?: error("Cannot open profile")
            LabStore.decode(text)
        }.onSuccess { showProfile(it); message("Imported into editor. Tap Apply to activate.") }.onFailure { message(it.message ?: "Invalid profile") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(30)) }
        setContentView(ScrollView(this).apply { addView(content) })
        heading("ELM Lab", 30f)
        heading("OBD-II simulator", 20f)
        heading("Simulate a vehicle for diagnostic software, development, and training.", 15f)
        status = heading("Stopped", 17f)
        heading("Same phone: 127.0.0.1:35000\nOther device: ${addresses()}:35000\nTCP and Bluetooth Classic SPP · CAN 11-bit / 500 kbit/s", 14f)
        start = button("Start simulator") {
            if (service?.running == true) service?.stopSimulation()
            else if (applyProfile()) {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                ContextCompat.startForegroundService(this, Intent(this, EmulatorService::class.java))
            }
        }
        button("Enable Bluetooth") { requestBluetooth() }
        heading("Profile", 22f)
        profiles = Spinner(this)
        content.addView(profiles)
        button("Load selected profile") { choices.getOrNull(profiles.selectedItemPosition)?.let { showProfile(it); message("Loaded into editor. Tap Apply to activate.") } }
        addField("name", "Profile name", false)
        addField("vin", "Simulated VIN (17 characters)", false)
        addField("speed", "Speed (km/h)")
        addField("rpm", "Engine speed (RPM)")
        addField("coolant", "Coolant (°C)")
        addField("supply", "Adapter supply (V)")
        addField("module", "Control module voltage (V)")
        addField("load", "Engine load (%)")
        addField("intake", "Intake temperature (°C)")
        addField("airflow", "Mass airflow (g/s)")
        addField("throttle", "Throttle (%)")
        addField("runtime", "Runtime (seconds)")
        addField("fuel", "Fuel level (%)")
        addField("ambient", "Ambient temperature (°C)")
        readiness = CheckBox(this).apply { text = "Readiness monitors complete" }; content.addView(readiness)
        heading("Diagnostic trouble codes", 22f)
        heading("Separate codes with commas. Example: P0300, U0100. Mode 04 clears stored/pending codes and freeze frame; permanent codes remain.", 14f)
        addField("stored", "Stored DTCs", false)
        addField("pending", "Pending DTCs", false)
        addField("permanent", "Permanent DTCs", false)
        freeze = CheckBox(this).apply { text = "Include freeze frame (first stored DTC)" }; content.addView(freeze)
        button("Capture freeze frame now") {
            runCatching {
                val p = readProfile()
                require(p.storedDtcs.isNotEmpty()) { "Add a stored DTC first" }
                p.freezeFrame = FreezeFrame(p.storedDtcs.first(), p.rpm, p.speedKph, p.coolantC, p.engineLoadPercent)
                showProfile(p)
                message("Freeze frame captured in editor. Tap Apply to activate.")
            }.onFailure { message(it.message ?: "Invalid readings") }
        }
        heading("Response faults", 22f)
        faults = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Normal", "NO DATA", "CAN ERROR", "Malformed data")) }; content.addView(faults)
        addField("delay", "Response delay (0–5000 ms)")
        heading("Custom response sequences", 22f)
        heading("JSON maps hex requests to response text arrays. Example: {\"010D\":[\"41 0D 00\",\"41 0D 28\"]}. Each connection advances independently, then repeats the last response. Text is sent exactly as entered, without an added header or prompt. Clear to use built-in responses.", 14f)
        script = EditText(this).apply { filters = arrayOf(android.text.InputFilter.LengthFilter(65536)); minLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; content.addView(this) }
        button("Apply to simulator") { if (applyProfile()) message("Simulator updated") }
        button("Refresh from simulator") { service?.let { showProfile(it.simulation.snapshot()) } }
        button("Save profile on phone") {
            runCatching { readProfile() }.onSuccess { p ->
                if (LabStore.saved(this).containsKey(p.name)) AlertDialog.Builder(this).setTitle("Replace saved profile?").setMessage(p.name)
                    .setPositiveButton("Replace") { _, _ -> save(p) }.setNegativeButton("Cancel", null).show()
                else save(p)
            }.onFailure { message(it.message ?: "Invalid profile") }
        }
        button("Delete selected saved profile") {
            val index = profiles.selectedItemPosition - VehicleProfile.presets().size
            if (index < 0) message("Starter scenarios cannot be deleted")
            else choices.getOrNull(profiles.selectedItemPosition)?.let { p ->
                AlertDialog.Builder(this).setTitle("Delete saved profile?").setMessage(p.name)
                    .setPositiveButton("Delete") { _, _ -> LabStore.delete(this, p.name); refreshProfiles() }.setNegativeButton("Cancel", null).show()
            }
        }
        button("Export profile JSON") { runCatching { LabStore.encode(readProfile()) }.onSuccess { exportText(it, "elm-lab-profile.json") }.onFailure { message(it.message ?: "Invalid profile") } }
        button("Import profile JSON") { importProfile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
        heading("Command log", 22f)
        button("Export recorded response profile") {
            val responses = service?.recordedResponses().orEmpty()
            if (responses.isEmpty()) message("No diagnostic responses recorded yet")
            else runCatching { LabStore.encode(service!!.simulation.snapshot().copy(name = "Recorded responses", fault = ResponseFault.NONE, responseScript = responses)) }
                .onSuccess { exportText(it, "elm-lab-recorded-profile.json") }.onFailure { message(it.message ?: "Recording is too large") }
        }
        button("Export command log") { exportText(service?.transcript().orEmpty(), "elm-lab-commands.txt") }
        button("Clear command log") { service?.clearLog() }
        transcript = heading("", 12f).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        heading("Profiles and logs stay on your device unless you export them. TCP accepts connections from devices on the same network while running. No real vehicle connection is required.", 14f)
        button("About ELM Lab") {
            val license = assets.open("LICENSE.txt").bufferedReader().use { it.readText() }
            AlertDialog.Builder(this).setTitle("ELM Lab 0.3.0 beta").setMessage("General-purpose OBD-II simulator.\n\nNo account, analytics, or in-app purchases. Profiles and logs remain local unless exported.\n\n$license").setPositiveButton("Close", null).show()
        }
        refreshProfiles()
        val restored = savedInstanceState?.getString("draft")?.let { runCatching { LabStore.decode(it) }.getOrNull() }
        restoredDraft = restored != null
        showProfile(restored ?: draft)
    }
    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, EmulatorService::class.java), connection, BIND_AUTO_CREATE)
        handler.post(refresh)
    }
    override fun onStop() {
        handler.removeCallbacks(refresh)
        if (bound) { unbindService(connection); bound = false }
        service = null
        super.onStop()
    }
    override fun onSaveInstanceState(out: Bundle) {
        runCatching { LabStore.encode(readProfile()) }.onSuccess { out.putString("draft", it) }
        super.onSaveInstanceState(out)
    }
    private fun readProfile(): VehicleProfile {
        fun value(key: String) = fields.getValue(key).text.toString().trim()
        fun integer(key: String) = value(key).toIntOrNull() ?: error("Invalid number: $key")
        fun decimal(key: String) = value(key).toDoubleOrNull() ?: error("Invalid number: $key (use a decimal point)")
        fun codes(key: String) = value(key).split(',', ' ', '\n').filter { it.isNotBlank() }.map { it.uppercase(Locale.ROOT) }.distinct()
        val p = VehicleProfile(name = value("name"), vin = value("vin").uppercase(Locale.ROOT), speedKph = integer("speed"), rpm = integer("rpm"),
            coolantC = integer("coolant"), supplyVoltage = decimal("supply"), controlModuleVoltage = decimal("module"), engineLoadPercent = integer("load"),
            intakeC = integer("intake"), massAirflowGps = decimal("airflow"), throttlePercent = integer("throttle"), runtimeSeconds = integer("runtime"),
            fuelPercent = integer("fuel"), ambientC = integer("ambient"), storedDtcs = codes("stored"), pendingDtcs = codes("pending"), permanentDtcs = codes("permanent"),
            monitorsComplete = readiness.isChecked, responseDelayMs = integer("delay"), fault = ResponseFault.entries[faults.selectedItemPosition],
            responseScript = script.text.toString().trim().takeIf { it.isNotEmpty() }?.let { text ->
                val j = org.json.JSONObject(text)
                j.keys().asSequence().associateWith { key -> j.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } } }
            } ?: emptyMap())
        if (freeze.isChecked) {
            require(p.storedDtcs.isNotEmpty()) { "Freeze frame needs a stored DTC" }
            p.freezeFrame = draft.freezeFrame?.takeIf { it.dtc == p.storedDtcs.first() } ?: FreezeFrame(p.storedDtcs.first(), p.rpm, p.speedKph, p.coolantC, p.engineLoadPercent)
        }
        p.validate()
        return p
    }
    private fun showProfile(p: VehicleProfile) {
        draft = p.snapshot()
        val values = mapOf("name" to p.name, "vin" to p.vin, "speed" to p.speedKph, "rpm" to p.rpm, "coolant" to p.coolantC, "supply" to p.supplyVoltage,
            "module" to p.controlModuleVoltage, "load" to p.engineLoadPercent, "intake" to p.intakeC, "airflow" to p.massAirflowGps,
            "throttle" to p.throttlePercent, "runtime" to p.runtimeSeconds, "fuel" to p.fuelPercent, "ambient" to p.ambientC,
            "stored" to p.storedDtcs.joinToString(", "), "pending" to p.pendingDtcs.joinToString(", "), "permanent" to p.permanentDtcs.joinToString(", "), "delay" to p.responseDelayMs)
        values.forEach { (key, value) -> fields.getValue(key).setText(value.toString()) }
        script.setText(if (p.responseScript.isEmpty()) "" else org.json.JSONObject().apply { p.responseScript.forEach { (key, responses) -> put(key, org.json.JSONArray(responses)) } }.toString(2))
        readiness.isChecked = p.monitorsComplete; freeze.isChecked = p.freezeFrame != null; faults.setSelection(p.fault.ordinal)
    }
    private fun applyProfile(): Boolean = runCatching {
        val s = service ?: error("Simulator is connecting. Try again.")
        s.simulation.replace(readProfile()); true
    }.getOrElse { message(it.message ?: "Invalid profile"); false }
    private fun save(p: VehicleProfile) {
        runCatching { LabStore.save(this, p) }.onSuccess { refreshProfiles(); message("Profile saved") }.onFailure { message(it.message ?: "Save failed") }
    }
    private fun exportText(text: String, filename: String) {
        runCatching { java.io.File(cacheDir, "pending-export.txt").writeText(text); export.launch(filename) }.onFailure { message(it.message ?: "Export failed") }
    }
    private fun refreshProfiles() {
        val presets = VehicleProfile.presets()
        val saved = LabStore.saved(this).values.toList()
        choices = presets + saved
        profiles.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets.map { "Starter: ${it.name}" } + saved.map { "Saved: ${it.name}" })
    }
    private fun requestBluetooth() {
        if (service?.running != true) { message("Start the simulator first"); return }
        if (Build.VERSION.SDK_INT >= 31) {
            val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) { bluetoothPermission.launch(permissions); return }
        }
        enableBluetooth()
    }
    private fun enableBluetooth() {
        if (service?.running != true) return
        ContextCompat.startForegroundService(this, Intent(this, EmulatorService::class.java).putExtra("bluetooth", true))
        runCatching { discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)) }
            .onFailure { message("Bluetooth discovery unavailable. TCP remains available.") }
    }
    private fun addField(key: String, title: String, numeric: Boolean = true) {
        heading(title, 14f)
        fields[key] = EditText(this).apply {
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT
            setSingleLine(true); content.addView(this)
        }
    }
    private fun heading(text: String, size: Float): TextView = TextView(this).apply { this.text = text; textSize = size; setPadding(0, dp(8), 0, dp(4)); content.addView(this) }
    private fun button(title: String, action: () -> Unit): Button = Button(this).apply { text = title; setOnClickListener { action() }; content.addView(this) }
    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun addresses(): String = runCatching { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.filter { !it.isLoopbackAddress && it is java.net.Inet4Address }.joinToString(" / ") { it.hostAddress.orEmpty() }.ifEmpty { "phone IP" } }.getOrDefault("phone IP")
}
