/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink.sms

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Telephony
import android.util.Log
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import xyz.hanson.fosslink.network.ProtocolMessage
import java.util.UUID

/**
 * Observes SMS/MMS changes via ContentObserver and pushes real-time events
 * to the desktop. Also handles desktop commands (mark read, delete).
 *
 * No queue, no acks. WebSocket handles delivery while connected.
 * On disconnect, the desktop does a full resync on reconnect.
 */
class SmsEventHandler(private val context: Context) {
    private val TAG = "SmsEventHandler"

    private val CONVERSATION_URI: Uri = "content://mms-sms/conversations?simple=true".toUri()
    private val MMS_ID_OFFSET = 2_000_000_000L

    private var observer: ContentObserver? = null
    private var sendFn: ((ProtocolMessage) -> Unit)? = null

    val isRunning: Boolean get() = observer != null

    // State for diffing
    private var mostRecentTimestamp: Long = 0
    private var knownThreadIds: Set<Long> = emptySet()
    private var unreadThreadIds: Set<Long> = emptySet()
    private var messageIdsByThread: Map<Long, Set<Long>> = emptyMap()
    private var initialized = false

    /**
     * Start observing SMS/MMS changes and pushing events via [send].
     * Call this when the WebSocket connection is established and paired.
     */
    fun start(send: (ProtocolMessage) -> Unit) {
        if (observer != null) {
            Log.w(TAG, "Already started, stopping first")
            stop()
        }

        sendFn = send
        mostRecentTimestamp = SmsReader.getNewestMessageTimestamp(context)
        initialized = false
        Log.i(TAG, "Starting event handler, mostRecentTimestamp=$mostRecentTimestamp")

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                detectNewMessages()
                detectStateChanges()
            }
        }
        context.contentResolver.registerContentObserver(CONVERSATION_URI, true, observer!!)

        // Push initial settings
        sendSettings()
    }

    /**
     * Stop observing and clean up state.
     * Call this when the WebSocket disconnects.
     */
    fun stop() {
        observer?.let {
            context.contentResolver.unregisterContentObserver(it)
            Log.i(TAG, "ContentObserver unregistered")
        }
        observer = null
        sendFn = null
        initialized = false
        knownThreadIds = emptySet()
        unreadThreadIds = emptySet()
        messageIdsByThread = emptyMap()
        mostRecentTimestamp = 0
    }

    /**
     * Handle an incoming command from the desktop.
     */
    fun handleCommand(msg: ProtocolMessage) {
        when (msg.type) {
            ProtocolMessage.TYPE_MARK_READ -> handleMarkRead(msg)
            ProtocolMessage.TYPE_DELETE -> handleDelete(msg)
            ProtocolMessage.TYPE_DELETE_THREAD -> handleDeleteThread(msg)
            else -> Log.w(TAG, "Unknown command type: ${msg.type}")
        }
    }

    // --- New message detection ---

    private fun detectNewMessages() {
        val send = sendFn ?: return

        val newMessages = SmsReader.getMessagesNewerThan(context, mostRecentTimestamp)
        if (newMessages.isEmpty()) return

        // Update timestamp to the newest message
        val newestDate = newMessages.maxOfOrNull { it.optLong("date", 0) } ?: mostRecentTimestamp
        if (newestDate > mostRecentTimestamp) {
            mostRecentTimestamp = newestDate
        }

        // Split by type: 1=received, 2+=sent
        val received = newMessages.filter { it.optInt("type", 0) == 1 }
        val sent = newMessages.filter { it.optInt("type", 0) >= 2 }

        if (received.isNotEmpty()) {
            val event = buildMessageEvent("received", received)
            Log.i(TAG, "Pushing ${received.size} received message(s)")
            send(event)
        }

        if (sent.isNotEmpty()) {
            val event = buildMessageEvent("sent", sent)
            Log.i(TAG, "Pushing ${sent.size} sent message(s)")
            send(event)
        }
    }

    // --- State change detection (read, deleted, thread_deleted) ---

    private fun detectStateChanges() {
        val send = sendFn ?: return

        val currentThreadIds = SmsReader.getAllThreadIds(context).toSet()
        val currentUnreadThreadIds = SmsReader.getUnreadThreadIds(context)
        val currentMessageIds = SmsReader.getMessageIdsByThread(context)

        if (!initialized) {
            // First run: just populate baseline state, don't emit events
            knownThreadIds = currentThreadIds
            unreadThreadIds = currentUnreadThreadIds
            messageIdsByThread = currentMessageIds
            initialized = true
            Log.i(TAG, "State initialized: ${knownThreadIds.size} threads, ${unreadThreadIds.size} unread")
            return
        }

        // Detect thread deletions
        val deletedThreads = knownThreadIds - currentThreadIds
        for (threadId in deletedThreads) {
            Log.i(TAG, "Thread deleted: $threadId")
            send(buildStateEvent("thread_deleted", threadId = threadId))
        }

        // Detect read status changes (was unread, now read, not deleted)
        val newlyRead = (unreadThreadIds - currentUnreadThreadIds) - deletedThreads
        for (threadId in newlyRead) {
            Log.i(TAG, "Thread marked read: $threadId")
            send(buildStateEvent("read", threadId = threadId))
        }

        // Detect message deletions within existing threads
        for ((threadId, oldIds) in messageIdsByThread) {
            if (threadId in deletedThreads) continue
            val currentIds = currentMessageIds[threadId] ?: emptySet()
            val deletedIds = oldIds - currentIds
            if (deletedIds.isNotEmpty()) {
                Log.i(TAG, "Messages deleted in thread $threadId: $deletedIds")
                send(buildStateEvent("deleted", messageIds = deletedIds.toList()))
            }
        }

        // Update stored state
        knownThreadIds = currentThreadIds
        unreadThreadIds = currentUnreadThreadIds
        messageIdsByThread = currentMessageIds
    }

    // --- Desktop command handlers ---

    private fun handleMarkRead(msg: ProtocolMessage) {
        val threadId = msg.body.optLong("threadId", -1)
        if (threadId < 0) {
            Log.w(TAG, "mark_read missing threadId")
            return
        }
        Log.i(TAG, "Marking thread $threadId as read")

        val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }

        // Mark SMS as read
        try {
            val smsUpdated = context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString())
            )
            Log.i(TAG, "Marked $smsUpdated SMS as read in thread $threadId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark SMS as read (not default SMS app?)", e)
        }

        // Mark MMS as read
        try {
            val mmsValues = ContentValues().apply { put(Telephony.Mms.READ, 1) }
            val mmsUpdated = context.contentResolver.update(
                Telephony.Mms.CONTENT_URI,
                mmsValues,
                "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0",
                arrayOf(threadId.toString())
            )
            Log.i(TAG, "Marked $mmsUpdated MMS as read in thread $threadId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark MMS as read (not default SMS app?)", e)
        }
    }

    private fun handleDelete(msg: ProtocolMessage) {
        val messageId = msg.body.optLong("messageId", -1)
        if (messageId < 0) {
            Log.w(TAG, "delete missing messageId")
            return
        }

        if (messageId >= MMS_ID_OFFSET) {
            // MMS — subtract offset to get real ID
            val realId = messageId - MMS_ID_OFFSET
            Log.i(TAG, "Deleting MMS $realId (offset messageId=$messageId)")
            try {
                val deleted = context.contentResolver.delete(
                    Telephony.Mms.CONTENT_URI,
                    "${Telephony.Mms._ID} = ?",
                    arrayOf(realId.toString())
                )
                Log.i(TAG, "Deleted $deleted MMS record(s)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete MMS (not default SMS app?)", e)
            }
        } else {
            // SMS
            Log.i(TAG, "Deleting SMS $messageId")
            try {
                val deleted = context.contentResolver.delete(
                    Telephony.Sms.CONTENT_URI,
                    "${Telephony.Sms._ID} = ?",
                    arrayOf(messageId.toString())
                )
                Log.i(TAG, "Deleted $deleted SMS record(s)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete SMS (not default SMS app?)", e)
            }
        }
    }

    private fun handleDeleteThread(msg: ProtocolMessage) {
        val threadId = msg.body.optLong("threadId", -1)
        if (threadId < 0) {
            Log.w(TAG, "delete_thread missing threadId")
            return
        }
        Log.i(TAG, "Deleting thread $threadId")

        // Delete all SMS in thread
        try {
            val smsDeleted = context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString())
            )
            Log.i(TAG, "Deleted $smsDeleted SMS in thread $threadId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete SMS in thread (not default SMS app?)", e)
        }

        // Delete all MMS in thread
        try {
            val mmsDeleted = context.contentResolver.delete(
                Telephony.Mms.CONTENT_URI,
                "${Telephony.Mms.THREAD_ID} = ?",
                arrayOf(threadId.toString())
            )
            Log.i(TAG, "Deleted $mmsDeleted MMS in thread $threadId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete MMS in thread (not default SMS app?)", e)
        }
    }

    // --- Settings push ---

    private fun sendSettings() {
        sendSettingsTo(sendFn ?: return)
    }

    /** Send current settings to a specific client (used when a new desktop connects) */
    fun sendSettingsTo(send: (ProtocolMessage) -> Unit) {
        val prefs = context.getSharedPreferences("fosslink", Context.MODE_PRIVATE)
        val rootEnabled = prefs.getBoolean("root_integration", false)

        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.totalBytes
        val freeBytes = stat.availableBytes

        val body = JSONObject().apply {
            put("rootEnabled", rootEnabled)
            put("storageTotalBytes", totalBytes)
            put("storageFreeBytes", freeBytes)
        }

        Log.i(TAG, "Pushing settings: rootEnabled=$rootEnabled, total=${totalBytes / (1024*1024)}MB, free=${freeBytes / (1024*1024)}MB")
        send(ProtocolMessage(ProtocolMessage.TYPE_SETTINGS, body))
    }

    // --- Event builders ---

    private fun buildMessageEvent(eventType: String, messages: List<JSONObject>): ProtocolMessage {
        val messagesArray = JSONArray()
        for (msg in messages) {
            messagesArray.put(msg)
        }

        val body = JSONObject().apply {
            put("event", eventType)
            put("eventId", UUID.randomUUID().toString())
            put("messages", messagesArray)
        }

        return ProtocolMessage(ProtocolMessage.TYPE_SMS_EVENT, body)
    }

    private fun buildStateEvent(
        eventType: String,
        threadId: Long? = null,
        messageIds: List<Long>? = null
    ): ProtocolMessage {
        val body = JSONObject().apply {
            put("event", eventType)
            put("eventId", UUID.randomUUID().toString())
            if (threadId != null) put("threadId", threadId)
            if (messageIds != null) {
                val arr = JSONArray()
                for (id in messageIds) arr.put(id)
                put("messageIds", arr)
            }
        }

        return ProtocolMessage(ProtocolMessage.TYPE_SMS_EVENT, body)
    }
}
