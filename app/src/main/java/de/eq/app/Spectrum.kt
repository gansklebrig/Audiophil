package de.eq.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Live-Spektrum der gerade laufenden Musik (Androids Visualizer auf der Gesamtausgabe).
 * Liefert [BARS] Werte von 0..1, logarithmisch verteilt von 20 Hz bis 20 kHz.
 *
 * Android koppelt den Visualizer an die Mikrofon-Berechtigung, aufgenommen wird aber nichts:
 * analysiert wird nur der Ton, der ohnehin abgespielt wird.
 */
class SpectrumAnalyzer(private val onData: (FloatArray) -> Unit) {
    private var visualizer: Visualizer? = null
    private val levels = FloatArray(BARS)
    private val main = Handler(Looper.getMainLooper())

    fun start(): Boolean {
        if (visualizer != null) return true
        return try {
            val v = Visualizer(0)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(2048)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, data: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, rate: Int) {
                    if (fft != null) process(fft, v.samplingRate / 1000f)
                }
            }, (Visualizer.getMaxCaptureRate() / 2).coerceAtMost(20000), false, true)
            v.enabled = true
            visualizer = v
            true
        } catch (e: Exception) {
            Log.w("Spectrum", "Visualizer nicht verfügbar: ${e.message}")
            stop()
            false
        }
    }

    fun stop() {
        try { visualizer?.enabled = false; visualizer?.release() } catch (_: Exception) {}
        visualizer = null
        levels.fill(0f)
        main.post { onData(levels.copyOf()) }
    }

    private fun process(fft: ByteArray, sampleRate: Float) {
        val out = barsFromFft(fft, sampleRate)
        for (i in 0 until BARS) {
            // schnell steigen, langsam fallen – wirkt ruhiger
            levels[i] = if (out[i] > levels[i]) out[i] else levels[i] * 0.85f + out[i] * 0.15f
        }
        val copy = levels.copyOf()
        main.post { onData(copy) }
    }

    companion object {
        const val BARS = 48
        private const val F_MIN = 20.0
        private const val F_MAX = 20000.0
        private const val FLOOR_DB = -70f

        /** Visualizer-FFT (Byte-Paare re/im) → Balken 0..1 (dB-Skala). */
        fun barsFromFft(fft: ByteArray, sampleRate: Float): FloatArray {
            val n = fft.size                 // = captureSize
            val bins = n / 2
            val binHz = sampleRate / n
            val out = FloatArray(BARS)
            for (b in 0 until BARS) {
                val lo = F_MIN * (F_MAX / F_MIN).pow(b.toDouble() / BARS)
                val hi = F_MIN * (F_MAX / F_MIN).pow((b + 1).toDouble() / BARS)
                var k0 = (lo / binHz).toInt().coerceIn(1, bins - 1)
                val k1 = (hi / binHz).toInt().coerceIn(1, bins - 1)
                if (k1 < k0) k0 = k1
                var peak = 0.0
                for (k in k0..k1) {
                    val re = fft[2 * k].toDouble()
                    val im = fft[2 * k + 1].toDouble()
                    peak = maxOf(peak, hypot(re, im))
                }
                val db = if (peak <= 0) FLOOR_DB else (20 * log10(peak / 128.0)).toFloat()
                out[b] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
            }
            return out
        }

        /** Gemeinsame Darstellung der Balken (für Kurve und eigenständigen Streifen). */
        fun draw(c: Canvas, data: FloatArray?, left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            if (data == null) return
            val w = (right - left) / data.size
            for (i in data.indices) {
                val h = data[i] * (bottom - top)
                if (h < 0.5f) continue
                c.drawRect(left + i * w + w * 0.15f, bottom - h, left + (i + 1) * w - w * 0.15f, bottom, paint)
            }
        }

        /** x-Position (0..1) einer Frequenz auf der logarithmischen Achse. */
        fun xFraction(f: Double) = (ln(f / F_MIN) / ln(F_MAX / F_MIN)).toFloat()
    }
}

/** Schmaler Streifen mit dem Live-Spektrum (im Band-EQ-Modus). */
class SpectrumView(ctx: Context) : View(ctx) {
    var data: FloatArray? = null
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.gold; alpha = 150 }
    private val base = Paint().apply { color = Palette.gold; alpha = 60 }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (70 * resources.displayMetrics.density).toInt())
    }

    override fun onDraw(c: Canvas) {
        SpectrumAnalyzer.draw(c, data, 0f, 0f, width.toFloat(), height.toFloat() - 1, paint)
        c.drawLine(0f, height - 1f, width.toFloat(), height - 1f, base)
    }
}
