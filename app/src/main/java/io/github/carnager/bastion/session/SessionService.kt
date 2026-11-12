package io.github.carnager.bastion.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.carnager.bastion.R
import io.github.carnager.bastion.ui.MainActivity

/** Keeps the process (and so the SSH sockets) alive while sessions are open and the app is in the background. */
class SessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        startForeground(NOTIFICATION_ID, notification(count), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_NOT_STICKY
    }

    private fun notification(count: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_sessions), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(resources.getQuantityString(R.plurals.sessions_active, count, count))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "sessions"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_COUNT = "count"

        fun update(context: Context, count: Int) {
            val intent = Intent(context, SessionService::class.java)
            if (count == 0) {
                context.stopService(intent)
            } else {
                context.startForegroundService(intent.putExtra(EXTRA_COUNT, count))
            }
        }
    }
}
