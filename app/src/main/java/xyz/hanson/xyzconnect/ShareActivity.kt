/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.json.JSONObject
import xyz.hanson.fosslink.network.ProtocolMessage
import xyz.hanson.fosslink.service.ConnectionService

class ShareActivity : ComponentActivity() {
    private val TAG = "ShareActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val url = extractUrl(text)
            if (url != null) {
                sendUrlToDesktop(url)
            } else {
                Toast.makeText(this, "No URL found in shared text", Toast.LENGTH_SHORT).show()
            }
        }

        finish()
    }

    private fun extractUrl(text: String): String? {
        val regex = Regex("https?://\\S+")
        return regex.find(text)?.value
    }

    private fun sendUrlToDesktop(url: String) {
        val service = ConnectionService.instance
        val clients = service?.getAllConnectedClients()
        if (clients.isNullOrEmpty()) {
            Toast.makeText(this, "Not connected to desktop", Toast.LENGTH_SHORT).show()
            return
        }

        val msg = ProtocolMessage(
            ProtocolMessage.TYPE_URL_SHARE,
            JSONObject().apply { put("url", url) }
        )
        // Send to all connected desktops
        clients.forEach { it.send(msg) }
        Log.i(TAG, "Shared URL to ${clients.size} desktop(s): $url")
        Toast.makeText(this, "URL shared to desktop", Toast.LENGTH_SHORT).show()
    }
}
