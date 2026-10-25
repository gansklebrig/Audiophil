package de.eq.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Hörtest: misst je Ohr und Frequenz, ab welcher Lautstärke ein Ton gehört wird,
 * und errechnet daraus eine Hörkorrektur (siehe [HearingFit]).
 *
 * Ablauf je Messpunkt: Ein pulsierender Ton beginnt sehr leise und wird alle 0,45 s um 2,5 dB lauter.
 * Sobald er gehört wird, tippt man auf den großen Knopf. Am Ende wird 1 kHz links zur Kontrolle wiederholt.
 */
class HearingTestActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager
    private val main = Handler(Looper.getMainLooper())

    private data class Step(val ear: Int, val freq: Float, val control: Boolean = false)

    private val steps: List<Step> = buildList {
        for (ear in 0..1) for (f in HearingFit.FREQS) add(Step(ear, f))
        add(Step(0, 1000f, control = true))
    }
    private var stepIndex = 0
    private val left = HashMap<Float, Float>()
    private val right = HashMap<Float, Float>()
    private val lastLevel = floatArrayOf(Float.NaN, Float.NaN)   // zuletzt gemessene Schwelle je Ohr
    private var controlDiff = 0f
    /** Bis zur Höchstlautstärke nicht gehört – dort wird nichts korrigiert. */
    private val notHeard = HashSet<Pair<Int, Float>>()
    /** Startpegel des laufenden Messpunkts; tippt man sofort, wird leiser neu begonnen. */
    private var stepStart = 0f
    private var startOverride: Float? = null
    private var startVolume = -1
    private var tone: Tone? = null
    private var testing = false

    // Ansicht während des Tests
    private var stepText: TextView? = null
    private var earText: TextView? = null
    private var pulseText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        audio = getSystemService(AudioManager::class.java)
        showIntro()
    }

    override fun onPause() {
        super.onPause()
        stopTone()
        EqService.instance?.setBypass(EqService.BYPASS_NONE)
        if (testing) {
            testing = false
            ui.toast("Hörtest unterbrochen")
            showIntro()
        }
    }

    /** Ergebnis wird gerade angezeigt, ist aber noch nicht übernommen. */
    private var resultShown = false

    /** Zurück: laufenden Test bzw. ein nicht übernommenes Ergebnis nicht versehentlich verwerfen. */
    @Deprecated("Activity-API")
    override fun onBackPressed() {
        val what = when {
            testing -> "Hörtest abbrechen?"
            resultShown -> "Ergebnis verwerfen?"
            else -> null
        }
        if (what == null) { @Suppress("DEPRECATION") super.onBackPressed(); return }
        AlertDialog.Builder(this)
            .setTitle(what)
            .setMessage("Die bisherigen Messwerte gehen verloren.")
            .setPositiveButton("Ja") { _, _ -> testing = false; stopTone(); finish() }
            .setNegativeButton("Weiter", null)
            .show()
    }

    private fun page(build: (LinearLayout) -> Unit) {
        resultShown = false
        val root = ui.vbox().apply { setPadding(ui.dp(22), ui.dp(28), ui.dp(22), ui.dp(32)) }
        build(root)
        setContentView(ScrollView(this).apply { setBackgroundColor(Palette.bg); addView(root) })
    }

    // ---------- Einführung ----------

    private fun showIntro() {
        page { root ->
            root.addView(ui.title("HÖRTEST"))
            root.addView(ui.section("So funktioniert’s"))
            listOf(
                "Ruhigen Ort suchen und die Kopfhörer aufsetzen, für die das Profil gedacht ist.",
                "Handy-Lautstärke auf deine übliche Musiklautstärke stellen und während des Tests nicht verändern.",
                "Du hörst piepende Töne, die langsam lauter werden – erst links, dann rechts.",
                "Tippe auf den großen Knopf, sobald du einen Ton hörst. Lieber einen Moment zu spät als zu früh.",
                "Dauer etwa 4 Minuten. Der Equalizer wird währenddessen automatisch umgangen.",
            ).forEachIndexed { i, t -> root.addView(ui.hint("${i + 1}.  $t").apply { textSize = 15f; alpha = 0.95f }) }

            val dev = AudioDevices.current(this@HearingTestActivity)
            root.addView(ui.hint("Ausgabe: ${dev.label}").apply { setTextColor(Palette.gold) })
            if (dev.key == AudioDevices.SPEAKER_KEY) {
                root.addView(ui.hint("Bitte Kopfhörer verwenden – über den Lautsprecher ist der Test nicht aussagekräftig.")
                    .apply { setTextColor(Palette.warn) })
            }

            root.addView(ui.section("Lautstärke prüfen"))
            root.addView(ui.hint("Der Probeton sollte deutlich, aber angenehm zu hören sein."))
            root.addView(ui.row(ui.smallButton("Probeton abspielen") { playProbe() }))

            root.addView(ui.spaced(ui.goldButton("Test starten").apply {
                textSize = 18f
                ui.setActive(this, true)
                setOnClickListener { startTest() }
            }, 28))
            root.addView(ui.spaced(ui.goldButton("Abbrechen").apply { setOnClickListener { finish() } }, 8))
        }
    }

    private fun playProbe() {
        stopTone()
        EqService.instance?.setBypass(EqService.BYPASS_ALL)
        tone = Tone(1000f, -1, -30f, stepDb = 0f, maxPulses = 6, onLevel = {}, onEnd = {
            main.post { if (!testing) EqService.instance?.setBypass(EqService.BYPASS_NONE) }
        }).also { it.start() }
    }

    // ---------- Test ----------

    private fun startTest() {
        stopTone()
        left.clear(); right.clear(); notHeard.clear()
        lastLevel.fill(Float.NaN)
        startOverride = null
        stepIndex = 0
        testing = true
        startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        EqService.instance?.setBypass(EqService.BYPASS_ALL)
        page { root ->
            root.addView(ui.title("HÖRTEST"))
            stepText = ui.hint("").apply { textSize = 14f }
            root.addView(stepText)
            earText = TextView(this).apply {
                textSize = 30f
                setTextColor(Palette.goldLight)
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(24), 0, ui.dp(4))
            }
            root.addView(earText)
            pulseText = ui.hint("").apply { gravity = Gravity.CENTER }
            root.addView(pulseText)

            val heard = ui.goldButton("Ich höre den Ton").apply {
                textSize = 22f
                ui.setActive(this, true)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(180)).apply { topMargin = ui.dp(28) }
                setOnClickListener { onHeard() }
            }
            root.addView(heard)
            root.addView(ui.spaced(ui.goldButton("Test abbrechen").apply {
                setOnClickListener {
                    testing = false
                    stopTone()
                    EqService.instance?.setBypass(EqService.BYPASS_NONE)
                    showIntro()
                }
            }, 24))
        }
        main.postDelayed({ runStep() }, 900)
    }

    private fun runStep() {
        if (!testing) return
        val step = steps[stepIndex]
        stepText?.text = if (step.control) "Kontrollmessung" else "Messpunkt ${stepIndex + 1} von ${steps.size - 1}"
        earText?.text = (if (step.ear == 0) "Linkes Ohr" else "Rechtes Ohr") + "\n" + formatFreq(step.freq)
        pulseText?.text = "Ton läuft – tippe, sobald du ihn hörst"
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != startVolume) {
            ui.toast("Die Lautstärke wurde verändert – das Ergebnis kann ungenau werden")
            startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        // Start 20 dB unter der zuletzt gemessenen Schwelle dieses Ohrs
        val prev = lastLevel[step.ear]
        val start = startOverride ?: ((if (prev.isNaN()) -55f else prev) - 20f).coerceIn(MIN_DB, -40f)
        startOverride = null
        stepStart = start
        // Gemessen wird „durch“ den parametrischen EQ des Profils (z. B. AutoEq), sonst würde
        // die Kopfhörer-Korrektur später doppelt ausgeglichen.
        val s = prefs.state
        val eqDb = if (s.mode == MODE_PARAMETRIC) ParamEq.response(s, step.freq.toDouble(), step.ear).toFloat() else 0f
        lateinit var t: Tone
        t = Tone(step.freq, step.ear, start, STEP_DB, MAX_DB, onLevel = {}, gainDb = eqDb, onEnd = {
            // Bis zur Höchstlautstärke nicht gehört – nur werten, wenn dieser Ton noch der aktuelle ist
            main.post { if (testing && tone === t) { if (!step.control) notHeard.add(step.ear to step.freq); record(MAX_DB) } }
        })
        tone = t
        t.start()
    }

    private fun onHeard() {
        val t = tone ?: return
        if (!testing) return
        // Sofort beim ersten oder zweiten Puls gehört: Der Start war schon zu laut –
        // diesen Punkt 20 dB leiser neu beginnen, damit die Schwelle wirklich von unten erreicht wird.
        if (t.level <= stepStart + STEP_DB && stepStart > MIN_DB + 1f) {
            stopTone()
            startOverride = (stepStart - 20f).coerceAtLeast(MIN_DB)
            pulseText?.text = "Noch einmal etwas leiser …"
            main.postDelayed({ runStep() }, 900L + (Math.random() * 700).toLong())
            return
        }
        // Pufferlatenz + Reaktionszeit ≈ ein Puls: gehört wurde der vorige Ton
        val level = (t.level - STEP_DB).coerceAtLeast(MIN_DB)
        record(level)
    }

    private fun record(level: Float) {
        stopTone()
        val step = steps[stepIndex]
        if (step.control) controlDiff = abs(level - (left[1000f] ?: level))
        else {
            (if (step.ear == 0) left else right)[step.freq] = level
            lastLevel[step.ear] = level
        }
        stepIndex++
        if (stepIndex >= steps.size) {
            testing = false
            EqService.instance?.setBypass(EqService.BYPASS_NONE)
            showResult()
        } else {
            pulseText?.text = "✓"
            // unregelmäßige Pause, damit man den Takt nicht erraten kann
            main.postDelayed({ runStep() }, 700L + (Math.random() * 900).toLong())
        }
    }

    private fun stopTone() {
        tone?.running = false
        tone = null
        main.removeCallbacksAndMessages(null)
    }

    // ---------- Ergebnis ----------

    private fun showResult() {
        // Nicht gehörte Frequenzen nicht „hochdrehen“ – dort wäre jede Korrektur geraten
        val l = left.filterKeys { (0 to it) !in notHeard }
        val r = right.filterKeys { (1 to it) !in notHeard }
        val filters = HearingFit.correction(l, r)
        page { root ->
            root.addView(ui.title("ERGEBNIS"))
            root.addView(ui.section("Deine Hörkorrektur"))
            root.addView(CorrectionView(this, filters))
            root.addView(ui.hint("— links (gold)   - - rechts (hell)   ·   Anhebung in dB je Frequenz"))

            for (ear in 0..1) {
                val fl = filters.filter { it.channel == if (ear == 0) CH_LEFT else CH_RIGHT }
                val maxF = fl.maxByOrNull { it.gain }
                val name = if (ear == 0) "Links" else "Rechts"
                root.addView(ui.hint(
                    if (maxF == null || maxF.gain < 1f) "$name: kaum Korrektur nötig"
                    else "$name: stärkste Anhebung %+.1f dB bei %s".format(maxF.gain, formatFreq(maxF.freq))
                ).apply { textSize = 15f })
            }
            if (notHeard.isNotEmpty()) {
                val list = notHeard.sortedWith(compareBy({ it.first }, { it.second }))
                    .joinToString(", ") { (ear, f) -> (if (ear == 0) "links " else "rechts ") + formatFreq(f) }
                root.addView(ui.hint(
                    "Bis zur Höchstlautstärke nicht gehört: $list. Dort wird nichts korrigiert. " +
                    "Wenn das nicht an den Kopfhörern liegt, lass dein Gehör bitte ärztlich prüfen."
                ).apply { setTextColor(Palette.warn) })
            }
            if (controlDiff > 10f) {
                root.addView(ui.hint(
                    "Die Kontrollmessung weicht um %.0f dB ab – die Ergebnisse schwanken. ".format(controlDiff) +
                    "Am besten den Test in ruhigerer Umgebung wiederholen."
                ).apply { setTextColor(Palette.warn) })
            }
            root.addView(ui.hint(
                "Ausgeglichen wird die Hälfte der gemessenen Unterschiede – so wie bei Hörgeräten üblich. " +
                "Die Stärke lässt sich später im Bereich „Hörprofil“ anpassen. Kein Ersatz für einen ärztlichen Hörtest."
            ))

            val current = prefs.currentProfileName()
            root.addView(ui.spaced(ui.goldButton(
                if (current != null) "Für ★ $current übernehmen" else "Übernehmen"
            ).apply {
                ui.setActive(this, true)
                setOnClickListener { apply(filters, all = false) }
            }, 24))
            if (prefs.profiles.size > 1) {
                root.addView(ui.spaced(ui.goldButton("Für alle ★-Profile übernehmen").apply {
                    setOnClickListener { apply(filters, all = true) }
                }, 8))
            }
            root.addView(ui.spaced(ui.goldButton("Test wiederholen").apply { setOnClickListener { showIntro() } }, 8))
            root.addView(ui.spaced(ui.goldButton("Verwerfen").apply {
                setOnClickListener {
                    AlertDialog.Builder(this@HearingTestActivity)
                        .setMessage("Ergebnis verwerfen?")
                        .setPositiveButton("Verwerfen") { _, _ -> finish() }
                        .setNegativeButton("Zurück", null)
                        .show()
                }
            }, 8))
        }
        resultShown = true
    }

    private fun apply(filters: List<PFilter>, all: Boolean) {
        val now = System.currentTimeMillis()
        fun withHearing(s: EqState) = s.copy(hearing = filters, hearingOn = true, hearingAmount = 100, hearingDate = now)
        prefs.state = withHearing(prefs.state)
        if (all) {
            prefs.profiles = prefs.profiles.map { it.copy(state = withHearing(it.state)) }
        } else {
            prefs.currentProfileName()?.let { prefs.saveProfile(it) }
        }
        EqService.instance?.let { it.applyNow(); it.notifyUi() }
        ui.toast("Hörkorrektur übernommen")
        setResult(RESULT_OK)
        finish()
    }

    private fun formatFreq(f: Float) = if (f >= 1000) "%.0f kHz".format(f / 1000) else "%.0f Hz".format(f)

    companion object {
        private const val MIN_DB = -95f
        private const val MAX_DB = -12f
        private const val STEP_DB = 2.5f
    }
}

/**
 * Erzeugt einen pulsierenden Sinuston (0,30 s an, 0,15 s aus) auf einem Ohr (0 = links, 1 = rechts, −1 = beide).
 * Nach jedem Puls steigt der Pegel um [stepDb]. Endet bei [maxDb] oder nach [maxPulses] Pulsen.
 */
class Tone(
    private val freq: Float,
    private val ear: Int,
    startDb: Float,
    private val stepDb: Float,
    private val maxDb: Float = 0f,
    private val maxPulses: Int = Int.MAX_VALUE,
    private val onLevel: (Float) -> Unit,
    /** Zusätzliche Verstärkung beim Abspielen (z. B. Kurve des Kopfhörer-EQ); [level] bleibt der Nennpegel. */
    private val gainDb: Float = 0f,
    private val onEnd: () -> Unit,
) : Thread("Testton") {
    @Volatile var running = true
    /** Pegel (dBFS) des zuletzt in den Puffer geschriebenen Pulses. */
    @Volatile var level = startDb
        private set

    override fun run() {
        val sr = 48000
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)   // Gleitkomma: auch sehr leise Töne sauber
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build())
                .setBufferSizeInBytes(minBuf.coerceAtLeast(8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            onEnd(); return
        }
        val toneN = (sr * 0.30).toInt()
        val gapN = (sr * 0.15).toInt()
        val fade = (sr * 0.025).toInt()
        val buf = FloatArray((toneN + gapN) * 2)
        val dPhase = 2 * PI * freq / sr
        var pulses = 0
        var reachedEnd = false
        try {
            track.play()
            while (running) {
                val amp = 10f.pow(((level + gainDb).coerceAtMost(0f)) / 20f)
                buf.fill(0f)
                var phase = 0.0
                for (i in 0 until toneN) {
                    // weiche Ein-/Ausblendung (Kosinus), damit es nicht knackt
                    val env = when {
                        i < fade -> 0.5f - 0.5f * cos(PI * i / fade).toFloat()
                        i > toneN - fade -> 0.5f - 0.5f * cos(PI * (toneN - i) / fade).toFloat()
                        else -> 1f
                    }
                    val v = (sin(phase) * amp * env).toFloat()
                    phase += dPhase
                    if (ear != 1) buf[2 * i] = v
                    if (ear != 0) buf[2 * i + 1] = v
                }
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                if (!running) break
                onLevel(level)
                pulses++
                if (pulses >= maxPulses || (stepDb > 0 && level >= maxDb)) { reachedEnd = true; break }
                level = (level + stepDb).coerceAtMost(maxDb.takeIf { stepDb > 0 } ?: level)
            }
        } catch (_: Exception) {
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
        if (reachedEnd && running) onEnd()
        else if (maxPulses != Int.MAX_VALUE) onEnd()
    }
}

/** Zeigt die errechnete Hörkorrektur für beide Ohren. */
class CorrectionView(ctx: Context, private val filters: List<PFilter>) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.gold; alpha = 40; strokeWidth = d * 0.7f }
    private val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.gold; alpha = 100; strokeWidth = d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.champagne; alpha = 130; textSize = d * 9 }
    private val left = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.goldLight; style = Paint.Style.STROKE; strokeWidth = d * 2.2f
    }
    private val right = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.champagne; style = Paint.Style.STROKE; strokeWidth = d * 1.8f
        pathEffect = DashPathEffect(floatArrayOf(d * 5, d * 3), 0f)
    }
    private val path = Path()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (200 * d).toInt())

    override fun onDraw(c: Canvas) {
        val pl = 28 * d; val pr = 8 * d; val pt = 10 * d; val pb = 18 * d
        val w = width - pl - pr; val h = height - pt - pb
        val fMin = 100.0; val fMax = 10000.0; val top = 15f; val bottom = -6f
        fun x(f: Double) = pl + (ln(f / fMin) / ln(fMax / fMin)).toFloat() * w
        fun y(db: Float) = pt + (top - db.coerceIn(bottom, top)) / (top - bottom) * h

        text.textAlign = Paint.Align.CENTER
        for ((f, l) in listOf(125 to "125", 250 to "250", 500 to "500", 1000 to "1k", 2000 to "2k", 4000 to "4k", 8000 to "8k")) {
            c.drawLine(x(f.toDouble()), pt, x(f.toDouble()), pt + h, grid)
            c.drawText(l, x(f.toDouble()), height - 4 * d, text)
        }
        text.textAlign = Paint.Align.RIGHT
        for (db in listOf(-6, 0, 6, 12)) {
            c.drawLine(pl, y(db.toFloat()), width - pr, y(db.toFloat()), if (db == 0) zero else grid)
            c.drawText(if (db > 0) "+$db" else "$db", pl - 4 * d, y(db.toFloat()) + 3 * d, text)
        }
        for (ch in 0..1) {
            path.reset()
            for (i in 0..120) {
                val f = fMin * (fMax / fMin).pow(i / 120.0)
                val yy = y(ParamEq.sumDb(filters, f, ch).toFloat())
                if (i == 0) path.moveTo(x(f), yy) else path.lineTo(x(f), yy)
            }
            c.drawPath(path, if (ch == 0) left else right)
        }
    }
}
