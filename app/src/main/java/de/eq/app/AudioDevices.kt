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

    /** Reihenfolge: Bluetooth vor USB vor Kabel – so wie Android selbst den Ton leitet. */
    private val priority = bluetooth + usb + wired

    fun current(context: Context): OutDevice {
        val am = context.getSystemService(AudioManager::class.java)
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (type in priority) {
            val d = outputs.firstOrNull { it.type == type } ?: continue
            val name = d.productName?.toString()?.trim().orEmpty()
            return when (type) {
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
        return OutDevice(SPEAKER_KEY, "Handy-Lautsprecher")
    }
}
