/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink.sms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Telephony
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Clean-room SMS/MMS content provider reader.
 *
 * Queries Android's SMS and MMS content providers and builds JSONObjects
 * matching the desktop's expected message format.
 */
object SmsReader {
    private const val TAG = "SmsReader"

    /**
     * Offset added to MMS _id values to prevent collisions with SMS _ids.
     * Android's SMS and MMS tables have separate auto-increment _id sequences.
     */
    private const val MMS_ID_OFFSET = 2_000_000_000L

    private const val THUMBNAIL_SIZE = 100

    // MMS address types (from PduHeaders)
    private const val PDU_HEADER_FROM = 137
    // private const val PDU_HEADER_TO = 151

    private val MMS_PART_URI: Uri = "content://mms/part/".toUri()
    private val CONVERSATION_URI: Uri = "content://mms-sms/conversations?simple=true".toUri()

    // SMS columns to query
    private val SMS_COLUMNS = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.THREAD_ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE,
        Telephony.Sms.READ,
        Telephony.Sms.SUBSCRIPTION_ID,
    )

    // MMS columns to query
    private val MMS_COLUMNS = arrayOf(
        Telephony.Mms._ID,
        Telephony.Mms.THREAD_ID,
        Telephony.Mms.DATE,
        Telephony.Mms.READ,
        Telephony.Mms.MESSAGE_BOX,
        Telephony.Mms.SUBSCRIPTION_ID,
    )

    /**
     * Get the latest message from each conversation thread (for conversation snippets).
     * Returns one JSONObject per thread, sorted most-recent first.
     */
    fun getConversationSnippets(context: Context): List<JSONObject> {
        val threadIds = getThreadIds(context)
        val snippets = mutableListOf<JSONObject>()

        for (threadId in threadIds) {
            try {
                val msg = getLatestMessageInThread(context, threadId)
                if (msg != null) {
                    snippets.add(msg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting snippet for thread $threadId", e)
            }
        }

        return snippets
    }

    /**
     * Get all messages newer than the given timestamp.
     * If timestamp is 0, returns all messages.
     */
    fun getMessagesNewerThan(context: Context, timestamp: Long): List<JSONObject> {
        val smsMessages = getSmsMessages(context, timestamp)
        val mmsMessages = getMmsMessages(context, timestamp)

        val all = mutableListOf<JSONObject>()
        all.addAll(smsMessages)
        all.addAll(mmsMessages)

        // Sort by date descending (newest first)
        all.sortByDescending { it.optLong("date", 0) }
        return all
    }

    // --- Query methods for event diffing ---

    /**
     * Get the timestamp of the newest message across SMS and MMS.
     * Returns 0 if no messages exist.
     */
    fun getNewestMessageTimestamp(context: Context): Long {
        var newest = 0L

        // Check SMS (dates in milliseconds)
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("MAX(${Telephony.Sms.DATE}) AS max_date"),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val maxDate = cursor.getLong(0)
                    if (maxDate > newest) newest = maxDate
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying newest SMS timestamp", e)
        }

        // Check MMS (dates in seconds, convert to ms)
        try {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf("MAX(${Telephony.Mms.DATE}) AS max_date"),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val maxDate = cursor.getLong(0) * 1000
                    if (maxDate > newest) newest = maxDate
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying newest MMS timestamp", e)
        }

        return newest
    }

    /**
     * Get all current thread IDs, sorted most-recent first.
     */
    fun getAllThreadIds(context: Context): List<Long> = getThreadIds(context)

    /**
     * Get thread IDs that have at least one unread message.
     */
    fun getUnreadThreadIds(context: Context): Set<Long> {
        val unread = mutableSetOf<Long>()

        // Unread SMS
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("DISTINCT ${Telephony.Sms.THREAD_ID}"),
                "${Telephony.Sms.READ} = 0",
                null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    unread.add(cursor.getLong(0))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying unread SMS threads", e)
        }

        // Unread MMS
        try {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf("DISTINCT ${Telephony.Mms.THREAD_ID}"),
                "${Telephony.Mms.READ} = 0",
                null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    unread.add(cursor.getLong(0))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying unread MMS threads", e)
        }

        return unread
    }

    /**
     * Get all message IDs grouped by thread ID.
     * MMS IDs are offset by MMS_ID_OFFSET to match what we send to the desktop.
     */
    fun getMessageIdsByThread(context: Context): Map<Long, Set<Long>> {
        val result = mutableMapOf<Long, MutableSet<Long>>()

        // SMS IDs
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val threadCol = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                while (cursor.moveToNext()) {
                    val threadId = cursor.getLong(threadCol)
                    val msgId = cursor.getLong(idCol)
                    result.getOrPut(threadId) { mutableSetOf() }.add(msgId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying SMS message IDs", e)
        }

        // MMS IDs (with offset)
        try {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                val threadCol = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
                while (cursor.moveToNext()) {
                    val threadId = cursor.getLong(threadCol)
                    val msgId = cursor.getLong(idCol) + MMS_ID_OFFSET
                    result.getOrPut(threadId) { mutableSetOf() }.add(msgId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MMS message IDs", e)
        }

        return result
    }

    // --- Thread IDs ---

    private fun getThreadIds(context: Context): List<Long> {
        val threadIds = mutableListOf<Pair<Long, Long>>() // (threadId, date)

        try {
            context.contentResolver.query(
                CONVERSATION_URI,
                null,
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex("_id")
                val dateCol = cursor.getColumnIndex("date")

                while (cursor.moveToNext()) {
                    if (idCol >= 0 && !cursor.isNull(idCol)) {
                        val threadId = cursor.getLong(idCol)
                        val date = if (dateCol >= 0 && !cursor.isNull(dateCol)) cursor.getLong(dateCol) else 0L
                        threadIds.add(threadId to date)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying thread IDs", e)
        }

        // Sort most-recent first
        threadIds.sortByDescending { it.second }
        return threadIds.map { it.first }
    }

    private fun getLatestMessageInThread(context: Context, threadId: Long): JSONObject? {
        // Try SMS first
        val sms = getSmsMessagesInThread(context, threadId, 1)
        // Try MMS
        val mms = getMmsMessagesInThread(context, threadId, 1)

        // Return whichever is newer
        val smsDate = sms.firstOrNull()?.optLong("date", 0) ?: 0
        val mmsDate = mms.firstOrNull()?.optLong("date", 0) ?: 0

        return when {
            smsDate >= mmsDate && sms.isNotEmpty() -> sms.first()
            mms.isNotEmpty() -> mms.first()
            sms.isNotEmpty() -> sms.first()
            else -> null
        }
    }

    // --- SMS Queries ---

    private fun getSmsMessages(context: Context, newerThan: Long): List<JSONObject> {
        val selection = if (newerThan > 0) "${Telephony.Sms.DATE} >= ?" else null
        val selectionArgs = if (newerThan > 0) arrayOf(newerThan.toString()) else null

        return querySms(context, selection, selectionArgs, null)
    }

    private fun getSmsMessagesInThread(context: Context, threadId: Long, limit: Int): List<JSONObject> {
        val selection = "${Telephony.Sms.THREAD_ID} = ?"
        val selectionArgs = arrayOf(threadId.toString())

        return querySms(context, selection, selectionArgs, limit)
    }

    private fun querySms(
        context: Context,
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int?,
    ): List<JSONObject> {
        val messages = mutableListOf<JSONObject>()
        val sortOrder = "${Telephony.Sms.DATE} DESC" + if (limit != null) " LIMIT $limit" else ""

        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                SMS_COLUMNS,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    try {
                        messages.add(parseSms(cursor))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing SMS message", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying SMS", e)
        }

        return messages
    }

    private fun parseSms(cursor: android.database.Cursor): JSONObject {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID))
        val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
        val address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
        val body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
        val date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE))
        val type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
        val read = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ))

        val subId = try {
            cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID))
        } catch (_: Exception) { 0 }

        return JSONObject().apply {
            put("_id", id)
            put("thread_id", threadId)
            put("addresses", JSONArray().put(JSONObject().put("address", address)))
            put("body", body)
            put("date", date)  // SMS dates are already in milliseconds
            put("type", type)
            put("read", read)
            put("sub_id", subId)
            put("event", 1)  // EVENT_TEXT_MESSAGE
        }
        // Note: no "attachments" key for SMS — this tells the desktop it's SMS, not MMS
    }

    // --- MMS Queries ---

    private fun getMmsMessages(context: Context, newerThan: Long): List<JSONObject> {
        // MMS dates are in seconds, not milliseconds
        val newerThanSeconds = newerThan / 1000
        val selection = if (newerThan > 0) "${Telephony.Mms.DATE} >= ?" else null
        val selectionArgs = if (newerThan > 0) arrayOf(newerThanSeconds.toString()) else null

        return queryMms(context, selection, selectionArgs, null)
    }

    private fun getMmsMessagesInThread(context: Context, threadId: Long, limit: Int): List<JSONObject> {
        val selection = "${Telephony.Mms.THREAD_ID} = ?"
        val selectionArgs = arrayOf(threadId.toString())

        return queryMms(context, selection, selectionArgs, limit)
    }

    private fun queryMms(
        context: Context,
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int?,
    ): List<JSONObject> {
        val messages = mutableListOf<JSONObject>()
        val sortOrder = "${Telephony.Mms.DATE} DESC" + if (limit != null) " LIMIT $limit" else ""

        try {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                MMS_COLUMNS,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    try {
                        messages.add(parseMms(context, cursor))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing MMS message", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MMS", e)
        }

        return messages
    }

    private fun parseMms(context: Context, cursor: android.database.Cursor): JSONObject {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms._ID))
        val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID))
        val dateSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.DATE))
        val read = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.READ))
        val messageBox = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX))

        val subId = try {
            cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.SUBSCRIPTION_ID))
        } catch (_: Exception) { 0 }

        // Convert MMS message box to SMS type
        val type = when (messageBox) {
            Telephony.Mms.MESSAGE_BOX_INBOX -> 1  // received
            Telephony.Mms.MESSAGE_BOX_SENT -> 2   // sent
            else -> messageBox
        }

        // Get MMS body text and attachments
        val bodyAndParts = getMmsBodyAndParts(context, id)
        val addresses = getMmsAddresses(context, id)

        // Determine event flags
        var event = 1  // EVENT_TEXT_MESSAGE
        if (addresses.length() > 1) {
            event = event or 2  // EVENT_MULTI_TARGET
        }

        val json = JSONObject().apply {
            put("_id", id)  // Will be offset later
            put("thread_id", threadId)
            put("addresses", addresses)
            put("body", bodyAndParts.first)
            put("date", dateSeconds * 1000)  // Convert to milliseconds
            put("type", type)
            put("read", read)
            put("sub_id", subId)
            put("event", event)
            put("attachments", bodyAndParts.second)  // Always present for MMS (even empty)
        }

        // Apply MMS ID offset
        offsetMmsId(json)

        return json
    }

    /**
     * Get MMS text body and attachment parts.
     * Returns (bodyText, attachmentsJSONArray).
     */
    private fun getMmsBodyAndParts(context: Context, mmsId: Long): Pair<String, JSONArray> {
        var body = ""
        val attachments = JSONArray()

        val columns = arrayOf(
            Telephony.Mms.Part._ID,
            Telephony.Mms.Part.CONTENT_TYPE,
            Telephony.Mms.Part._DATA,
            Telephony.Mms.Part.TEXT,
        )

        try {
            context.contentResolver.query(
                MMS_PART_URI,
                columns,
                "${Telephony.Mms.Part.MSG_ID} = ?",
                arrayOf(mmsId.toString()),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val partId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.Part._ID))
                    val contentType = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)) ?: continue
                    val data = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Mms.Part._DATA))
                    val text = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT))

                    when {
                        contentType.startsWith("text/plain") -> {
                            body = if (data != null) {
                                getMmsText(context, partId)
                            } else {
                                text ?: ""
                            }
                        }
                        contentType.startsWith("image/") -> {
                            val thumbnail = generateThumbnail(context, partId)
                            val filename = data?.substringAfterLast('/') ?: "image_$partId"
                            attachments.put(JSONObject().apply {
                                put("part_id", partId)
                                put("unique_identifier", partId)
                                put("mime_type", contentType)
                                if (thumbnail != null) put("encoded_thumbnail", thumbnail)
                                put("filename", filename)
                            })
                        }
                        contentType.startsWith("video/") || contentType.startsWith("audio/") -> {
                            val filename = data?.substringAfterLast('/') ?: "media_$partId"
                            attachments.put(JSONObject().apply {
                                put("part_id", partId)
                                put("unique_identifier", partId)
                                put("mime_type", contentType)
                                put("filename", filename)
                            })
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MMS parts for mmsId=$mmsId", e)
        }

        return body to attachments
    }

    /**
     * Read MMS text body from content provider.
     */
    private fun getMmsText(context: Context, partId: Long): String {
        val partUri = Uri.withAppendedPath(MMS_PART_URI, partId.toString())
        return try {
            context.contentResolver.openInputStream(partUri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error reading MMS text for partId=$partId", e)
            ""
        }
    }

    /**
     * Get MMS addresses, filtering out the local user's numbers.
     */
    private fun getMmsAddresses(context: Context, mmsId: Long): JSONArray {
        val addresses = JSONArray()
        val uri = "content://mms/$mmsId/addr".toUri()

        try {
            context.contentResolver.query(
                uri,
                arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
                null,
                null,
                null
            )?.use { cursor ->
                val addressCol = cursor.getColumnIndex(Telephony.Mms.Addr.ADDRESS)
                val typeCol = cursor.getColumnIndex(Telephony.Mms.Addr.TYPE)

                while (cursor.moveToNext()) {
                    val address = if (addressCol >= 0) cursor.getString(addressCol) else null
                    val type = if (typeCol >= 0) cursor.getInt(typeCol) else 0

                    // Skip "from" addresses for outgoing (they're us), but include for incoming
                    // Skip the placeholder "insert-address-token"
                    if (address != null && address != "insert-address-token") {
                        // For outgoing MMS (type=137 is FROM header), skip our own number
                        // For incoming MMS, the FROM is the sender (include it)
                        // Type 137 = FROM, Type 151 = TO
                        // Include all non-placeholder addresses — the desktop handles dedup
                        addresses.put(JSONObject().put("address", address))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MMS addresses for mmsId=$mmsId", e)
        }

        // If we got no addresses, add an empty one to avoid null on desktop
        if (addresses.length() == 0) {
            addresses.put(JSONObject().put("address", "unknown"))
        }

        return addresses
    }

    /**
     * Generate a base64-encoded thumbnail for an image attachment.
     */
    private fun generateThumbnail(context: Context, partId: Long): String? {
        val partUri = Uri.withAppendedPath(MMS_PART_URI, partId.toString())
        return try {
            context.contentResolver.openInputStream(partUri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                // First pass: get dimensions
                BitmapFactory.decodeStream(stream, null, options)

                // Calculate sample size
                val sampleSize = maxOf(
                    options.outWidth / THUMBNAIL_SIZE,
                    options.outHeight / THUMBNAIL_SIZE,
                    1
                )

                // Second pass: decode with sample size
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }

                context.contentResolver.openInputStream(partUri)?.use { stream2 ->
                    val bitmap = BitmapFactory.decodeStream(stream2, null, decodeOptions)
                    if (bitmap != null) {
                        val scaled = Bitmap.createScaledBitmap(bitmap, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)
                        val baos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                        val encoded = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                        if (scaled !== bitmap) scaled.recycle()
                        bitmap.recycle()
                        encoded
                    } else null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate thumbnail for partId=$partId", e)
            null
        }
    }

    /**
     * Add MMS_ID_OFFSET to _id if this message has an "attachments" key (is MMS).
     */
    fun offsetMmsId(json: JSONObject) {
        if (json.has("attachments")) {
            json.put("_id", json.getLong("_id") + MMS_ID_OFFSET)
        }
    }
}
