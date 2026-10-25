package de.eq.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Ein Ausgabegerät: [key] identifiziert es dauerhaft, [label] ist der Anzeigename. */
data class OutDevice(val key: String, val label: String)

/** Erkennt, worüber gerade Ton ausgegeben wird (Bluetooth, USB, Kabel, Lautsprecher). */
object AudioDevices {
    const val SPEAKER_KEY = "speaker"

    private val bluetooth = listOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )
    private val usb = listOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
    private val wired = listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)

    /** Reihenfolge, falls unklar ist, was zuletzt angeschlossen wurde: Bluetooth vor USB vor Kabel. */
    private val priority = bluetooth + usb + wired

    /** Zuletzt angeschlossener Kopfhörer – Android leitet den Ton meist auf das neueste Gerät. */
    @Volatile private var lastAddedId = -1

    fun noteAdded(added: Array<out AudioDeviceInfo>?) {
        // Beim Start meldet Android alle Geräte auf einmal – dann ist nichts „neu“
        val phones = added.orEmpty().filter { it.isSink && it.type in priority }
        if (phones.size == 1) lastAddedId = phones[0].id
    }

    fun current(context: Context): OutDevice {
        val am = context.getSystemService(AudioManager::class.java)
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // zuerst der zuletzt angeschlossene Kopfhörer, sonst nach fester Reihenfolge
        val d = outputs.firstOrNull { it.id == lastAddedId && it.type in priority }
            ?: priority.firstNotNullOfOrNull { t -> outputs.firstOrNull { it.type == t } }
            ?: return OutDevice(SPEAKER_KEY, "Handy-Lautsprecher")
        val name = d.productName?.toString()?.trim().orEmpty()
        return when (d.type) {
            in bluetooth -> {
                val n = name.ifEmpty { "Bluetooth-Gerät" }
                OutDevice("bt:$n", n)
            }
            in usb -> {
                val n = name.ifEmpty { "USB-Audio" }
                OutDevice("usb:$n", "$n (USB)")
            }
            else -> OutDevice("wired", "Kabelkopfhörer")
        }
    }
}
