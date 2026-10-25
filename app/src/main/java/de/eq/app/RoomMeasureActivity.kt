package de.eq.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Boxen einmessen: Über die angeschlossenen Boxen läuft rosa Rauschen, das Handy-Mikrofon nimmt es
 * am Hörplatz auf. Aus bis zu drei Messpunkten wird ein parametrischer EQ berechnet ([RoomEq]).
 */
class RoomMeasureActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager
    private val main = Handler(Looper.getMainLooper())

    private var targetType = 0
    private var positions = 3
    private val results = ArrayList<RoomResponse>()
    private var probe: Probe? = null
    private var measure: Measure? = null
    private var measuring = false
    private var fit: RoomFit? = null
    private var shown: RoomFit? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        audio = getSystemService(AudioManager::class.java)
        showIntro()
    }

    override fun onPause() {
        super.onPause()
        stopProbe()
        if (measuring) {
            measure?.running = false
            measuring = false
            ui.toast("Messung unterbrochen")
            showIntro()
        }
        EqService.instance?.setBypass(EqService.BYPASS_NONE)
    }

    @Deprecated("Activity-API")
    override fun onBackPressed() {
        if (measuring) { measure?.running = false; measuring = false; showIntro() }
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun page(title: String, onBack: () -> Unit, build: (LinearLayout) -> Unit) {
        val root = ui.vbox().apply { setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(36)) }
        root.addView(ui.pageHeader(title, onBack))
        build(root)
        setContentView(ScrollView(this).apply { setBackgroundColor(Palette.bg); addView(root) })
    }

    private fun numbered(c: LinearLayout, items: List<String>) = items.forEachIndexed { i, t ->
        c.addView(ui.hint("${i + 1}.  $t").apply { textSize = 14f; setTextColor(Palette.champagne) })
    }

    // =====================================================================
    // Einführung + Pegel
    // =====================================================================

    private fun showIntro() {
        stopProbe()
        measuring = false
        page("Boxen einmessen", { finish() }) { root ->
            val how = ui.card("So geht’s")
            numbered(how, listOf(
                "Boxen per Bluetooth oder Kabel verbinden.",
                "Handy an deinen Hörplatz legen – etwa auf Ohrhöhe, das Mikrofon (Unterseite) zu den Boxen. " +
                    "Nicht in der Hand halten, z. B. an ein Glas oder eine Stuhllehne lehnen.",
                "Für die Messung muss es im Raum ruhig sein. Es ist ein kräftiges Rauschen zu hören.",
                "Am genauesten wird es mit 3 Messungen an leicht versetzten Stellen (je 30–50 cm).",
            ))
            root.addView(how)

            val dev = AudioDevices.current(this)
            val out = ui.card("Ausgabe")
            out.addView(TextView(this).apply { text = dev.label; textSize = 16f; setTextColor(Palette.goldLight) })
            if (dev.key == AudioDevices.SPEAKER_KEY) {
                out.addView(ui.hint("Der Ton kommt gerade aus dem Handy-Lautsprecher. Bitte zuerst die Boxen verbinden.")
                    .apply { setTextColor(Palette.warn) })
            } else if (dev.key == "wired") {
                out.addView(ui.hint("Für Kopfhörer sind Hörtest und AutoEq besser geeignet – diese Messung ist für Boxen gedacht."))
            }
            root.addView(out)

            val lvl = ui.card("Lautstärke einstellen")
            lvl.addView(ui.hint(
                "Testrauschen starten und die Handy-Lautstärke so einstellen, dass es kräftig, aber nicht " +
                "unangenehm laut ist. Die Anzeige sollte „gut“ melden."
            ))
            val meter = TextView(this).apply {
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Palette.muted)
                setPadding(0, ui.dp(10), 0, ui.dp(6))
                text = "–"
            }
            lvl.addView(meter)
            lvl.addView(ui.wideButton("Testrauschen abspielen") {}.also { b ->
                b.setOnClickListener {
                    if (probe != null) {
                        stopProbe(); b.text = "Testrauschen abspielen"; meter.text = "–"; meter.setTextColor(Palette.muted)
                    } else withMic {
                        b.text = "Testrauschen stoppen"
                        startProbe { rms, peak -> showLevel(meter, rms, peak) }
                    }
                }
            })
            root.addView(lvl)

            val opt = ui.card("Einstellungen")
            opt.addView(ui.hint("Zielklang"))
            val tSp: Spinner = ui.choice { targetType = it }
            ui.setChoices(tSp, RoomEq.TARGET_NAMES, targetType)
            opt.addView(tSp)
            opt.addView(ui.hint("Messpunkte"))
            val pSp: Spinner = ui.choice { positions = if (it == 0) 1 else 3 }
            ui.setChoices(pSp, listOf("1 Messung (schnell)", "3 Messungen (genauer, empfohlen)"), if (positions == 1) 0 else 1)
            opt.addView(pSp)
            root.addView(opt)

            root.addView(ui.wideButton("Messung starten", primary = true) {
                withMic {
                    stopProbe()
                    results.clear()
                    runPosition(0)
                }
            }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(20) })
        }
    }

    private fun showLevel(meter: TextView, rms: Float, peak: Float) {
        val (txt, col) = when {
            peak > -0.5f -> "zu laut – etwas leiser drehen" to Palette.warn
            rms < -55f -> "zu leise – lauter drehen" to Palette.warn
            rms < -45f -> "etwas leise" to Palette.gold
            else -> "gut" to Palette.goldLight
        }
        meter.text = "%.0f dB  ·  %s".format(rms, txt)
        meter.setTextColor(col)
    }

    // =====================================================================
    // Messablauf
    // =====================================================================

    private fun runPosition(i: Int) {
        measuring = true
        lateinit var status: TextView
        lateinit var sub: TextView
        lateinit var bar: ProgressBar
        page("Messung ${i + 1} von $positions", { measure?.running = false; measuring = false; showIntro() }) { root ->
            val c = ui.card()
            status = TextView(this).apply {
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Palette.goldLight)
                setPadding(0, ui.dp(24), 0, ui.dp(8))
            }
            sub = ui.hint("").apply { gravity = Gravity.CENTER; textSize = 14f }
            bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progressTintList = ColorStateList.valueOf(Palette.gold)
                progressBackgroundTintList = ColorStateList.valueOf(Palette.track)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(10)).apply { topMargin = ui.dp(16) }
            }
            c.addView(status); c.addView(sub); c.addView(bar)
            root.addView(c)
            root.addView(ui.wideButton("Abbrechen") { measure?.running = false; measuring = false; showIntro() })
        }

        // kurzer Countdown, damit es ruhig ist, wenn die Messung beginnt
        var n = 3
        val tick = object : Runnable {
            override fun run() {
                if (!measuring) return
                if (n > 0) {
                    status.text = "$n"
                    sub.text = "Gleich geht’s los – bitte ruhig sein"
                    n--
                    main.postDelayed(this, 1000)
                    return
                }
                EqService.instance?.setBypass(EqService.BYPASS_ALL)
                measure = Measure(
                    onProgress = { phase, frac, level ->
                        status.text = if (phase == 0) "Raumgeräusch …" else "Messe Boxen …"
                        sub.text = if (phase == 0) "Stille" else "Pegel %.0f dB".format(level)
                        bar.progress = (((if (phase == 0) 0f else 0.21f) + frac * (if (phase == 0) 0.21f else 0.79f)) * 1000).toInt()
                    },
                    onDone = { resp, error ->
                        EqService.instance?.setBypass(EqService.BYPASS_NONE)
                        if (measuring) {
                            if (error != null || resp == null) showError(i, error ?: "Abgebrochen")
                            else {
                                results.add(resp)
                                if (i + 1 < positions) showMove(i + 1) else showResult()
                            }
                        }
                    },
                ).also { it.start() }
            }
        }
        main.post(tick)
    }

    private fun showError(i: Int, msg: String) {
        measuring = false
        page("Messung ${i + 1} von $positions", { showIntro() }) { root ->
            val c = ui.card("Messung nicht verwertbar")
            c.addView(ui.hint(msg).apply { textSize = 14f; setTextColor(Palette.warn) })
            root.addView(c)
            root.addView(ui.wideButton("Nochmal messen", primary = true) { runPosition(i) })
            if (results.isNotEmpty()) root.addView(ui.wideButton("Mit ${results.size} Messung(en) auswerten") { showResult() })
            root.addView(ui.wideButton("Zurück") { showIntro() })
        }
    }

    private fun showMove(next: Int) {
        measuring = false
        page("Messpunkt ${next + 1} von $positions", { showIntro() }) { root ->
            val c = ui.card("Handy versetzen")
            c.addView(ui.hint(
                "Lege das Handy 30–50 cm weiter weg ab – z. B. etwas links oder rechts vom Hörplatz, " +
                "wieder auf Ohrhöhe. Lautstärke nicht verändern."
            ).apply { textSize = 14f; setTextColor(Palette.champagne) })
            root.addView(c)
            root.addView(ui.wideButton("Weiter messen", primary = true) { runPosition(next) })
            root.addView(ui.wideButton("Mit ${results.size} Messung(en) auswerten") { showResult() })
        }
    }

    // =====================================================================
    // Ergebnis
    // =====================================================================

    private fun showResult() {
        measuring = false
        val f = RoomEq.fit(RoomEq.average(results), targetType)
        fit = f
        shown = f
        lateinit var graph: RoomView
        lateinit var summary: TextView
        page("Ergebnis", { showIntro() }) { root ->
            val c = ui.card("Frequenzgang am Hörplatz")
            graph = RoomView(this).apply { data = f }
            c.addView(graph)
            c.addView(ui.hint("— gemessen (hell)   — mit Korrektur (gold)   - - Ziel"))
            summary = ui.hint("").apply { textSize = 14f; setTextColor(Palette.champagne) }
            c.addView(summary)
            root.addView(c)

            val s = ui.card("Korrekturstärke")
            val v = ui.valueText()
            s.addView(ui.labelRow("Stärke", v))
            s.addView(ui.row(ui.slider(20, 20, display = { v.text = "${it * 5} %" }) { p ->
                val sf = f.scaled(p * 5)
                shown = sf
                graph.data = sf
                summary.text = describe(sf)
            }))
            s.addView(ui.hint("Bei 100 % wird voll ausgeglichen. Klingt es zu „technisch“, etwas zurücknehmen."))
            root.addView(s)

            root.addView(ui.wideButton("Als ★-Profil speichern", primary = true) { askSave() }
                .apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(20) })
            root.addView(ui.wideButton("Neu messen") { results.clear(); showIntro() })
            root.addView(ui.wideButton("Verwerfen") { finish() })
        }
        summary.text = describe(f)
    }

    private fun describe(f: RoomFit): String {
        val strongest = f.filters.minByOrNull { it.gain }
        val range = f.deviation()
        return buildString {
            append("Deine Boxen reichen bis etwa ${formatFreq(f.fLow)} hinab.\n")
            append("${f.filters.size} Filter")
            if (strongest != null && strongest.gain < -1f) {
                append(" · stärkste Absenkung %+.1f dB bei %s".format(strongest.gain, formatFreq(strongest.freq.toDouble())))
            }
            append("\nGrößte Abweichung vom Ziel ab %s: vorher ±%.1f dB → nachher ±%.1f dB".format(formatFreq(f.from), range.first, range.second))
        }
    }

    private fun askSave() {
        val f = shown ?: return
        val dev = AudioDevices.current(this)
        val def = if (dev.key == AudioDevices.SPEAKER_KEY) "Raum" else "Boxen · ${dev.label}"
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(def)
            setSelection(def.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Profil speichern")
            .setView(ui.vbox().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(input, MATCH_PARENT, WRAP_CONTENT) })
            .setPositiveButton("Speichern") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { def }
                save(name, f, dev)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun save(name: String, f: RoomFit, dev: OutDevice) {
        // Eigenständiges Profil: nur die Raumkorrektur, ohne Kopfhörer-Einstellungen (Hörtest, Boost …)
        prefs.state = EqState(
            mode = MODE_PARAMETRIC,
            bands = IntArray(prefs.state.bands.size),
            filters = f.filters,
            preamp = f.preamp,
        )
        prefs.saveProfile(name)
        prefs.presetName = PROFILE_PREFIX + name
        EqService.instance?.let { it.applyNow(); it.notifyUi() }

        fun done() {
            ui.toast("★ $name gespeichert")
            setResult(RESULT_OK)
            finish()
        }
        if (dev.key == AudioDevices.SPEAKER_KEY) { done(); return }
        AlertDialog.Builder(this)
            .setTitle("Mit den Boxen verknüpfen?")
            .setMessage("Soll „$name“ automatisch aktiviert werden, wenn „${dev.label}“ verbunden ist?")
            .setPositiveButton("Ja") { _, _ ->
                prefs.assignDevice(dev.key, dev.label, name)
                prefs.lastDeviceKey = dev.key
                done()
            }
            .setNegativeButton("Nein") { _, _ -> done() }
            .setOnCancelListener { done() }
            .show()
    }

    // =====================================================================
    // Mikrofon & Wiedergabe
    // =====================================================================

    private var pendingMic: (() -> Unit)? = null

    private fun withMic(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { action(); return }
        pendingMic = action
        AlertDialog.Builder(this)
            .setTitle("Mikrofon")
            .setMessage("Für die Messung hört das Handy über sein Mikrofon mit. Gespeichert wird nur das Messergebnis.")
            .setPositiveButton("Weiter") { _, _ -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) pendingMic?.invoke()
        else ui.toast("Ohne Mikrofon ist keine Messung möglich")
        pendingMic = null
    }

    /** Aufnahme über das eingebaute Mikrofon – möglichst ohne Signalverarbeitung des Handys. */
    private fun createRecorder(): AudioRecord? {
        val unprocessed = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = listOfNotNull(
            if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else null,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        val minBuf = AudioRecord.getMinBufferSize(RoomEq.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        for (src in sources) {
            try {
                @Suppress("MissingPermission")
                val r = AudioRecord(src, RoomEq.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, RoomEq.SR / 2) * 2)
                if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
                // Immer das eingebaute Mikrofon – nicht etwa ein Headset- oder USB-Eingang
                audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    ?.let { r.setPreferredDevice(it) }
                return r
            } catch (_: Exception) {}
        }
        return null
    }

    private fun createTrack(): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build())
        .setAudioFormat(AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(RoomEq.SR)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build())
        .setBufferSizeInBytes(max(AudioTrack.getMinBufferSize(RoomEq.SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT), 16384))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    /** Spielt [stim] (mono) auf beiden Kanälen; bei [loop] endlos. */
    private inner class Player(private val stim: FloatArray, private val loop: Boolean) : Thread("Messrauschen") {
        @Volatile var running = true
        override fun run() {
            val track = try { createTrack() } catch (e: Exception) { return }
            val chunk = RoomEq.SR / 20
            val buf = FloatArray(chunk * 2)
            try {
                track.play()
                var pos = 0
                while (running) {
                    if (pos >= stim.size) { if (loop) pos = RoomEq.SR / 4 else break }   // Einblendung nur einmal
                    val n = minOf(chunk, stim.size - pos)
                    for (i in 0 until n) { buf[2 * i] = stim[pos + i]; buf[2 * i + 1] = stim[pos + i] }
                    track.write(buf, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                    pos += n
                }
            } catch (_: Exception) {
            } finally {
                try { track.stop() } catch (_: Exception) {}
                track.release()
            }
        }
    }

    /** Pegelprüfung: Rauschen in Schleife, Mikrofonpegel laufend anzeigen. */
    private inner class Probe(private val onLevel: (Float, Float) -> Unit) : Thread("Pegelprüfung") {
        @Volatile var running = true
        override fun run() {
            val rec = createRecorder() ?: run { main.post { ui.toast("Mikrofon nicht verfügbar") }; return }
            val player = Player(RoomEq.pinkNoise(RoomEq.SR * 4, seed = 11L), loop = true)
            val buf = ShortArray(RoomEq.SR / 5)
            try {
                rec.startRecording()
                player.start()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var s = 0.0; var pk = 0
                    for (i in 0 until n) { val v = buf[i].toInt(); s += v.toDouble() * v; pk = max(pk, abs(v)) }
                    val rms = (10 * log10((s / n).coerceAtLeast(1e-9) / (32768.0 * 32768.0))).toFloat()
                    val peak = (20 * log10(pk.coerceAtLeast(1) / 32768.0)).toFloat()
                    main.post { if (running) onLevel(rms, peak) }
                }
            } catch (_: Exception) {
            } finally {
                player.running = false
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }
    }

    private fun startProbe(onLevel: (Float, Float) -> Unit) {
        stopProbe()
        EqService.instance?.setBypass(EqService.BYPASS_ALL)
        probe = Probe(onLevel).also { it.start() }
    }

    private fun stopProbe() {
        probe?.running = false
        probe = null
        if (!measuring) EqService.instance?.setBypass(EqService.BYPASS_NONE)
    }

    /**
     * Eine Messung: 2,3 s Stille (Raumgeräusch), dann 7 s Rauschen bei laufender Aufnahme.
     * Ausgewertet wird 1,0 – 6,5 s nach Rauschbeginn, so spielt eine Bluetooth-Verzögerung keine Rolle.
     */
    private inner class Measure(
        private val onProgress: (phase: Int, frac: Float, level: Float) -> Unit,
        private val onDone: (RoomResponse?, String?) -> Unit,
    ) : Thread("Raummessung") {
        @Volatile var running = true

        override fun run() {
            val sr = RoomEq.SR
            val rec = createRecorder()
            if (rec == null) { done(null, "Das Mikrofon konnte nicht geöffnet werden."); return }
            val stim = RoomEq.pinkNoise(7 * sr)
            var player: Player? = null
            try {
                rec.startRecording()
                val silence = read(rec, (2.3 * sr).toInt(), 0) ?: return done(null, null)
                player = Player(stim, loop = false).also { it.start() }
                val noisy = read(rec, (7.5 * sr).toInt(), 1) ?: return done(null, null)

                val from = sr; val to = (6.5 * sr).toInt()
                var peak = 0f
                for (i in from until to) peak = max(peak, abs(noisy[i]))
                if (peak > 0.97f) return done(null, "Zu laut – das Mikrofon übersteuert. Bitte die Lautstärke etwas senken und nochmal messen.")
                val resp = RoomEq.response(noisy, from, to, stim, silence.copyOfRange((0.3 * sr).toInt(), silence.size))
                val snr = RoomEq.broadbandSnr(resp)
                if (snr < 15f) return done(null,
                    "Zu leise gegenüber dem Raumgeräusch (Abstand %.0f dB). Bitte lauter drehen oder für mehr Ruhe sorgen.".format(snr))
                done(resp, null)
            } catch (e: Exception) {
                done(null, "Messung fehlgeschlagen: ${e.message}")
            } finally {
                player?.running = false
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }

        private fun done(r: RoomResponse?, err: String?) {
            if (!running) return
            main.post { onDone(r, err ?: if (r == null) "Abgebrochen" else null) }
        }

        /** Liest [n] Samples; meldet Fortschritt und Pegel. null = abgebrochen. */
        private fun read(rec: AudioRecord, n: Int, phase: Int): FloatArray? {
            val out = FloatArray(n)
            val buf = ShortArray(RoomEq.SR / 10)
            var pos = 0
            while (pos < n) {
                if (!running) return null
                val r = rec.read(buf, 0, minOf(buf.size, n - pos))
                if (r <= 0) continue
                var s = 0.0
                for (i in 0 until r) { val v = buf[i] / 32768f; out[pos + i] = v; s += v.toDouble() * v }
                pos += r
                val level = (10 * log10((s / r).coerceAtLeast(1e-12))).toFloat()
                val frac = pos.toFloat() / n
                main.post { if (running) onProgress(phase, frac, level) }
            }
            return out
        }
    }

    private fun formatFreq(f: Double) = if (f >= 1000) "%.1f kHz".format(f / 1000) else "%.0f Hz".format(f)

    companion object {
        private const val REQ_MIC = 21
    }
}

/** Grafik des Messergebnisses: gemessen, Ziel und mit Korrektur. */
class RoomView(ctx: Context) : View(ctx) {
    var data: RoomFit? = null
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.gold; alpha = 36; strokeWidth = d * 0.7f }
    private val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.gold; alpha = 90; strokeWidth = d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.champagne; alpha = 130; textSize = d * 9 }
    private val measured = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.champagne; alpha = 150; style = Paint.Style.STROKE; strokeWidth = d * 1.4f
    }
    private val corrected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.goldLight; style = Paint.Style.STROKE; strokeWidth = d * 2.3f; strokeJoin = Paint.Join.ROUND
    }
    private val target = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.muted; style = Paint.Style.STROKE; strokeWidth = d * 1.3f
        pathEffect = DashPathEffect(floatArrayOf(d * 5, d * 4), 0f)
    }
    private val shade = Paint().apply { color = Palette.bg; alpha = 150 }
    private val path = Path()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (230 * d).toInt())

    override fun onDraw(c: Canvas) {
        val f = data ?: return
        val pl = 28 * d; val pr = 8 * d; val pt = 10 * d; val pb = 18 * d
        val w = width - pl - pr; val h = height - pt - pb
        val all = f.measured.toList() + f.corrected.toList() + f.target.toList()
        val top = (ceil(all.max() / 6.0) * 6).toFloat().coerceIn(6f, 18f)
        val bottom = (floor(all.min() / 6.0) * 6).toFloat().coerceIn(-30f, -6f)
        fun x(fr: Double) = pl + (ln(fr / 20.0) / ln(1000.0)).toFloat() * w
        fun y(db: Float) = pt + (top - db.coerceIn(bottom, top)) / (top - bottom) * h

        text.textAlign = Paint.Align.CENTER
        for ((fr, l) in listOf(50 to "50", 100 to "100", 200 to "200", 500 to "500", 1000 to "1k", 2000 to "2k", 5000 to "5k", 10000 to "10k")) {
            c.drawLine(x(fr.toDouble()), pt, x(fr.toDouble()), pt + h, grid)
            c.drawText(l, x(fr.toDouble()), height - 4 * d, text)
        }
        text.textAlign = Paint.Align.RIGHT
        var db = bottom
        while (db <= top) {
            c.drawLine(pl, y(db), width - pr, y(db), if (db == 0f) zero else grid)
            c.drawText(if (db > 0) "+%.0f".format(db) else "%.0f".format(db), pl - 4 * d, y(db) + 3 * d, text)
            db += 6f
        }

        fun curve(v: FloatArray, p: Paint) {
            path.reset()
            v.indices.forEach { i ->
                val xx = x(RoomEq.FREQS[i]); val yy = y(v[i])
                if (i == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy)
            }
            c.drawPath(path, p)
        }
        curve(f.target, target)
        curve(f.measured, measured)
        curve(f.corrected, corrected)

        // Bereich unterhalb der Box-Grenze abdunkeln – dort wird nicht korrigiert
        c.drawRect(pl, pt, x(f.fLow * 0.8), pt + h, shade)
    }
}
