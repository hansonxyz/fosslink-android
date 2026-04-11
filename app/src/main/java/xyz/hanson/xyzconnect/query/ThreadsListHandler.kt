package xyz.hanson.fosslink.query

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Query handler for threads.list
 *
 * Returns all conversation threads with their most recent message (snippet),
 * addresses, and unread count. Desktop uses this to build the thread list UI.
 */
class ThreadsListHandler(private val context: Context) : QueryHandler {
    private val TAG = "ThreadsListHandler"

    override fun handle(params: JSONObject): JSONArray {
        val result = JSONArray()
        val threads = mutableMapOf<Long, JSONObject>()

        // Get all SMS messages grouped by thread, latest first
        querySmsThreads(threads)

        // Get all MMS messages grouped by thread, latest first
        queryMmsThreads(threads)

        // Count unread per thread
        val unreadCounts = queryUnreadCounts()

        // Build output sorted by most recent date descending
        val sorted = threads.values.sortedByDescending { it.optLong("snippetDate", 0) }
        for (thread in sorted) {
            val threadId = thread.getLong("threadId")
            thread.put("unreadCount", unreadCounts[threadId] ?: 0)
            result.put(thread)
        }

        Log.i(TAG, "threads.list: ${result.length()} threads")
        return result
    }

    private fun querySmsThreads(threads: MutableMap<Long, JSONObject>) {
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("_id", "thread_id", "address", "body", "date", "type", "read"),
            null, null,
            "date DESC"
        ) ?: return

        cursor.use {
            while (it.moveToNext()) {
                val threadId = it.getLong(it.getColumnIndexOrThrow("thread_id"))
                if (threadId == 0L) continue

                // Only keep the most recent message per thread (first seen = most recent)
                if (threads.containsKey(threadId)) continue

                val address = it.getString(it.getColumnIndexOrThrow("address")) ?: ""
                val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                val date = it.getLong(it.getColumnIndexOrThrow("date"))

                threads[threadId] = JSONObject().apply {
                    put("threadId", threadId)
                    put("addresses", address)
                    put("snippet", body)
                    put("snippetDate", date)
                }
            }
        }
    }

    private fun queryMmsThreads(threads: MutableMap<Long, JSONObject>) {
        val cursor = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf("_id", "thread_id", "date", "read"),
            null, null,
            "date DESC"
        ) ?: return

        cursor.use {
            while (it.moveToNext()) {
                val threadId = it.getLong(it.getColumnIndexOrThrow("thread_id"))
                if (threadId == 0L) continue

                val mmsId = it.getLong(it.getColumnIndexOrThrow("_id"))
                val dateSec = it.getLong(it.getColumnIndexOrThrow("date"))
                val dateMs = dateSec * 1000

                // If SMS already has a more recent message for this thread, skip
                val existing = threads[threadId]
                if (existing != null && existing.optLong("snippetDate", 0) >= dateMs) continue

                // Get MMS body from parts
                val body = getMmsBody(mmsId)
                // Get MMS address
                val address = getMmsAddress(mmsId)

                threads[threadId] = JSONObject().apply {
                    put("threadId", threadId)
                    put("addresses", address)
                    put("snippet", body)
                    put("snippetDate", dateMs)
                }
            }
        }
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
                val contentType = it.getString(it.getColumnIndexOrThrow("ct")) ?: ""
                if (contentType == "text/plain") {
                    val text = it.getString(it.getColumnIndexOrThrow("text"))
                    if (!text.isNullOrEmpty()) return text
                }
            }
        }
        return ""
    }

    private fun getMmsAddress(mmsId: Long): String {
        val cursor = context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            null, null, null
        ) ?: return ""

        cursor.use {
            while (it.moveToNext()) {
                val addr = it.getString(it.getColumnIndexOrThrow("address")) ?: continue
                if (addr == "insert-address-token") continue
                return addr
            }
        }
        return ""
    }

    private fun queryUnreadCounts(): Map<Long, Int> {
        val counts = mutableMapOf<Long, Int>()

        // SMS unread
        val smsCursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("thread_id"),
            "read = 0", null, null
        )
        smsCursor?.use {
            while (it.moveToNext()) {
                val threadId = it.getLong(0)
                counts[threadId] = (counts[threadId] ?: 0) + 1
            }
        }

        // MMS unread
        val mmsCursor = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf("thread_id"),
            "read = 0", null, null
        )
        mmsCursor?.use {
            while (it.moveToNext()) {
                val threadId = it.getLong(0)
                counts[threadId] = (counts[threadId] ?: 0) + 1
            }
        }

        return counts
    }
}
