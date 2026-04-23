package xyz.hanson.fosslink.query

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Query handler for attachments.sizes
 *
 * Returns byte sizes for a list of (partId, messageId) pairs so the desktop
 * can decide whether to skip re-downloading an attachment whose export
 * target already exists with a matching size.
 *
 * Params:
 *   items: Array<{ partId: number, messageId: number }>
 *
 * Returns: Array<{ partId, messageId, size }> — size is -1 if unknown.
 */
class AttachmentSizesHandler(private val context: Context) : QueryHandler {
    private val TAG = "AttachmentSizesHandler"

    override fun handle(params: JSONObject): JSONArray {
        val items = params.optJSONArray("items") ?: return JSONArray()
        val result = JSONArray()

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val partId = item.optLong("partId", -1L)
            val messageId = item.optLong("messageId", -1L)
            if (partId < 0) continue

            val size = getSize(partId)
            result.put(JSONObject().apply {
                put("partId", partId)
                put("messageId", messageId)
                put("size", size)
            })
        }

        Log.i(TAG, "attachments.sizes: ${result.length()} items")
        return result
    }

    private fun getSize(partId: Long): Long {
        // Fast path only: read _data column and stat the file. We deliberately
        // do NOT fall back to streaming the content URI — that serialises a
        // potentially multi-megabyte read per attachment through the query
        // server, which starves real attachment downloads of bandwidth and
        // causes them to time out. If _data is missing, return -1 ("unknown")
        // and the desktop will just re-download rather than skip.
        val cursor = try {
            context.contentResolver.query(
                Uri.parse("content://mms/part/$partId"),
                arrayOf("_data"),
                null, null, null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query part $partId: ${e.message}")
            return -1L
        } ?: return -1L

        cursor.use {
            if (it.moveToFirst()) {
                val path = it.getString(0)
                if (!path.isNullOrEmpty()) {
                    val f = File(path)
                    if (f.exists()) return f.length()
                }
            }
        }
        return -1L
    }
}
