package xyz.hanson.fosslink.query

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Telephony
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Query handler for threads.messages
 *
 * Returns messages for a given thread, optionally filtered by date range.
 * Handles both SMS and MMS messages, including MMS attachment metadata.
 *
 * Params:
 *   threadId: number (required)
 *   sinceDate: number (optional, epoch ms — only messages >= this date)
 *   untilDate: number (optional, epoch ms — only messages <= this date)
 */
class ThreadsMessagesHandler(private val context: Context) : QueryHandler {
    private val TAG = "ThreadsMessagesHandler"
    private val MMS_ID_OFFSET = 2_000_000_000L
    private val THUMB_MAX = 100

    override fun handle(params: JSONObject): JSONArray {
        val threadId = params.optLong("threadId", -1)
        if (threadId < 0) {
            Log.w(TAG, "Missing threadId param")
            return JSONArray()
        }

        val sinceDate = if (params.has("sinceDate")) params.optLong("sinceDate", 0) else null
        val untilDate = if (params.has("untilDate")) params.optLong("untilDate", 0) else null

        val messages = mutableListOf<JSONObject>()

        // Query SMS
        querySms(threadId, sinceDate, untilDate, messages)

        // Query MMS
        queryMms(threadId, sinceDate, untilDate, messages)

        // Sort by date ascending
        messages.sortBy { it.optLong("date", 0) }

        Log.i(TAG, "threads.messages: threadId=$threadId, ${messages.size} messages" +
            (sinceDate?.let { " (since ${it})" } ?: ""))

        val result = JSONArray()
        for (msg in messages) result.put(msg)
        return result
    }

    private fun querySms(
        threadId: Long,
        sinceDate: Long?,
        untilDate: Long?,
        out: MutableList<JSONObject>
    ) {
        val selection = buildString {
            append("thread_id = ?")
            if (sinceDate != null) append(" AND date >= $sinceDate")
            if (untilDate != null) append(" AND date <= $untilDate")
        }

        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("_id", "thread_id", "address", "body", "date", "type", "read", "sub_id"),
            selection, arrayOf(threadId.toString()),
            "date ASC"
        ) ?: return

        cursor.use {
            while (it.moveToNext()) {
                val address = it.getString(it.getColumnIndexOrThrow("address")) ?: ""
                val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""

                out.add(JSONObject().apply {
                    put("_id", it.getLong(it.getColumnIndexOrThrow("_id")))
                    put("thread_id", threadId)
                    put("address", address)
                    put("body", body)
                    put("date", it.getLong(it.getColumnIndexOrThrow("date")))
                    put("type", it.getInt(it.getColumnIndexOrThrow("type")))
                    put("read", it.getInt(it.getColumnIndexOrThrow("read")))
                    put("sub_id", it.getInt(it.getColumnIndexOrThrow("sub_id")))
                    put("event", 1)
                })
            }
        }
    }

    private fun queryMms(
        threadId: Long,
        sinceDate: Long?,
        untilDate: Long?,
        out: MutableList<JSONObject>
    ) {
        val selection = buildString {
            append("thread_id = ?")
            if (sinceDate != null) append(" AND date >= ${sinceDate / 1000}")
            if (untilDate != null) append(" AND date <= ${untilDate / 1000}")
        }

        val cursor = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf("_id", "thread_id", "date", "read", "msg_box", "sub_id"),
            selection, arrayOf(threadId.toString()),
            "date ASC"
        ) ?: return

        cursor.use {
            while (it.moveToNext()) {
                val mmsId = it.getLong(it.getColumnIndexOrThrow("_id"))
                val dateSec = it.getLong(it.getColumnIndexOrThrow("date"))
                val dateMs = dateSec * 1000
                val msgBox = it.getInt(it.getColumnIndexOrThrow("msg_box"))
                val type = if (msgBox == 2) 2 else 1 // sent vs received

                val address = getMmsAddress(mmsId, type)
                val body = getMmsBody(mmsId)
                val attachments = getMmsAttachments(mmsId)

                out.add(JSONObject().apply {
                    put("_id", mmsId + MMS_ID_OFFSET)
                    put("thread_id", threadId)
                    put("address", address)
                    put("body", body)
                    put("date", dateMs)
                    put("type", type)
                    put("read", it.getInt(it.getColumnIndexOrThrow("read")))
                    put("sub_id", it.getInt(it.getColumnIndexOrThrow("sub_id")))
                    put("event", 1)
                    put("attachments", attachments)
                })
            }
        }
    }

    private fun getMmsAddress(mmsId: Long, msgType: Int): String {
        val cursor = context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            null, null, null
        ) ?: return ""

        cursor.use {
            while (it.moveToNext()) {
                val addr = it.getString(it.getColumnIndexOrThrow("address")) ?: continue
                if (addr == "insert-address-token") continue
                val addrType = it.getInt(it.getColumnIndexOrThrow("type"))
                // For received messages, return FROM address (137); for sent, return TO (151)
                if (msgType == 1 && addrType == 137) return addr
                if (msgType == 2 && addrType == 151) return addr
            }
        }

        // Fallback: return any non-placeholder address
        val fallback = context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address"),
            null, null, null
        )
        fallback?.use {
            while (it.moveToNext()) {
                val addr = it.getString(0) ?: continue
                if (addr != "insert-address-token") return addr
            }
        }
        return ""
    }

    private fun getMmsBody(mmsId: Long): String {
        val cursor = context.contentResolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "text"),
            "mid = ?", arrayOf(mmsId.toString()),
            null
        ) ?: return ""

        cursor.use {
            while (it.moveToNext()) {
                val ct = it.getString(it.getColumnIndexOrThrow("ct")) ?: ""
                if (ct == "text/plain") {
                    val text = it.getString(it.getColumnIndexOrThrow("text"))
                    if (!text.isNullOrEmpty()) return text

                    // Try reading from stream
                    val partId = it.getLong(it.getColumnIndexOrThrow("_id"))
                    try {
                        val stream = context.contentResolver.openInputStream(
                            Uri.parse("content://mms/part/$partId")
                        )
                        stream?.use { s -> return s.bufferedReader().readText() }
                    } catch (_: Exception) {}
                }
            }
        }
        return ""
    }

    private fun getMmsAttachments(mmsId: Long): JSONArray {
        val attachments = JSONArray()
        val cursor = context.contentResolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "_data", "cl"),
            "mid = ?", arrayOf(mmsId.toString()),
            null
        ) ?: return attachments

        cursor.use {
            while (it.moveToNext()) {
                val partId = it.getLong(it.getColumnIndexOrThrow("_id"))
                val contentType = it.getString(it.getColumnIndexOrThrow("ct")) ?: continue
                val filename = it.getString(it.getColumnIndexOrThrow("cl")) ?: ""

                // Skip text parts (body, not attachment)
                if (contentType == "text/plain" || contentType == "application/smil") continue

                val att = JSONObject().apply {
                    put("part_id", partId)
                    put("unique_identifier", partId)
                    put("mime_type", contentType)
                    put("filename", filename)
                }

                // Generate thumbnail for images
                if (contentType.startsWith("image/")) {
                    try {
                        val stream = context.contentResolver.openInputStream(
                            Uri.parse("content://mms/part/$partId")
                        )
                        stream?.use { s ->
                            val bitmap = BitmapFactory.decodeStream(s)
                            if (bitmap != null) {
                                val scale = minOf(
                                    THUMB_MAX.toFloat() / bitmap.width,
                                    THUMB_MAX.toFloat() / bitmap.height,
                                    1f
                                )
                                val thumb = Bitmap.createScaledBitmap(
                                    bitmap,
                                    (bitmap.width * scale).toInt(),
                                    (bitmap.height * scale).toInt(),
                                    true
                                )
                                val baos = ByteArrayOutputStream()
                                thumb.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                                att.put("encoded_thumbnail", Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                                if (thumb !== bitmap) thumb.recycle()
                                bitmap.recycle()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to generate thumbnail for part $partId", e)
                    }
                }

                attachments.put(att)
            }
        }
        return attachments
    }
}
