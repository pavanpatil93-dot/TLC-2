package com.example.notifications

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import com.example.data.UserSettingsManager
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue

object TeamVocalAlertManager {
    private val initLock = Any()
    @Volatile
    private var tts: TextToSpeech? = null
    @Volatile
    private var isReady: Boolean = false
    @Volatile
    private var isInitializing: Boolean = false

    private val pendingQueue = ConcurrentLinkedQueue<String>()

    fun isQuietHoursIst(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            timeInMillis = nowMillis
        }
        val minutesOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // Quiet hours: 9:30 PM (21:30 = 1290) through 8:30 AM (08:30 = 510) IST
        return minutesOfDay >= (21 * 60 + 30) || minutesOfDay < (8 * 60 + 30)
    }

    fun isRingerSilentOrVibrate(context: Context): Boolean {
        val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        return audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL
    }

    fun isBluetoothOrDrivingActive(context: Context): Boolean {
        val appContext = context.applicationContext
        val uiModeManager = appContext.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_CAR) {
            return true
        }
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull()
            ?: return false
        return outputs.any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
                else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                    )
            }
        }
    }

    fun shouldSpeakNow(context: Context, mode: VocalAlertMode = UserSettingsManager.readVocalAlertMode(context)): Boolean {
        if (mode == VocalAlertMode.OFF) return false
        if (isRingerSilentOrVibrate(context)) return false
        if (isQuietHoursIst()) return false
        return when (mode) {
            VocalAlertMode.ALWAYS_ON -> true
            VocalAlertMode.BLUETOOTH_DRIVING_ONLY -> isBluetoothOrDrivingActive(context)
            VocalAlertMode.OFF -> false
        }
    }

    fun speakAlert(context: Context, message: String) {
        val clean = message.trim()
        if (clean.isBlank()) return
        if (!shouldSpeakNow(context)) return
        enqueueAndSpeak(context.applicationContext, clean)
    }

    /**
     * Explicit user-triggered vocal preview from Settings.
     * Returns a short status string so the UI can explain if Silent mode muted playback.
     */
    fun speakTestAlert(context: Context): String {
        val mode = UserSettingsManager.readVocalAlertMode(context)
        if (mode == VocalAlertMode.OFF) {
            return "Vocal Alerts are set to Off. Select Bluetooth / Driving Only or Always On to test."
        }
        if (isRingerSilentOrVibrate(context)) {
            return "Phone ringer is on Silent/Vibrate — Vocal Alerts automatically stay muted."
        }
        enqueueAndSpeak(
            context.applicationContext,
            "New property added by Pawan in Kharadi."
        )
        return if (mode == VocalAlertMode.BLUETOOTH_DRIVING_ONLY && !isBluetoothOrDrivingActive(context)) {
            "Playing voice sample (live alerts will wait for Bluetooth / Driving mode)."
        } else {
            "Playing Indian English vocal alert sample..."
        }
    }

    private fun enqueueAndSpeak(appContext: Context, text: String) {
        synchronized(initLock) {
            val engine = tts
            if (engine != null && isReady) {
                runCatching {
                    engine.speak(text, TextToSpeech.QUEUE_ADD, null, "tlc_vocal_${System.currentTimeMillis()}")
                }
                return
            }
            if (pendingQueue.size < 6) {
                pendingQueue.offer(text)
            }
            if (isInitializing) return
            isInitializing = true
            tts = TextToSpeech(appContext) { status ->
                synchronized(initLock) {
                    isInitializing = false
                    if (status == TextToSpeech.SUCCESS) {
                        val instance = tts
                        if (instance != null) {
                            val inLocale = Locale("en", "IN")
                            val res = instance.setLanguage(inLocale)
                            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                                instance.setLanguage(Locale.ENGLISH)
                            }
                            instance.setSpeechRate(1.02f)
                            instance.setPitch(1.0f)
                            isReady = true
                            while (true) {
                                val next = pendingQueue.poll() ?: break
                                runCatching {
                                    instance.speak(next, TextToSpeech.QUEUE_ADD, null, "tlc_vocal_${System.nanoTime()}")
                                }
                            }
                        }
                    } else {
                        isReady = false
                        pendingQueue.clear()
                    }
                }
            }
        }
    }
}
