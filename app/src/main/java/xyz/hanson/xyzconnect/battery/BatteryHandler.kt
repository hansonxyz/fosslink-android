/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import org.json.JSONObject
import xyz.hanson.fosslink.network.ProtocolMessage

/**
 * Monitors battery state and pushes updates to the desktop.
 * Sends fosslink.battery on connect and on change.
 * Responds to fosslink.battery.request with current state.
 */
class BatteryHandler(private val context: Context) {
    private val TAG = "BatteryHandler"

    private var sendFn: ((ProtocolMessage) -> Unit)? = null
    private var receiver: BroadcastReceiver? = null

    val isRunning: Boolean get() = receiver != null

    // Track last sent values to avoid duplicate pushes
    private var lastCharge = -1
    private var lastCharging = false

    /**
     * Start monitoring battery and pushing updates via [send].
     * Call when WebSocket is connected and paired.
     */
    fun start(send: (ProtocolMessage) -> Unit) {
        if (receiver != null) {
            stop()
        }

        sendFn = send

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                    handleBatteryChanged(intent)
                }
            }
        }

        // Register for battery changes — sticky broadcast returns current state immediately
        val intent = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            handleBatteryChanged(intent)
        }

        Log.i(TAG, "Battery monitoring started")
    }

    /**
     * Stop monitoring battery.
     * Call when WebSocket disconnects.
     */
    fun stop() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister battery receiver", e)
            }
        }
        receiver = null
        sendFn = null
        lastCharge = -1
        lastCharging = false
        Log.i(TAG, "Battery monitoring stopped")
    }

    /**
     * Handle a battery request from the desktop.
     * Responds with current battery state.
     */
    fun handleRequest(send: (ProtocolMessage) -> Unit) {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            val (charge, charging) = extractBatteryState(intent)
            sendBatteryPacket(send, charge, charging)
        }
    }

    private fun handleBatteryChanged(intent: Intent) {
        val (charge, charging) = extractBatteryState(intent)

        // Only send if values changed
        if (charge != lastCharge || charging != lastCharging) {
            lastCharge = charge
            lastCharging = charging
            sendFn?.let { sendBatteryPacket(it, charge, charging) }
        }
    }

    private fun extractBatteryState(intent: Intent): Pair<Int, Boolean> {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val charge = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return charge to charging
    }

    private fun sendBatteryPacket(send: (ProtocolMessage) -> Unit, charge: Int, charging: Boolean) {
        val body = JSONObject().apply {
            put("currentCharge", charge)
            put("isCharging", charging)
        }
        Log.i(TAG, "Sending battery: charge=$charge%, charging=$charging")
        send(ProtocolMessage(ProtocolMessage.TYPE_BATTERY, body))
    }
}
