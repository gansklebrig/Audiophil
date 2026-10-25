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
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
 * Aufbau: Eine schlanke Startseite (Ein/Aus, Profil, Klang, Lautstärke-Boost, Menü-Kacheln)
 * und Untermenüs für alle Detail-Einstellungen. Die Untermenüs liegen als eigene Seiten
 * übereinander und werden ein-/ausgeblendet; die Zurück-Taste führt zur Startseite.
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

    // Seiten
    private lateinit var host: FrameLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var homeBody: LinearLayout
    private val pages = HashMap<Int, ScrollView>()
    private var openPage = NO_PAGE
    private val tileSubs = HashMap<Int, TextView>()

    // Update aus der App
    private lateinit var updater: UpdateUi
    private lateinit var updateBanner: LinearLayout
    private lateinit var updateBannerText: TextView
    private var updateInfo: TextView? = null
    private var tokenSub: TextView? = null
    private var autoUpdateSwitch: Switch? = null

    // Der Klang-Bereich (Kurve bzw. Bänder) wandert zwischen Startseite und Equalizer-Seite
    private lateinit var soundBox: LinearLayout
    private lateinit var homeSoundHolder: LinearLayout
    private lateinit var eqSoundHolder: LinearLayout
    private lateinit var soundTitle: TextView
    private lateinit var filterInfoRow: LinearLayout
    private lateinit var filterInfo: TextView

    // Bedienelemente
    private lateinit var powerBtn: TextView
    private lateinit var globalSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var presetSpinner: Spinner
    private lateinit var renameBtn: Button
    private lateinit var deleteBtn: Button
    private lateinit var modeGraphicBtn: Button
    private lateinit var modeParamBtn: Button
    private lateinit var graphicBox: LinearLayout
    private lateinit var paramBox: LinearLayout
    private lateinit var graphicHint: TextView
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

    // Verlauf, Vorher/Nachher, Spektrum, Kanalwahl, Hörprofil, Klangautomatik
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
                setTextColor(Palette.champagne)
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
        updater.checkQuietly()
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

    /** Zurück-Taste: aus einem Untermenü zur Startseite. */
    @Deprecated("Activity-API")
    override fun onBackPressed() {
        if (built && openPage != NO_PAGE) closePage()
        else @Suppress("DEPRECATION") super.onBackPressed()
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
        host = FrameLayout(this).apply { setBackgroundColor(Palette.bg) }
        updater = UpdateUi(this, ui) { renderUpdate() }

        // Bausteine, die mehrere Seiten nutzen, zuerst anlegen
        buildSoundBox()
        paramBox = ui.vbox().also { buildParametric(it) }

        homeScroll = scroll(buildHome())
        host.addView(homeScroll)

        addPage(PAGE_EQ, "Equalizer") { buildEqPage(it) }
        addPage(PAGE_VOLUME, "Lautstärke & Dynamik") { buildVolumePage(it) }
        addPage(PAGE_HEARING, "Hörprofil") { buildHearingPage(it) }
        addPage(PAGE_PHONES, "Kopfhörer") { buildPhonesPage(it) }
        addPage(PAGE_PROFILES, "Profile") { buildProfilesPage(it) }
        addPage(PAGE_SETTINGS, "Einstellungen") { buildSettingsPage(it) }
        return host
    }

    private fun scroll(content: View) = ScrollView(this).apply {
        setBackgroundColor(Palette.bg)
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(content)
    }

    private fun page() = ui.vbox().apply { setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(36)) }

    private fun addPage(id: Int, title: String, content: (LinearLayout) -> Unit) {
        val p = page()
        p.addView(ui.pageHeader(title) { closePage() })
        content(p)
        val sv = scroll(p).apply { visibility = View.GONE }
        pages[id] = sv
        host.addView(sv)
    }

    // ----- Startseite -----

    private fun buildHome(): View {
        val root = page()

        powerBtn = ui.powerButton { togglePower() }
        root.addView(ui.row(
            ui.title("EQUALIZER").apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) },
            powerBtn,
        ))
        statusText = ui.hint("")
        root.addView(statusText)

        homeBody = ui.vbox()
        root.addView(homeBody)

        // Hinweis, wenn auf GitHub eine neuere Version liegt
        updateBannerText = TextView(this).apply {
            textSize = 14f
            setTextColor(Palette.goldLight)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        updateBanner = ui.card().apply {
            visibility = View.GONE
            addView(ui.row(
                updateBannerText,
                ui.goldButton("Installieren").apply {
                    ui.setActive(this, true)
                    setPadding(ui.dp(16), 0, ui.dp(16), 0)
                    setOnClickListener { updater.installLatest() }
                },
            ))
        }
        homeBody.addView(updateBanner)

        // Profil
        val pc = ui.card("Profil")
        presetSpinner = ui.choice(big = true) { pos -> entries.getOrNull(pos)?.let { selectEntry(it) } }
        pc.addView(presetSpinner)
        homeBody.addView(pc)

        // Klang
        val sc = ui.card()
        undoBtn = ui.iconButton("↶") { doUndo() }
        redoBtn = ui.iconButton("↷") { doRedo() }
        soundTitle = ui.cardTitle("Klang").apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        sc.addView(ui.row(soundTitle, undoBtn, redoBtn).apply { setPadding(0, 0, 0, ui.dp(4)) })
        homeSoundHolder = ui.vbox()
        homeSoundHolder.addView(soundBox)
        sc.addView(homeSoundHolder)

        filterInfo = TextView(this).apply {
            textSize = 13f
            setTextColor(Palette.champagne)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val edit = TextView(this).apply {
            text = "Bearbeiten ›"
            textSize = 13f
            setTextColor(Palette.goldLight)
            setPadding(ui.dp(12), ui.dp(8), 0, ui.dp(8))
            setOnClickListener { openPage(PAGE_EQ) }
        }
        filterInfoRow = ui.row(filterInfo, edit)
        sc.addView(filterInfoRow)
        sc.addView(abButton())
        homeBody.addView(sc)

        // Lautstärke-Boost
        homeBody.addView(buildBoostCard())

        // Menü
        homeBody.addView(ui.cardTitle("Einstellungen").apply { setPadding(ui.dp(4), ui.dp(22), 0, ui.dp(2)) })
        val grid = ui.vbox().apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(-ui.dp(5), 0, -ui.dp(5), 0)
            }
        }
        fun tile(id: Int, icon: String, title: String): View {
            val (v, sub) = ui.tile(icon, title) { openPage(id) }
            tileSubs[id] = sub
            return v
        }
        val (room, roomSub) = ui.tile("◎", "Boxen einmessen") { startRoomMeasure() }
        tileSubs[PAGE_ROOM] = roomSub
        grid.addView(ui.row(tile(PAGE_EQ, "≈", "Equalizer"), tile(PAGE_VOLUME, "◢", "Lautstärke")))
        grid.addView(ui.row(tile(PAGE_HEARING, "◐", "Hörprofil"), room))
        grid.addView(ui.row(tile(PAGE_PHONES, "∩", "Kopfhörer"), tile(PAGE_PROFILES, "★", "Profile")))
        val (upd, updSub) = ui.tile("⇣", "Update") {
            if (Updater.knownNewer(this) > 0) updater.installLatest() else updater.checkNow()
        }
        tileSubs[PAGE_UPDATE] = updSub
        grid.addView(ui.row(tile(PAGE_SETTINGS, "☰", "Einstellungen"), upd))
        homeBody.addView(grid)
        return root
    }

    /** Boxen per Mikrofon einmessen (eigene Ansicht). */
    private fun startRoomMeasure() {
        if (!dpSupported) { ui.toast("Das Einmessen braucht Android 9 oder neuer."); return }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, RoomMeasureActivity::class.java), REQ_ROOM)
    }

    /** Vorher/Nachher: gedrückt halten → Klangfärbung kurz aus. */
    private fun abButton(): Button {
        val label = "Vorher / Nachher  ·  halten"
        val ab = ui.wideButton(label) {}
        ab.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val svc = EqService.instance
                    if (svc == null) ui.toast("Der Equalizer ist ausgeschaltet")
                    else { svc.setBypass(EqService.BYPASS_EQ); ab.text = "Vorher – ohne Klangfärbung"; ui.setActive(ab, true) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    EqService.instance?.setBypass(EqService.BYPASS_NONE)
                    ab.text = label
                    ui.setActive(ab, false)
                    if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
        return ab
    }

    private fun togglePower() {
        val on = !prefs.enabled
        prefs.enabled = on
        if (on) EqService.start(this) else EqService.stop(this)
        renderPower()
        host.postDelayed({ updateStatus(); updateTiles() }, 400)
    }

    private fun renderPower() {
        ui.setPower(powerBtn, prefs.enabled)
        homeBody.animate().alpha(if (prefs.enabled) 1f else 0.45f).setDuration(200).start()
    }

    // ----- Seitenwechsel -----

    private fun openPage(id: Int) {
        val p = pages[id] ?: return
        if (openPage != NO_PAGE) pages[openPage]?.visibility = View.GONE
        openPage = id
        if (id == PAGE_EQ) moveSound(eqSoundHolder)
        render()
        p.scrollTo(0, 0)
        p.animate().cancel()
        p.visibility = View.VISIBLE
        p.alpha = 0f
        p.translationX = ui.dp(36).toFloat()
        p.animate().alpha(1f).translationX(0f).setDuration(190).start()
        homeScroll.animate().cancel()
        homeScroll.animate().alpha(0f).setDuration(120).withEndAction {
            if (openPage != NO_PAGE) homeScroll.visibility = View.GONE
        }.start()
    }

    private fun closePage() {
        val p = pages[openPage] ?: return
        openPage = NO_PAGE
        moveSound(homeSoundHolder)
        render()
        homeScroll.animate().cancel()
        homeScroll.visibility = View.VISIBLE
        homeScroll.alpha = 0f
        homeScroll.animate().alpha(1f).setDuration(190).start()
        p.animate().cancel()
        p.animate().alpha(0f).translationX(ui.dp(36).toFloat()).setDuration(150).withEndAction {
            if (openPage == NO_PAGE) p.visibility = View.GONE
        }.start()
    }

    /** Kurve bzw. Bänder in den Halter der gerade sichtbaren Seite hängen. */
    private fun moveSound(holder: LinearLayout) {
        if (soundBox.parent === holder) return
        (soundBox.parent as? ViewGroup)?.removeView(soundBox)
        holder.addView(soundBox)
    }

    /** Update-Hinweise auf Startseite, Kachel und in den Einstellungen. */
    private fun renderUpdate() {
        if (!built) return
        val mine = Updater.installedVersion(this)
        val newer = Updater.knownNewer(this)
        updateBanner.visibility = if (newer > 0) View.VISIBLE else View.GONE
        updateBannerText.text = "Update 1.$newer ist da"
        tileSubs[PAGE_UPDATE]?.text = if (newer > 0) "Neu: 1.$newer" else "Version 1.$mine"
        tileSubs[PAGE_UPDATE]?.setTextColor(if (newer > 0) Palette.goldLight else Palette.muted)
        updateInfo?.text = if (newer > 0) "Installiert: 1.$mine  ·  verfügbar: 1.$newer" else "Installiert: Version 1.$mine"
        tokenSub?.text = if (Updater.token(this).isEmpty()) "Nicht eingetragen – nötig, solange das Repo privat ist" else "Eingetragen ✓"
        val was = ui.silent
        ui.silent = true
        autoUpdateSwitch?.isChecked = Updater.autoCheck(this)
        ui.silent = was
    }

    /** Kurzer Stand je Menü-Kachel. */
    private fun updateTiles() {
        if (!built) return
        val s = st
        val param = s.mode == MODE_PARAMETRIC && dpSupported
        tileSubs[PAGE_EQ]?.text =
            if (param) "Parametrisch · ${s.filters.size} Filter" else "Band-EQ · $bandCount Bänder"
        tileSubs[PAGE_VOLUME]?.text = if (!dpSupported) "Boost-Schrittweite" else listOfNotNull(
            if (s.limiter) "Limiter an" else "Limiter aus",
            if (s.balance != 0) formatBalance(s.balance) else null,
            if (s.night > 0) "Nacht" else null,
            if (s.loudComp > 0) "Ausgleich" else null,
        ).joinToString(" · ")
        tileSubs[PAGE_HEARING]?.text = when {
            !dpSupported -> "Ab Android 9"
            s.hearing.isEmpty() -> "Hörtest machen"
            s.hearingOn -> "Aktiv · ${s.hearingAmount} %"
            else -> "Ausgeschaltet"
        }
        tileSubs[PAGE_ROOM]?.text = if (!dpSupported) "Ab Android 9" else "Automatisch per Mikrofon"
        tileSubs[PAGE_PHONES]?.text = currentDevice().label
        val n = prefs.profileNames().size
        tileSubs[PAGE_PROFILES]?.text = if (n == 0) "Noch keine gespeichert" else "$n gespeichert"
        tileSubs[PAGE_SETTINGS]?.text = if (prefs.global) "Globaler Modus" else "App-Modus"
    }

    // ----- Klang-Bereich (Kurve bzw. Bänder) -----

    private fun buildSoundBox() {
        soundBox = ui.vbox()
        curve = CurveView(this).apply {
            onSelect = { i -> selFilter = i; bindFilter() }
            onFilterChange = { i, f -> setFilter(i, f) }
        }
        soundBox.addView(curve)
        graphicBox = ui.vbox().also { buildGraphic(it); soundBox.addView(it) }
    }

    /** Band-EQ: je Band eine kompakte Zeile – Frequenz, −, Regler, +, Wert (antippen = exakt). */
    private fun buildGraphic(box: LinearLayout) {
        spectrumView = SpectrumView(this).apply { visibility = View.GONE }
        box.addView(spectrumView)
        for (b in 0 until bandCount) {
            val value = ui.valueText().apply {
                minWidth = ui.dp(64)
                setOnClickListener {
                    askNumber("${formatFreq(bandFreqs[b])} in dB", "%.1f".format(st.bands.getOrElse(b) { 0 } / 100f)) { v ->
                        setBand(b, (v * 100).roundToInt())
                    }
                }
            }
            val bar = ui.slider(maxLevel - minLevel, 0, display = { value.text = formatDb(it + minLevel) }) { p ->
                setBand(b, p + minLevel)
            }
            bandBars.add(bar)
            box.addView(ui.row(
                rowLabel(formatFreq(bandFreqs[b])),
                ui.stepButton("−") { setBand(b, st.bands.getOrElse(b) { 0 } - BAND_STEP) },
                bar,
                ui.stepButton("+") { setBand(b, st.bands.getOrElse(b) { 0 } + BAND_STEP) },
                value,
            ))
        }
        if (bassSupported) {
            val v = ui.valueText().apply { minWidth = ui.dp(64) }
            val bar = ui.slider(1000, 0, display = { v.text = "${it / 10} %" }) { p -> update { it.copy(bass = p) } }
            bassBar = bar
            box.addView(ui.divider())
            box.addView(ui.row(rowLabel("Bass"), bar, v))
        }
    }

    private fun rowLabel(t: String) = TextView(this).apply {
        text = t
        textSize = 12.5f
        setTextColor(Palette.muted)
        layoutParams = LinearLayout.LayoutParams(ui.dp(52), WRAP_CONTENT)
    }

    private fun setBand(b: Int, mb: Int) {
        val v = mb.coerceIn(minLevel, maxLevel)
        update { s -> s.copy(bands = s.bands.copyOf().also { if (b < it.size) it[b] = v }) }
        bandBars.getOrNull(b)?.progress = v - minLevel
    }

    // ----- Lautstärke-Boost (Startseite) -----

    private fun buildBoostCard(): View {
        val c = ui.card("Lautstärke-Boost")
        val loudValue = TextView(this).apply {
            textSize = 30f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, ui.dp(2))
            setOnClickListener {
                askNumber("Lautstärke-Boost in dB", "%.2f".format(st.loudness / 100f)) { v ->
                    setLoudness((v * 100).roundToInt())
                }
            }
        }
        c.addView(loudValue)
        loudBar = ui.slider(MAX_LOUDNESS, 0, display = {
            loudValue.text = "+%.2f dB".format(it / 100f)
            loudValue.setTextColor(if (it > 1000) Palette.warn else Palette.goldLight)
        }) { p -> update { it.copy(loudness = p) } }
        c.addView(ui.row(
            ui.stepButton("−") { setLoudness(st.loudness - loudStep) },
            loudBar,
            ui.stepButton("+") { setLoudness(st.loudness + loudStep) },
        ))
        return c
    }

    private fun setLoudness(mb: Int) {
        val v = mb.coerceIn(0, MAX_LOUDNESS)
        update { it.copy(loudness = v) }
        loudBar.progress = v
    }

    // =====================================================================
    // Untermenüs
    // =====================================================================

    // ----- Equalizer -----

    private fun buildEqPage(p: LinearLayout) {
        val art = ui.card("Art")
        modeGraphicBtn = ui.smallButton("Band-EQ") { switchMode(MODE_GRAPHIC) }
        modeParamBtn = ui.smallButton("Parametrisch") { switchMode(MODE_PARAMETRIC) }
        art.addView(ui.row(modeGraphicBtn, modeParamBtn))
        art.addView(ui.hint(
            if (dpSupported) "Band-EQ: die festen Bänder deines Handys. Parametrisch: frei setzbare Filter mit Frequenz, Verstärkung und Güte."
            else "Parametrischer EQ, Limiter und Balance brauchen Android 9 oder neuer."
        ))
        spectrumSwitch = ui.switch("Live-Spektrum anzeigen") { on -> setSpectrum(on) }
        art.addView(spectrumSwitch)
        p.addView(art)

        val sound = ui.card("Klang")
        eqSoundHolder = ui.vbox()
        sound.addView(eqSoundHolder)
        graphicHint = ui.hint("−/+ ändern ein Band um 0,1 dB, gedrückt halten zählt weiter. Wert antippen für exakte Eingabe.")
        sound.addView(graphicHint)
        p.addView(sound)

        p.addView(paramBox)
    }

    /** Filter-Bearbeitung und Vorverstärkung (nur parametrisch). */
    private fun buildParametric(box: LinearLayout) {
        val fc = ui.card("Filter")
        fc.addView(ui.hint("Auf der Kurve: Punkt ziehen = Frequenz und Verstärkung · zwei Finger = Güte (Q)"))
        filterTitle = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Palette.goldLight)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        fc.addView(ui.row(
            ui.iconButton("‹") { selectFilter(selFilter - 1) },
            filterTitle,
            ui.iconButton("›") { selectFilter(selFilter + 1) },
        ))

        filterType = ui.choice { pos -> curFilter()?.let { setFilter(selFilter, it.copy(type = pos)) } }
            .apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        filterChannel = ui.choice { pos -> curFilter()?.let { setFilter(selFilter, it.copy(channel = pos)) } }
            .apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        fc.addView(ui.row(filterType, filterChannel))
        filterOn = ui.switch("Filter aktiv") { on -> curFilter()?.let { setFilter(selFilter, it.copy(on = on)) } }
        fc.addView(filterOn)

        // Frequenz
        freqValue = ui.valueText().apply {
            setOnClickListener {
                val f = curFilter() ?: return@setOnClickListener
                askNumber("Frequenz in Hz", "%.0f".format(f.freq)) { v ->
                    curFilter()?.let { setFilter(selFilter, it.copy(freq = v.coerceIn(20f, 20000f).roundToInt().toFloat())) }
                }
            }
        }
        fc.addView(ui.labelRow("Frequenz", freqValue))
        freqBar = ui.slider(FREQ_STEPS, 0) { p ->
            curFilter()?.let { setFilter(selFilter, it.copy(freq = progToFreq(p).roundToInt().toFloat()), sync = false) }
        }
        fc.addView(ui.row(ui.stepButton("−") { nudgeFreq(-1) }, freqBar, ui.stepButton("+") { nudgeFreq(+1) }))

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
        fc.addView(gainBox)

        // Güte
        qValue = ui.valueText().apply {
            setOnClickListener {
                val f = curFilter() ?: return@setOnClickListener
                askNumber("Güte (Q)", "%.2f".format(f.q)) { v ->
                    curFilter()?.let { setFilter(selFilter, it.copy(q = v.coerceIn(CurveView.Q_MIN, CurveView.Q_MAX))) }
                }
            }
        }
        fc.addView(ui.labelRow("Güte (Q) – klein = breit", qValue))
        qBar = ui.slider(Q_STEPS, 0) { p ->
            curFilter()?.let { setFilter(selFilter, it.copy(q = (progToQ(p) * 100).roundToInt() / 100f), sync = false) }
        }
        fc.addView(ui.row(ui.stepButton("−") { nudgeQ(-0.01f) }, qBar, ui.stepButton("+") { nudgeQ(+0.01f) }))
        fc.addView(ui.hint("Wert antippen für exakte Eingabe."))

        fc.addView(ui.row(
            ui.smallButton("+ Filter") { addFilter() },
            ui.smallButton("Filter löschen") { deleteFilter() },
        ).apply { setPadding(0, ui.dp(10), 0, 0) })
        box.addView(fc)

        // Vorverstärkung
        val pc = ui.card("Vorverstärkung")
        preampValue = ui.valueText().apply {
            setOnClickListener {
                askNumber("Vorverstärkung in dB", "%.1f".format(st.preamp)) { v -> setPreamp(v) }
            }
        }
        pc.addView(ui.labelRow("Gesamtpegel vor den Filtern", preampValue))
        preampBar = ui.slider(PREAMP_STEPS, 0) { p -> setPreamp(p / 10f + PREAMP_MIN, syncBar = false) }
        pc.addView(ui.row(
            ui.stepButton("−") { setPreamp(st.preamp - 0.1f) },
            preampBar,
            ui.stepButton("+") { setPreamp(st.preamp + 0.1f) },
        ))
        pc.addView(ui.hint("Bei kräftigen Anhebungen etwas absenken, damit nichts übersteuert."))
        box.addView(pc)
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
        // Kurzinfo unter der Kurve auf der Startseite
        filterInfo.text = if (f == null) "Noch keine Filter" else listOfNotNull(
            "${selFilter + 1}/${st.filters.size}",
            FilterType.names.getOrElse(f.type) { "" }.substringBefore(" ("),
            formatHz(f.freq),
            if (FilterType.hasGain(f.type)) formatDbF(f.gain) else null,
            "Q %.2f".format(f.q),
        ).joinToString("  ·  ")
    }

    // ----- Lautstärke & Dynamik -----

    private fun buildVolumePage(p: LinearLayout) {
        val boost = ui.card("Lautstärke-Boost")
        val stepViews = ArrayList<Button>()
        val stepRow = ui.row()
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
        boost.addView(ui.labelRow("Schrittweite der −/+-Tasten", TextView(this)))
        boost.addView(stepRow)
        boost.addView(ui.hint(
            "Macht alles lauter als die normale Maximal-Lautstärke – gut für leise Kopfhörer. " +
            "Hohe Werte können dem Gehör schaden: langsam hochdrehen und die Handy-Lautstärke vorher runterregeln."
        ).apply { setTextColor(Palette.warn) })
        p.addView(boost)

        if (!dpSupported) {
            p.addView(ui.card().apply { addView(ui.hint("Limiter, Balance, Lautheitsausgleich und Nachtmodus brauchen Android 9 oder neuer.")) })
            return
        }

        // Limiter
        val lim = ui.card("Limiter")
        limiterSwitch = ui.switch("Spitzen abfangen (kein Knacken)") { on -> update { it.copy(limiter = on) } }
        lim.addView(limiterSwitch)
        val thr = ui.valueText()
        lim.addView(ui.labelRow("Grenze", thr))
        limiterBar = ui.slider(LIMITER_STEPS, 0, display = { thr.text = formatDbF(it / 10f + LIMITER_MIN) }) { pr ->
            update { it.copy(limiterThreshold = pr / 10f + LIMITER_MIN) }
        }
        lim.addView(ui.row(
            ui.stepButton("−") { setLimiter(st.limiterThreshold - 0.1f) },
            limiterBar,
            ui.stepButton("+") { setLimiter(st.limiterThreshold + 0.1f) },
        ))
        p.addView(lim)

        // Balance
        val bal = ui.card("Balance")
        val bv = ui.valueText().apply { setOnClickListener { setBalance(0) } }
        bal.addView(ui.labelRow("Links ↔ Rechts", bv))
        val bar = ui.slider(200, 100, display = { bv.text = formatBalance(it - 100) }) { pr -> update { it.copy(balance = pr - 100) } }
        balanceBar = bar
        bal.addView(ui.row(
            ui.stepButton("−") { setBalance(st.balance - 1) },
            bar,
            ui.stepButton("+") { setBalance(st.balance + 1) },
        ))
        bal.addView(ui.hint("Wert antippen setzt auf die Mitte zurück."))
        p.addView(bal)

        // Lautheitsausgleich
        val lc = ui.card("Lautheitsausgleich")
        val lv = ui.valueText()
        lc.addView(ui.labelRow("Stärke", lv))
        val lBar = ui.slider(100, 0, display = { lv.text = if (it == 0) "Aus" else "$it %" }) { pr ->
            update { it.copy(loudComp = pr) }
            updateLoudInfo()
        }
        loudCompBar = lBar
        lc.addView(ui.row(
            ui.stepButton("−") { setLoudComp(st.loudComp - 5) },
            lBar,
            ui.stepButton("+") { setLoudComp(st.loudComp + 5) },
        ))
        loudInfo = ui.hint("").apply { setTextColor(Palette.gold) }
        lc.addView(loudInfo)
        lc.addView(ui.hint(
            "Bei leiser Handy-Lautstärke hebt er Bass und Höhen an, damit Musik auch leise voll klingt. " +
            "Bei voller Lautstärke wirkt er nicht."
        ))
        p.addView(lc)

        // Nachtmodus
        val nc = ui.card("Nachtmodus")
        val nv = ui.valueText()
        nc.addView(ui.labelRow("Stärke", nv))
        val nBar = ui.slider(100, 0, display = { nv.text = if (it == 0) "Aus" else "$it %" }) { pr ->
            update { it.copy(night = pr) }
        }
        nightBar = nBar
        nc.addView(ui.row(
            ui.stepButton("−") { setNight(st.night - 5) },
            nBar,
            ui.stepButton("+") { setNight(st.night + 5) },
        ))
        nightInfo = ui.hint("").apply { setTextColor(Palette.warn) }
        nc.addView(nightInfo)
        nc.addView(ui.hint("Leise Stellen lauter, laute Spitzen leiser – gut für Podcasts, Filme, Bahnfahrten oder nachts."))
        p.addView(nc)
    }

    private fun setLimiter(db: Float) {
        val v = ((db.coerceIn(LIMITER_MIN, 0f)) * 10).roundToInt() / 10f
        update { it.copy(limiterThreshold = v) }
        limiterBar?.progress = ((v - LIMITER_MIN) * 10).roundToInt()
    }

    private fun setBalance(b: Int) {
        val v = b.coerceIn(-100, 100)
        update { it.copy(balance = v) }
        balanceBar?.progress = v + 100
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

    // ----- Hörprofil -----

    private fun buildHearingPage(p: LinearLayout) {
        if (!dpSupported) {
            p.addView(ui.card().apply { addView(ui.hint("Der Hörtest braucht Android 9 oder neuer.")) })
            return
        }
        val c = ui.card("Dein Hörprofil")
        hearingInfo = ui.hint("").apply { textSize = 14f; setTextColor(Palette.champagne) }
        c.addView(hearingInfo)
        hearingGraph = ui.vbox()
        c.addView(hearingGraph)

        hearingControls = ui.vbox().apply {
            hearingSwitch = ui.switch("Hörkorrektur aktiv") { on -> update { it.copy(hearingOn = on) } }
            addView(hearingSwitch)
            val v = ui.valueText()
            addView(ui.labelRow("Stärke", v))
            val bar = ui.slider(HEARING_MAX, 100, display = { v.text = "$it %" }) { pr -> update { it.copy(hearingAmount = pr) } }
            hearingBar = bar
            addView(ui.row(
                ui.stepButton("−") { setHearingAmount(st.hearingAmount - 5) },
                bar,
                ui.stepButton("+") { setHearingAmount(st.hearingAmount + 5) },
            ))
        }
        c.addView(hearingControls)
        p.addView(c)

        val t = ui.card("Hörtest")
        t.addView(ui.hint(
            "Misst in etwa 4 Minuten je Ohr, wie gut du mit deinen Kopfhörern hörst, und gleicht Unterschiede aus. " +
            "Die Korrektur gehört zum Profil – am besten je Kopfhörer einmal testen. Kein Ersatz für einen ärztlichen Hörtest."
        ))
        t.addView(ui.wideButton("Hörtest starten", primary = true) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, HearingTestActivity::class.java), REQ_HEARING)
        })
        val del = ui.wideButton("Korrektur löschen") {
            AlertDialog.Builder(this)
                .setMessage("Hörkorrektur aus diesem Profil löschen?")
                .setPositiveButton("Löschen") { _, _ -> update { it.copy(hearing = emptyList()) }; render() }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
        t.addView(del)
        hearingControls?.tag = del
        p.addView(t)
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
        (hearingControls?.tag as? View)?.visibility = if (has) View.VISIBLE else View.GONE
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

    // ----- Kopfhörer -----

    private fun buildPhonesPage(p: LinearLayout) {
        val ae = ui.card("Kopfhörer-Korrektur")
        ae.addView(ui.hint(
            "AutoEq enthält Messungen für tausende Kopfhörer. Die Korrektur lässt dein Modell neutral " +
            "klingen – ein guter Ausgangspunkt für eigene Anpassungen. Wird als ★-Profil gespeichert."
        ))
        ae.addView(ui.linkRow("AutoEq durchsuchen", "Über 8.800 gemessene Modelle") { showAutoEqDialog() })
        ae.addView(ui.linkRow("Aus Datei laden", "ParametricEQ.txt (Equalizer APO)") { importFile() })
        p.addView(ae)

        val au = ui.card("Automatik")
        autoSwitchView = ui.switch("Profil je Kopfhörer automatisch wechseln") { on -> prefs.autoSwitch = on }
        au.addView(autoSwitchView)
        deviceText = ui.valueText().apply { gravity = Gravity.START; setPadding(0, ui.dp(6), 0, ui.dp(2)) }
        au.addView(deviceText)

        au.addView(ui.hint("Profil für dieses Gerät"))
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
        au.addView(deviceSpinner)

        au.addView(ui.hint("Für alle anderen Geräte"))
        defaultSpinner = ui.choice { pos ->
            prefs.assignDevice(DEFAULT_DEVICE_KEY, "Alle anderen Geräte", profileNames.getOrNull(pos - 1))
            render()
        }
        au.addView(defaultSpinner)

        assignBox = ui.vbox()
        au.addView(assignBox)
        au.addView(ui.hint(
            "Für jeden Kopfhörer ein ★-Profil anlegen (auch mit passendem Boost), Kopfhörer verbinden und hier " +
            "das Profil wählen. Beim nächsten Verbinden schaltet der Equalizer automatisch um. „Für alle anderen " +
            "Geräte“ schützt davor, dass ein hoher Boost versehentlich auf lauten Kopfhörern landet."
        ))
        p.addView(au)
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
        if (list.isNotEmpty()) assignBox.addView(ui.divider())
        list.forEach { a ->
            val label = TextView(this).apply {
                text = "${a.label}  →  ${PROFILE_PREFIX}${a.profile}"
                textSize = 13f
                setTextColor(Palette.champagne)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            val remove = ui.iconButton("✕", 34) {
                prefs.assignDevice(a.key, a.label, null)
                renderDevices()
            }.apply { textSize = 13f }
            assignBox.addView(ui.row(label, remove))
        }
    }

    // ----- Profile -----

    private fun buildProfilesPage(p: LinearLayout) {
        val c = ui.card("Aktuelles Profil")
        c.addView(ui.wideButton("Speichern als …", primary = true) {
            askText("Neues Profil", "", "z. B. Sony-Kopfhörer, Auto, Podcast …") { name ->
                prefs.saveProfile(name)
                prefs.presetName = PROFILE_PREFIX + name
                render()
                ui.toast("Profil „$name“ gespeichert")
            }
        })
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
        c.addView(ui.row(renameBtn, deleteBtn).apply { setPadding(0, ui.dp(8), 0, 0) })
        c.addView(ui.hint("Ein Profil enthält alles: EQ-Art, Bänder, Filter, Boost, Limiter, Balance. Änderungen an einem ★-Profil werden automatisch gespeichert."))
        p.addView(c)

        val io = ui.card("Sichern & Übertragen")
        io.addView(ui.linkRow("Exportieren", "Alle ★-Profile als JSON-Datei") { exportProfiles() })
        io.addView(ui.linkRow("Importieren", "Profile oder ParametricEQ.txt laden") { importFile() })
        p.addView(io)

        val r = ui.card("Zurücksetzen")
        r.addView(ui.hint("Setzt Bänder, Filter, Boost und alle Klangeinstellungen auf neutral."))
        r.addView(ui.wideButton("Alles zurücksetzen") {
            AlertDialog.Builder(this)
                .setMessage("Alle Klangeinstellungen auf neutral setzen?")
                .setPositiveButton("Zurücksetzen") { _, _ ->
                    pushUndo(prefs.state)
                    prefs.state = EqState(mode = st.mode, bands = IntArray(bandCount))
                    prefs.presetName = FLAT
                    render()
                    EqService.instance?.applyAll()
                    ui.toast("Zurückgesetzt")
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        })
        p.addView(r)
    }

    // ----- Einstellungen -----

    private fun buildSettingsPage(p: LinearLayout) {
        val w = ui.card("Wirkung")
        globalSwitch = ui.switch("Globaler Modus (alle Apps)") { on ->
            prefs.global = on
            EqService.instance?.refreshGlobal()
            updateTiles()
        }
        w.addView(globalSwitch)
        w.addView(ui.hint(
            "Wirkt auf die gesamte Audioausgabe. Funktioniert nicht auf jedem Gerät – dann in der " +
            "Musik-App den Equalizer einschalten (z. B. Spotify → Einstellungen → Wiedergabe → Equalizer) und diese App wählen."
        ))
        p.addView(w)

        val bg = ui.card("Im Hintergrund")
        bg.addView(ui.linkRow("Akku-Optimierung ausschalten", "Damit Android den Equalizer nicht beendet") {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (e: Exception) { ui.toast("Einstellung nicht gefunden") }
        })
        iconSwitch = ui.switch("Symbol in der Statusleiste") { on ->
            prefs.showIcon = on
            EqService.instance?.refreshNotification()
        }
        bg.addView(iconSwitch)
        bg.addView(ui.linkRow("Benachrichtigungen", "Ganz ausblenden – der Equalizer läuft weiter") {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        })
        p.addView(bg)

        val u = ui.card("App-Update")
        updateInfo = ui.hint("").apply { textSize = 14f; setTextColor(Palette.champagne) }
        u.addView(updateInfo)
        u.addView(ui.linkRow("Nach Update suchen", "Neueste Version von GitHub laden und installieren") { updater.checkNow() })
        u.addView(ui.hint("Quelle: github.com/${Updater.REPO} (öffentlich, kein Zugang nötig)"))
        autoUpdateSwitch = ui.switch("Beim Öffnen automatisch prüfen") { on ->
            Updater.setAutoCheck(this, on)
            if (on) updater.checkQuietly()
        }
        u.addView(autoUpdateSwitch)
        p.addView(u)

        val q = ui.card("Schnellzugriff")
        q.addView(ui.hint(
            "Schnelleinstellungen: Leiste ganz herunterziehen → Bearbeiten (Stift) → die Kacheln „Equalizer“ " +
            "(an/aus) und „EQ-Profil“ (nächstes ★-Profil) hinzufügen. Dazu gibt es ein Homescreen-Widget " +
            "und in der Benachrichtigung „Nächstes Profil“ und „Ausschalten“."
        ))
        p.addView(q)
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
        updateTiles()
    }

    /** Gespeicherten Zustand komplett in die Bedienelemente übertragen. */
    private fun render() {
        val was = ui.silent
        ui.silent = true
        normalizeBands()
        val s = prefs.state
        renderPower()
        globalSwitch.isChecked = prefs.global
        autoSwitchView.isChecked = prefs.autoSwitch
        iconSwitch.isChecked = prefs.showIcon

        val param = s.mode == MODE_PARAMETRIC && dpSupported
        ui.setActive(modeGraphicBtn, !param)
        ui.setActive(modeParamBtn, param)
        graphicBox.visibility = if (param) View.GONE else View.VISIBLE
        curve.visibility = if (param) View.VISIBLE else View.GONE
        paramBox.visibility = if (param) View.VISIBLE else View.GONE
        filterInfoRow.visibility = if (param) View.VISIBLE else View.GONE
        graphicHint.visibility = if (param) View.GONE else View.VISIBLE
        soundTitle.text = (if (param) "Klangkurve" else "Frequenzbänder").uppercase()

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
        updateTiles()
        renderUpdate()
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
        statusText.text = if (s == null) "Ausgeschaltet  ·  $dev" else {
            val sessions = s.activeSessions
            val apps = sessions.count { it != 0 }
            val where = when {
                0 in sessions -> "systemweit"
                apps == 1 -> "1 App"
                apps > 1 -> "$apps Apps"
                else -> "wartet auf Musik-App"
            }
            "Aktiv · $where  ·  $dev"
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
        if (requestCode == REQ_HEARING || requestCode == REQ_ROOM) {
            if (resultCode == RESULT_OK) { clearHistory(); selFilter = 0 }
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
        private const val NO_PAGE = -1
        private const val PAGE_EQ = 1
        private const val PAGE_VOLUME = 2
        private const val PAGE_HEARING = 3
        private const val PAGE_PHONES = 4
        private const val PAGE_PROFILES = 5
        private const val PAGE_SETTINGS = 6
        private const val PAGE_UPDATE = 8           // keine Unterseite – sucht direkt nach einem Update
        private const val PAGE_ROOM = 7             // keine Unterseite – öffnet die Mess-Ansicht
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
        private const val REQ_ROOM = 14
        private const val HISTORY_GAP_MS = 700L
        private const val HISTORY_MAX = 60
        private const val HEARING_MAX = 150
    }
}
