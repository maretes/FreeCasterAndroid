package com.freecaster.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Тримає SSH-тунель живим у фоні й перепідключає його при зміні мережі. */
class TunnelService : Service() {
    private var cm: ConnectivityManager? = null
    private val statusListener: (SshTunnel.Status) -> Unit = { notifyStatus(it) }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        private var current: Network? = null

        override fun onAvailable(network: Network) {
            val prev = current
            current = network
            SshTunnel.onNetworkChanged(changed = prev != null && prev != network)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "SSH-тунель", NotificationManager.IMPORTANCE_LOW)
        )
        SshTunnel.addListener(statusListener)
        cm = getSystemService(ConnectivityManager::class.java)
        cm?.registerDefaultNetworkCallback(netCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SshTunnel.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(SshTunnel.status), type)

        // Система перезапустила процес: відновлюємо тунель, щоб kill switch не зник
        if (!SshTunnel.isEnabled && !(SshSettings.wasEnabled(this) && SshTunnel.start(this))) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        SshTunnel.removeListener(statusListener)
        runCatching { cm?.unregisterNetworkCallback(netCallback) }
        super.onDestroy()
    }

    private fun notifyStatus(s: SshTunnel.Status) {
        if (s.state == SshTunnel.State.OFF) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(s))
    }

    private fun build(s: SshTunnel.Status): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tunnel)
            .setContentTitle("SSH-тунель")
            .setContentText(SshTunnel.describe(s))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Відключити", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "tunnel"
        private const val NOTIFICATION_ID = 2 // 1 зайнятий ProxyService
        private const val ACTION_STOP = "stop"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, TunnelService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, TunnelService::class.java))
    }
}
