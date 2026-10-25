package de.eq.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
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

/** Schwarz-Gold-Palette. Gold nur für Aktives und Werte, sonst ruhige Grau-/Champagnertöne. */
object Palette {
    val gold = Color.parseColor("#C9A961")        // Akzent
    val goldLight = Color.parseColor("#E6CF8F")   // Titel, Werte
    val champagne = Color.parseColor("#E9E1CF")   // normaler Text
    val muted = Color.parseColor("#8E8674")       // Nebentext, Beschriftungen
    val surface = Color.parseColor("#1A1611")     // Knöpfe
    val card = Color.parseColor("#13110D")        // Karten
    val bg = Color.parseColor("#0B0A08")          // Hintergrund
    val warn = Color.parseColor("#D9663C")        // Kupferrot für Warnungen
    val track = Color.parseColor("#3A3122")       // Regler-Hintergrund

    /** Gold mit Deckkraft (0–255), z. B. für feine Ränder. */
    fun goldA(a: Int) = Color.argb(a, Color.red(gold), Color.green(gold), Color.blue(gold))
}

/** Baukasten für die einheitlich gestalteten Bedienelemente. */
class Ui(private val ctx: Context) {
    private val repeatHandler = Handler(Looper.getMainLooper())
    private val bigSpinners = HashSet<Spinner>()

    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()

    private fun rounded(color: Int, radius: Int, strokeAlpha: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (strokeAlpha > 0) setStroke(dp(1), Palette.goldA(strokeAlpha))
    }

    /** Hintergrund mit Berührungs-Effekt. */
    private fun touchBg(color: Int, radius: Int, strokeAlpha: Int) =
        RippleDrawable(ColorStateList.valueOf(Palette.goldA(60)), rounded(color, radius, strokeAlpha), rounded(Color.WHITE, radius))

    // ----- Knöpfe -----

    /** Dezenter Knopf: dunkle Fläche, feiner Goldrand, Goldschrift. */
    fun goldButton(label: String? = null) = Button(ctx).apply {
        label?.let { text = it }
        isAllCaps = false
        textSize = 14f
        minHeight = dp(46)
        minimumHeight = dp(46)
        setTextColor(Palette.goldLight)
        stateListAnimator = null
        background = buttonBg(false)
    }

    private fun buttonBg(active: Boolean) =
        if (active) touchBg(Palette.gold, 14, 0) else touchBg(Palette.surface, 14, 70)

    /** Für Umschalter (Band-EQ / Parametrisch): aktiver Knopf golden gefüllt. */
    fun setActive(b: Button, active: Boolean) {
        b.background = buttonBg(active)
        b.setTextColor(if (active) Palette.bg else Palette.goldLight)
    }

    /** Knopf, der sich die Zeilenbreite mit anderen teilt. */
    fun smallButton(label: String, onClick: () -> Unit) = goldButton(label).apply {
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
        setOnClickListener { onClick() }
    }

    /** Breiter Knopf über die ganze Zeile. */
    fun wideButton(label: String, primary: Boolean = false, onClick: () -> Unit) = goldButton(label).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        if (primary) setActive(this, true)
        setOnClickListener { onClick() }
    }

    /** −/+ Taste: Tippen = ein Schritt, Gedrückthalten = schnell weiterzählen. */
    fun stepButton(label: String, onStep: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 18f
        setTextColor(Palette.goldLight)
        stateListAnimator = null
        background = touchBg(Palette.surface, 12, 45)
        setPadding(0, 0, 0, 0)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { setMargins(dp(2), 0, dp(2), 0) }
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

    /** Runder Symbolknopf ohne Wiederholung (z. B. ↶ ↷ ‹). */
    fun iconButton(label: String, size: Int = 40, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 18f
        setTextColor(Palette.goldLight)
        stateListAnimator = null
        background = touchBg(Palette.surface, size / 2, 45)
        setPadding(0, 0, 0, 0)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { setMargins(dp(3), 0, dp(3), 0) }
        setOnClickListener { onClick() }
    }

    /** Großer Ein/Aus-Knopf oben rechts. */
    fun powerButton(onClick: () -> Unit) = TextView(ctx).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        letterSpacing = 0.12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(dp(18), 0, dp(18), 0)
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(40))
        isClickable = true
        setOnClickListener { onClick() }
        setPower(this, false)
    }

    fun setPower(v: TextView, on: Boolean) {
        v.text = if (on) "●  AN" else "○  AUS"
        v.setTextColor(if (on) Palette.bg else Palette.muted)
        v.background = if (on) touchBg(Palette.gold, 20, 0) else touchBg(Palette.surface, 20, 60)
    }

    // ----- Regler -----

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

    // ----- Anordnung -----

    /** Waagerechte Zeile; null-Einträge werden übersprungen. */
    fun row(vararg views: View?) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        views.filterNotNull().forEach { addView(it) }
    }

    fun vbox() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    /** Füllt den Rest einer Zeile. */
    fun spacer() = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    /** Karte: abgerundete, leicht abgesetzte Fläche; optional mit kleinem Titel. */
    fun card(title: String? = null) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.card, 20, 34)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        title?.let { addView(cardTitle(it)) }
    }

    fun cardTitle(t: String) = TextView(ctx).apply {
        text = t.uppercase()
        textSize = 11.5f
        letterSpacing = 0.2f
        setTextColor(Palette.gold)
        setPadding(0, 0, 0, dp(6))
    }

    /** Kachel fürs Hauptmenü: Symbol, Titel und eine Zeile mit dem aktuellen Stand. */
    fun tile(icon: String, title: String, onClick: () -> Unit): Pair<View, TextView> {
        val sub = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Palette.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = touchBg(Palette.card, 18, 34)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
            isClickable = true
            setOnClickListener { onClick() }
            addView(TextView(ctx).apply {
                text = icon
                textSize = 20f
                setTextColor(Palette.gold)
                setPadding(0, 0, 0, dp(10))
            })
            addView(TextView(ctx).apply {
                text = title
                textSize = 15f
                setTextColor(Palette.champagne)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(sub)
        }
        return v to sub
    }

    /** Zeile in einem Untermenü, die eine weitere Aktion öffnet („Titel …  ›“). */
    fun linkRow(title: String, sub: String? = null, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = touchBg(Palette.card, 12, 0)
        setPadding(0, dp(12), 0, dp(12))
        isClickable = true
        setOnClickListener { onClick() }
        addView(vbox().apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply { text = title; textSize = 15f; setTextColor(Palette.champagne) })
            sub?.let { addView(TextView(ctx).apply { text = it; textSize = 12f; setTextColor(Palette.muted) }) }
        })
        addView(TextView(ctx).apply { text = "›"; textSize = 22f; setTextColor(Palette.gold) })
    }

    /** Kopf eines Untermenüs: Zurück-Pfeil und Titel. */
    fun pageHeader(t: String, onBack: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(4))
        addView(iconButton("‹", 42, onBack).apply {
            textSize = 24f
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(12), 0)
        })
        addView(TextView(ctx).apply {
            text = t
            textSize = 22f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            setTextColor(Palette.goldLight)
        })
    }

    /** Beschriftung links, Wert rechts. */
    fun labelRow(label: String, value: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, 0)
        addView(TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(Palette.champagne)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        addView(value)
    }

    /** Goldener Zahlenwert (oft antippbar für exakte Eingabe). */
    fun valueText() = TextView(ctx).apply {
        setTextColor(Palette.goldLight)
        textSize = 14f
        gravity = Gravity.END
        setPadding(dp(8), dp(4), 0, dp(4))
    }

    /** Zwischenüberschrift (z. B. im Hörtest). */
    fun section(t: String) = TextView(ctx).apply {
        text = t.uppercase()
        textSize = 11.5f
        letterSpacing = 0.2f
        setTextColor(Palette.gold)
        setPadding(0, dp(22), 0, dp(6))
    }

    fun hint(t: String) = TextView(ctx).apply {
        text = t
        textSize = 12.5f
        setLineSpacing(0f, 1.15f)
        setTextColor(Palette.muted)
        setPadding(0, dp(4), 0, dp(4))
    }

    fun divider() = View(ctx).apply {
        setBackgroundColor(Palette.goldA(30))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { setMargins(0, dp(10), 0, dp(4)) }
    }

    fun switch(label: String, onChange: (Boolean) -> Unit) = Switch(ctx).apply {
        text = label
        textSize = 15f
        setPadding(0, dp(8), 0, dp(8))
        setTextColor(Palette.champagne)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Palette.goldLight, Color.parseColor("#6E6758")))
        trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#8A7440"), Color.parseColor("#2C2820")))
        setOnCheckedChangeListener { _, on -> if (!silent) onChange(on) }
    }

    /** Während true lösen programmatische Änderungen keine Rückmeldungen aus. */
    var silent = false

    /** Auswahlliste, die nur auf echte Auswahl durch den Nutzer reagiert. [big] = große, goldene Anzeige. */
    fun choice(big: Boolean = false, onPick: (Int) -> Unit) = Spinner(ctx).apply {
        tag = intArrayOf(0)
        background?.setTint(Palette.gold)
        if (big) bigSpinners.add(this)
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
        if (!same) sp.adapter = adapterFor(items, sp in bigSpinners)
        if (!same || sp.selectedItemPosition != sel) sp.setSelection(sel)
    }

    private fun adapterFor(items: List<String>, big: Boolean) =
        object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(pos: Int, convert: View?, parent: ViewGroup): View =
                (super.getView(pos, convert, parent) as TextView).apply {
                    setTextColor(if (big) Palette.goldLight else Palette.champagne)
                    textSize = if (big) 19f else 14f
                    typeface = if (big) Typeface.create(Typeface.SERIF, Typeface.NORMAL) else Typeface.DEFAULT
                    setPadding(0, dp(6), dp(8), dp(6))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
        }

    /** Abstand nach oben (für Layout-Parameter). */
    fun spaced(v: View, top: Int) = v.apply {
        layoutParams = (layoutParams as? ViewGroup.MarginLayoutParams
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT))
            .apply { topMargin = dp(top) }
    }

    fun title(t: String) = TextView(ctx).apply {
        text = t
        textSize = 24f
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        letterSpacing = 0.25f
        setTextColor(Palette.goldLight)
    }
}
