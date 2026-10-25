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
 * Die Releases liegen im öffentlichen Repository gansklebrig/Audiophil – dafür ist kein
 * Zugangsschlüssel nötig. (Ein optionaler Token wird nur mitgeschickt, falls einer gespeichert ist.)
 */
object Updater {
    const val REPO = "gansklebrig/Audiophil"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val ASSET = "Equalizer.apk"
    private const val PREFS = "update"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    /** Ergebnis einer Abfrage. */
    data class Release(
        val version: Int, val name: String, val notes: String, val assetApi: String, val assetUrl: String,
        val size: Long = -1, val sha256: String? = null,
    )

    class UpdateException(msg: String) : Exception(msg)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Ist die App gerade sichtbar? Dann kann die Installations-Bestätigung direkt erscheinen. */
    @Volatile var uiVisible = false

    /** Bestätigung, die ankam, während die App im Hintergrund war – wird beim nächsten Öffnen gezeigt. */
    @Volatile var pendingConfirm: Intent? = null

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

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Equalizer-App")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

    /** Neuestes Release abfragen (blockiert – im Hintergrund aufrufen). */
    fun fetchLatest(ctx: Context): Release {
        prefs(ctx).edit().remove("token").apply()     // Zugang aus der privaten Zeit wird nicht mehr gebraucht
        val c = open(API, "application/vnd.github+json")
        val j = try {
            when (val code = c.responseCode) {
                200 -> JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                404 -> throw UpdateException("Auf GitHub ist noch keine Version veröffentlicht.")
                403, 429 -> throw UpdateException("GitHub hat die Abfrage gerade begrenzt. Bitte später noch einmal.")
                else -> throw UpdateException("GitHub antwortet mit Fehler $code.")
            }
        } finally { c.disconnect() }
        val tag = j.optString("tag_name")                    // z. B. "v1.7"
        val version = Regex("""^v?1\.(\d+)""").find(tag)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw UpdateException("Unbekannte Version „$tag“.")
        val assets = j.optJSONArray("assets")
        var api = ""; var url = ""; var size = -1L; var sha: String? = null
        for (i in 0 until (assets?.length() ?: 0)) {
            val a = assets!!.getJSONObject(i)
            if (a.optString("name") == ASSET) {
                api = a.optString("url"); url = a.optString("browser_download_url")
                size = a.optLong("size", -1)
                // GitHub liefert eine Prüfsumme „sha256:…“ mit
                sha = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
            }
        }
        if (api.isEmpty()) throw UpdateException("Im Release fehlt die Datei $ASSET.")
        val notes = j.optString("body").substringBefore("Installieren:").trim()
        prefs(ctx).edit()
            .putLong("last", System.currentTimeMillis())
            .putInt("newer", version)
            .apply()
        return Release(version, j.optString("name").ifEmpty { "Equalizer 1.$version" }, notes, api, url, size, sha)
    }

    // ---------- Heruntergeladene Dateien ----------

    private fun dir(ctx: Context) = File(ctx.cacheDir, "update").apply { mkdirs() }
    private fun apkFile(ctx: Context, version: Int) = File(dir(ctx), "Equalizer-1.$version.apk")

    /** Passt die Datei genau zum Release (Größe und – falls bekannt – Prüfsumme)? */
    private fun matches(f: File, size: Long, sha: String?): Boolean {
        if (!f.isFile || f.length() == 0L) return false
        if (size > 0 && f.length() != size) return false
        if (sha != null && hashOf(f) != sha) return false
        return true
    }

    // Prüfsumme nur neu rechnen, wenn sich die Datei geändert hat (die Anzeige fragt öfter nach)
    private var hashCache: Pair<String, String>? = null
    private fun hashOf(f: File): String {
        val key = "${f.path}|${f.length()}|${f.lastModified()}"
        hashCache?.let { (k, h) -> if (k == key) return h }
        return sha256(f).also { hashCache = key to it }
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Liegt diese Version schon vollständig heruntergeladen vor? */
    fun readyFile(ctx: Context, r: Release): File? = apkFile(ctx, r.version).takeIf { matches(it, r.size, r.sha256) }

    /**
     * Fertig heruntergeladene, noch nicht installierte Version – auch ohne Internet nutzbar
     * (z. B. wenn die Installation abgebrochen wurde).
     */
    fun localRelease(ctx: Context): Release? {
        val p = prefs(ctx)
        val v = p.getInt("dlVersion", 0)
        if (v <= installedVersion(ctx)) return null
        val r = Release(v, "Equalizer 1.$v", p.getString("dlNotes", "") ?: "", "", "",
            p.getLong("dlSize", -1), p.getString("dlSha", null))
        return if (readyFile(ctx, r) != null) r else null
    }

    /**
     * Aufräumen: alte Installationsdateien und abgebrochene Downloads löschen. Behalten wird nur
     * eine vollständige Datei einer Version, die neuer ist als die installierte.
     */
    fun cleanup(ctx: Context) {
        val keep = localRelease(ctx)?.let { apkFile(ctx, it.version) }
        dir(ctx).listFiles()?.forEach { if (it != keep) it.delete() }
        if (keep == null) prefs(ctx).edit().remove("dlVersion").remove("dlSize").remove("dlSha").remove("dlNotes").apply()
    }

    /**
     * APK herunterladen (blockiert). Liegt die Version schon vollständig vor, wird sie direkt verwendet.
     * [progress] bekommt 0..1 oder -1, wenn die Größe unbekannt ist.
     */
    fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File {
        readyFile(ctx, r)?.let { progress(1f); return it }
        // andere Versionen und Reste abgebrochener Downloads weg
        dir(ctx).listFiles()?.forEach { it.delete() }
        // GitHub leitet auf einen Speicher-Link um; den Weiterleitungen von Hand folgen.
        var c = open(r.assetApi, "application/octet-stream")
        var hops = 0
        while (c.responseCode in 301..308 && hops++ < 5) {
            val next = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = open(next, "application/octet-stream")
        }
        val out = apkFile(ctx, r.version)
        val part = File(out.path + ".part")    // erst nach vollständiger Prüfung umbenennen
        try {
            if (c.responseCode != 200) throw UpdateException("Download fehlgeschlagen (Fehler ${c.responseCode}).")
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { inp ->
                part.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        progress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
            if (total > 0 && done != total) throw UpdateException("Download unvollständig – bitte noch einmal versuchen.")
            if (!matches(part, r.size, r.sha256)) throw UpdateException("Die heruntergeladene Datei ist beschädigt – bitte noch einmal versuchen.")
            if (!part.renameTo(out)) throw UpdateException("Datei konnte nicht gespeichert werden.")
            prefs(ctx).edit()
                .putInt("dlVersion", r.version).putLong("dlSize", r.size)
                .putString("dlSha", r.sha256).putString("dlNotes", r.notes)
                .apply()
        } catch (e: Exception) {
            part.delete()
            out.delete()
            throw if (e is UpdateException) e else UpdateException("Download abgebrochen: ${e.message ?: "Verbindung verloren"}")
        } finally { c.disconnect() }
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
        try {
            pi.openSession(id).use { s ->
                s.openWrite(ASSET, 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val intent = Intent(ctx, InstallReceiver::class.java)
                s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            try { pi.abandonSession(id) } catch (_: Exception) {}
            throw UpdateException("Installation konnte nicht starten: ${e.message}")
        }
    }

    /** Hinweis in der Benachrichtigungsleiste, falls die Bestätigung im Hintergrund ankam. */
    fun notifyConfirm(ctx: Context, confirm: Intent) {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(
            "eq_update", "App-Update", android.app.NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 7, confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, "eq_update")
            .setSmallIcon(R.drawable.ic_eq)
            .setContentTitle("Equalizer-Update bereit")
            .setContentText("Antippen, um das Update zu installieren")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(7, n) } catch (_: SecurityException) {}
    }
}

/** Rückmeldung des Installers: Bestätigungsdialog anzeigen bzw. Fehler melden. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Aus dem Hintergrund darf Android kein Fenster öffnen – dann merken und benachrichtigen
                val shown = Updater.uiVisible && runCatching { ctx.startActivity(confirm) }.isSuccess
                if (!shown) {
                    Updater.pendingConfirm = confirm
                    Updater.notifyConfirm(ctx, confirm)
                }
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

    /** Ergebnisse aus dem Hintergrund nur anzeigen, wenn das Fenster noch da ist (sonst Absturz). */
    private fun ui(block: () -> Unit) = main.post {
        busy = false
        if (!a.isFinishing && !a.isDestroyed) block()
    }

    /** Beim Schließen der Ansicht: offene Rückmeldungen verwerfen. */
    fun release() = main.removeCallbacksAndMessages(null)

    /** Sichtbare Suche (Knopf). */
    fun checkNow() {
        if (busy) return
        busy = true
        ui.toast("Suche nach Update …")
        Thread {
            val res = runCatching { Updater.fetchLatest(a) }
            ui {
                onState()
                res.onSuccess { offer(it, quietIfCurrent = false) }
                res.onFailure { e ->
                    // Keine Verbindung, aber das Update liegt schon fertig vor → trotzdem anbieten
                    Updater.localRelease(a)?.let { offer(it, quietIfCurrent = false); return@onFailure }
                    AlertDialog.Builder(a)
                        .setTitle("Update")
                        .setMessage((e as? Updater.UpdateException)?.message ?: "Keine Verbindung zu GitHub.")
                        .setPositiveButton("OK", null)
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
            runCatching { Updater.cleanup(a) }
            runCatching { Updater.fetchLatest(a) }
            ui { onState() }
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
        val ready = Updater.readyFile(a, r) != null
        AlertDialog.Builder(a)
            .setTitle("${r.name} ist da")
            .setMessage(buildString {
                append("Installiert: 1.$mine  →  neu: 1.${r.version}")
                if (ready) append("\nBereits heruntergeladen – wird direkt installiert.")
                if (r.notes.isNotBlank()) append("\n\n").append(r.notes.take(1500))
                append("\n\nProfile und Einstellungen bleiben erhalten.")
            })
            .setPositiveButton(if (ready) "Installieren" else "Jetzt installieren") { _, _ -> startInstall(r) }
            .setNegativeButton("Später", null)
            .show()
    }

    /** Für Banner und Kachel: Release erneut holen und direkt anbieten. */
    fun installLatest() {
        if (busy) return
        busy = true
        Thread {
            val res = runCatching { Updater.fetchLatest(a) }
            ui {
                res.onSuccess { offer(it, quietIfCurrent = false) }
                res.onFailure { e ->
                    val local = Updater.localRelease(a)
                    if (local != null) offer(local, quietIfCurrent = false)
                    else ui.toast(e.message ?: "Keine Verbindung zu GitHub")
                }
                onState()
            }
        }.start()
    }

    /** Eine im Hintergrund angekommene Installations-Bestätigung jetzt zeigen. */
    fun showPendingConfirm() {
        val c = Updater.pendingConfirm ?: return
        Updater.pendingConfirm = null
        runCatching { a.startActivity(c) }
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
                runCatching { dlg.dismiss() }
                if (a.isFinishing || a.isDestroyed) return@post
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
}
