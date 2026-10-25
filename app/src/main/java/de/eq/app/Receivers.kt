package de.eq.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log

/** Empfängt "Session geöffnet/geschlossen" von Musik-Apps. */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs(context).enabled) return
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (session <= 0) return

        val service = EqService.instance
        if (service != null) {
            service.handleSessionIntent(intent)
        } else if (intent.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
            try {
                EqService.start(context, intent)
            } catch (e: Exception) {
                // Android 12+ kann den Start aus dem Hintergrund blockieren.
                Log.w("SessionReceiver", "Dienst konnte nicht gestartet werden: ${e.message}")
            }
        }
    }
}

/** Startet den Equalizer nach einem Neustart des Handys und nach einem App-Update automatisch. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (ok && Prefs(context).enabled) {
            try { EqService.start(context) } catch (_: Exception) {}
        }
    }
}
