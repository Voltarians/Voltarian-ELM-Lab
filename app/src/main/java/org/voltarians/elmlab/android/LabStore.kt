package org.voltarians.elmlab.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.voltarians.elmlab.*

/** Local-only profiles. Versioned JSON can also be exported to a user-selected file. */
object LabStore {
    fun encode(p: VehicleProfile): String {
        p.validate()
        val encoded = JSONObject().apply {
            put("schemaVersion", 1)
            put("name", p.name); put("vin", p.vin)
            put("supplyVoltage", p.supplyVoltage); put("controlModuleVoltage", p.controlModuleVoltage)
            put("coolantC", p.coolantC); put("rpm", p.rpm); put("speedKph", p.speedKph)
            put("engineLoadPercent", p.engineLoadPercent); put("intakeC", p.intakeC)
            put("massAirflowGps", p.massAirflowGps); put("throttlePercent", p.throttlePercent)
            put("runtimeSeconds", p.runtimeSeconds); put("fuelPercent", p.fuelPercent); put("ambientC", p.ambientC)
            put("storedDtcs", JSONArray(p.storedDtcs)); put("pendingDtcs", JSONArray(p.pendingDtcs)); put("permanentDtcs", JSONArray(p.permanentDtcs))
            put("monitorsComplete", p.monitorsComplete); put("responseDelayMs", p.responseDelayMs); put("fault", p.fault.name)
            put("responseScript", JSONObject().apply { p.responseScript.forEach { (key, responses) -> put(key, JSONArray(responses)) } })
            p.freezeFrame?.let { f -> put("freezeFrame", JSONObject().apply {
                put("dtc", f.dtc); put("rpm", f.rpm); put("speedKph", f.speedKph); put("coolantC", f.coolantC); put("engineLoadPercent", f.engineLoadPercent)
            }) }
        }.toString(2)
        require(encoded.toByteArray(Charsets.UTF_8).size <= 65536) { "Profile export exceeds 64 KiB; use fewer recorded responses" }
        return encoded
    }

    fun decode(text: String): VehicleProfile {
        require(text.length <= 65536) { "Profile is too large" }
        val j = JSONObject(text)
        require(j.getInt("schemaVersion") == 1) { "Unsupported profile version" }
        fun codes(key: String) = j.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
        return VehicleProfile(
            name = j.getString("name"), vin = j.getString("vin"), supplyVoltage = j.getDouble("supplyVoltage"),
            controlModuleVoltage = j.getDouble("controlModuleVoltage"), coolantC = j.getInt("coolantC"), rpm = j.getInt("rpm"), speedKph = j.getInt("speedKph"),
            engineLoadPercent = j.getInt("engineLoadPercent"), intakeC = j.getInt("intakeC"), massAirflowGps = j.getDouble("massAirflowGps"),
            throttlePercent = j.getInt("throttlePercent"), runtimeSeconds = j.getInt("runtimeSeconds"), fuelPercent = j.getInt("fuelPercent"), ambientC = j.getInt("ambientC"),
            storedDtcs = codes("storedDtcs"), pendingDtcs = codes("pendingDtcs"), permanentDtcs = codes("permanentDtcs"),
            monitorsComplete = j.getBoolean("monitorsComplete"), responseDelayMs = j.getInt("responseDelayMs"), fault = ResponseFault.valueOf(j.getString("fault")),
            responseScript = j.optJSONObject("responseScript")?.let { script -> script.keys().asSequence().associateWith { key -> script.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } } } } ?: emptyMap(),
            freezeFrame = j.optJSONObject("freezeFrame")?.let { FreezeFrame(it.getString("dtc"), it.getInt("rpm"), it.getInt("speedKph"), it.getInt("coolantC"), it.getInt("engineLoadPercent")) }
        ).also { it.validate() }
    }
    fun saved(context: Context): Map<String, VehicleProfile> = context.getSharedPreferences("profiles", Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
        runCatching { key to decode(value as String) }.getOrNull()
    }.toMap().toSortedMap()
    fun save(context: Context, p: VehicleProfile) { p.validate(); context.getSharedPreferences("profiles", Context.MODE_PRIVATE).edit().putString(p.name, encode(p)).apply() }
    fun delete(context: Context, name: String) { context.getSharedPreferences("profiles", Context.MODE_PRIVATE).edit().remove(name).apply() }
}
