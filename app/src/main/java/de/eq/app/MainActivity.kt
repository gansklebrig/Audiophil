package de.eq.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Hauptansicht. Die Oberfläche wird im Code gebaut (keine Layout-Dateien nötig).
 *
 * Ablauf: Jede Bedienung ändert den [EqState] in den [Prefs] (→ update), der Dienst wendet ihn an.
 * [render] überträgt umgekehrt den gespeicherten Zustand in alle Bedienelemente –
 * z. B. nach einem automatischen Profilwechsel durch den Kopfhörer.
 */
class MainActivity : Activity() {

    /** Ein Eintrag in der Profil-Auswahl. */
    private sealed class Entry(val label: String) {
        object Custom : Entry(CUSTOM)
        object Flat : Entry(FLAT)
        class Device(label: String, val levels: IntArray) : Entry(label)
        class User(val name: String) : Entry(PROFILE_PREFIX + name)
    }

    private lateinit var prefs: Prefs
    private lateinit var ui: Ui

    // Fähigkeiten des Geräts
    private var bandCount = 0
    private var minLevel = -1500
    private var maxLevel = 1500
    private val bandFreqs = ArrayList<Int>()
    private val devicePresets = ArrayList<Entry.Device>()
    private var bassSupported = false
    private var dpSupported = false

    // Bedienelemente
    private lateinit var enabledSwitch: Switch
    private lateinit var globalSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var presetSpinner: Spinner
    private lateinit var renameBtn: Button
    private lateinit var deleteBtn: Button
    private lateinit var modeGraphicBtn: Button
    private lateinit var modeParamBtn: Button
    private lateinit var graphicBox: LinearLayout
    private lateinit var paramBox: LinearLayout
    private val bandBars = ArrayList<SeekBar>()
    private var bassBar: SeekBar? = null

    private lateinit var curve: CurveView
    private lateinit var filterTitle: TextView
    private lateinit var filterType: Spinner
    private lateinit var filterOn: Switch
    private lateinit var freqBar: SeekBar
    private lateinit var freqValue: TextView
    private lateinit var gainBox: LinearLayout
    private lateinit var gainBar: SeekBar
    private lateinit var gainValue: TextView
    private lateinit var qBar: SeekBar
    private lateinit var qValue: TextView
    private lateinit var preampBar: SeekBar
    private lateinit var preampValue: TextView

    private lateinit var loudBar: SeekBar
    private var limiterSwitch: Switch? = null
    private var limiterBar: SeekBar? = null
    private var balanceBar: SeekBar? = null

    private lateinit var autoSwitchView: Switch
    private lateinit var deviceText: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var defaultSpinner: Spinner
    private lateinit var assignBox: LinearLayout
    private lateinit var iconSwitch: Switch

    // Neu: Verlauf, Vorher/Nachher, Spektrum, Kanalwahl, Hörprofil, Klangautomatik
    private lateinit var undoBtn: Button
    private lateinit var redoBtn: Button
    private lateinit var spectrumSwitch: Switch
    private lateinit var spectrumView: SpectrumView
    private var analyzer: SpectrumAnalyzer? = null
    private lateinit var filterChannel: Spinner
    private var hearingInfo: TextView? = null
    private var hearingGraph: LinearLayout? = null
    private var hearingControls: LinearLayout? = null
    private var hearingSwitch: Switch? = null
    private var hearingBar: SeekBar? = null
    private var hearingGraphKey = 0
    private var loudCompBar: SeekBar? = null
    private var loudInfo: TextView? = null
    private var nightBar: SeekBar? = null
    private var nightInfo: TextView? = null

    private val undo = ArrayDeque<EqState>()
    private val redo = ArrayDeque<EqState>()
    private var lastChange = 0L
    private var resumed = false
    private var presetAtPause: String? = null

    private var entries: List<Entry> = emptyList()
    private var profileNames: List<String> = emptyList()
    private var selFilter = 0
    private var loudStep = 10    // Schrittweite der Lautstärke-Tasten in mB (1 mB = 0,01 dB)
    private var built = false

    private val st: EqState get() = prefs.state

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this)

        if (!probeDevice()) {
            setContentView(TextView(this).apply {
                text = "Dieses Gerät unterstützt keinen Equalizer-Effekt."
                setPadding(48, 48, 48, 48)
            })
            return
        }
        normalizeBands()
        setContentView(buildUi())
        built = true

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (prefs.enabled) EqService.start(this)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        EqService.uiListener = { runOnUiThread { if (built) render() } }
        EqService.volumeListener = { runOnUiThread { if (built) updateLoudInfo() } }
        EqService.profileSwitchListener = { runOnUiThread { clearHistory() } }
        if (!built) return
        // Profil wurde außerhalb gewechselt (Widget, Kachel, Kopfhörer) → Verlauf passt nicht mehr
        if (presetAtPause != null && presetAtPause != prefs.presetName) clearHistory()
        render()
        updateSpectrum()
    }

    override fun onPause() {
        resumed = false
        presetAtPause = prefs.presetName
        EqService.uiListener = null
        EqService.volumeListener = null
        EqService.profileSwitchListener = null
        EqService.instance?.setBypass(EqService.BYPASS_NONE)
        if (built) updateSpectrum()
        super.onPause()
    }

    /** Liest Bänder, Frequenzen und Presets des Geräts aus. */
    private fun probeDevice(): Boolean {
        val am = getSystemService(AudioManager::class.java)
        val session = am.generateAudioSessionId()
        return try {
            val eq = Equalizer(0, session)
            bandCount = eq.numberOfBands.toInt()
            minLevel = eq.bandLevelRange[0].toInt()
            maxLevel = eq.bandLevelRange[1].toInt()
            for (b in 0 until bandCount) bandFreqs.add(eq.getCenterFreq(b.toShort()) / 1000)
            for (p in 0 until eq.numberOfPresets) {
                eq.usePreset(p.toShort())
                devicePresets.add(Entry.Device(
                    eq.getPresetName(p.toShort()),
                    IntArray(bandCount) { eq.getBandLevel(it.toShort()).toInt() }
                ))
            }
            eq.release()
            bassSupported = try {
                val bb = BassBoost(0, session); val s = bb.strengthSupported; bb.release(); s
            } catch (e: Exception) { false }
            dpSupported = Build.VERSION.SDK_INT >= 28 && try {
                DynamicsProcessing(session).release(); true
            } catch (e: Exception) { false }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Bänder-Anzahl an dieses Gerät anpassen (z. B. nach Import von einem anderen Handy). */
    private fun normalizeBands() {
        val s = prefs.state
        if (s.bands.size != bandCount) {
            prefs.state = s.copy(bands = IntArray(bandCount) { s.bands.getOrElse(it) { 0 } })
        }
    }

    // =====================================================================
    // Aufbau der Oberfläche
    // =====================================================================

    private fun buildUi(): View {
        val root = ui.vbox().apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(32)) }
        root.addView(ui.title("EQUALIZER"))

        enabledSwitch = ui.switch("Equalizer aktiv") { on ->
            prefs.enabled = on
            if (on) EqService.start(this) else EqService.stop(this)
            root.postDelayed({ updateStatus() }, 400)
        }.apply { textSize = 18f }
        root.addView(enabledSwitch)

        globalSwitch = ui.switch("Globaler Modus (alle Apps)") { on ->
            prefs.global = on
            EqService.instance?.refreshGlobal()
        }
        root.addView(globalSwitch)
        root.addView(ui.hint(
            "Wirkt auf die gesamte Audioausgabe. Funktioniert nicht auf jedem Gerät – dann in der " +
            "Musik-App den Equalizer einschalten (z. B. Spotify → Einstellungen → Wiedergabe → Equalizer)."
        ))
        statusText = ui.hint("").apply { setTextColor(Palette.gold) }
        root.addView(statusText)

        // Rückgängig / Wiederholen / Vorher-Nachher
        undoBtn = ui.stepButton("↶") { doUndo() }.apply { setOnLongClickListener { false } }
        redoBtn = ui.stepButton("↷") { doRedo() }.apply { setOnLongClickListener { false } }
        val ab = ui.smallButton("Vorher / Nachher (halten)") {}
        ab.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val svc = EqService.instance
                    if (svc == null) ui.toast("Der Equalizer ist ausgeschaltet")
                    else { svc.setBypass(EqService.BYPASS_EQ); ab.text = "Vorher – ohne EQ"; ui.setActive(ab, true) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    EqService.instance?.setBypass(EqService.BYPASS_NONE)
                    ab.text = "Vorher / Nachher (halten)"
                    ui.setActive(ab, false)
                    if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
        root.addView(ui.row(undoBtn, redoBtn, ab))

        buildProfiles(root)

        root.addView(ui.section("Equalizer-Art"))
        modeGraphicBtn = ui.smallButton("Band-EQ") { switchMode(MODE_GRAPHIC) }
        modeParamBtn = ui.smallButton("Parametrisch") { switchMode(MODE_PARAMETRIC) }
        root.addView(ui.row(modeGraphicBtn, modeParamBtn))
        spectrumSwitch = ui.switch("Live-Spektrum anzeigen") { on -> setSpectrum(on) }
        root.addView(spectrumSwitch)
        root.addView(ui.hint(
            if (dpSupported) "Band-EQ: feste Bänder deines Handys. Parametrisch: frei setzbare Filter mit Frequenz, Verstärkung und Güte."
            else "Parametrischer EQ, Limiter und Balance brauchen Android 9 oder neuer."
        ))

        graphicBox = ui.vbox().also { buildGraphic(it); root.addView(it) }
        paramBox = ui.vbox().also { buildParametric(it); root.addView(it) }
        if (dpSupported) buildHearing(root)
        buildVolume(root)
        if (dpSupported) buildAutomation(root)
        if (dpSupported) buildBalance(root)
        buildDevices(root)
        buildNotification(root)

        root.addView(ui.spaced(ui.goldButton("Alles zurücksetzen").apply {
            setOnClickListener {
                pushUndo(prefs.state)
                prefs.state = EqState(mode = st.mode, bands = IntArray(bandCount))
                prefs.presetName = FLAT
                render()
                EqService.instance?.applyAll()
                ui.toast("Zurückgesetzt")
            }
        }, 28))

        return ScrollView(this).apply { setBackgroundColor(Palette.bg); addView(root) }
    }

    // ----- Profile -----

    private fun buildProfiles(root: LinearLayout) {
        root.addView(ui.section("Profil"))
        presetSpinner = ui.choice { pos -> entries.getOrNull(pos)?.let { selectEntry(it) } }
        root.addView(presetSpinner)

        val save = ui.smallButton("Speichern als …") {
            askText("Neues Profil", "", "z. B. Sony-Kopfhörer, Auto, Podcast …") { name ->
                prefs.saveProfile(name)
                prefs.presetName = PROFILE_PREFIX + name
                render()
                ui.toast("Profil „$name“ gespeichert")
            }
        }
        renameBtn = ui.smallButton("Umbenennen") {
            val name = prefs.currentProfileName() ?: return@smallButton
            askText("Profil umbenennen", name, "") { new ->
                prefs.renameProfile(name, new)
                prefs.presetName = PROFILE_PREFIX + new
                render()
            }
        }
        deleteBtn = ui.smallButton("Löschen") {
            val name = prefs.currentProfileName() ?: return@smallButton
            AlertDialog.Builder(this)
                .setTitle("Profil löschen?")
                .setMessage("„$name“ wird endgültig gelöscht.")
                .setPositiveButton("Löschen") { _, _ ->
                    prefs.deleteProfile(name)
                    prefs.presetName = CUSTOM
                    render()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
        root.addView(ui.row(save, renameBtn, deleteBtn))
        root.addView(ui.row(
            ui.smallButton("Exportieren") { exportProfiles() },
            ui.smallButton("Importieren") { importFile() },
        ))
        root.addView(ui.hint("Änderungen an einem ★-Profil werden automatisch darin gespeichert."))
    }

    // ----- Band-EQ -----

    private fun buildGraphic(box: LinearLayout) {
        spectrumView = SpectrumView(this).apply { visibility = View.GONE }
        box.addView(spectrumView)
        box.addView(ui.section("Frequenzbänder"))
        for (b in 0 until bandCount) {
            val value = ui.valueText()
            box.addView(ui.labelRow(formatFreq(bandFreqs[b]), value))
            val bar = ui.slider(maxLevel - minLevel, 0, display = { value.text = formatDb(it + minLevel) }) { p ->
                setBand(b, p + minLevel)
            }
            bandBars.add(bar)
            box.addView(ui.row(
                ui.stepButton("−") { setBand(b, st.bands.getOrElse(b) { 0 } - BAND_STEP) },
                bar,
                ui.stepButton("+") { setBand(b, st.bands.getOrElse(b) { 0 } + BAND_STEP) },
            ))
        }
        box.addView(ui.hint("−/+ ändern ein Band um 0,1 dB, gedrückt halten zählt weiter."))

        if (bassSupported) {
            box.addView(ui.section("Bass-Boost"))
            val v = ui.valueText().apply { minWidth = ui.dp(56) }
            val bar = ui.slider(1000, 0, display = { v.text = "${it / 10} %" }) { p -> update { it.copy(bass = p) } }
            bassBar = bar
            box.addView(ui.row(bar, v))
        }
    }

    private fun setBand(b: Int, mb: Int) {
        val v = mb.coerceIn(minLevel, maxLevel)
        update { s -> s.copy(bands = s.bands.copyOf().also { if (b < it.size) it[b] = v }) }
        bandBars.getOrNull(b)?.progress = v - minLevel
    }

    // ----- Parametrischer EQ -----

    private fun buildParametric(box: LinearLayout) {
        box.addView(ui.section("Klangkurve"))
        curve = CurveView(this).apply {
            onSelect = { i -> selFilter = i; bindFilter() }
            onFilterChange = { i, f -> setFilter(i, f) }
        }
        box.addView(curve)
        box.addView(ui.hint("Punkt ziehen: Frequenz und Verstärkung · zwei Finger spreizen oder zusammenziehen: Güte (Q)"))

        box.addView(ui.section("Filter"))
        filterTitle = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Palette.goldLight)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        box.addView(ui.row(
            ui.stepButton("‹") { selectFilter(selFilter - 1) },
            filterTitle,
            ui.stepButton("›") { selectFilter(selFilter + 1) },
        ))

        filterType = ui.choice { pos -> curFilter()?.let { setFilter(selFilter, it.copy(type = pos)) } }
            .apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        filterChannel = ui.choice { pos -> curFilter()?.let { setFilter(selFilter, it.copy(channel = pos)) } }
            .apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        box.addView(ui.row(filterType, filterChannel))
        filterOn = ui.switch("Filter aktiv") { on -> curFilter()?.let { setFilter(selFilter, it.copy(on = on)) } }
        box.addView(filterOn)

        // Frequenz
        freqValue = ui.valueText().apply {
            setOnClickListener {
                val f = curFilter() ?: return@setOnClickListener
                askNumber("Frequenz in Hz", "%.0f".format(f.freq)) { v ->
                    curFilter()?.let { setFilter(selFilter, it.copy(freq = v.coerceIn(20f, 20000f).roundToInt().toFloat())) }
                }
            }
        }
        box.addView(ui.labelRow("Frequenz", freqValue))
        freqBar = ui.slider(FREQ_STEPS, 0) { p ->
            curFilter()?.let { setFilter(selFilter, it.copy(freq = progToFreq(p).roundToInt().toFloat()), sync = false) }
        }
        box.addView(ui.row(ui.stepButton("−") { nudgeFreq(-1) }, freqBar, ui.stepButton("+") { nudgeFreq(+1) }))

        // Verstärkung
        gainValue = ui.valueText().apply {
            setOnClickListener {
                val f = curFilter() ?: return@setOnClickListener
                askNumber("Verstärkung in dB", "%.1f".format(f.gain)) { v ->
                    curFilter()?.let { setFilter(selFilter, it.copy(gain = v.coerceIn(-30f, 30f))) }
                }
            }
        }
        gainBar = ui.slider(GAIN_STEPS, 0) { p ->
            curFilter()?.let { setFilter(selFilter, it.copy(gain = p / 10f - CurveView.GAIN_RANGE), sync = false) }
        }
        gainBox = ui.vbox().apply {
            addView(ui.labelRow("Verstärkung", gainValue))
            addView(ui.row(ui.stepButton("−") { nudgeGain(-0.1f) }, gainBar, ui.stepButton("+") { nudgeGain(+0.1f) }))
        }
        box.addView(gainBox)

        // Güte
        qValue = ui.valueText().apply {
            setOnClickListener {
                val f = curFilter() ?: return@setOnClickListener
                askNumber("Güte (Q)", "%.2f".format(f.q)) { v ->
                    curFilter()?.let { setFilter(selFilter, it.copy(q = v.coerceIn(CurveView.Q_MIN, CurveView.Q_MAX))) }
                }
            }
        }
        box.addView(ui.labelRow("Güte (Q) – klein = breit", qValue))
        qBar = ui.slider(Q_STEPS, 0) { p ->
            curFilter()?.let { setFilter(selFilter, it.copy(q = (progToQ(p) * 100).roundToInt() / 100f), sync = false) }
        }
        box.addView(ui.row(ui.stepButton("−") { nudgeQ(-0.01f) }, qBar, ui.stepButton("+") { nudgeQ(+0.01f) }))
        box.addView(ui.hint("Tippe auf einen Wert, um ihn exakt einzugeben."))

        box.addView(ui.row(
            ui.smallButton("+ Filter") { addFilter() },
            ui.smallButton("Filter löschen") { deleteFilter() },
        ))

        // Vorverstärkung
        box.addView(ui.section("Vorverstärkung"))
        preampValue = ui.valueText().apply {
            setOnClickListener {
                askNumber("Vorverstärkung in dB", "%.1f".format(st.preamp)) { v -> setPreamp(v) }
            }
        }
        box.addView(ui.labelRow("Gesamtpegel vor den Filtern", preampValue))
        preampBar = ui.slider(PREAMP_STEPS, 0) { p -> setPreamp(p / 10f + PREAMP_MIN, syncBar = false) }
        box.addView(ui.row(
            ui.stepButton("−") { setPreamp(st.preamp - 0.1f) },
            preampBar,
            ui.stepButton("+") { setPreamp(st.preamp + 0.1f) },
        ))
        box.addView(ui.hint("Bei kräftigen Anhebungen die Vorverstärkung etwas absenken, damit nichts übersteuert."))

        // AutoEq
        box.addView(ui.section("Kopfhörer-Korrektur"))
        box.addView(ui.hint(
            "AutoEq enthält Messungen für tausende Kopfhörer. Die Korrektur lässt dein Modell neutral " +
            "klingen – ein guter Ausgangspunkt für eigene Anpassungen. Wird als ★-Profil gespeichert."
        ))
        box.addView(ui.row(
            ui.smallButton("AutoEq durchsuchen") { showAutoEqDialog() },
            ui.smallButton("Aus Datei laden") { importFile() },
        ))
    }

    private fun curFilter(): PFilter? = st.filters.getOrNull(selFilter)

    private fun selectFilter(i: Int) {
        val n = st.filters.size
        if (n == 0) return
        selFilter = ((i % n) + n) % n
        bindFilter()
    }

    /** Filter ändern. sync=false, wenn der Nutzer gerade genau diesen Regler bewegt. */
    private fun setFilter(i: Int, f: PFilter, sync: Boolean = true) {
        update { s ->
            if (i !in s.filters.indices) s
            else s.copy(filters = s.filters.toMutableList().also { it[i] = f })
        }
        if (sync) bindFilter() else bindFilterTexts()
    }

    private fun nudgeFreq(dir: Int) {
        val f = curFilter() ?: return
        // 1/48 Oktave je Schritt, mindestens 1 Hz
        var nf = (f.freq * 2.0.pow(dir / 48.0)).roundToInt().toFloat()
        if (nf == f.freq) nf += dir
        setFilter(selFilter, f.copy(freq = nf.coerceIn(20f, 20000f)))
    }

    private fun nudgeGain(d: Float) {
        val f = curFilter() ?: return
        setFilter(selFilter, f.copy(gain = ((f.gain + d) * 10).roundToInt() / 10f))
    }

    private fun nudgeQ(d: Float) {
        val f = curFilter() ?: return
        setFilter(selFilter, f.copy(q = (((f.q + d) * 100).roundToInt() / 100f).coerceIn(CurveView.Q_MIN, CurveView.Q_MAX)))
    }

    private fun addFilter() {
        if (st.filters.size >= MAX_FILTERS) { ui.toast("Maximal $MAX_FILTERS Filter"); return }
        update { it.copy(filters = it.filters + PFilter(FilterType.PEAK, 1000f, 0f, 1f)) }
        selFilter = st.filters.size - 1
        bindFilter()
    }

    private fun deleteFilter() {
        if (curFilter() == null) return
        update { s -> s.copy(filters = s.filters.filterIndexed { i, _ -> i != selFilter }) }
        selFilter = selFilter.coerceAtMost((st.filters.size - 1).coerceAtLeast(0))
        bindFilter()
    }

    private fun setPreamp(v: Float, syncBar: Boolean = true) {
        val p = ((v.coerceIn(PREAMP_MIN, PREAMP_MAX)) * 10).roundToInt() / 10f
        update { it.copy(preamp = p) }
        preampValue.text = formatDbF(p)
        if (syncBar) preampBar.progress = ((p - PREAMP_MIN) * 10).roundToInt()
    }

    /** Ausgewählten Filter in die Bedienelemente übertragen. */
    private fun bindFilter() {
        val was = ui.silent
        ui.silent = true
        val f = curFilter()
        val n = st.filters.size
        val enabled = f != null
        listOf<View>(filterType, filterChannel, filterOn, freqBar, gainBar, qBar).forEach { it.isEnabled = enabled; it.alpha = if (enabled) 1f else 0.4f }
        if (f == null) {
            filterTitle.text = "Keine Filter – „+ Filter“ tippen"
        } else {
            filterTitle.text = "Filter ${selFilter + 1} von $n"
            ui.setChoices(filterType, FilterType.names, f.type)
            filterOn.isChecked = f.on
            ui.setChoices(filterChannel, CHANNEL_NAMES, f.channel)
            freqBar.progress = freqToProg(f.freq)
            gainBar.progress = ((f.gain + CurveView.GAIN_RANGE) * 10).roundToInt().coerceIn(0, GAIN_STEPS)
            qBar.progress = qToProg(f.q)
            gainBox.visibility = if (FilterType.hasGain(f.type)) View.VISIBLE else View.GONE
        }
        bindFilterTexts()
        curve.selected = selFilter
        ui.silent = was
    }

    private fun bindFilterTexts() {
        val f = curFilter()
        freqValue.text = f?.let { formatHz(it.freq) } ?: "–"
        gainValue.text = f?.let { formatDbF(it.gain) } ?: "–"
        qValue.text = f?.let { "%.2f".format(it.q) } ?: "–"
    }

    // ----- Hörprofil -----

    private fun buildHearing(root: LinearLayout) {
        root.addView(ui.section("Hörprofil"))
        hearingInfo = ui.hint("")
        root.addView(hearingInfo)
        hearingGraph = ui.vbox()
        root.addView(hearingGraph)

        hearingControls = ui.vbox().apply {
            hearingSwitch = ui.switch("Hörkorrektur aktiv") { on -> update { it.copy(hearingOn = on) } }
            addView(hearingSwitch)
            val v = ui.valueText()
            addView(ui.labelRow("Stärke", v))
            val bar = ui.slider(HEARING_MAX, 100, display = { v.text = "$it %" }) { p -> update { it.copy(hearingAmount = p) } }
            hearingBar = bar
            addView(ui.row(
                ui.stepButton("−") { setHearingAmount(st.hearingAmount - 5) },
                bar,
                ui.stepButton("+") { setHearingAmount(st.hearingAmount + 5) },
            ))
            addView(ui.row(ui.smallButton("Korrektur löschen") {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Hörkorrektur aus diesem Profil löschen?")
                    .setPositiveButton("Löschen") { _, _ -> update { it.copy(hearing = emptyList()) }; render() }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }))
        }
        root.addView(hearingControls)
        root.addView(ui.row(ui.smallButton("Hörtest starten") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, HearingTestActivity::class.java), REQ_HEARING)
        }))
        root.addView(ui.hint(
            "Misst in etwa 4 Minuten, wie gut du mit deinen Kopfhörern hörst – getrennt für jedes Ohr – " +
            "und gleicht Unterschiede aus. Die Korrektur gehört zum Profil, also am besten je Kopfhörer einmal testen."
        ))
    }

    private fun setHearingAmount(v: Int) {
        val a = v.coerceIn(0, HEARING_MAX)
        update { it.copy(hearingAmount = a) }
        hearingBar?.progress = a
    }

    private fun renderHearing(s: EqState) {
        val info = hearingInfo ?: return
        val has = s.hearing.isNotEmpty()
        hearingControls?.visibility = if (has) View.VISIBLE else View.GONE
        info.text = if (!has) "Für dieses Profil gibt es noch keinen Hörtest." else {
            val date = if (s.hearingDate > 0) SimpleDateFormat("d. MMMM yyyy", Locale.GERMANY).format(Date(s.hearingDate)) else "–"
            "Hörtest vom $date"
        }
        hearingSwitch?.isChecked = s.hearingOn
        hearingBar?.progress = s.hearingAmount
        // Grafik nur neu bauen, wenn sich die Korrektur geändert hat
        val key = s.hearing.hashCode()
        if (key != hearingGraphKey) {
            hearingGraphKey = key
            hearingGraph?.removeAllViews()
            if (has) hearingGraph?.addView(CorrectionView(this, s.hearing))
        }
    }

    // ----- Klangautomatik -----

    private fun buildAutomation(root: LinearLayout) {
        root.addView(ui.section("Klangautomatik"))

        val lv = ui.valueText()
        root.addView(ui.labelRow("Lautheitsausgleich", lv))
        val lBar = ui.slider(100, 0, display = { lv.text = if (it == 0) "Aus" else "$it %" }) { p ->
            update { it.copy(loudComp = p) }
            updateLoudInfo()
        }
        loudCompBar = lBar
        root.addView(ui.row(
            ui.stepButton("−") { setLoudComp(st.loudComp - 5) },
            lBar,
            ui.stepButton("+") { setLoudComp(st.loudComp + 5) },
        ))
        loudInfo = ui.hint("").apply { setTextColor(Palette.gold) }
        root.addView(loudInfo)
        root.addView(ui.hint(
            "Bei leiser Handy-Lautstärke hört das Ohr Bass und Höhen schwächer. Der Ausgleich hebt sie dann " +
            "automatisch an, damit Musik auch leise voll klingt. Bei voller Lautstärke wirkt er nicht."
        ))

        val nv = ui.valueText()
        root.addView(ui.labelRow("Nachtmodus (Kompressor)", nv))
        val nBar = ui.slider(100, 0, display = { nv.text = if (it == 0) "Aus" else "$it %" }) { p ->
            update { it.copy(night = p) }
        }
        nightBar = nBar
        root.addView(ui.row(
            ui.stepButton("−") { setNight(st.night - 5) },
            nBar,
            ui.stepButton("+") { setNight(st.night + 5) },
        ))
        nightInfo = ui.hint("").apply { setTextColor(Palette.warn) }
        root.addView(nightInfo)
        root.addView(ui.hint(
            "Gleicht Lautstärkeunterschiede aus: leise Stellen werden lauter, laute Spitzen leiser. " +
            "Gut für Podcasts, Filme, Bahnfahrten oder nachts."
        ))
    }

    private fun setLoudComp(v: Int) {
        val x = v.coerceIn(0, 100)
        update { it.copy(loudComp = x) }
        loudCompBar?.progress = x
        updateLoudInfo()
    }

    private fun setNight(v: Int) {
        val x = v.coerceIn(0, 100)
        update { it.copy(night = x) }
        nightBar?.progress = x
    }

    /** Zeigt, was der Lautheitsausgleich bei der aktuellen Handy-Lautstärke gerade anhebt. */
    private fun updateLoudInfo() {
        val info = loudInfo ?: return
        val s = st
        val vol = EqService.instance?.musicVolume() ?: run {
            val am = getSystemService(AudioManager::class.java)
            am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        }
        val k = s.loudComp / 100f * (1f - vol)
        info.text = if (s.loudComp == 0) "" else
            "Handy-Lautstärke %d %% → Bass %+.1f dB, Höhen %+.1f dB".format(
                (vol * 100).roundToInt(), ParamEq.MAX_LOUD_BASS * k, ParamEq.MAX_LOUD_TREBLE * k)
        info.visibility = if (s.loudComp == 0) View.GONE else View.VISIBLE

        val svc = EqService.instance
        nightInfo?.let {
            val unsupported = svc != null && svc.activeSessions.isNotEmpty() && !svc.nightSupported
            it.text = "Der Kompressor wird von diesem Gerät leider nicht unterstützt."
            it.visibility = if (unsupported && s.night > 0) View.VISIBLE else View.GONE
        }
    }

    // ----- Live-Spektrum -----

    private fun setSpectrum(on: Boolean) {
        if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this)
                .setTitle("Live-Spektrum")
                .setMessage(
                    "Android gibt den Ton zur Analyse nur mit der Mikrofon-Berechtigung frei. " +
                    "Aufgenommen wird dabei nichts – ausgewertet wird nur, was ohnehin abgespielt wird."
                )
                .setPositiveButton("Weiter") { _, _ -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) }
                .setNegativeButton("Abbrechen") { _, _ -> render() }
                .setOnCancelListener { render() }
                .show()
            return
        }
        prefs.spectrum = on
        updateSpectrum()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        prefs.spectrum = granted
        if (!granted) ui.toast("Ohne Berechtigung kann das Spektrum nicht angezeigt werden")
        render()
        updateSpectrum()
    }

    /** Analyse nur, solange die App sichtbar ist und das Spektrum eingeschaltet ist. */
    private fun updateSpectrum() {
        val allowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val want = prefs.spectrum && allowed && resumed
        spectrumView.visibility = if (prefs.spectrum && allowed) View.VISIBLE else View.GONE
        if (want) {
            if (analyzer == null) {
                val a = SpectrumAnalyzer { data -> curve.spectrum = data; spectrumView.data = data }
                if (a.start()) analyzer = a
                else {
                    ui.toast("Das Live-Spektrum ist auf diesem Gerät nicht verfügbar")
                    prefs.spectrum = false
                    spectrumSwitch.isChecked = false
                }
            }
        } else {
            analyzer?.stop()
            analyzer = null
            curve.spectrum = null
            spectrumView.data = null
        }
    }

    // ----- Lautstärke -----

    private fun buildVolume(root: LinearLayout) {
        root.addView(ui.section("Lautstärke-Boost"))
        val loudValue = TextView(this).apply {
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(4), 0, ui.dp(4))
            setOnClickListener {
                askNumber("Lautstärke-Boost in dB", "%.2f".format(st.loudness / 100f)) { v ->
                    setLoudness((v * 100).roundToInt())
                }
            }
        }
        root.addView(loudValue)
        root.addView(ui.hint("Tippe auf den Wert, um ihn exakt einzugeben.").apply { gravity = Gravity.CENTER })

        loudBar = ui.slider(MAX_LOUDNESS, 0, display = {
            loudValue.text = "+%.2f dB".format(it / 100f)
            loudValue.setTextColor(if (it > 1000) Palette.warn else Palette.gold)
        }) { p -> update { it.copy(loudness = p) } }
        root.addView(ui.row(
            ui.stepButton("−") { setLoudness(st.loudness - loudStep) },
            loudBar,
            ui.stepButton("+") { setLoudness(st.loudness + loudStep) },
        ))

        // Schrittweite der −/+ Tasten
        val stepViews = ArrayList<Button>()
        val stepRow = ui.row(TextView(this).apply {
            text = "Schritt:"
            setTextColor(Palette.champagne)
            setPadding(0, 0, ui.dp(8), 0)
        })
        listOf(1 to "0,01 dB", 10 to "0,1 dB", 100 to "1 dB").forEach { (mb, label) ->
            val b = ui.smallButton(label) {}
            b.setOnClickListener {
                loudStep = mb
                stepViews.forEach { v -> ui.setActive(v, v === b) }
            }
            ui.setActive(b, mb == loudStep)
            stepViews.add(b)
            stepRow.addView(b)
        }
        root.addView(stepRow)

        if (dpSupported) {
            limiterSwitch = ui.switch("Limiter (verhindert Übersteuern und Knacken)") { on ->
                update { it.copy(limiter = on) }
            }
            root.addView(limiterSwitch)
            val thr = ui.valueText()
            root.addView(ui.labelRow("Grenze", thr))
            limiterBar = ui.slider(LIMITER_STEPS, 0, display = { thr.text = formatDbF(it / 10f + LIMITER_MIN) }) { p ->
                update { it.copy(limiterThreshold = p / 10f + LIMITER_MIN) }
            }
            root.addView(ui.row(
                ui.stepButton("−") { setLimiter(st.limiterThreshold - 0.1f) },
                limiterBar,
                ui.stepButton("+") { setLimiter(st.limiterThreshold + 0.1f) },
            ))
        }
        root.addView(ui.hint(
            "Macht alles lauter als die normale Maximal-Lautstärke – gut für leise Kopfhörer. " +
            "Hohe Werte können dem Gehör schaden: langsam hochdrehen und die Handy-Lautstärke vorher runterregeln."
        ).apply { setTextColor(Palette.warn) })
    }

    private fun setLoudness(mb: Int) {
        val v = mb.coerceIn(0, MAX_LOUDNESS)
        update { it.copy(loudness = v) }
        loudBar.progress = v
    }

    private fun setLimiter(db: Float) {
        val v = ((db.coerceIn(LIMITER_MIN, 0f)) * 10).roundToInt() / 10f
        update { it.copy(limiterThreshold = v) }
        limiterBar?.progress = ((v - LIMITER_MIN) * 10).roundToInt()
    }

    // ----- Balance -----

    private fun buildBalance(root: LinearLayout) {
        root.addView(ui.section("Balance"))
        val v = ui.valueText().apply { setOnClickListener { setBalance(0) } }
        root.addView(ui.labelRow("Links ↔ Rechts", v))
        val bar = ui.slider(200, 100, display = { v.text = formatBalance(it - 100) }) { p -> update { it.copy(balance = p - 100) } }
        balanceBar = bar
        root.addView(ui.row(
            ui.stepButton("−") { setBalance(st.balance - 1) },
            bar,
            ui.stepButton("+") { setBalance(st.balance + 1) },
        ))
        root.addView(ui.hint("Tippe auf den Wert, um auf die Mitte zurückzusetzen."))
    }

    private fun setBalance(b: Int) {
        val v = b.coerceIn(-100, 100)
        update { it.copy(balance = v) }
        balanceBar?.progress = v + 100
    }

    // ----- Kopfhörer-Automatik -----

    private fun buildDevices(root: LinearLayout) {
        root.addView(ui.section("Kopfhörer-Automatik"))
        autoSwitchView = ui.switch("Profil je Kopfhörer automatisch wechseln") { on -> prefs.autoSwitch = on }
        root.addView(autoSwitchView)
        deviceText = ui.valueText().apply { gravity = Gravity.START; setPadding(0, ui.dp(6), 0, ui.dp(2)) }
        root.addView(deviceText)

        root.addView(ui.hint("Profil für dieses Gerät:"))
        deviceSpinner = ui.choice { pos ->
            val d = currentDevice()
            val name = profileNames.getOrNull(pos - 1)
            prefs.assignDevice(d.key, d.label, name)
            prefs.lastDeviceKey = d.key
            if (name != null && prefs.autoSwitch) {
                prefs.loadProfile(name)
                EqService.instance?.applyNow()
                ui.toast("„$name“ ist jetzt mit ${d.label} verknüpft")
            }
            render()
        }
        root.addView(deviceSpinner)

        root.addView(ui.hint("Für alle anderen Geräte:"))
        defaultSpinner = ui.choice { pos ->
            prefs.assignDevice(DEFAULT_DEVICE_KEY, "Alle anderen Geräte", profileNames.getOrNull(pos - 1))
            render()
        }
        root.addView(defaultSpinner)

        assignBox = ui.vbox()
        root.addView(assignBox)
        root.addView(ui.hint(
            "So geht’s: Für jeden Kopfhörer ein ★-Profil anlegen (auch mit passendem Lautstärke-Boost), " +
            "Kopfhörer verbinden und hier das Profil wählen. Beim nächsten Verbinden schaltet der Equalizer " +
            "automatisch um – solange er aktiv ist. „Für alle anderen Geräte“ schützt davor, dass ein hoher " +
            "Boost versehentlich auf lauten Kopfhörern landet."
        ))
    }

    private fun currentDevice(): OutDevice = EqService.instance?.currentDevice ?: AudioDevices.current(this)

    private fun renderDevices() {
        val d = currentDevice()
        deviceText.text = "Verbunden: ${d.label}"
        profileNames = prefs.profileNames()
        val items = profileNames.map { PROFILE_PREFIX + it }
        val all = prefs.deviceAssignments()
        val cur = all.firstOrNull { it.key == d.key }?.profile
        ui.setChoices(deviceSpinner, listOf("— keins —") + items, cur?.let { profileNames.indexOf(it) + 1 } ?: 0)
        val def = all.firstOrNull { it.key == DEFAULT_DEVICE_KEY }?.profile
        ui.setChoices(defaultSpinner, listOf("— aktuelles Profil beibehalten —") + items,
            def?.let { profileNames.indexOf(it) + 1 } ?: 0)

        assignBox.removeAllViews()
        val list = all.filter { it.key != DEFAULT_DEVICE_KEY }
        if (list.isNotEmpty()) assignBox.addView(ui.hint("Gespeicherte Zuordnungen:"))
        list.forEach { a ->
            val label = TextView(this).apply {
                text = "${a.label}  →  ${PROFILE_PREFIX}${a.profile}"
                setTextColor(Palette.champagne)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            val remove = ui.stepButton("✕") {
                prefs.assignDevice(a.key, a.label, null)
                renderDevices()
            }
            assignBox.addView(ui.row(label, remove))
        }
    }

    // ----- Benachrichtigung & Schnellzugriff -----

    private fun buildNotification(root: LinearLayout) {
        root.addView(ui.section("Benachrichtigung & Schnellzugriff"))
        iconSwitch = ui.switch("Symbol in der Statusleiste anzeigen") { on ->
            prefs.showIcon = on
            EqService.instance?.refreshNotification()
        }
        root.addView(iconSwitch)
        root.addView(ui.hint(
            "Android verlangt für dauerhaft laufende Apps eine Benachrichtigung. Ohne Symbol erscheint sie nur noch " +
            "klein in der heruntergezogenen Leiste. Ganz ausblenden lässt sie sich in den Android-Einstellungen – " +
            "der Equalizer läuft trotzdem weiter."
        ))
        root.addView(ui.row(ui.smallButton("Android-Benachrichtigungseinstellungen") {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }))
        root.addView(ui.hint(
            "Schnelleinstellungen: Leiste ganz herunterziehen → Bearbeiten (Stift) → die Kacheln „Equalizer“ " +
            "(an/aus) und „EQ-Profil“ (nächstes ★-Profil) hinzufügen. Die Benachrichtigung hat außerdem " +
            "die Knöpfe „Nächstes Profil“ und „Ausschalten“."
        ))
    }

    // =====================================================================
    // Zustand ↔ Oberfläche
    // =====================================================================

    /** Zustand ändern, speichern und anwenden. */
    private fun update(change: (EqState) -> EqState) {
        val before = prefs.state
        // Zusammenhängende Bewegungen (z. B. ein Schieberegler-Zug) ergeben nur einen Verlaufsschritt
        val now = SystemClock.uptimeMillis()
        if (now - lastChange > HISTORY_GAP_MS) pushUndo(before)
        lastChange = now
        prefs.state = change(before)
        onUserChange()
    }

    private fun pushUndo(s: EqState) {
        undo.addLast(s)
        if (undo.size > HISTORY_MAX) undo.removeFirst()
        redo.clear()
        updateHistoryButtons()
    }

    private fun doUndo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(prefs.state)
        restore(prev)
    }

    private fun doRedo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(prefs.state)
        restore(next)
    }

    private fun restore(s: EqState) {
        lastChange = 0L
        prefs.state = s
        onUserChange()
        render()
    }

    private fun clearHistory() {
        undo.clear(); redo.clear()
        lastChange = 0L
        if (built) updateHistoryButtons()
    }

    private fun updateHistoryButtons() {
        undoBtn.isEnabled = undo.isNotEmpty(); undoBtn.alpha = if (undo.isNotEmpty()) 1f else 0.35f
        redoBtn.isEnabled = redo.isNotEmpty(); redoBtn.alpha = if (redo.isNotEmpty()) 1f else 0.35f
    }

    /** Nach jeder Bedienung: ★-Profil automatisch mitspeichern, Dienst informieren, Kurve neu zeichnen. */
    private fun onUserChange() {
        val name = prefs.currentProfileName()
        if (name != null) {
            prefs.saveProfile(name)
        } else if (prefs.presetName != CUSTOM) {
            prefs.presetName = CUSTOM
            refreshEntries()
        }
        EqService.instance?.applyAll()
        curve.state = prefs.state
    }

    /** Gespeicherten Zustand komplett in die Bedienelemente übertragen. */
    private fun render() {
        val was = ui.silent
        ui.silent = true
        normalizeBands()
        val s = prefs.state
        enabledSwitch.isChecked = prefs.enabled
        globalSwitch.isChecked = prefs.global
        autoSwitchView.isChecked = prefs.autoSwitch
        iconSwitch.isChecked = prefs.showIcon

        val param = s.mode == MODE_PARAMETRIC && dpSupported
        ui.setActive(modeGraphicBtn, !param)
        ui.setActive(modeParamBtn, param)
        graphicBox.visibility = if (param) View.GONE else View.VISIBLE
        paramBox.visibility = if (param) View.VISIBLE else View.GONE

        for (b in 0 until bandCount) bandBars[b].progress = s.bands.getOrElse(b) { 0 } - minLevel
        bassBar?.progress = s.bass
        loudBar.progress = s.loudness
        limiterSwitch?.isChecked = s.limiter
        limiterBar?.progress = ((s.limiterThreshold - LIMITER_MIN) * 10).roundToInt()
        balanceBar?.progress = s.balance + 100
        preampBar.progress = ((s.preamp - PREAMP_MIN) * 10).roundToInt()
        preampValue.text = formatDbF(s.preamp)

        spectrumSwitch.isChecked = prefs.spectrum
        loudCompBar?.progress = s.loudComp
        nightBar?.progress = s.night
        updateLoudInfo()
        renderHearing(s)
        updateHistoryButtons()

        curve.state = s
        selFilter = selFilter.coerceIn(0, (s.filters.size - 1).coerceAtLeast(0))
        bindFilter()
        refreshEntries()
        renderDevices()
        updateStatus()
        ui.silent = was
    }

    private fun switchMode(mode: Int) {
        if (mode == MODE_PARAMETRIC && !dpSupported) {
            ui.toast("Der parametrische EQ braucht Android 9 oder neuer.")
            return
        }
        if (st.mode == mode) return
        update { it.copy(mode = mode) }
        render()
    }

    /** Baut die Profil-Liste neu und wählt den gespeicherten Eintrag. */
    private fun refreshEntries() {
        entries = listOf(Entry.Custom, Entry.Flat) + devicePresets + prefs.profileNames().map { Entry.User(it) }
        val pos = entries.indexOfFirst { it.label == prefs.presetName }.coerceAtLeast(0)
        ui.setChoices(presetSpinner, entries.map { it.label }, pos)
        val isUser = entries[pos] is Entry.User
        listOf(renameBtn, deleteBtn).forEach { it.isEnabled = isUser; it.alpha = if (isUser) 1f else 0.4f }
    }

    private fun selectEntry(e: Entry) {
        when (e) {
            is Entry.Custom -> prefs.presetName = CUSTOM
            is Entry.Flat -> {
                pushUndo(st)
                applyState(
                    st.copy(bands = IntArray(bandCount), filters = st.filters.map { it.copy(gain = 0f) }, preamp = 0f),
                    FLAT,
                )
            }
            is Entry.Device -> {
                pushUndo(st)
                applyState(st.copy(mode = MODE_GRAPHIC, bands = e.levels.copyOf()), e.label)
            }
            is Entry.User -> {
                prefs.loadProfile(e.name)
                selFilter = 0
                clearHistory()
            }
        }
        render()
        EqService.instance?.applyAll()
    }

    private fun applyState(s: EqState, label: String) {
        prefs.state = s
        prefs.presetName = label
    }

    private fun updateStatus() {
        val s = EqService.instance
        val dev = currentDevice().label
        statusText.text = if (s == null) "Status: aus · $dev" else {
            val sessions = s.activeSessions
            val global = if (0 in sessions) "global an" else "global aus"
            "Status: läuft · $global · ${sessions.count { it != 0 }} App(s) · $dev"
        }
    }

    // =====================================================================
    // AutoEq, Import und Export
    // =====================================================================

    private fun showAutoEqDialog() {
        val box = ui.vbox().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val search = EditText(this).apply {
            hint = "Modell suchen, z. B. WH-1000XM4"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val info = ui.hint("Lade Kopfhörer-Liste …")
        val shown = ArrayList<AutoEq.Entry>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList())
        val list = ListView(this).apply {
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(360))
        }
        box.addView(search); box.addView(info); box.addView(list)

        val dialog = AlertDialog.Builder(this)
            .setTitle("AutoEq – Kopfhörer wählen")
            .setView(box)
            .setNegativeButton("Schließen", null)
            .show()

        var all: List<AutoEq.Entry> = emptyList()
        fun filter() {
            val terms = search.text.toString().lowercase().split(" ").filter { it.isNotBlank() }
            shown.clear()
            shown.addAll(all.filter { e -> terms.all { e.name.lowercase().contains(it) } }.take(300))
            adapter.clear()
            adapter.addAll(shown.map { "${it.name}   ·   ${it.source}" })
            info.text = if (all.isEmpty()) info.text
                        else if (terms.isEmpty()) "${all.size} Kopfhörer – Suchbegriff eingeben"
                        else "${shown.size} Treffer"
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = filter()
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            shown.getOrNull(pos)?.let { dialog.dismiss(); importAutoEq(it) }
        }

        Thread {
            try {
                val idx = AutoEq.loadIndex(this)
                runOnUiThread { all = idx; filter() }
            } catch (e: Exception) {
                runOnUiThread { info.text = "Keine Verbindung zur AutoEq-Datenbank: ${e.message}" }
            }
        }.start()
    }

    private fun importAutoEq(e: AutoEq.Entry) {
        ui.toast("Lade Korrektur für ${e.name} …")
        Thread {
            try {
                val parsed = AutoEq.parseParametric(AutoEq.download(AutoEq.parametricUrl(e)))
                    ?: throw IllegalStateException("Datei enthält keine Filter")
                runOnUiThread { createParamProfile(e.name, parsed) }
            } catch (ex: Exception) {
                runOnUiThread { ui.toast("Laden fehlgeschlagen: ${ex.message}") }
            }
        }.start()
    }

    /** Neues ★-Profil im parametrischen Modus anlegen (Lautstärke, Limiter, Balance bleiben). */
    private fun createParamProfile(name: String, parsed: ParsedEq) {
        clearHistory()
        prefs.state = st.copy(mode = MODE_PARAMETRIC, filters = parsed.filters, preamp = parsed.preamp)
        prefs.saveProfile(name)
        prefs.presetName = PROFILE_PREFIX + name
        selFilter = 0
        render()
        EqService.instance?.applyAll()
        ui.toast("★ $name angelegt (${parsed.filters.size} Filter)")

        val d = currentDevice()
        if (d.key != AudioDevices.SPEAKER_KEY) {
            AlertDialog.Builder(this)
                .setTitle("Mit Kopfhörer verknüpfen?")
                .setMessage("Soll „$name“ automatisch aktiviert werden, wenn „${d.label}“ verbunden ist?")
                .setPositiveButton("Ja") { _, _ ->
                    prefs.assignDevice(d.key, d.label, name)
                    prefs.lastDeviceKey = d.key
                    renderDevices()
                }
                .setNegativeButton("Nein", null)
                .show()
        }
    }

    private fun exportProfiles() {
        if (prefs.profiles.isEmpty()) { ui.toast("Noch keine ★-Profile zum Exportieren"); return }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "equalizer-profile.json")
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun importFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(i, REQ_IMPORT)
    }

    @Deprecated("Activity-API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HEARING) {
            if (resultCode == RESULT_OK) clearHistory()
            return
        }
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                REQ_EXPORT -> {
                    contentResolver.openOutputStream(uri)?.use { it.write(prefs.exportJson().toByteArray()) }
                    ui.toast("${prefs.profiles.size} Profil(e) exportiert")
                }
                REQ_IMPORT -> handleImport(uri)
                REQ_HEARING -> {}
            }
        } catch (e: Exception) {
            ui.toast("Fehler: ${e.message}")
        }
    }

    /** Erkennt exportierte Profile (JSON) und parametrische EQ-Dateien (AutoEq / Equalizer APO). */
    private fun handleImport(uri: Uri) {
        val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
        val t = text.trim()
        if (t.startsWith("{") || t.startsWith("[")) {
            val n = prefs.importJson(t)
            clearHistory()
            render()
            ui.toast("$n Profil(e) importiert")
            return
        }
        val parsed = AutoEq.parseParametric(t)
        if (parsed == null) { ui.toast("Datei nicht erkannt"); return }
        val name = (displayName(uri) ?: "Importiert")
            .substringBeforeLast('.')
            .removeSuffix(" ParametricEQ")
            .trim()
            .ifEmpty { "Importiert" }
        createParamProfile(name, parsed)
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) { null }

    // =====================================================================
    // Dialoge & Formatierung
    // =====================================================================

    private fun askText(title: String, preset: String, hintText: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(preset)
            setSelection(preset.length)
            hint = hintText
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ui.vbox().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(input, MATCH_PARENT, WRAP_CONTENT) })
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) ui.toast("Bitte einen Namen eingeben") else onOk(name)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Exakte Zahleneingabe (Komma oder Punkt, auch negativ). */
    private fun askNumber(title: String, current: String, onOk: (Float) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(current)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ui.vbox().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(input, MATCH_PARENT, WRAP_CONTENT) })
            .setPositiveButton("OK") { _, _ ->
                val v = input.text.toString().replace(',', '.').toFloatOrNull()
                if (v == null) ui.toast("Ungültige Zahl") else onOk(v)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun progToFreq(p: Int) = 20f * 1000f.pow(p / FREQ_STEPS.toFloat())
    private fun freqToProg(f: Float) =
        (ln(f.coerceIn(20f, 20000f) / 20f) / ln(1000f) * FREQ_STEPS).roundToInt().coerceIn(0, FREQ_STEPS)
    private fun progToQ(p: Int) = CurveView.Q_MIN * 100f.pow(p / Q_STEPS.toFloat())
    private fun qToProg(q: Float) =
        (ln(q.coerceIn(CurveView.Q_MIN, CurveView.Q_MAX) / CurveView.Q_MIN) / ln(100f) * Q_STEPS).roundToInt().coerceIn(0, Q_STEPS)

    private fun formatFreq(hz: Int) =
        if (hz >= 1000) "%.1f kHz".format(hz / 1000f).replace(",0 ", " ").replace(".0 ", " ") else "$hz Hz"
    private fun formatHz(hz: Float) = if (hz >= 1000) "%.2f kHz".format(hz / 1000f) else "%.0f Hz".format(hz)
    private fun formatDb(mb: Int) = "%+.1f dB".format(mb / 100f)
    private fun formatDbF(db: Float) = "%+.1f dB".format(db)
    private fun formatBalance(b: Int) = when {
        b == 0 -> "Mitte"
        b < 0 -> "Links ${-b} %"
        else -> "Rechts $b %"
    }

    companion object {
        /** Maximaler Lautstärke-Boost: 1500 mB = +15 dB */
        private const val MAX_LOUDNESS = 1500
        /** Schritt der −/+ Tasten bei den Frequenzbändern: 10 mB = 0,1 dB */
        private const val BAND_STEP = 10
        private const val FREQ_STEPS = 2000
        private const val GAIN_STEPS = 360          // −18 … +18 dB in 0,1-dB-Schritten
        private const val Q_STEPS = 1000
        private const val PREAMP_MIN = -20f
        private const val PREAMP_MAX = 10f
        private const val PREAMP_STEPS = 300
        private const val LIMITER_MIN = -12f
        private const val LIMITER_STEPS = 120
        private const val MAX_FILTERS = 20
        private const val REQ_EXPORT = 10
        private const val REQ_IMPORT = 11
        private const val REQ_HEARING = 12
        private const val REQ_MIC = 13
        private const val HISTORY_GAP_MS = 700L
        private const val HISTORY_MAX = 60
        private const val HEARING_MAX = 150
    }
}
