package de.eq.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import kotlin.math.log10

/**
 * Hintergrund-Dienst, der die Klang-Effekte am Leben hält.
 *
 * Zwei Wege, um fremde Apps zu beeinflussen:
 *  1. Globaler Modus: Effekt auf Audio-Session 0 (= gemischte Gesamtausgabe).
 *  2. Session-Modus: Musik-Apps melden ihre Session per Broadcast.
 *
 * Effekte je Session:
 *  - Equalizer + BassBoost → Band-EQ
 *  - DynamicsProcessing (ab Android 9) → parametrischer EQ je Kanal, Hörkorrektur,
 *    Lautheitsausgleich, Nachtmodus (Kompressor), Lautstärke-Boost, Balance, Limiter
 *  - LoudnessEnhancer → Lautstärke-Boost auf älteren Geräten
 */
class EqService : Service() {

    private class Fx(
        val eq: Equalizer?,
        val bass: BassBoost?,
        val dp: DynamicsProcessing?,
        val cutoffs: FloatArray,
        val hasMbc: Boolean,
        val loud: LoudnessEnhancer?,
    )

    private val effects = LinkedHashMap<Int, Fx>()   // in Reihenfolge des Hinzufügens
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var applyPending = false

    /** Aktuell erkanntes Ausgabegerät (Kopfhörer, Lautsprecher …). */
    var currentDevice: OutDevice? = null
        private set

    /** Vorher/Nachher-Vergleich und Hörtest schalten Effekte vorübergehend ab. */
    var bypass = BYPASS_NONE
        private set

    /** Dynamisch registriert, weil Android 8+ implizite Broadcasts an Manifest-Receiver oft nicht zustellt. */
    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = handleSessionIntent(i)
    }

    /** Handy-Lautstärke geändert → Lautheitsausgleich nachführen. */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // nur die Medien-Lautstärke ist relevant (nicht Klingelton, Wecker …)
            val stream = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC)
            if (stream != AudioManager.STREAM_MUSIC) return
            if (prefs.state.loudComp > 0) applyAll()
            volumeListener?.invoke()
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
            AudioDevices.noteAdded(added)
            onDevicesChanged()
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = onDevicesChanged()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audio = getSystemService(AudioManager::class.java)
        instance = this
        currentDevice = AudioDevices.current(this)
        startInForeground()
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        val volFilter = IntentFilter(VOLUME_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sessionReceiver, filter, RECEIVER_EXPORTED)
            registerReceiver(volumeReceiver, volFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(sessionReceiver, filter)
            registerReceiver(volumeReceiver, volFilter)
        }
        refreshGlobal()
        // meldet sich sofort mit allen verbundenen Geräten und danach bei jeder Änderung
        audio.registerAudioDeviceCallback(deviceCallback, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.enabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NEXT_PROFILE -> {
                val next = prefs.nextProfile()
                if (next == null) toast("Noch keine ★-Profile angelegt")
                else { applyNow(); toast("Profil: $next"); profileSwitchListener?.invoke() }
                notifyUi()
            }
        }
        if (!prefs.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.let { handleSessionIntent(it) }
        return START_STICKY
    }

    private fun handleSessionIntent(i: Intent) {
        val session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (session <= 0) return
        when (i.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> addSession(session)
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> removeSession(session)
        }
    }

    // ---------- Kopfhörer-Automatik ----------

    private fun onDevicesChanged() {
        val d = AudioDevices.current(this)
        currentDevice = d
        if (d.key != prefs.lastDeviceKey) {
            prefs.lastDeviceKey = d.key
            if (prefs.autoSwitch) {
                val target = prefs.profileForDevice(d.key)
                if (target != null && target != prefs.currentProfileName() && prefs.loadProfile(target)) {
                    applyNow()
                    toast("„$target“ für ${d.label} aktiviert")
                    profileSwitchListener?.invoke()
                }
            }
        }
        notifyUi()
    }

    // ---------- Effekte verwalten ----------

    fun addSession(session: Int) {
        if (effects.containsKey(session)) return
        // Im globalen Modus wirkt der Effekt schon auf die Gesamtausgabe – zusätzlich auf
        // die Session einer Musik-App angewendet, würde alles doppelt verstärkt.
        if (session > 0 && prefs.global && effects.containsKey(0)) return
        // Apps, die abstürzen, melden ihre Session nie ab → nur die neuesten behalten
        val apps = effects.keys.filter { it > 0 }
        if (session > 0 && apps.size >= MAX_APP_SESSIONS) removeSession(apps.first())
        val eq = try { Equalizer(PRIORITY, session) } catch (e: Exception) { null }
        val bass = try { BassBoost(PRIORITY, session) } catch (e: Exception) { null }
        var dp: DynamicsProcessing? = null
        var cutoffs = FloatArray(0)
        var hasMbc = false
        if (Build.VERSION.SDK_INT >= 28) {
            // Bevorzugt mit Kompressor (Nachtmodus); falls das Gerät ablehnt, ohne
            val attempts = listOf(DP_BANDS to true, DP_BANDS_FALLBACK to true, DP_BANDS to false, DP_BANDS_FALLBACK to false)
            for ((n, mbc) in attempts) {
                try {
                    val cfg = DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        true, n,                    // Pre-EQ: parametrischer EQ, Hörkorrektur, Lautheit
                        mbc, if (mbc) 1 else 0,     // Kompressor (ein Band = Breitband)
                        false, 0,                   // Post-EQ
                        true,                       // Limiter
                    // 40 ms: ergibt ~23-Hz-Raster im Bass (bei 10 ms nur ~94 Hz – schmale Bass-Filter gingen verloren)
                    ).setPreferredFrameDuration(40f).build()
                    dp = DynamicsProcessing(PRIORITY, session, cfg)
                    cutoffs = ParamEq.cutoffs(n)
                    hasMbc = mbc
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "DynamicsProcessing ($n Bänder, Kompressor=$mbc) nicht möglich: ${e.message}")
                }
            }
        }
        val loud = if (dp == null) try { LoudnessEnhancer(session) } catch (e: Exception) { null } else null

        if (eq == null && dp == null) {
            Log.w(TAG, "Session $session: keine Effekte möglich")
            bass?.release(); loud?.release()
            notifyUi()
            return
        }
        val fx = Fx(eq, bass, dp, cutoffs, hasMbc, loud)
        effects[session] = fx
        // Übernimmt eine andere Equalizer-App kurz die Kontrolle, danach die Einstellungen neu setzen
        listOfNotNull<AudioEffect>(eq, bass, dp, loud).forEach { e ->
            try {
                e.setControlStatusListener { _, granted ->
                    if (granted) handler.post { effects[session]?.let { applyTo(it, prefs.state) } }
                }
            } catch (_: Exception) {}
        }
        applyTo(fx, prefs.state)
        Log.i(TAG, "Session $session übernommen (EQ=${eq != null}, DP=${dp != null}, MBC=$hasMbc)")
        notifyUi()
    }

    fun removeSession(session: Int) {
        effects.remove(session)?.let { release(it) }
        notifyUi()
    }

    /** Globalen Effekt (Session 0) je nach Einstellung an- oder abschalten. */
    fun refreshGlobal() {
        if (prefs.global) {
            addSession(0)
            // klappt der globale Effekt, die App-Sessions freigeben (sonst wirkt alles doppelt)
            if (effects.containsKey(0)) effects.keys.filter { it > 0 }.forEach { removeSession(it) }
        } else removeSession(0)
    }

    /** Einstellungen anwenden – gebündelt, damit schnelles Schieben flüssig bleibt. */
    fun applyAll() {
        if (applyPending) return
        applyPending = true
        handler.postDelayed({ applyPending = false; applyNow() }, 30)
    }

    /** Einstellungen sofort anwenden. */
    fun applyNow() {
        val s = prefs.state
        effects.values.forEach { applyTo(it, s) }
        scheduleWidgetUpdate()
    }

    /** Vorher/Nachher bzw. Hörtest: Effekte vorübergehend umgehen. */
    fun setBypass(level: Int) {
        if (bypass == level) return
        bypass = level
        applyNow()
    }

    /** Handy-Lautstärke 0..1 (für den Lautheitsausgleich). */
    fun musicVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun applyTo(fx: Fx, stored: EqState) {
        if (bypass == BYPASS_ALL) {
            disableAll(fx)
            return
        }
        // Vorher/Nachher: nur die Klangfärbung weglassen – Lautstärke, Balance und Limiter bleiben,
        // damit der Vergleich nicht durch einen Lautstärkesprung verfälscht wird.
        val s = if (bypass == BYPASS_EQ) neutral(stored) else stored
        // Ist der parametrische EQ gewählt, aber auf dieser Session nicht verfügbar,
        // bleibt der Band-EQ trotzdem aus – sonst klänge es nach einem anderen Profil.
        val parametric = s.mode == MODE_PARAMETRIC
        // Jeder Effekt einzeln: scheitert einer (z. B. weil eine andere App ihn gerade steuert),
        // werden die übrigen trotzdem gesetzt.
        guard("Equalizer") {
            fx.eq?.let { eq ->
                if (!parametric) {
                    val n = eq.numberOfBands.toInt()
                    val lo = eq.bandLevelRange[0].toInt(); val hi = eq.bandLevelRange[1].toInt()
                    for (b in 0 until minOf(n, s.bands.size)) eq.setBandLevel(b.toShort(), s.bands[b].coerceIn(lo, hi).toShort())
                }
                eq.enabled = !parametric
            }
        }
        guard("BassBoost") {
            fx.bass?.let {
                if (it.strengthSupported) it.setStrength(s.bass.coerceIn(0, 1000).toShort())
                it.enabled = !parametric && s.bass > 0
            }
        }
        guard("DynamicsProcessing") { fx.dp?.let { applyDynamics(it, fx, s, parametric) } }
        guard("LoudnessEnhancer") {
            fx.loud?.let {
                it.setTargetGain(s.loudness.coerceIn(0, 1500))
                it.enabled = s.loudness > 0
            }
        }
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w(TAG, "$what: Anwenden fehlgeschlagen: ${e.message}") }
    }

    /** Zustand ohne Klangfärbung (für den Vorher/Nachher-Vergleich). */
    private fun neutral(s: EqState) = s.copy(
        // Im parametrischen Modus bleibt die Vorverstärkung (z. B. −6 dB von AutoEq) –
        // sonst wäre „Vorher“ lauter und der Vergleich unfair.
        bands = IntArray(s.bands.size), bass = 0, filters = emptyList(),
        hearingOn = false, loudComp = 0, night = 0,
    )

    private fun disableAll(fx: Fx) {
        try { fx.eq?.enabled = false } catch (_: Exception) {}
        try { fx.bass?.enabled = false } catch (_: Exception) {}
        try { fx.dp?.enabled = false } catch (_: Exception) {}
        try { fx.loud?.enabled = false } catch (_: Exception) {}
    }

    private fun applyDynamics(dp: DynamicsProcessing, fx: Fx, s: EqState, parametric: Boolean) {
        if (Build.VERSION.SDK_INT < 28) return
        val cutoffs = fx.cutoffs
        val loud = ParamEq.loudnessFilters(s.loudComp, musicVolume())
        val eqActive = parametric || s.hearingActive || loud.isNotEmpty()

        // Pre-EQ je Kanal: parametrischer EQ + Hörkorrektur + Lautheitsausgleich
        for (ch in 0..1) {
            val gains = if (eqActive) ParamEq.bandGains(s, cutoffs, ch, parametric, loud) else null
            val eq = DynamicsProcessing.Eq(true, eqActive, cutoffs.size)
            for (i in cutoffs.indices) {
                eq.setBand(i, DynamicsProcessing.EqBand(true, cutoffs[i], gains?.get(i) ?: 0f))
            }
            dp.setPreEqByChannelIndex(ch, eq)
        }

        // Nachtmodus: Breitband-Kompressor – leise Stellen lauter, Spitzen leiser
        if (fx.hasMbc) {
            val n = s.night / 100f
            val threshold = -10f - 30f * n
            val ratio = 1.5f + 4.5f * n
            val makeup = ((-threshold) * (1f - 1f / ratio) * 0.5f).coerceAtMost(12f)
            val band = DynamicsProcessing.MbcBand(
                s.night > 0, 20000f,
                10f, 200f,              // Anschlag, Freigabe (ms)
                ratio, threshold, 6f,   // Verhältnis, Schwelle (dB), Knie (dB)
                -90f, 1f,               // Noise-Gate aus, kein Expander
                0f, if (s.night > 0) makeup else 0f,
            )
            val mbc = DynamicsProcessing.Mbc(true, s.night > 0, 1)
            mbc.setBand(0, band)
            dp.setMbcAllChannelsTo(mbc)
        }

        // Limiter: Anschlag 1 ms, Freigabe 60 ms, Verhältnis 10:1
        dp.setLimiterAllChannelsTo(
            // Verhältnis 50:1 – praktisch eine harte Grenze (bei 10:1 kam Übersteuern noch durch)
            DynamicsProcessing.Limiter(true, s.limiter, 0, 1f, 60f, 50f, s.limiterThreshold, 0f)
        )

        // Lautstärke-Boost + Balance als Eingangsverstärkung je Kanal
        val boost = s.loudness / 100f
        val (left, right) = balanceDb(s.balance)
        dp.setInputGainbyChannel(0, boost + left)
        dp.setInputGainbyChannel(1, boost + right)

        dp.enabled = eqActive || s.limiter || s.loudness > 0 || s.balance != 0 || (fx.hasMbc && s.night > 0)
    }

    /** Balance → Absenkung des jeweils anderen Kanals in dB. */
    private fun balanceDb(b: Int): Pair<Float, Float> {
        fun att(pct: Int) = if (pct >= 100) -60f else (20 * log10(1.0 - pct / 100.0)).toFloat()
        return when {
            b > 0 -> att(b) to 0f      // nach rechts → links leiser
            b < 0 -> 0f to att(-b)     // nach links → rechts leiser
            else -> 0f to 0f
        }
    }

    private fun release(fx: Fx) {
        try { fx.eq?.enabled = false; fx.eq?.release() } catch (_: Exception) {}
        try { fx.bass?.enabled = false; fx.bass?.release() } catch (_: Exception) {}
        try { fx.dp?.enabled = false; fx.dp?.release() } catch (_: Exception) {}
        try { fx.loud?.enabled = false; fx.loud?.release() } catch (_: Exception) {}
    }

    val activeSessions: List<Int> get() = effects.keys.sorted()

    /** Kann der Nachtmodus genutzt werden (Kompressor auf mindestens einer Session)? */
    val nightSupported: Boolean get() = effects.values.any { it.hasMbc }

    /** Oberfläche, Benachrichtigung, Kacheln und Widget aktualisieren. */
    fun notifyUi() {
        uiListener?.invoke()
        updateNotification()
        updateTiles(this)
        scheduleWidgetUpdate()
    }

    private val widgetUpdate = Runnable { EqWidget.updateAll(this) }
    private fun scheduleWidgetUpdate() {
        handler.removeCallbacks(widgetUpdate)
        handler.postDelayed(widgetUpdate, 400)
    }

    // ---------- Benachrichtigung ----------

    private fun ensureChannels(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CH_VISIBLE, "Equalizer (mit Symbol)", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_QUIET, "Equalizer (ohne Symbol)", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
        nm.deleteNotificationChannel(OLD_CHANNEL)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        ensureChannels(nm)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val next = PendingIntent.getService(
            this, 1, Intent(this, EqService::class.java).setAction(ACTION_NEXT_PROFILE), flags
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, EqService::class.java).setAction(ACTION_STOP), flags
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_eq)

        val profile = prefs.currentProfileName()?.let { "★ $it" } ?: prefs.presetName
        val apps = effects.keys.count { it != 0 }
        val text = buildString {
            currentDevice?.let { append(it.label).append(" · ") }
            append(if (effects.containsKey(0)) "global" else "nur Apps")
            if (apps > 0) append(" · $apps App(s)")
        }
        return Notification.Builder(this, if (prefs.showIcon) CH_VISIBLE else CH_QUIET)
            .setSmallIcon(R.drawable.ic_eq)
            .setContentTitle(profile)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(icon, "Nächstes Profil", next).build())
            .addAction(Notification.Action.Builder(icon, "Ausschalten", stop).build())
            .build()
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Benachrichtigung: ${e.message}")
        }
    }

    /** Nach Umschalten des Statusleisten-Symbols aufrufen. */
    fun refreshNotification() = updateNotification()

    private fun toast(t: String) = handler.post { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { audio.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        try { unregisterReceiver(sessionReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
        effects.values.forEach { release(it) }
        effects.clear()
        instance = null
        uiListener?.invoke()
        updateTiles(this)
        EqWidget.updateAll(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "EqService"
        private const val OLD_CHANNEL = "eq_channel"
        private const val CH_VISIBLE = "eq_status"
        private const val CH_QUIET = "eq_status_quiet"
        private const val NOTIF_ID = 1
        private const val DP_BANDS = 64
        private const val DP_BANDS_FALLBACK = 32
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        /** Hohe Priorität, damit wir Vorrang vor anderen Effekten bekommen. */
        private const val PRIORITY = Int.MAX_VALUE
        private const val MAX_APP_SESSIONS = 8

        const val BYPASS_NONE = 0
        const val BYPASS_EQ = 1     // Vorher/Nachher: Klangfärbung aus, Pegel bleibt
        const val BYPASS_ALL = 2    // Hörtest: alles aus

        const val ACTION_STOP = "de.eq.app.STOP"
        const val ACTION_NEXT_PROFILE = "de.eq.app.NEXT_PROFILE"

        @Volatile var instance: EqService? = null
            private set
        var uiListener: (() -> Unit)? = null
        /** Wird bei Änderung der Handy-Lautstärke aufgerufen. */
        var volumeListener: (() -> Unit)? = null
        /** Wird nach einem automatischen Profilwechsel aufgerufen. */
        var profileSwitchListener: (() -> Unit)? = null

        fun start(context: Context, extra: Intent? = null) {
            val i = Intent(context, EqService::class.java)
            extra?.let { i.action = it.action; i.putExtras(it) }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EqService::class.java))
            updateTiles(context)
            EqWidget.updateAll(context)
        }

        /** Schnelleinstellungs-Kacheln bitten, ihren Zustand neu anzuzeigen. */
        fun updateTiles(context: Context) {
            try {
                TileService.requestListeningState(context, ComponentName(context, EqToggleTile::class.java))
                TileService.requestListeningState(context, ComponentName(context, EqProfileTile::class.java))
            } catch (_: Exception) {}
        }
    }
}
