package com.example.modelrouter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.modelrouter.R
import com.example.modelrouter.ui.MainActivity

class RouterService : Service() {

    companion object {
        private const val CHANNEL_ID = "model_router_service"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "RouterService"

        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, RouterService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RouterService::class.java))
        }
    }

    private var restartAttemptCount = 0
    private val maxRestartAttempts = 5

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("服务运行中 · 端口 8190-8194"))
        Log.i(TAG, "RouterService created and started foreground")
        HealthChecker.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true

        if (intent?.action == "RESTART_SERVERS") {
            Log.i(TAG, "Received restart servers action")
            notifyServersToRestart()
        }

        if (intent?.action == "UPDATE_NOTIFICATION") {
            val text = intent.getStringExtra("text") ?: "服务运行中"
            updateNotification(text)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        HealthChecker.stop()
        super.onDestroy()
        Log.i(TAG, "RouterService destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "Task removed, attempting to restart service")
        if (restartAttemptCount < maxRestartAttempts) {
            restartAttemptCount++
            val restartIntent = Intent(this, RouterService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun notifyServersToRestart() {
        val broadcast = Intent("com.example.modelrouter.ACTION_RESTART_SERVERS")
        broadcast.setPackage(packageName)
        sendBroadcast(broadcast)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Router 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 Model Router 服务运行"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restartIntent = Intent(this, RouterService::class.java).apply {
            action = "RESTART_SERVERS"
        }
        val restartPendingIntent = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Model Router")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_notification, "重启服务", restartPendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }
}
