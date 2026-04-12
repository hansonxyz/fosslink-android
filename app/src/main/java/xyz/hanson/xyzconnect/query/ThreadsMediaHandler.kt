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
 * Query handler for threads.media
 *
 * Returns all image and video attachments from MMS messages in a thread,
 * sorted by date descending (newest first). Returns attachment metadata
 * only — thumbnails/files are downloaded on demand via the existing
 * attachment system.
 *
 * Params:
 *   threadId: number (required)
 *
 * Returns: array of { partId, messageId, mimeType, filename, date, kind }
 */
class ThreadsMediaHandler(private val context: Context) : QueryHandler {
    private val TAG = "ThreadsMediaHandler"
    private val MMS_ID_OFFSET = 2_000_000_000L
    private val THUMB_MAX = 150
    private val THUMB_QUALITY = 60

    override fun handle(params: JSONObject): JSONArray {
        val threadId = params.optLong("threadId", -1)
        if (threadId < 0) {
            Log.w(TAG, "Missing threadId param")
            return JSONArray()
        }

        val mediaItems = mutableListOf<JSONObject>()

        // Get all MMS messages in this thread
        val mmsCursor = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf("_id", "date"),
            "thread_id = ?",
            arrayOf(threadId.toString()),
            "date DESC"
        ) ?: return JSONArray()

        mmsCursor.use {
            while (it.moveToNext()) {
                val mmsId = it.getLong(it.getColumnIndexOrThrow("_id"))
                val dateSec = it.getLong(it.getColumnIndexOrThrow("date"))
                val dateMs = dateSec * 1000

                // Get media parts for this MMS
                val partsCursor = context.contentResolver.query(
                    Uri.parse("content://mms/part"),
                    arrayOf("_id", "ct", "cl"),
                    "mid = ?",
                    arrayOf(mmsId.toString()),
                    null
                ) ?: continue

                partsCursor.use { parts ->
                    while (parts.moveToNext()) {
                        val partId = parts.getLong(parts.getColumnIndexOrThrow("_id"))
                        val contentType = parts.getString(parts.getColumnIndexOrThrow("ct")) ?: continue
                        val filename = parts.getString(parts.getColumnIndexOrThrow("cl")) ?: ""

                        // Only include images and videos
                        val kind = when {
                            contentType.startsWith("image/") -> "image"
                            contentType.startsWith("video/") -> "video"
                            else -> continue
                        }

                        val item = JSONObject().apply {
                            put("partId", partId)
                            put("messageId", mmsId + MMS_ID_OFFSET)
                            put("mimeType", contentType)
                            put("filename", filename)
                            put("date", dateMs)
                            put("kind", kind)
                        }

                        // Generate inline thumbnail for images
                        if (kind == "image") {
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
                                        thumb.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, baos)
                                        item.put("thumbnail", Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                                        if (thumb !== bitmap) thumb.recycle()
                                        bitmap.recycle()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to generate thumbnail for part $partId", e)
                            }
                        }

                        mediaItems.add(item)
                    }
                }
            }
        }

        Log.i(TAG, "threads.media: threadId=$threadId, ${mediaItems.size} media items")

        val result = JSONArray()
        for (item in mediaItems) result.put(item)
        return result
    }
}
