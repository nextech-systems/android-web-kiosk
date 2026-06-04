package org.screenlite.webkiosk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.screenlite.webkiosk.MainActivity
import org.screenlite.webkiosk.data.KioskSettingsFactory

class StayOnTopService : Service() {
    companion object {
        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isActivityVisible = false

        fun restart(context: Context) {
            if (isRunning) {
                context.stopService(Intent(context, StayOnTopService::class.java))
            }
            context.startService(Intent(context, StayOnTopService::class.java))
        }

        private const val CHANNEL_ID = "kiosk_channel"
        private const val NOTIFICATION_ID = 1
        private const val RECOVER_NOTIFICATION_ID = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private var checkInterval: Long = 10_000L

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var visibilityJob: Job? = null

    private val checkTask = object : Runnable {
        override fun run() {
            if (!isActivityVisible) {
                bringAppToFront()
            } else {
                Log.i("StayOnTopService", "Activity is already visible, skipping bring to front")
            }
            handler.postDelayed(this, checkInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val kioskSettings = KioskSettingsFactory.get(this)

        serviceScope.launch {
            kioskSettings.getCheckInterval().collect { interval ->
                Log.i("StayOnTopService", "Interval updated: $interval")
                updateCheckInterval(interval)
            }
        }

        startForegroundService()
        handler.post(checkTask)
        isRunning = true
        Log.i("StayOnTopService", "Service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(checkTask)
        visibilityJob?.cancel()
        serviceScope.cancel()
        isRunning = false
        isActivityVisible = false
        Log.i("StayOnTopService", "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Bring the kiosk activity to the foreground.
     *
     * On Android 10+ a foreground service cannot call startActivity() directly
     * (allowBackgroundActivityStart: false).  Instead we post a full-screen
     * notification whose fullScreenIntent is allowed to launch activities even
     * from the background — the same mechanism used by incoming call UIs.
     *
     * On older Android we fall back to a direct startActivity() call.
     */
    private fun bringAppToFront() {
        Log.i("StayOnTopService", "Bringing app to front")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            bringAppToFrontViaNotification()
        } else {
            bringAppToFrontLegacy()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun bringAppToFrontViaNotification() {
        try {
            val activityIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // Ensure a HIGH-importance channel exists for full-screen intents
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val recoverChannel = NotificationChannel(
                    "kiosk_recover_channel",
                    "Screenlite Kiosk Recovery",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                }
                nm.createNotificationChannel(recoverChannel)
            }

            val notification = Notification.Builder(this, "kiosk_recover_channel")
                .setContentTitle("Screenlite Web Kiosk")
                .setContentText("Resuming kiosk session…")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .build()

            nm.notify(RECOVER_NOTIFICATION_ID, notification)
            Log.i("StayOnTopService", "Full-screen notification posted to recover activity")
        } catch (e: Exception) {
            Log.e("StayOnTopService", "Failed to post full-screen notification: ${e.message}")
        }
    }

    private fun bringAppToFrontLegacy() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
            Log.i("StayOnTopService", "Legacy startActivity() called")
        } catch (e: Exception) {
            Log.e("StayOnTopService", "Failed to bring app to front: ${e.message}")
        }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundServiceOreo()
        } else {
            startForegroundServiceLegacy()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startForegroundServiceOreo() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screenlite Web Kiosk",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Screenlite Web Kiosk")
            .setContentText("Keeping app on top")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun startForegroundServiceLegacy() {
        val notification: Notification = Notification.Builder(this)
            .setContentTitle("Screenlite Web Kiosk")
            .setContentText("Keeping app on top")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateCheckInterval(newInterval: Long) {
        checkInterval = newInterval
        handler.removeCallbacks(checkTask)
        handler.post(checkTask)
    }
}
