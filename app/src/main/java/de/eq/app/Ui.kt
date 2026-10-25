package de.eq.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Schwarz-Gold-Palette. */
object Palette {
    val gold = Color.parseColor("#C9A961")        // Akzent
    val goldLight = Color.parseColor("#E6CF8F")   // Titel, Werte
    val champagne = Color.parseColor("#E9E1CF")   // normaler Text
    val surface = Color.parseColor("#16130E")     // Knöpfe, Flächen
    val bg = Color.parseColor("#0B0A08")          // Hintergrund
    val warn = Color.parseColor("#D9663C")        // Kupferrot für Warnungen
    val track = Color.parseColor("#3A3122")       // Regler-Hintergrund
}

/** Baukasten für die einheitlich gestalteten Bedienelemente. */
class Ui(private val ctx: Context) {
    private val repeatHandler = Handler(Looper.getMainLooper())

    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()

    /** Dezenter Knopf: dunkle Fläche, feiner Goldrand, Goldschrift. */
    fun goldButton(label: String? = null) = Button(ctx).apply {
        label?.let { text = it }
        isAllCaps = false
        setTextColor(Palette.goldLight)
        stateListAnimator = null
        background = buttonBg(false)
    }

    private fun buttonBg(active: Boolean) = GradientDrawable().apply {
        setColor(if (active) Palette.gold else Palette.surface)
        cornerRadius = dp(10).toFloat()
        setStroke(dp(1), Color.argb(110, Color.red(Palette.gold), Color.green(Palette.gold), Color.blue(Palette.gold)))
    }

    /** Für Umschalter (Band-EQ / Parametrisch): aktiver Knopf golden gefüllt. */
    fun setActive(b: Button, active: Boolean) {
        b.background = buttonBg(active)
        b.setTextColor(if (active) Palette.bg else Palette.goldLight)
    }

    /** Knopf, der sich die Zeilenbreite mit anderen teilt. */
    fun smallButton(label: String, onClick: () -> Unit) = goldButton(label).apply {
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
        setOnClickListener { onClick() }
    }

    /** −/+ Taste: Tippen = ein Schritt, Gedrückthalten = schnell weiterzählen. */
    fun stepButton(label: String, onStep: () -> Unit) = goldButton(label).apply {
        textSize = 20f
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(40)).apply { setMargins(dp(2), 0, dp(2), 0) }
        val repeater = object : Runnable {
            override fun run() {
                onStep()
                repeatHandler.postDelayed(this, 80)
            }
        }
        setOnClickListener { onStep() }
        setOnLongClickListener { repeatHandler.post(repeater); true }
        setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
                repeatHandler.removeCallbacks(repeater)
            }
            false
        }
    }

    /** Goldener Schieberegler. [display] wird bei jeder Änderung aufgerufen, [onUser] nur bei Bedienung. */
    fun slider(max: Int, start: Int, display: (Int) -> Unit = {}, onUser: (Int) -> Unit) =
        SeekBar(ctx).apply {
            this.max = max
            progress = start
            progressTintList = ColorStateList.valueOf(Palette.gold)
            thumbTintList = ColorStateList.valueOf(Palette.goldLight)
            progressBackgroundTintList = ColorStateList.valueOf(Palette.track)
            display(start)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    display(p)
                    if (fromUser) onUser(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

    /** Waagerechte Zeile; null-Einträge werden übersprungen. */
    fun row(vararg views: View?) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        views.filterNotNull().forEach { addView(it) }
    }

    fun vbox() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    /** Beschriftung links, Wert rechts. */
    fun labelRow(label: String, value: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, 0)
        addView(TextView(ctx).apply {
            text = label
            setTextColor(Palette.champagne)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        addView(value)
    }

    /** Goldener Zahlenwert (oft antippbar für exakte Eingabe). */
    fun valueText() = TextView(ctx).apply {
        setTextColor(Palette.goldLight)
        gravity = Gravity.END
        setPadding(dp(8), dp(4), 0, dp(4))
    }

    fun section(t: String) = TextView(ctx).apply {
        text = t.uppercase()
        textSize = 13f
        letterSpacing = 0.18f
        setTextColor(Palette.gold)
        setPadding(0, dp(28), 0, dp(6))
        // feine Goldlinie darunter
        foreground = object : Drawable() {
            private val paint = Paint().apply { color = Palette.gold; alpha = 70 }
            override fun draw(c: Canvas) {
                val y = bounds.bottom.toFloat() - 1
                c.drawLine(0f, y, bounds.right.toFloat(), y, paint)
            }
            override fun setAlpha(a: Int) {}
            override fun setColorFilter(cf: ColorFilter?) {}
            @Deprecated("") override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
    }

    fun hint(t: String) = TextView(ctx).apply {
        text = t
        textSize = 13f
        alpha = 0.8f
        setTextColor(Palette.champagne)
        setPadding(0, dp(4), 0, dp(4))
    }

    fun switch(label: String, onChange: (Boolean) -> Unit) = Switch(ctx).apply {
        text = label
        setPadding(0, dp(8), 0, dp(4))
        setTextColor(Palette.champagne)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Palette.goldLight, Color.parseColor("#6E6758")))
        trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#8A7440"), Color.parseColor("#2C2820")))
        setOnCheckedChangeListener { _, on -> if (!silent) onChange(on) }
    }

    /** Während true lösen programmatische Änderungen keine Rückmeldungen aus. */
    var silent = false

    /** Auswahlliste, die nur auf echte Auswahl durch den Nutzer reagiert. */
    fun choice(onPick: (Int) -> Unit) = Spinner(ctx).apply {
        tag = intArrayOf(0)
        background?.setTint(Palette.gold)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val cur = tag as IntArray
                if (pos == cur[0]) return
                cur[0] = pos
                onPick(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** Einträge setzen – die Liste wird nur neu aufgebaut, wenn sie sich wirklich geändert hat. */
    fun setChoices(sp: Spinner, items: List<String>, selected: Int) {
        val sel = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        (sp.tag as IntArray)[0] = sel
        val a = sp.adapter
        val same = a != null && a.count == items.size && items.indices.all { a.getItem(it) == items[it] }
        if (!same) sp.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, items)
        if (!same || sp.selectedItemPosition != sel) sp.setSelection(sel)
    }

    /** Abstand nach unten (für Layout-Parameter). */
    fun spaced(v: View, top: Int) = v.apply {
        layoutParams = (layoutParams as? ViewGroup.MarginLayoutParams
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT))
            .apply { topMargin = dp(top) }
    }

    fun title(t: String) = TextView(ctx).apply {
        text = t
        textSize = 26f
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        letterSpacing = 0.25f
        setTextColor(Palette.goldLight)
    }
}
