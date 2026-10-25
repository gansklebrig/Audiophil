package de.eq.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

const val MODE_GRAPHIC = 0
const val MODE_PARAMETRIC = 1

/** Kanal eines Filters: beide Seiten, nur links, nur rechts. */
const val CH_BOTH = 0
const val CH_LEFT = 1
const val CH_RIGHT = 2
val CHANNEL_NAMES = listOf("Beide Seiten", "Nur links", "Nur rechts")

/** Filtertypen des parametrischen EQ. */
object FilterType {
    const val PEAK = 0
    const val LOW_SHELF = 1
    const val HIGH_SHELF = 2
    const val LOW_PASS = 3
    const val HIGH_PASS = 4

    val names = listOf("Glocke (Peak)", "Low-Shelf (Bass)", "High-Shelf (Höhen)", "Tiefpass", "Hochpass")

    /** Tief-/Hochpass haben keine Verstärkung, nur Frequenz und Güte. */
    fun hasGain(type: Int) = type == PEAK || type == LOW_SHELF || type == HIGH_SHELF
}

/** Ein Filter des parametrischen EQ. Frequenz in Hz, Verstärkung in dB, Güte Q, Kanal (CH_*). */
data class PFilter(
    val type: Int = FilterType.PEAK,
    val freq: Float = 1000f,
    val gain: Float = 0f,
    val q: Float = 1f,
    val on: Boolean = true,
    val channel: Int = CH_BOTH,
) {
    /** Wirkt dieser Filter auf Kanal [ch] (0 = links, 1 = rechts)? */
    fun appliesTo(ch: Int) = channel == CH_BOTH || channel == ch + 1

    fun toJson(): JSONObject = JSONObject()
        .put("type", type)
        .put("freq", freq.toDouble())
        .put("gain", gain.toDouble())
        .put("q", q.toDouble())
        .put("on", on)
        .put("ch", channel)

    companion object {
        fun fromJson(o: JSONObject) = PFilter(
            o.optInt("type", FilterType.PEAK),
            o.optDouble("freq", 1000.0).toFloat(),
            o.optDouble("gain", 0.0).toFloat(),
            o.optDouble("q", 1.0).toFloat(),
            o.optBoolean("on", true),
            o.optInt("ch", CH_BOTH),
        )
    }
}

private fun filtersToJson(list: List<PFilter>) = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
private fun filtersFromJson(a: JSONArray?): List<PFilter>? =
    a?.let { arr -> (0 until arr.length()).map { PFilter.fromJson(arr.getJSONObject(it)) } }

/**
 * Kompletter Klangzustand – genau das, was in einem ★-Profil gespeichert wird.
 *  bands/bass gehören zum Band-EQ, filters/preamp zum parametrischen EQ,
 *  alles andere gilt für beide.
 */
data class EqState(
    val mode: Int = MODE_GRAPHIC,
    val bands: IntArray = IntArray(0),          // Millibel je Geräteband
    val bass: Int = 0,                          // 0..1000
    val loudness: Int = 0,                      // Millibel, 0..1500
    val filters: List<PFilter> = DEFAULT_FILTERS,
    val preamp: Float = 0f,                     // dB
    val limiter: Boolean = true,
    val limiterThreshold: Float = -1f,          // dB
    val balance: Int = 0,                       // -100 (nur links) .. +100 (nur rechts)
    val hearing: List<PFilter> = emptyList(),   // Hörkorrektur aus dem Hörtest (je Ohr)
    val hearingOn: Boolean = true,
    val hearingAmount: Int = 100,               // Stärke der Hörkorrektur in %
    val hearingDate: Long = 0L,
    val loudComp: Int = 0,                      // Lautheitsausgleich 0..100 %
    val night: Int = 0,                         // Nachtmodus / Kompressor 0..100 %
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("bands", JSONArray(bands.toList()))
        put("bass", bass)
        put("loudness", loudness)
        put("filters", filtersToJson(filters))
        put("preamp", preamp.toDouble())
        put("limiter", limiter)
        put("limiterThreshold", limiterThreshold.toDouble())
        put("balance", balance)
        put("hearing", filtersToJson(hearing))
        put("hearingOn", hearingOn)
        put("hearingAmount", hearingAmount)
        put("hearingDate", hearingDate)
        put("loudComp", loudComp)
        put("night", night)
    }

    /** Ist eine Hörkorrektur vorhanden und eingeschaltet? */
    val hearingActive get() = hearingOn && hearing.isNotEmpty() && hearingAmount > 0

    companion object {
        val DEFAULT_FILTERS = listOf(
            PFilter(FilterType.LOW_SHELF, 105f, 0f, 0.7f),
            PFilter(FilterType.PEAK, 250f, 0f, 1f),
            PFilter(FilterType.PEAK, 1000f, 0f, 1f),
            PFilter(FilterType.PEAK, 4000f, 0f, 1f),
            PFilter(FilterType.HIGH_SHELF, 10000f, 0f, 0.7f),
        )

        /** Liest auch Profile älterer App-Versionen (fehlende Felder bekommen Standardwerte). */
        fun fromJson(o: JSONObject): EqState {
            val b = o.optJSONArray("bands") ?: JSONArray()
            return EqState(
                mode = o.optInt("mode", MODE_GRAPHIC),
                bands = IntArray(b.length()) { b.optInt(it) },
                bass = o.optInt("bass", 0),
                loudness = o.optInt("loudness", 0),
                filters = filtersFromJson(o.optJSONArray("filters")) ?: DEFAULT_FILTERS,
                preamp = o.optDouble("preamp", 0.0).toFloat(),
                limiter = o.optBoolean("limiter", true),
                limiterThreshold = o.optDouble("limiterThreshold", -1.0).toFloat(),
                balance = o.optInt("balance", 0),
                hearing = filtersFromJson(o.optJSONArray("hearing")) ?: emptyList(),
                hearingOn = o.optBoolean("hearingOn", true),
                hearingAmount = o.optInt("hearingAmount", 100),
                hearingDate = o.optLong("hearingDate", 0L),
                loudComp = o.optInt("loudComp", 0),
                night = o.optInt("night", 0),
            )
        }
    }
}

data class Profile(val name: String, val state: EqState) {
    fun toJson(): JSONObject = state.toJson().put("name", name)

    companion object {
        fun fromJson(o: JSONObject) = Profile(o.getString("name"), EqState.fromJson(o))
    }
}

/**
 * Mathematik des parametrischen EQ.
 *
 * Jeder Filter wird als klassischer Biquad (RBJ Audio-EQ-Cookbook) berechnet.
 * Android bietet systemweit keinen echten parametrischen EQ, aber mit DynamicsProcessing
 * einen EQ mit vielen frei platzierbaren Bändern je Kanal. Wir berechnen deshalb die exakte
 * Gesamtkurve und tasten sie auf diese feinen Bänder ab.
 */
object ParamEq {
    private const val FS = 48000.0

    /** Verstärkung eines einzelnen Filters bei [freq] Hz, in dB. */
    /** Biquad-Koeffizienten (b0, b1, b2, a0, a1, a2) nach dem RBJ-Cookbook für Abtastrate [fs]. */
    fun coeffs(f: PFilter, fs: Double = FS): DoubleArray {
        val w0 = 2 * PI * f.freq.toDouble().coerceIn(10.0, fs * 0.48) / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * f.q.toDouble().coerceAtLeast(0.05))
        val a = 10.0.pow(f.gain / 40.0)
        val sa = 2 * sqrt(a) * alpha

        val b0: Double; val b1: Double; val b2: Double
        val a0: Double; val a1: Double; val a2: Double
        when (f.type) {
            FilterType.LOW_SHELF -> {
                b0 = a * ((a + 1) - (a - 1) * cw + sa)
                b1 = 2 * a * ((a - 1) - (a + 1) * cw)
                b2 = a * ((a + 1) - (a - 1) * cw - sa)
                a0 = (a + 1) + (a - 1) * cw + sa
                a1 = -2 * ((a - 1) + (a + 1) * cw)
                a2 = (a + 1) + (a - 1) * cw - sa
            }
            FilterType.HIGH_SHELF -> {
                b0 = a * ((a + 1) + (a - 1) * cw + sa)
                b1 = -2 * a * ((a - 1) + (a + 1) * cw)
                b2 = a * ((a + 1) + (a - 1) * cw - sa)
                a0 = (a + 1) - (a - 1) * cw + sa
                a1 = 2 * ((a - 1) - (a + 1) * cw)
                a2 = (a + 1) - (a - 1) * cw - sa
            }
            FilterType.LOW_PASS -> {
                b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2
                a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
            }
            FilterType.HIGH_PASS -> {
                b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2
                a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
            }
            else -> { // PEAK
                b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a
            }
        }
        return doubleArrayOf(b0, b1, b2, a0, a1, a2)
    }

    fun filterDb(f: PFilter, freq: Double): Double {
        if (!f.on) return 0.0
        val c = coeffs(f, FS)
        val b0 = c[0]; val b1 = c[1]; val b2 = c[2]; val a0 = c[3]; val a1 = c[4]; val a2 = c[5]
        val w = 2 * PI * freq / FS
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = a0 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        val num = (nr * nr + ni * ni).coerceAtLeast(1e-20)
        val den = (dr * dr + di * di).coerceAtLeast(1e-20)
        return 10 * log10(num / den)
    }

    /** Summe einer Filterliste auf Kanal [ch] (0 = links, 1 = rechts). */
    fun sumDb(filters: List<PFilter>, freq: Double, ch: Int): Double =
        filters.sumOf { if (it.appliesTo(ch)) filterDb(it, freq) else 0.0 }

    /** Kurve des parametrischen EQ (Vorverstärkung + Filter) auf Kanal [ch]. */
    fun response(s: EqState, freq: Double, ch: Int = 0): Double =
        s.preamp + sumDb(s.filters, freq, ch)

    /** Unterscheiden sich linker und rechter Kanal? */
    fun isStereo(filters: List<PFilter>) = filters.any { it.channel != CH_BOTH }

    /**
     * Lautheitsausgleich: Bei leiser Wiedergabe hört das Ohr Bass und Höhen schwächer
     * (Kurven gleicher Lautstärke, ISO 226). [volume] = Handy-Lautstärke 0..1.
     */
    fun loudnessFilters(strength: Int, volume: Float): List<PFilter> {
        val k = (strength / 100f) * (1f - volume.coerceIn(0f, 1f))
        if (k <= 0.001f) return emptyList()
        return listOf(
            PFilter(FilterType.LOW_SHELF, 120f, MAX_LOUD_BASS * k, 0.7f),
            PFilter(FilterType.HIGH_SHELF, 9000f, MAX_LOUD_TREBLE * k, 0.7f),
        )
    }
    const val MAX_LOUD_BASS = 12f
    const val MAX_LOUD_TREBLE = 4f

    /**
     * Gesamte Zusatzkurve, die über DynamicsProcessing läuft, für Kanal [ch]:
     * parametrischer EQ (falls gewählt) + Hörkorrektur + Lautheitsausgleich.
     */
    fun totalDb(s: EqState, freq: Double, ch: Int, parametric: Boolean, loud: List<PFilter>): Double {
        var g = 0.0
        if (parametric) g += response(s, freq, ch)
        if (s.hearingActive) g += sumDb(s.hearing, freq, ch) * s.hearingAmount / 100.0
        if (loud.isNotEmpty()) g += sumDb(loud, freq, ch)
        return g
    }

    /** Obere Grenzfrequenzen von [n] logarithmisch verteilten Bändern zwischen 20 Hz und 20 kHz. */
    fun cutoffs(n: Int) = FloatArray(n) { (20.0 * 1000.0.pow((it + 1).toDouble() / n)).toFloat() }

    /** Geometrische Bandmitten zu [cut]. */
    fun centers(cut: FloatArray) = DoubleArray(cut.size) { i ->
        val lo = if (i == 0) cut[0] * cut[0] / cut[1] else cut[i - 1]
        sqrt(lo.toDouble() * cut[i])
    }

    /** Verstärkung je Band für Kanal [ch]. */
    fun bandGains(s: EqState, cut: FloatArray, ch: Int, parametric: Boolean, loud: List<PFilter>): FloatArray {
        val c = centers(cut)
        return FloatArray(cut.size) { i -> totalDb(s, c[i], ch, parametric, loud).toFloat().coerceIn(-30f, 30f) }
    }
}

/**
 * Auswertung des Hörtests.
 *
 * Gemessen wird die Hörschwelle je Frequenz und Ohr (in dBFS, über die eigenen Kopfhörer).
 * Davon wird die normale Hörschwelle (ISO 226) abgezogen; als Bezug dient der Median aller Werte,
 * damit nur die Unterschiede zwischen Frequenzen und Ohren zählen – nicht die absolute Lautstärke.
 * Ausgeglichen wird nach der „Halbe-Verstärkung-Regel“ aus der Hörgeräteanpassung,
 * begrenzt auf −6 … +12 dB.
 */
object HearingFit {
    val FREQS = floatArrayOf(1000f, 2000f, 3000f, 4000f, 6000f, 8000f, 500f, 250f, 125f)

    /** Normale Ruhehörschwelle nach ISO 226:2003 (dB SPL), für die Testfrequenzen. */
    private val normal = mapOf(
        125f to 22.1f, 250f to 11.4f, 500f to 4.4f, 1000f to 2.4f, 2000f to -1.3f,
        3000f to -5.5f, 4000f to -5.4f, 6000f to 4.3f, 8000f to 12.6f,
    )

    /** Abweichung je Frequenz/Ohr in dB (positiv = schlechter als der eigene Durchschnitt). */
    fun deviations(left: Map<Float, Float>, right: Map<Float, Float>): Pair<Map<Float, Float>, Map<Float, Float>> {
        fun rel(m: Map<Float, Float>) = m.mapValues { (f, t) -> t - (normal[f] ?: 0f) }
        val l = rel(left); val r = rel(right)
        val all = (l.values + r.values).sorted()
        if (all.isEmpty()) return l to r
        val median = if (all.size % 2 == 1) all[all.size / 2] else (all[all.size / 2 - 1] + all[all.size / 2]) / 2
        return l.mapValues { it.value - median } to r.mapValues { it.value - median }
    }

    /** Korrekturfilter je Ohr, so angepasst, dass die Summe der Glockenfilter den Zielwerten folgt. */
    fun correction(left: Map<Float, Float>, right: Map<Float, Float>): List<PFilter> {
        val (dl, dr) = deviations(left, right)
        return fitEar(dl, CH_LEFT) + fitEar(dr, CH_RIGHT)
    }

    private fun fitEar(dev: Map<Float, Float>, channel: Int): List<PFilter> {
        val allFreqs = dev.keys.sorted()
        if (allFreqs.isEmpty()) return emptyList()
        val targetOf = allFreqs.associateWith { (dev.getValue(it) * 0.5f).coerceIn(-6f, 12f) }

        // Überlappende Glocken addieren sich – Verstärkungen schrittweise nachführen,
        // bis die Summe an allen Messpunkten dem Ziel entspricht.
        fun fit(freqs: List<Float>, start: List<Float>): List<Float> {
            val gains = start.toMutableList()
            repeat(12) {
                val fl = freqs.mapIndexed { i, f -> peak(f, gains[i], channel) }
                for (i in freqs.indices) {
                    val actual = fl.sumOf { ParamEq.filterDb(it, freqs[i].toDouble()) }.toFloat()
                    gains[i] = (gains[i] + (targetOf.getValue(freqs[i]) - actual) * 0.8f).coerceIn(-9f, 15f)
                }
            }
            return gains
        }

        val first = fit(allFreqs, allFreqs.map { targetOf.getValue(it) })
        // Unbedeutende Filter weglassen und den Rest noch einmal genau einpassen
        val kept = allFreqs.filterIndexed { i, _ -> abs(first[i]) >= 0.3f }
        if (kept.isEmpty()) return emptyList()
        val gains = fit(kept, kept.map { f -> first[allFreqs.indexOf(f)] })
        return kept.mapIndexed { i, f -> peak(f, (gains[i] * 10).roundToInt() / 10f, channel) }
    }

    private fun peak(f: Float, gain: Float, channel: Int) =
        PFilter(FilterType.PEAK, f, gain, if (f <= 125f) 1.0f else 1.4f, true, channel)
}
