/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink.sms

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import xyz.hanson.fosslink.network.ProtocolMessage
import kotlin.math.ceil
import kotlin.math.min

/**
 * Handles the SMS sync protocol.
 *
 * When the desktop sends a sync_start message, this handler:
 * 1. Queries SMS/MMS content providers for messages newer than lastSyncTimestamp
 * 2. Sends conversation snippets as batch 0
 * 3. Sends messages in batches of BATCH_SIZE
 * 4. Sends sync_complete with total count and latest timestamp
 *
 * Must be called from a background thread (IO dispatcher).
 */
class SmsSyncHandler(private val context: Context) {

    companion object {
        private const val TAG = "SmsSyncHandler"
        private const val BATCH_SIZE = 100
    }

    /**
     * Handle an incoming sync_start message.
     * Reads all SMS/MMS newer than the given timestamp and sends them in batches.
     *
     * @param msg The sync_start protocol message
     * @param send Function to send a ProtocolMessage back to the desktop
     */
    fun handleMessage(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        when (msg.type) {
            ProtocolMessage.TYPE_SYNC_START -> {
                val lastSyncTimestamp = msg.body.optLong("lastSyncTimestamp", 0)
                handleSyncStart(lastSyncTimestamp, send)
            }
            ProtocolMessage.TYPE_SYNC_ACK -> {
                val batchIndex = msg.body.optInt("batchIndex", -1)
                Log.d(TAG, "Sync ack received: batchIndex=$batchIndex")
            }
        }
    }

    private fun handleSyncStart(lastSyncTimestamp: Long, send: (ProtocolMessage) -> Unit) {
        Log.i(TAG, "Sync requested, lastSyncTimestamp=$lastSyncTimestamp")

        // Step 1: Get conversation snippets (latest message per thread)
        val conversations = SmsReader.getConversationSnippets(context)
        Log.i(TAG, "Found ${conversations.size} conversations")

        // Step 2: Get all messages newer than lastSyncTimestamp
        val messages = SmsReader.getMessagesNewerThan(context, lastSyncTimestamp)
        Log.i(TAG, "Found ${messages.size} messages to sync")

        // Calculate batch counts
        val messageBatchCount = if (messages.isEmpty()) 0
            else ceil(messages.size / BATCH_SIZE.toFloat()).toInt()
        val totalBatches = 1 + messageBatchCount  // 1 for conversations + N for messages

        // Batch 0: conversation snippets
        sendBatch(conversations, 0, totalBatches, send)

        // Batches 1..N: messages in chunks of BATCH_SIZE
        var latestTimestamp = lastSyncTimestamp
        for (i in 0 until messageBatchCount) {
            val start = i * BATCH_SIZE
            val end = min(start + BATCH_SIZE, messages.size)
            val chunk = messages.subList(start, end)

            // Track the latest timestamp across all messages
            for (msg in chunk) {
                val ts = msg.optLong("date", 0)
                if (ts > latestTimestamp) latestTimestamp = ts
            }

            sendBatch(chunk, i + 1, totalBatches, send)
        }

        // Send sync_complete
        send(ProtocolMessage(ProtocolMessage.TYPE_SYNC_COMPLETE, JSONObject().apply {
            put("messageCount", messages.size)
            put("latestTimestamp", latestTimestamp)
        }))

        Log.i(TAG, "Sync complete: ${messages.size} messages in $totalBatches batches, latestTimestamp=$latestTimestamp")
    }

    private fun sendBatch(
        messages: List<JSONObject>,
        batchIndex: Int,
        totalBatches: Int,
        send: (ProtocolMessage) -> Unit,
    ) {
        val messagesArray = JSONArray()
        for (msg in messages) {
            messagesArray.put(msg)
        }

        send(ProtocolMessage(ProtocolMessage.TYPE_SYNC_BATCH, JSONObject().apply {
            put("messages", messagesArray)
            put("batchIndex", batchIndex)
            put("totalBatches", totalBatches)
        }))

        Log.d(TAG, "Sent batch $batchIndex/$totalBatches: ${messages.size} messages")
    }
}
