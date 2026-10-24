package app.greeny.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Keeps the mic alive while a session runs, even if the screen goes off or the app is in the background. */
class SessionService : Service() {
    companion object {
        private const val CHANNEL = "greeny-session"
        fun start(context: Context, name: String) {
            try {
                ContextCompat.startForegroundService(context,
                    Intent(context, SessionService::class.java).putExtra("name", name))
            } catch (_: Exception) {}
        }
        fun stop(context: Context) { context.stopService(Intent(context, SessionService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Gespräch", NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("${intent?.getStringExtra("name") ?: "Greeny"} hört zu")
            .setContentText("Tippen zum Öffnen. Doppeltippen schickt ihn schlafen.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
        return START_NOT_STICKY
    }
}
