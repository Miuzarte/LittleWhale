package io.github.miuzarte.littlewhale.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.miuzarte.littlewhale.MainActivity
import io.github.miuzarte.littlewhale.R
import io.github.miuzarte.littlewhale.channel.PrivilegedBridge
import io.github.miuzarte.littlewhale.channel.PrivilegedChannel
import io.github.miuzarte.littlewhale.channel.VirtualScreen

/**
 * Keeps the host process alive while the app is not in front
 *
 * Android reclaims a backgrounded app process, and the host is meant to keep answering the
 * browser GUI (and later the model's tools) whether or not the activity is visible, so the
 * process rides a special-use foreground service
 */
class DshHostService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        // The privileged channel belongs to the app process, which outlives any activity, and the
        // socket the host's tools call it through is opened here so that whoever starts the host
        // finds it already there
        PrivilegedChannel.initialize(this)
        warmUpChannel()
        VirtualScreen.initialize(this)
        PrivilegedBridge.start()
        DshHost.start(this)
    }

    /**
     * Connect the privileged channel now rather than when something first needs it
     *
     * Connecting means starting an `app_process` and waiting for its binder, which is seconds - and
     * without this the bill would land on whichever call comes first, which is the model's first
     * screenshot. Doing it in the background at startup moves that cost to where nobody is waiting,
     * and it is also what lets the settings page show a real state the moment it is opened
     *
     * What it may ask for: `su` (a root manager decides whether that is a prompt or nothing at all)
     * and Shizuku's own permission dialog when Shizuku is running but this app is not allowed yet.
     * Both appear while the user is opening the app, which is the moment they can answer
     */
    private fun warmUpChannel() {
        Thread({
            try {
                PrivilegedChannel.ensure()
            } catch (error: Throwable) {
                Log.w(TAG, "the warm-up connection failed", error)
            }
        }, "lw-channel-warmup").apply { isDaemon = true }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DshHost.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        DshHost.stop()
        PrivilegedBridge.stop()
        // The privileged process would exit on its own once this one dies, but releasing root
        // explicitly is the difference between a service restart and a root process left running
        // on a thread of its own, because the call is a binder round trip and this is not
        Thread({ PrivilegedChannel.close() }, "lw-channel-close").apply { isDaemon = true }.start()
        super.onDestroy()
    }

    /** Notification channel for the host, low importance because the host is not an event */
    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.host_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.host_channel_description) }
        manager.createNotificationChannel(channel)
    }

    /** Persistent notification that is also the way back into the GUI */
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.host_notification_title))
            .setContentText(getString(R.string.host_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "DshHostService"

        private const val CHANNEL_ID = "dsh-host"
        private const val NOTIFICATION_ID = 1

        /** Bring the host service up, from an activity or a receiver */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, DshHostService::class.java))
        }
    }
}
