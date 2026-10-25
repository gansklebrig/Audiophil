package de.eq.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update direkt aus der App: fragt das neueste GitHub-Release ab, lädt Equalizer.apk herunter
 * und installiert es über Androids PackageInstaller (Profile und Einstellungen bleiben erhalten).
 *
 * Das Repository ist privat – dann braucht die App einen GitHub-Zugangsschlüssel (Token) mit
 * Lesezugriff auf „Contents“. Bei einem öffentlichen Repo geht es auch ohne.
 */
object Updater {
    const val REPO = "gansklebrig/Equalizer"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val ASSET = "Equalizer.apk"
    private const val PREFS = "update"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    /** Ergebnis einer Abfrage. */
    data class Release(val version: Int, val name: String, val notes: String, val assetApi: String, val assetUrl: String)

    class UpdateException(msg: String) : Exception(msg)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(ctx: Context): String = prefs(ctx).getString("token", "") ?: ""
    fun setToken(ctx: Context, t: String) = prefs(ctx).edit().putString("token", t.trim()).apply()

    fun autoCheck(ctx: Context) = prefs(ctx).getBoolean("auto", true)
    fun setAutoCheck(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto", on).apply()

    /** Beim letzten Mal gefundene neuere Version (0 = keine). */
    fun knownNewer(ctx: Context): Int = prefs(ctx).getInt("newer", 0).takeIf { it > installedVersion(ctx) } ?: 0

    /** Eigene Versionsnummer: 1.N → N (versionCode = 100 + N). */
    @Suppress("DEPRECATION")
    fun installedVersion(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
        return code - 100
    }

    fun installedName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    fun dueForCheck(ctx: Context) =
        autoCheck(ctx) && System.currentTimeMillis() - prefs(ctx).getLong("last", 0) > CHECK_EVERY_MS

    private fun open(url: String, ctx: Context, accept: String, auth: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Equalizer-App")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val t = token(ctx)
            if (auth && t.isNotEmpty()) setRequestProperty("Authorization", "Bearer $t")
        }

    /** Neuestes Release abfragen (blockiert – im Hintergrund aufrufen). */
    fun fetchLatest(ctx: Context): Release {
        val c = open(API, ctx, "application/vnd.github+json", auth = true)
        val code = c.responseCode
        if (code == 401) throw UpdateException("Der GitHub-Zugang ist ungültig oder abgelaufen.")
        if (code == 404) throw UpdateException(
            if (token(ctx).isEmpty()) "Das Repository ist privat – bitte einmal den GitHub-Zugang eintragen."
            else "Kein Release gefunden, oder der Zugang darf dieses Repository nicht lesen."
        )
        if (code == 403) throw UpdateException("GitHub hat die Abfrage gerade begrenzt. Bitte später noch einmal.")
        if (code != 200) throw UpdateException("GitHub antwortet mit Fehler $code.")
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val tag = j.optString("tag_name")                    // z. B. "v1.7"
        val version = tag.substringAfterLast('.').toIntOrNull() ?: throw UpdateException("Unbekannte Version „$tag“.")
        val assets = j.optJSONArray("assets")
        var api = ""; var url = ""
        for (i in 0 until (assets?.length() ?: 0)) {
            val a = assets!!.getJSONObject(i)
            if (a.optString("name") == ASSET) { api = a.optString("url"); url = a.optString("browser_download_url") }
        }
        if (api.isEmpty()) throw UpdateException("Im Release fehlt die Datei $ASSET.")
        val notes = j.optString("body").substringBefore("Installieren:").trim()
        prefs(ctx).edit()
            .putLong("last", System.currentTimeMillis())
            .putInt("newer", version)
            .apply()
        return Release(version, j.optString("name").ifEmpty { "Equalizer 1.$version" }, notes, api, url)
    }

    /** APK herunterladen (blockiert). [progress] bekommt 0..1 oder -1, wenn die Größe unbekannt ist. */
    fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File {
        // Über die API (funktioniert auch bei privaten Repos). GitHub leitet auf einen
        // Speicher-Link um, der selbst schon berechtigt ist – dorthin ohne Zugangsschlüssel.
        var c = open(r.assetApi, ctx, "application/octet-stream", auth = true)
        var hops = 0
        while (c.responseCode in 301..308 && hops++ < 5) {
            val next = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = open(next, ctx, "application/octet-stream", auth = false)
        }
        if (c.responseCode != 200) throw UpdateException("Download fehlgeschlagen (Fehler ${c.responseCode}).")
        val total = c.contentLengthLong
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, ASSET)
        c.inputStream.use { inp ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    progress(if (total > 0) done.toFloat() / total else -1f)
                }
            }
        }
        return out
    }

    /** Darf diese App Pakete installieren? (Android fragt das einmalig ab.) */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(a: Activity) {
        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
    }

    /** Übergibt die APK an Androids Installer; Android zeigt dann „Aktualisieren?“. */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite(ASSET, 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = Intent(ctx, InstallReceiver::class.java)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}

/** Rückmeldung des Installers: Bestätigungsdialog anzeigen bzw. Fehler melden. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unbekannter Fehler"
                android.widget.Toast.makeText(ctx, "Update fehlgeschlagen: $msg", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** Bedienung des Updates: Suchen, Hinweis mit Neuerungen, Download mit Fortschritt, Installation. */
class UpdateUi(private val a: Activity, private val ui: Ui, private val onState: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    /** Sichtbare Suche (Knopf). */
    fun checkNow() {
        if (busy) return
        busy = true
        ui.toast("Suche nach Update …")
        Thread {
            val res = runCatching { Updater.fetchLatest(a) }
            main.post {
                busy = false
                onState()
                res.onSuccess { offer(it, quietIfCurrent = false) }
                res.onFailure { e ->
                    val msg = (e as? Updater.UpdateException)?.message ?: "Keine Verbindung zu GitHub."
                    AlertDialog.Builder(a)
                        .setTitle("Update")
                        .setMessage(msg)
                        .apply { if (Updater.token(a).isEmpty() || e.message?.contains("Zugang") == true)
                            setPositiveButton("Zugang eintragen") { _, _ -> askToken() } }
                        .setNegativeButton("Schließen", null)
                        .show()
                }
            }
        }.start()
    }

    /** Stille Prüfung beim Öffnen der App (höchstens alle paar Stunden, Fehler werden verschluckt). */
    fun checkQuietly() {
        if (busy || !Updater.dueForCheck(a)) return
        busy = true
        Thread {
            runCatching { Updater.fetchLatest(a) }
            main.post { busy = false; onState() }
        }.start()
    }

    /** Hinweis mit Neuerungen zeigen, falls neuer als installiert. */
    fun offer(r: Updater.Release, quietIfCurrent: Boolean = true) {
        val mine = Updater.installedVersion(a)
        if (r.version <= mine) {
            if (!quietIfCurrent) AlertDialog.Builder(a)
                .setTitle("Alles aktuell")
                .setMessage("Du hast die neueste Version (1.$mine).")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(a)
            .setTitle("${r.name} ist da")
            .setMessage(buildString {
                append("Installiert: 1.$mine  →  neu: 1.${r.version}")
                if (r.notes.isNotBlank()) append("\n\n").append(r.notes.take(1500))
                append("\n\nProfile und Einstellungen bleiben erhalten.")
            })
            .setPositiveButton("Jetzt installieren") { _, _ -> startInstall(r) }
            .setNegativeButton("Später", null)
            .show()
    }

    /** Für das Banner auf der Startseite: Release erneut holen und direkt anbieten. */
    fun installLatest() {
        if (busy) return
        busy = true
        Thread {
            val res = runCatching { Updater.fetchLatest(a) }
            main.post {
                busy = false
                res.onSuccess { offer(it, quietIfCurrent = false) }
                res.onFailure { ui.toast(it.message ?: "Keine Verbindung zu GitHub") }
                onState()
            }
        }.start()
    }

    private fun startInstall(r: Updater.Release) {
        if (!Updater.canInstall(a)) {
            AlertDialog.Builder(a)
                .setTitle("Installieren erlauben")
                .setMessage(
                    "Android fragt einmalig, ob der Equalizer Updates installieren darf. " +
                    "Im nächsten Fenster „Dieser Quelle vertrauen“ einschalten, zurückkehren und noch einmal auf Installieren tippen."
                )
                .setPositiveButton("Weiter") { _, _ -> Updater.openInstallPermission(a) }
                .setNegativeButton("Abbrechen", null)
                .show()
            return
        }
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Palette.gold)
        }
        val label = TextView(a).apply { setTextColor(Palette.champagne); text = "Lade 1.${r.version} …" }
        val box = ui.vbox().apply {
            setPadding(ui.dp(22), ui.dp(16), ui.dp(22), ui.dp(8))
            addView(label)
            addView(bar, MATCH_PARENT, WRAP_CONTENT)
        }
        val dlg = AlertDialog.Builder(a).setTitle("Update").setView(box).setCancelable(false).show()
        busy = true
        Thread {
            val res = runCatching {
                var last = 0L
                val apk = Updater.download(a, r) { p ->
                    val now = System.currentTimeMillis()
                    if (now - last > 100) {
                        last = now
                        main.post { if (p < 0) bar.isIndeterminate = true else bar.progress = (p * 1000).toInt() }
                    }
                }
                main.post { label.text = "Installiere …"; bar.isIndeterminate = true }
                Updater.install(a, apk)
            }
            main.post {
                busy = false
                dlg.dismiss()
                res.onFailure { e ->
                    AlertDialog.Builder(a)
                        .setTitle("Update fehlgeschlagen")
                        .setMessage(e.message ?: "Unbekannter Fehler")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    /** GitHub-Zugang eintragen (für das private Repository). */
    fun askToken() {
        val input = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "github_pat_…"
            setText(Updater.token(a))
        }
        val box = ui.vbox().apply {
            setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0)
            addView(ui.hint(
                "Das Repository ist privat, deshalb braucht die App einen Lese-Zugang.\n\n" +
                "Auf github.com: Settings → Developer settings → Personal access tokens → Fine-grained tokens → " +
                "Generate new token. Bei „Repository access“ nur „${Updater.REPO}“ wählen, bei „Permissions“ " +
                "→ Contents: Read-only. Ablaufdatum nach Wunsch. Den Token hier einfügen.\n\n" +
                "Er wird nur auf diesem Handy gespeichert und nur an GitHub geschickt."
            ))
            addView(input, MATCH_PARENT, WRAP_CONTENT)
        }
        AlertDialog.Builder(a)
            .setTitle("GitHub-Zugang")
            .setView(box)
            .setPositiveButton("Speichern & suchen") { _, _ ->
                Updater.setToken(a, input.text.toString())
                onState()
                checkNow()
            }
            .setNeutralButton("Token anlegen") { _, _ ->
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/personal-access-tokens/new")))
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}
