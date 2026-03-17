package xyz.hanson.fosslink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootCompletedReceiver", "Starting ConnectionService")
            val serviceIntent = Intent(context, ConnectionService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
