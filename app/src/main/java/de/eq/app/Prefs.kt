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

    /** Aktueller Klangzustand (zwischengespeichert – beim Schieben wird er sehr oft gelesen). */
    var state: EqState
        get() {
            sp.getString("state", null)?.let { raw ->
                cachedState?.let { (r, s) -> if (r == raw) return s }
                val s = try { EqState.fromJson(JSONObject(raw)) } catch (e: Exception) { EqState() }
                cachedState = raw to s
                return s
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
        get() {
            val raw = sp.getString("profiles", "[]") ?: "[]"
            cachedProfiles?.let { (r, p) -> if (r == raw) return p }
            val list = try { parseProfiles(JSONArray(raw)) } catch (e: Exception) { emptyList() }
            cachedProfiles = raw to list
            return list
        }
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
        if (old == new) return
        profiles = profiles.filter { it.name != new }.map { if (it.name == old) it.copy(name = new) else it }
        deviceAssignments().filter { it.profile == old }.forEach { assignDevice(it.key, it.label, new) }
        val o = appsJson()
        o.keys().asSequence().toList().forEach { k -> o.optJSONObject(k)?.let { if (it.optString("profile") == old) it.put("profile", new) } }
        sp.edit().putString("apps", o.toString()).apply()
    }

    /** Profil laden und als aktiv markieren. */
    fun loadProfile(name: String): Boolean {
        val p = findProfile(name) ?: return false
        state = p.state
        presetName = PROFILE_PREFIX + name
        profileLoads++
        return true
    }

    /** Freier Name: „Name“, sonst „Name (2)“, „Name (3)“ … */
    fun uniqueName(base: String): String {
        val names = profiles.map { it.name }.toSet()
        if (base !in names) return base
        var i = 2
        while ("$base ($i)" in names) i++
        return "$base ($i)"
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

    /** Einstiegs-Karte auf der Startseite ausgeblendet. */
    var introHidden: Boolean
        get() = sp.getBoolean("introHidden", false)
        set(v) = sp.edit().putBoolean("introHidden", v).apply()

    /** Vorverstärkung automatisch senken, wenn sich Anhebungen addieren. */
    var autoHeadroom: Boolean
        get() = sp.getBoolean("autoHeadroom", true)
        set(v) = sp.edit().putBoolean("autoHeadroom", v).apply()

    // ---------- Profil je App ----------

    /** Beim Start einer Musik-App automatisch deren ★-Profil laden. */
    var appSwitch: Boolean
        get() = sp.getBoolean("appSwitch", true)
        set(v) = sp.edit().putBoolean("appSwitch", v).apply()

    private fun appsJson() = try { JSONObject(sp.getString("apps", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    /** Musik-Apps, die schon einmal Ton gemeldet haben: Paketname → Anzeigename. */
    fun seenApps(): List<Pair<String, String>> {
        val o = appsJson()
        return o.keys().asSequence().map { k -> k to (o.optJSONObject(k)?.optString("label", k) ?: k) }
            .sortedBy { it.second.lowercase() }.toList()
    }

    /** App vormerken (und ihren Namen aktualisieren). */
    fun noteApp(pkg: String, label: String) {
        val o = appsJson()
        val e = o.optJSONObject(pkg) ?: JSONObject()
        if (e.optString("label") == label && o.has(pkg)) return
        o.put(pkg, e.put("label", label))
        sp.edit().putString("apps", o.toString()).apply()
    }

    fun profileForApp(pkg: String): String? =
        appsJson().optJSONObject(pkg)?.optString("profile", "")?.takeIf { it.isNotEmpty() && findProfile(it) != null }

    /** Profil einer App zuordnen; null entfernt die Zuordnung. */
    fun assignApp(pkg: String, profile: String?) {
        val o = appsJson()
        val e = o.optJSONObject(pkg) ?: JSONObject().put("label", pkg)
        if (profile == null) e.remove("profile") else e.put("profile", profile)
        o.put(pkg, e)
        sp.edit().putString("apps", o.toString()).apply()
    }

    fun forgetApp(pkg: String) {
        val o = appsJson()
        o.remove(pkg)
        sp.edit().putString("apps", o.toString()).apply()
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

    /**
     * Liest exportierte Profile ein (gleichnamige werden ersetzt). Kaputte Einträge werden übersprungen,
     * auch eine einzelne Profil-Datei ohne Liste wird erkannt. Gibt die eingelesenen Namen zurück.
     */
    fun importJson(text: String): List<String> {
        val t = text.trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else {
            val o = JSONObject(t)
            o.optJSONArray("profiles") ?: JSONArray().put(o)
        }
        // bei doppelten Namen in der Datei gewinnt der letzte
        val imported = parseProfiles(arr).associateBy { it.name }.values.toList()
        val names = imported.map { it.name }.toSet()
        profiles = profiles.filter { it.name !in names } + imported
        return imported.map { it.name }
    }

    private fun parseProfiles(arr: JSONArray): List<Profile> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val name = o.optString("name").trim()
        if (name.isEmpty()) null else runCatching { Profile(name, EqState.fromJson(o)) }.getOrNull()
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

    companion object {
        // Zwischenspeicher (gelten für alle Prefs-Instanzen im Prozess)
        @Volatile private var cachedState: Pair<String, EqState>? = null
        @Volatile private var cachedProfiles: Pair<String, List<Profile>>? = null

        /** Zählt jedes Laden eines ★-Profils – die Oberfläche erkennt daran Wechsel von außen (Kachel, Widget …). */
        @Volatile var profileLoads = 0
            private set
    }
}
