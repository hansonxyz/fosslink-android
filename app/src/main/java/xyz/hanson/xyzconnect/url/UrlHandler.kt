/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink.url

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import xyz.hanson.fosslink.R
import xyz.hanson.fosslink.network.ProtocolMessage

class UrlHandler(private val context: Context) {
    private val TAG = "UrlHandler"

    fun handleMessage(msg: ProtocolMessage) {
        val url = msg.body.optString("url", "")
        if (url.startsWith("http://") || url.startsWith("https://")) {
            Log.i(TAG, "Received URL from desktop: $url")
            openUrl(url)
        } else {
            Log.w(TAG, "Received invalid URL: $url")
        }
    }

    private val isRootEnabled: Boolean
        get() = context.getSharedPreferences("fosslink", Context.MODE_PRIVATE)
            .getBoolean("root_integration", false)

    private fun openUrl(url: String) {
        if (isRootEnabled && openUrlViaRoot(url)) {
            return
        }

        // Non-root: notification with full-screen intent (bypasses Android 10+ background launch restriction)
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val pendingIntent = PendingIntent.getActivity(
            context, url.hashCode(), viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Use the URL notification channel (high priority for full-screen intent)
        val notification = NotificationCompat.Builder(context, "fosslink_url")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("URL from Desktop")
            .setContentText(url)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun openUrlViaRoot(url: String): Boolean {
        try {
            val escaped = url.replace("'", "'\\''")
            val process = ProcessBuilder("su", "-c", "am start -a android.intent.action.VIEW -d '$escaped'")
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Log.i(TAG, "Opened URL via root: $url")
                return true
            }
            Log.w(TAG, "am start failed with exit code $exitCode")
        } catch (e: Exception) {
            Log.w(TAG, "Root URL open failed, falling back to notification", e)
        }
        return false
    }
}
