package com.winlauncher.app.domain.process

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the OS from killing the launched runtime process when the launcher UI
 * is backgrounded. The MVP starts/stops this alongside DummyRuntimeEngine;
 * the real runtime integration should do the same around the real process tree.
 */
class RuntimeForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "runtime_supervision"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Game runtime",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Game running")
            .setContentText("A runtime process is active")
            .setOngoing(true)
            .build()
    }
}
