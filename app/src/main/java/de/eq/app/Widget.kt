package de.eq.app

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews

/**
 * Homescreen-Widget: Ein/Aus, Profil vor/zurück und Lautstärke-Boost (±0,5 dB).
 */
class EqWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val prefs = Prefs(context)
        when (intent.action) {
            ACTION_OFF -> {
                prefs.enabled = false
                EqService.stop(context)
            }
            ACTION_NEXT, ACTION_PREV -> {
                val name = if (intent.action == ACTION_NEXT) prefs.nextProfile() else prefs.prevProfile()
                if (name != null) { changed(); EqService.profileSwitchListener?.invoke() }
            }
            ACTION_PLUS, ACTION_MINUS -> {
                val step = if (intent.action == ACTION_PLUS) WIDGET_STEP else -WIDGET_STEP
                val s = prefs.state
                val v = (s.loudness + step).coerceIn(0, MAX_BOOST)
                if (v != s.loudness) {
                    prefs.state = s.copy(loudness = v)
                    prefs.currentProfileName()?.let { prefs.saveProfile(it) }
                    changed()
                }
            }
            else -> return
        }
        updateAll(context)
    }

    private fun changed() {
        EqService.instance?.let { it.applyNow(); it.notifyUi() } ?: EqService.uiListener?.invoke()
    }

    companion object {
        private const val ACTION_OFF = "de.eq.app.widget.OFF"
        private const val ACTION_NEXT = "de.eq.app.widget.NEXT"
        private const val ACTION_PREV = "de.eq.app.widget.PREV"
        private const val ACTION_PLUS = "de.eq.app.widget.PLUS"
        private const val ACTION_MINUS = "de.eq.app.widget.MINUS"
        private const val WIDGET_STEP = 50          // 0,5 dB
        private const val MAX_BOOST = 1500

        /** Alle Widgets mit dem aktuellen Zustand neu zeichnen. */
        fun updateAll(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, EqWidget::class.java))
                if (ids.isEmpty()) return
                manager.updateAppWidget(ids, build(context))
            } catch (_: Exception) {}
        }

        private fun build(context: Context): RemoteViews {
            val prefs = Prefs(context)
            val s = prefs.state
            val on = prefs.enabled
            val v = RemoteViews(context.packageName, R.layout.widget_eq)

            v.setTextViewText(R.id.widget_status, if (on) "AN" else "AUS")
            v.setTextViewText(R.id.widget_profile, prefs.currentProfileName()?.let { "★ $it" } ?: prefs.presetName)
            v.setTextViewText(R.id.widget_boost, "+%.1f dB".format(s.loudness / 100f))
            v.setTextViewText(R.id.widget_power, if (on) "Aus" else "An")
            v.setInt(R.id.widget_power, "setBackgroundResource", if (on) R.drawable.widget_btn else R.drawable.widget_btn_on)
            v.setTextColor(R.id.widget_power, if (on) Palette.goldLight else Palette.bg)

            // Einschalten über eine unsichtbare Activity – so erlaubt Android den Start des Dienstes sicher
            val power = if (on) broadcast(context, ACTION_OFF, 1)
                        else PendingIntent.getActivity(
                            context, 2, Intent(context, StartActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
            v.setOnClickPendingIntent(R.id.widget_power, power)
            v.setOnClickPendingIntent(R.id.widget_prev, broadcast(context, ACTION_PREV, 3))
            v.setOnClickPendingIntent(R.id.widget_next, broadcast(context, ACTION_NEXT, 4))
            v.setOnClickPendingIntent(R.id.widget_minus, broadcast(context, ACTION_MINUS, 5))
            v.setOnClickPendingIntent(R.id.widget_plus, broadcast(context, ACTION_PLUS, 6))

            val open = PendingIntent.getActivity(
                context, 7, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_profile, open)
            v.setOnClickPendingIntent(R.id.widget_title, open)
            return v
        }

        private fun broadcast(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, code, Intent(context, EqWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

/** Unsichtbare Hilfs-Activity: schaltet den Equalizer ein und schließt sich sofort wieder. */
class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs(this).enabled = true
        try { EqService.start(this) } catch (_: Exception) {}
        EqWidget.updateAll(this)
        finish()
    }
}
