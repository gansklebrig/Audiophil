package de.eq.app

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Frequenzgang je Band: [db] relativ zum Testsignal, [snr] = Abstand zum Raumgeräusch. */
class RoomResponse(val db: FloatArray, val snr: FloatArray)

/** Ergebnis der automatischen Anpassung. Alle Kurven auf [RoomEq.FREQS]. */
class RoomFit(
    val filters: List<PFilter>,
    val preamp: Float,
    val measured: FloatArray,   // geglättet, auf die Zielkurve ausgerichtet
    val target: FloatArray,
    val corrected: FloatArray,  // Vorhersage: gemessen + Korrektur
    val fLow: Double,           // ungefähre untere Grenzfrequenz der Boxen
) {
    /** Ab hier wird voll korrigiert (darunter nur Absenkungen, den Bass-Abfall der Box lassen wir in Ruhe). */
    val from: Double get() = max(60.0, fLow * 2.0)

    /** Größte Abweichung vom Ziel zwischen [from] und 12 kHz: vorher → nachher. */
    fun deviation(): Pair<Float, Float> {
        val idx = RoomEq.FREQS.indices.filter { RoomEq.FREQS[it] in from..12000.0 }
        fun dev(c: FloatArray) = if (idx.isEmpty()) 0f else idx.maxOf { abs(c[it] - target[it]) }
        return dev(measured) to dev(corrected)
    }

    /** Dieselbe Korrektur mit [pct] Prozent Stärke. */
    fun scaled(pct: Int): RoomFit {
        val k = pct / 100f
        val fl = filters.map { it.copy(gain = (it.gain * k * 10).roundToInt() / 10f) }
        val corr = FloatArray(measured.size) { i -> measured[i] + ParamEq.sumDb(fl, RoomEq.FREQS[i], 0).toFloat() }
        return RoomFit(fl, RoomEq.preampFor(fl), measured, target, corr, fLow)
    }
}

/**
 * Raummessung mit dem Handy-Mikrofon.
 *
 * Über die Boxen läuft rosa Rauschen; das Handy nimmt es am Hörplatz auf. Verglichen werden die
 * Spektren von Aufnahme und abgespieltem Signal (Welch-Verfahren, 32768-Punkte-FFT, 1/12-Oktav-Bänder).
 * Weil nur Spektren verglichen werden, spielt die Verzögerung von Bluetooth keine Rolle.
 *
 * Die Korrektur senkt vor allem Überhöhungen ab (typisch: Raumresonanzen im Bass) und hebt Senken nur
 * vorsichtig an – Auslöschungen im Raum lassen sich mit Anheben nicht beheben, das kostet nur Leistung.
 */
object RoomEq {
    const val SR = 48000
    private const val FFT_N = 32768

    /** Bandmitten: 1/12 Oktave von 20 Hz bis ~20 kHz (121 Punkte). */
    val FREQS = DoubleArray(121) { 20.0 * 2.0.pow(it / 12.0) }

    // ---------- Zielkurven ----------

    val TARGET_NAMES = listOf("Neutral – leicht abfallend (empfohlen)", "Linear (ganz flach)", "Mehr Bass")

    /** Zielkurve in dB. Im Raum klingt ein zu den Höhen hin leicht abfallender Verlauf natürlich. */
    fun target(type: Int, f: Double): Float {
        val tilt = (-0.8 * log2(f / 1000.0)).toFloat().coerceIn(-4f, 4f)
        return when (type) {
            1 -> 0f
            2 -> tilt + ParamEq.filterDb(PFilter(FilterType.LOW_SHELF, 110f, 4f, 0.7f), f).toFloat()
            else -> tilt
        }
    }

    // ---------- Testsignal ----------

    /**
     * Rosa Rauschen (gleiche Energie je Oktave) nach Paul Kellet, mit weicher Ein-/Ausblendung.
     * Effektivwert ca. −15 dBFS, Spitzen begrenzt.
     */
    fun pinkNoise(n: Int, seed: Long = 7L): FloatArray {
        val rnd = Random(seed)
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        val out = FloatArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val w = rnd.nextGaussian() * 0.3
            b0 = 0.99886 * b0 + w * 0.0555179
            b1 = 0.99332 * b1 + w * 0.0750759
            b2 = 0.96900 * b2 + w * 0.1538520
            b3 = 0.86650 * b3 + w * 0.3104856
            b4 = 0.55000 * b4 + w * 0.5329522
            b5 = -0.7616 * b5 - w * 0.0168980
            val p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
            b6 = w * 0.115926
            out[i] = p.toFloat()
            sum += p * p
        }
        val rms = sqrt(sum / n)
        val gain = (0.178 / rms).toFloat()   // −15 dBFS
        val fade = SR / 4
        for (i in 0 until n) {
            var v = (out[i] * gain).coerceIn(-0.95f, 0.95f)
            if (i < fade) v *= i.toFloat() / fade
            if (i > n - fade) v *= (n - i).toFloat() / fade
            out[i] = v
        }
        return out
    }

    // ---------- Spektralanalyse ----------

    private val window: DoubleArray by lazy { DoubleArray(FFT_N) { 0.5 - 0.5 * cos(2 * PI * it / (FFT_N - 1)) } }

    /** Gemitteltes Leistungsspektrum (Welch, Hann-Fenster, 50 % Überlappung) über x[from until to]. */
    fun psd(x: FloatArray, from: Int, to: Int): DoubleArray {
        val n = FFT_N
        val acc = DoubleArray(n / 2 + 1)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        var frames = 0
        var start = from.coerceAtLeast(0)
        val end = to.coerceAtMost(x.size)
        while (start + n <= end) {
            for (i in 0 until n) { re[i] = x[start + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            for (k in 0..n / 2) acc[k] += re[k] * re[k] + im[k] * im[k]
            frames++
            start += n / 2
        }
        if (frames > 0) for (k in acc.indices) acc[k] /= frames.toDouble()
        return acc
    }

    /** Iterative Radix-2-FFT (in place). */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Mittlere Leistung je 1/12-Oktav-Band. */
    fun bands(psd: DoubleArray): DoubleArray {
        val binHz = SR.toDouble() / FFT_N
        return DoubleArray(FREQS.size) { b ->
            val lo = FREQS[b] * 2.0.pow(-1.0 / 24); val hi = FREQS[b] * 2.0.pow(1.0 / 24)
            val k0 = (lo / binHz).toInt() + 1
            val k1 = (hi / binHz).toInt()
            if (k1 >= k0) {
                var s = 0.0
                for (k in k0..k1.coerceAtMost(psd.size - 1)) s += psd[k]
                s / (k1.coerceAtMost(psd.size - 1) - k0 + 1)
            } else psd[(FREQS[b] / binHz).roundToInt().coerceIn(0, psd.size - 1)]
        }
    }

    /**
     * Frequenzgang einer Messung.
     * [rec] = Aufnahme während des Rauschens, [stim] = abgespieltes Signal, [silence] = Aufnahme in Stille.
     */
    fun response(rec: FloatArray, recFrom: Int, recTo: Int, stim: FloatArray, silence: FloatArray): RoomResponse {
        val pr = bands(psd(rec, recFrom, recTo))
        val ps = bands(psd(stim, SR / 2, stim.size - SR / 2))
        val pn = bands(psd(silence, 0, silence.size))
        val db = FloatArray(FREQS.size) { (10 * log10(pr[it].coerceAtLeast(1e-30) / ps[it].coerceAtLeast(1e-30))).toFloat() }
        val snr = FloatArray(FREQS.size) { (10 * log10(pr[it].coerceAtLeast(1e-30) / pn[it].coerceAtLeast(1e-30))).toFloat() }
        return RoomResponse(db, snr)
    }

    /** Breitbandiger Rauschabstand (100 Hz – 5 kHz) in dB. */
    fun broadbandSnr(r: RoomResponse): Float {
        val idx = FREQS.indices.filter { FREQS[it] in 100.0..5000.0 }
        return idx.map { r.snr[it] }.sorted()[idx.size / 2]
    }

    /** Mehrere Messpunkte energetisch mitteln (räumliche Mittelung glättet Raumeinflüsse). */
    fun average(list: List<RoomResponse>): RoomResponse {
        val n = FREQS.size
        val db = FloatArray(n) { i -> (10 * log10(list.sumOf { 10.0.pow(it.db[i] / 10.0) } / list.size)).toFloat() }
        val snr = FloatArray(n) { i -> list.minOf { it.snr[i] } }
        return RoomResponse(db, snr)
    }

    /**
     * Glättung: im tiefen Bass keine (Raumresonanzen sind schmal und sollen voll erfasst werden),
     * im Grundton 1/6 Oktave, darüber 1/3 Oktave (wie das Ohr Klangfarbe wahrnimmt).
     */
    fun smooth(db: FloatArray): FloatArray = FloatArray(db.size) { i ->
        val half = when {
            FREQS[i] < 150.0 -> 0
            FREQS[i] < 500.0 -> 1
            else -> 2
        }
        var s = 0.0; var c = 0
        for (j in (i - half)..(i + half)) if (j in db.indices) { s += 10.0.pow(db[j] / 10.0); c++ }
        (10 * log10(s / c)).toFloat()
    }

    // ---------- Korrektur berechnen ----------

    private const val MAX_CUT = 12f
    private const val MAX_BOOST = 6f
    private const val MAX_FILTERS = 10

    fun fit(r: RoomResponse, targetType: Int): RoomFit {
        val n = FREQS.size
        val m = smooth(r.db)
        val t = FloatArray(n) { target(targetType, FREQS[it]) }

        // Bezugspegel im Mittelton (dort misst ein Handy-Mikrofon am verlässlichsten)
        val mid = FREQS.indices.filter { FREQS[it] in 300.0..3000.0 }
        // Median statt Mittelwert: eine breite Überhöhung im Mittelton verschiebt sonst die ganze Kurve
        val ref = mid.map { m[it] - t[it] }.sorted().let { d -> if (d.isEmpty()) 0f else d[d.size / 2] }
        val meas = FloatArray(n) { m[it] - ref }

        // Untere Grenze der Boxen: ab hier liegt der Pegel höchstens 6 dB unter dem Mittelton
        // (Resonanzspitzen zählen nicht – geprüft wird, dass es auch danach nicht wieder abfällt)
        var lowIdx = 0
        for (i in 0 until n) {
            if (FREQS[i] > 250) break
            val ok = (i until minOf(n, i + 6)).count { meas[it] > -6f } >= 5
            if (ok) { lowIdx = i; break }
            lowIdx = i + 1
        }
        val fLow = FREQS[lowIdx.coerceAtMost(n - 1)]
        // Den natürlichen Bass-Abfall der Box nicht hochziehen – das belastet den Tieftöner
        val boostFrom = max(60.0, fLow * 2.0)

        // Gewünschte Korrektur je Band – mit Grenzen
        val want = FloatArray(n)
        val weight = FloatArray(n)
        for (i in 0 until n) {
            val f = FREQS[i]
            if (f < fLow * 0.8 || f > 16000 || r.snr[i] < 6f) continue
            // Anheben nur im sicheren Bereich, zu den Höhen hin sanft auslaufend
            // unterhalb von boostFrom über eine Oktave weich einblenden (eine harte Stufe
            // erzeugte sonst Paare gegenläufiger Filter um die Grenze)
            val boost = when {
                f < boostFrom / 2 -> 0f
                f < boostFrom -> MAX_BOOST * log2(f / (boostFrom / 2)).toFloat()
                f <= 6000 -> MAX_BOOST
                f >= 10000 -> 0f
                else -> MAX_BOOST * (log2(10000 / f) / log2(10000.0 / 6000)).toFloat()
            }
            val cut = if (f <= 12000) MAX_CUT else MAX_CUT * 0.25f
            want[i] = (t[i] - meas[i]).coerceIn(-cut, boost)
            weight[i] = 1f
        }

        val filters = fitPeaks(want, weight)
        val corr = FloatArray(n) { i -> meas[i] + ParamEq.sumDb(filters, FREQS[i], 0).toFloat() }
        return RoomFit(filters, preampFor(filters), meas, t, corr, fLow)
    }

    /**
     * Glockenfilter so setzen, dass ihre Summe der Wunschkurve folgt.
     * Position und Breite: größte verbleibende Abweichung zuerst (Absenkungen bevorzugt).
     * Stärken: gemeinsam über alle Bänder optimiert (gewichtete kleinste Quadrate mit Grenzen).
     */
    fun fitPeaks(want: FloatArray, weight: FloatArray): List<PFilter> {
        val n = FREQS.size
        val filters = ArrayList<PFilter>()
        val shapes = ArrayList<FloatArray>()     // Verlauf je Filter bei 1 dB Verstärkung
        // Auch außerhalb des Korrekturbereichs soll möglichst wenig passieren
        val w = FloatArray(n) { if (weight[it] > 0) 1f else 0.25f }
        fun total(i: Int) = filters.indices.sumOf { j -> (shapes[j][i] * filters[j].gain).toDouble() }.toFloat()

        repeat(MAX_FILTERS) {
            val r = FloatArray(n) { (want[it] - total(it)) * weight[it] }
            // Keine zwei Filter dichter als 1/4 Oktave – sonst arbeiten sie gegeneinander
            val taken = filters.map { f -> FREQS.indices.minByOrNull { abs(FREQS[it] - f.freq) }!! }
            val iMax = r.indices.filter { i -> taken.none { abs(it - i) < 3 } }
                .maxByOrNull { abs(r[it]) * (if (r[it] > 0) 0.6f else 1f) } ?: return@repeat
            val g = r[iMax]
            if (abs(g) < 1f) return@repeat
            // Breite: bis die Abweichung auf die Hälfte gefallen ist (oder das Vorzeichen wechselt)
            var lo = iMax; while (lo > 0 && r[lo - 1] * g > 0 && abs(r[lo - 1]) > abs(g) / 2) lo--
            var hi = iMax; while (hi < n - 1 && r[hi + 1] * g > 0 && abs(r[hi + 1]) > abs(g) / 2) hi++
            val bw = ((hi - lo + 1) / 12.0).coerceIn(0.15, 3.0)     // Oktaven
            // Schmale Filter nur zum Absenken von Raumresonanzen; Anhebungen immer breit
            val qMax = when {
                g > 0 -> 2f
                FREQS[iMax] < 250 -> 8f
                else -> 3f
            }
            val q = (sqrt(2.0.pow(bw)) / (2.0.pow(bw) - 1)).toFloat().coerceIn(0.4f, qMax)
            // Schmale Senken sind meist Auslöschungen – Anheben bringt dort wenig
            val gain = if (g > 0 && bw < 0.25) g.coerceAtMost(3f) else g
            val f = PFilter(FilterType.PEAK, FREQS[iMax].toFloat(), gain, q)
            filters.add(f)
            shapes.add(FloatArray(n) { i -> ParamEq.filterDb(f.copy(gain = 1f), FREQS[i]).toFloat() })

            // Alle Stärken gemeinsam nachführen (Koordinatenabstieg)
            repeat(40) {
                for (j in filters.indices) {
                    var num = 0.0; var den = 0.0
                    for (i in 0 until n) {
                        val others = total(i) - shapes[j][i] * filters[j].gain
                        num += w[i] * shapes[j][i] * (want[i] - others)
                        den += w[i] * shapes[j][i] * shapes[j][i]
                    }
                    if (den > 1e-9) {
                        val lim = if (filters[j].q > 2f) 0f else MAX_BOOST   // schmale Filter nur absenken
                        filters[j] = filters[j].copy(gain = (num / den).toFloat().coerceIn(-MAX_CUT, lim))
                    }
                }
            }
        }
        return filters
            .map { it.copy(freq = it.freq.roundToInt().toFloat(), gain = (it.gain * 10).roundToInt() / 10f, q = (it.q * 100).roundToInt() / 100f) }
            .filter { abs(it.gain) >= 0.5f }
            .sortedBy { it.freq }
    }

    /** Vorverstärkung so, dass die Korrektur nirgends über 0 dB anhebt (kein Übersteuern). */
    fun preampFor(filters: List<PFilter>): Float {
        val peak = FREQS.maxOf { ParamEq.sumDb(filters, it, 0) }.toFloat()
        return -((max(0f, peak) * 10).roundToInt() / 10f)
    }
}
