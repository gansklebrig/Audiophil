package de.eq.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Kachel in den Schnelleinstellungen: Equalizer an/aus. */
class EqToggleTile : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        val prefs = Prefs(this)
        if (prefs.enabled) {
            prefs.enabled = false
            EqService.stop(this)
        } else {
            prefs.enabled = true
            try {
                EqService.start(this)
            } catch (e: Exception) {
                // Manche Android-Versionen erlauben den Start nur aus der App heraus
                prefs.enabled = false
                openApp()
            }
        }
        refresh()
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val prefs = Prefs(this)
        tile.state = if (prefs.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Equalizer"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (!prefs.enabled) "Aus" else prefs.currentProfileName() ?: "An"
        }
        tile.updateTile()
    }
}

/** Kachel in den Schnelleinstellungen: zum nächsten ★-Profil wechseln. */
class EqProfileTile : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        val prefs = Prefs(this)
        val next = prefs.nextProfile()
        if (next == null) {
            Toast.makeText(this, "Noch keine ★-Profile angelegt", Toast.LENGTH_SHORT).show()
        } else {
            EqService.instance?.let { it.applyNow(); it.notifyUi() }
            EqService.uiListener?.invoke()
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val prefs = Prefs(this)
        val name = prefs.currentProfileName()
        tile.state = if (prefs.profiles.isEmpty()) Tile.STATE_UNAVAILABLE
                     else if (prefs.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (Build.VERSION.SDK_INT >= 29) "EQ-Profil" else (name ?: "EQ-Profil")
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = name ?: "—"
        tile.updateTile()
    }
}
