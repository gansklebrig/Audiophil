package de.eq.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

const val PROFILE_PREFIX = "★ "
const val CUSTOM = "Benutzerdefiniert"
const val FLAT = "Flach"
/** Schlüssel für „alle anderen Geräte“ in der Kopfhörer-Zuordnung. */
const val DEFAULT_DEVICE_KEY = "*"

data class DeviceAssignment(val key: String, val label: String, val profile: String)

/** Speichert alle Einstellungen dauerhaft. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("eq", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Globaler Modus: Effekt auf Session 0 (gesamte Audioausgabe). */
    var global: Boolean
        get() = sp.getBoolean("global", true)
        set(v) = sp.edit().putBoolean("global", v).apply()

    /** Symbol der Benachrichtigung in der Statusleiste zeigen. */
    var showIcon: Boolean
        get() = sp.getBoolean("showIcon", true)
        set(v) = sp.edit().putBoolean("showIcon", v).apply()

    /** Profil beim Wechsel des Kopfhörers automatisch umschalten. */
    var autoSwitch: Boolean
        get() = sp.getBoolean("autoSwitch", true)
        set(v) = sp.edit().putBoolean("autoSwitch", v).apply()

    /** Zuletzt erkanntes Ausgabegerät – damit nur bei echten Wechseln umgeschaltet wird. */
    var lastDeviceKey: String?
        get() = sp.getString("lastDevice", null)
        set(v) = sp.edit().putString("lastDevice", v).apply()

    /** Name des gewählten Eintrags in der Profil-Liste. */
    var presetName: String
        get() = sp.getString("preset", CUSTOM) ?: CUSTOM
        set(v) = sp.edit().putString("preset", v).apply()

    /** Aktueller Klangzustand. */
    var state: EqState
        get() {
            sp.getString("state", null)?.let {
                return try { EqState.fromJson(JSONObject(it)) } catch (e: Exception) { EqState() }
            }
            // Übernahme der Einstellungen aus der vorigen App-Version
            val s = sp.getString("bands", "") ?: ""
            val bands = if (s.isEmpty()) IntArray(0)
                        else s.split(",").mapNotNull { it.trim().toIntOrNull() }.toIntArray()
            return EqState(bands = bands, bass = sp.getInt("bass", 0), loudness = sp.getInt("loudness", 0))
        }
        set(v) = sp.edit().putString("state", v.toJson().toString()).apply()

    // ---------- Eigene Profile ----------

    var profiles: List<Profile>
        get() = try {
            val arr = JSONArray(sp.getString("profiles", "[]"))
            (0 until arr.length()).map { Profile.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            sp.edit().putString("profiles", arr.toString()).apply()
        }

    fun profileNames(): List<String> = profiles.map { it.name }.sortedBy { it.lowercase() }

    fun findProfile(name: String): Profile? = profiles.firstOrNull { it.name == name }

    /** Aktuelle Einstellungen unter einem Namen speichern (überschreibt gleichnamige). */
    fun saveProfile(name: String) {
        profiles = profiles.filter { it.name != name } + Profile(name, state)
    }

    fun deleteProfile(name: String) {
        profiles = profiles.filter { it.name != name }
        deviceAssignments().filter { it.profile == name }.forEach { assignDevice(it.key, it.label, null) }
    }

    fun renameProfile(old: String, new: String) {
        profiles = profiles.filter { it.name != new }.map { if (it.name == old) it.copy(name = new) else it }
        deviceAssignments().filter { it.profile == old }.forEach { assignDevice(it.key, it.label, new) }
    }

    /** Profil laden und als aktiv markieren. */
    fun loadProfile(name: String): Boolean {
        val p = findProfile(name) ?: return false
        state = p.state
        presetName = PROFILE_PREFIX + name
        return true
    }

    /** Name des aktiven ★-Profils oder null. */
    fun currentProfileName(): String? {
        val p = presetName
        if (!p.startsWith(PROFILE_PREFIX)) return null
        val name = p.removePrefix(PROFILE_PREFIX)
        return if (findProfile(name) != null) name else null
    }

    /** Zum nächsten ★-Profil wechseln (alphabetisch, im Kreis). */
    fun nextProfile(): String? = stepProfile(+1)

    /** Zum vorigen ★-Profil wechseln. */
    fun prevProfile(): String? = stepProfile(-1)

    private fun stepProfile(dir: Int): String? {
        val names = profileNames()
        if (names.isEmpty()) return null
        val idx = names.indexOf(currentProfileName())
        val start = if (idx < 0 && dir < 0) 0 else idx
        val next = names[((start + dir) % names.size + names.size) % names.size]
        loadProfile(next)
        return next
    }

    /** Merkt sich, ob das Live-Spektrum angezeigt werden soll. */
    var spectrum: Boolean
        get() = sp.getBoolean("spectrum", false)
        set(v) = sp.edit().putBoolean("spectrum", v).apply()

    // ---------- Export / Import ----------

    fun exportJson(): String = JSONObject()
        .put("app", "de.eq.app")
        .put("version", 2)
        .put("profiles", JSONArray().also { a -> profiles.forEach { a.put(it.toJson()) } })
        .toString(2)

    /** Liest exportierte Profile ein (gleichnamige werden ersetzt). Gibt die Anzahl zurück. */
    fun importJson(text: String): Int {
        val t = text.trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).getJSONArray("profiles")
        val imported = (0 until arr.length()).map { Profile.fromJson(arr.getJSONObject(it)) }
        val names = imported.map { it.name }.toSet()
        profiles = profiles.filter { it.name !in names } + imported
        return imported.size
    }

    // ---------- Kopfhörer-Zuordnung ----------

    fun deviceAssignments(): List<DeviceAssignment> {
        val o = try { JSONObject(sp.getString("devices", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        return o.keys().asSequence().mapNotNull { k ->
            val v = o.optJSONObject(k) ?: return@mapNotNull null
            DeviceAssignment(k, v.optString("label", k), v.optString("profile", ""))
        }.filter { it.profile.isNotEmpty() }.sortedBy { it.label.lowercase() }.toList()
    }

    /** Profil einem Gerät zuordnen; profile = null entfernt die Zuordnung. */
    fun assignDevice(key: String, label: String, profile: String?) {
        val o = try { JSONObject(sp.getString("devices", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        if (profile == null) o.remove(key)
        else o.put(key, JSONObject().put("label", label).put("profile", profile))
        sp.edit().putString("devices", o.toString()).apply()
    }

    /** Profil, das für dieses Gerät aktiviert werden soll (oder das Standardprofil). */
    fun profileForDevice(key: String): String? {
        val all = deviceAssignments()
        val p = (all.firstOrNull { it.key == key } ?: all.firstOrNull { it.key == DEFAULT_DEVICE_KEY })?.profile
        return p?.takeIf { findProfile(it) != null }
    }
}
