package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.TTS_NOTIFICATION_CHANNEL_ID

private const val TAG = "TtsPlaybackService"

/**
 * TTS 前台服务：在通知栏显示常驻通知，防止后台/锁屏时进程被系统杀死。
 * 本身不包含任何 TTS 播放逻辑，仅用于维持进程优先级。
 */
class TtsPlaybackService : Service() {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.TTS_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.TTS_STOP"
        const val NOTIFICATION_ID = 2002
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat()
            }
            ACTION_STOP, null -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, TTS_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("TTS 朗读中")
            .setContentText("正在后台播放语音")
            .setContentIntent(buildLaunchPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", buildStopPendingIntent())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildLaunchPendingIntent() = PendingIntent.getActivity(
        this,
        0,
        packageManager.getLaunchIntentForPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildStopPendingIntent(): PendingIntent {
        val stopIntent = Intent(this, TtsPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
    }
}
