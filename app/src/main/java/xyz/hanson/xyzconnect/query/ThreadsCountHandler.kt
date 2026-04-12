package xyz.hanson.fosslink.query

import android.content.Context
import android.provider.Telephony
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Query handler for threads.count
 *
 * Returns the total message count for one or more threads.
 * Used by the desktop to verify its local message count matches
 * the phone's, triggering a full sync if they diverge.
 *
 * Params:
 *   threadIds: number[] (required)
 *
 * Returns: array of { threadId, count }
 */
class ThreadsCountHandler(private val context: Context) : QueryHandler {
    private val TAG = "ThreadsCountHandler"
    private val MMS_ID_OFFSET = 2_000_000_000L

    override fun handle(params: JSONObject): JSONArray {
        val threadIds = params.optJSONArray("threadIds") ?: return JSONArray()
        val result = JSONArray()

        for (i in 0 until threadIds.length()) {
            val threadId = threadIds.optLong(i, -1)
            if (threadId < 0) continue

            val smsCount = countSms(threadId)
            val mmsCount = countMms(threadId)
            val total = smsCount + mmsCount

            result.put(JSONObject().apply {
                put("threadId", threadId)
                put("count", total)
            })
        }

        Log.i(TAG, "threads.count: ${result.length()} threads counted")
        return result
    }

    private fun countSms(threadId: Long): Int {
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("COUNT(*) AS cnt"),
            "thread_id = ?",
            arrayOf(threadId.toString()),
            null
        ) ?: return 0

        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun countMms(threadId: Long): Int {
        val cursor = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf("COUNT(*) AS cnt"),
            "thread_id = ?",
            arrayOf(threadId.toString()),
            null
        ) ?: return 0

        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
}
