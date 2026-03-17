/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink.findmyphone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import xyz.hanson.fosslink.R
import xyz.hanson.fosslink.network.ProtocolMessage

class FindMyPhoneHandler(private val context: Context) {
    private val TAG = "FindMyPhoneHandler"
    private val CHANNEL_ID = "fosslink_findmyphone"
    private val NOTIFICATION_ID = 9001
    private val TIMEOUT_MS = 5 * 60 * 1000L // 5 minute safety timeout
    private val VIBRATE_PATTERN = longArrayOf(0, 500, 500)

    private val audioManager: AudioManager =
        ContextCompat.getSystemService(context, AudioManager::class.java)!!
    private val vibrator: Vibrator
    private val notificationManager: NotificationManager =
        ContextCompat.getSystemService(context, NotificationManager::class.java)!!
    private var mediaPlayer: MediaPlayer? = null
    private var previousVolume: Int = -1
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { stopPlaying() }

    companion object {
        var instance: FindMyPhoneHandler? = null
            private set
    }

    init {
        instance = this
        createNotificationChannel()

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = ContextCompat.getSystemService(context, VibratorManager::class.java)
            vibratorManager?.defaultVibrator ?: ContextCompat.getSystemService(context, Vibrator::class.java)!!
        } else {
            @Suppress("DEPRECATION")
            ContextCompat.getSystemService(context, Vibrator::class.java)!!
        }
    }

    fun handleMessage(msg: ProtocolMessage) {
        Log.i(TAG, "Find my phone request received")
        startPlaying()
        showNotification()
    }

    fun startPlaying() {
        if (mediaPlayer?.isPlaying == true) {
            // Already playing, ignore duplicate request
            return
        }

        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Settings.System.DEFAULT_RINGTONE_URI)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build()
                )
                isLooping = true
                prepare()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPlayer", e)
            return
        }

        // Force alarm volume to maximum
        previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(
            AudioManager.STREAM_ALARM,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0
        )

        mediaPlayer?.start()

        // Start vibration
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(VIBRATE_PATTERN, 0))
        }

        // Safety timeout
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    fun stopPlaying() {
        handler.removeCallbacks(timeoutRunnable)

        // Restore volume
        if (previousVolume != -1) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
            previousVolume = -1
        }

        // Stop audio
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.stop()
            }
            player.release()
        }
        mediaPlayer = null

        // Stop vibration
        vibrator.cancel()

        // Dismiss notification
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    fun destroy() {
        stopPlaying()
        instance = null
    }

    private fun showNotification() {
        val activityIntent = Intent(context, FindMyPhoneActivity::class.java)
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val activityPendingIntent = PendingIntent.getActivity(
            context, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val foundItIntent = Intent(context, FindMyPhoneReceiver::class.java)
        foundItIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        foundItIntent.action = FindMyPhoneReceiver.ACTION_FOUND_IT

        val foundItPendingIntent = PendingIntent.getBroadcast(
            context, 0, foundItIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Find My Phone")
            .setContentText("Your phone is ringing")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(activityPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(0, "Found It", foundItPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Find My Phone",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when your phone is being located"
            enableVibration(true)
            setBypassDnd(true)
        }
        notificationManager.createNotificationChannel(channel)
    }
}
