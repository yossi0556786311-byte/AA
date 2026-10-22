package com.kosherdialer

import android.app.*
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** שירות רקע: שומר את החיבור למכשיר הכשר, מצלצל בשיחה נכנסת ומנסה לנתב את הקול. */
class HfpService : Service() {
    private var ringtone: Ringtone? = null
    private var audioOn = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("status", "חיבור", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("calls", "שיחות", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        Hfp.listener = { onCallState(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        val n = NotificationCompat.Builder(this, "status")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("טלפון")
            .setContentText("מחובר כאוזניות למכשיר הכשר")
            .setContentIntent(openApp())
            .setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, type)
        val addr = getSharedPreferences("p", 0).getString("dev", null)
        val ad = getSystemService(BluetoothManager::class.java)?.adapter
        if (addr != null && ad != null) Hfp.connect(ad.getRemoteDevice(addr))
        return START_STICKY
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun onCallState(st: Hfp.Call) {
        val nm = getSystemService(NotificationManager::class.java)
        if (st == Hfp.Call.INCOMING) {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            ringtone?.play()
            val n = NotificationCompat.Builder(this, "calls")
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("שיחה נכנסת")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(openApp(), true)
                .setContentIntent(openApp())
                .setOngoing(true).build()
            try { nm.notify(2, n) } catch (_: Exception) {}
        } else {
            ringtone?.stop(); ringtone = null
            nm.cancel(2)
        }
        audio(st == Hfp.Call.DIALING || st == Hfp.Call.ALERTING || st == Hfp.Call.ACTIVE)
    }

    /** ניסיון לנתב את הקול דרך חיבור ה-SCO של הבלוטות'. עובד רק אם המערכת במכשיר מזהה אותו. */
    private fun audio(on: Boolean) {
        if (on == audioOn) return
        audioOn = on
        val am = getSystemService(AudioManager::class.java)
        try {
            if (on) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                        ?.let { am.setCommunicationDevice(it) }
                } else {
                    @Suppress("DEPRECATION") am.startBluetoothSco()
                    @Suppress("DEPRECATION") run { am.isBluetoothScoOn = true }
                }
            } else {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                else {
                    @Suppress("DEPRECATION") am.stopBluetoothSco()
                    @Suppress("DEPRECATION") run { am.isBluetoothScoOn = false }
                }
                am.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        Hfp.listener = null
        Hfp.disconnect()
        ringtone?.stop()
        super.onDestroy()
    }
}
