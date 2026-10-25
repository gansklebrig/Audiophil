package de.eq.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Zeigt die Klangkurve des parametrischen EQ.
 *  - Punkt ziehen: Frequenz (waagerecht) und Verstärkung (senkrecht)
 *  - Zwei Finger spreizen/zusammenziehen: Güte (Q) des gewählten Filters
 */
class CurveView(ctx: Context) : View(ctx) {

    var state: EqState = EqState()
        set(v) { field = v; invalidate() }
    var selected = 0
        set(v) { field = v; invalidate() }

    /** Live-Spektrum im Hintergrund (0..1 je Balken) oder null. */
    var spectrum: FloatArray? = null
        set(v) { field = v; invalidate() }

    var onSelect: ((Int) -> Unit)? = null
    var onFilterChange: ((Int, PFilter) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dpf(v: Float) = v * density

    private val range = GAIN_RANGE
    private val fMin = 20.0
    private val fMax = 20000.0
    private val padL = dpf(28f)
    private val padR = dpf(10f)
    private val padT = dpf(14f)
    private val padB = dpf(18f)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.gold; alpha = 38; strokeWidth = dpf(0.7f)
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.gold; alpha = 95; strokeWidth = dpf(0.9f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.champagne; alpha = 120; textSize = dpf(9f)
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.goldLight; style = Paint.Style.STROKE
        strokeWidth = dpf(2f); strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.gold; alpha = 28; style = Paint.Style.FILL
    }
    private val pointFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.bg; style = Paint.Style.FILL }
    private val pointRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.gold; style = Paint.Style.STROKE; strokeWidth = dpf(1.5f)
    }
    private val selFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.goldLight; style = Paint.Style.FILL }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.champagne; textSize = dpf(9f); textAlign = Paint.Align.CENTER
    }
    private val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.champagne; style = Paint.Style.STROKE
        strokeWidth = dpf(1.6f); strokeJoin = Paint.Join.ROUND
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dpf(5f), dpf(3f)), 0f)
    }
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.champagne; textSize = dpf(10f)
    }
    private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.gold; alpha = 45 }
    private val curve = Path()
    private val curveR = Path()
    private val area = Path()

    private var dragging = -1

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            dragging = -1
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = state.filters.getOrNull(selected) ?: return true
            // Spreizen = breiter = kleineres Q
            val q = (f.q / d.scaleFactor).coerceIn(Q_MIN, Q_MAX)
            onFilterChange?.invoke(selected, f.copy(q = (q * 100).roundToInt() / 100f))
            return true
        }
    })

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), dpf(240f).toInt())
    }

    private val plotW get() = width - padL - padR
    private val plotH get() = height - padT - padB

    private fun xOf(f: Double) = padL + (ln(f / fMin) / ln(fMax / fMin)).toFloat() * plotW
    private fun fOf(x: Float) = fMin * (fMax / fMin).pow(((x - padL) / plotW).toDouble().coerceIn(0.0, 1.0))
    private fun yOf(db: Float) = padT + (range - db.coerceIn(-range, range)) / (2 * range) * plotH
    private fun dbOf(y: Float) = range - (y - padT) / plotH * 2 * range

    private fun pointY(f: PFilter) = yOf(if (FilterType.hasGain(f.type)) f.gain else 0f)

    override fun onDraw(c: Canvas) {
        // Live-Spektrum ganz hinten
        SpectrumAnalyzer.draw(c, spectrum, padL, padT, width - padR, padT + plotH, spectrumPaint)

        // Raster: Frequenzen
        val freqs = listOf(50 to "50", 100 to "100", 200 to "200", 500 to "500",
            1000 to "1k", 2000 to "2k", 5000 to "5k", 10000 to "10k")
        textPaint.textAlign = Paint.Align.CENTER
        for ((f, label) in freqs) {
            val x = xOf(f.toDouble())
            c.drawLine(x, padT, x, padT + plotH, gridPaint)
            c.drawText(label, x, height - dpf(4f), textPaint)
        }
        // Raster: dB
        textPaint.textAlign = Paint.Align.RIGHT
        for (db in -12..12 step 6) {
            val y = yOf(db.toFloat())
            c.drawLine(padL, y, width - padR, y, if (db == 0) zeroPaint else gridPaint)
            c.drawText(if (db > 0) "+$db" else "$db", padL - dpf(4f), y + dpf(3f), textPaint)
        }

        // Kurve – bei getrennten Filtern für links und rechts zwei Linien
        val stereo = ParamEq.isStereo(state.filters)
        curve.reset(); curveR.reset()
        val n = 180
        for (i in 0..n) {
            val f = fMin * (fMax / fMin).pow(i.toDouble() / n)
            val x = xOf(f)
            val y = yOf(ParamEq.response(state, f, 0).toFloat())
            if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
            if (stereo) {
                val yr = yOf(ParamEq.response(state, f, 1).toFloat())
                if (i == 0) curveR.moveTo(x, yr) else curveR.lineTo(x, yr)
            }
        }
        area.set(curve)
        area.lineTo(width - padR, yOf(0f))
        area.lineTo(padL, yOf(0f))
        area.close()
        c.drawPath(area, fillPaint)
        c.drawPath(curve, curvePaint)
        if (stereo) {
            c.drawPath(curveR, rightPaint)
            legendPaint.color = Palette.goldLight
            c.drawText("— links", width - padR - dpf(92f), padT + dpf(2f), legendPaint)
            legendPaint.color = Palette.champagne
            c.drawText("- - rechts", width - padR - dpf(46f), padT + dpf(2f), legendPaint)
        }

        // Filterpunkte
        state.filters.forEachIndexed { i, f ->
            val x = xOf(f.freq.toDouble())
            val y = pointY(f)
            val sel = i == selected
            val r = dpf(if (sel) 8f else 6.5f)
            val a = if (f.on) 255 else 90
            if (sel) {
                selFill.alpha = a
                c.drawCircle(x, y, r, selFill)
            } else {
                pointRing.alpha = a
                c.drawCircle(x, y, r, pointFill)
                c.drawCircle(x, y, r, pointRing)
            }
            numPaint.alpha = a
            val side = when (f.channel) { CH_LEFT -> "L"; CH_RIGHT -> "R"; else -> "" }
            c.drawText("${i + 1}$side", x, y - r - dpf(3f), numPaint)
        }
    }

    private fun nearest(x: Float, y: Float): Int {
        var best = -1
        var bestD = dpf(36f)
        state.filters.forEachIndexed { i, f ->
            val d = hypot(x - xOf(f.freq.toDouble()), y - pointY(f))
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = nearest(ev.x, ev.y)
                dragging = i
                if (i >= 0 && i != selected) {
                    selected = i
                    onSelect?.invoke(i)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = dragging
                if (i >= 0 && !scaleDetector.isInProgress && ev.pointerCount == 1) {
                    val f = state.filters.getOrNull(i) ?: return true
                    val freq = fOf(ev.x).roundToInt().toFloat().coerceIn(20f, 20000f)
                    val gain = if (FilterType.hasGain(f.type))
                        ((dbOf(ev.y).coerceIn(-range, range)) * 10).roundToInt() / 10f
                    else f.gain
                    onFilterChange?.invoke(i, f.copy(freq = freq, gain = gain))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = -1
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    companion object {
        const val GAIN_RANGE = 18f
        const val Q_MIN = 0.1f
        const val Q_MAX = 10f
    }
}
