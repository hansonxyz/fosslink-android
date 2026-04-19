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
 * addresses (all participants), and unread count. Desktop uses this to build
 * the thread list UI.
 *
 * Addresses are returned as a JSONArray of all participant phone numbers,
 * sourced from Android's canonical_addresses table via the mms-sms/conversations
 * URI. This correctly handles group MMS threads with multiple recipients.
 */
class ThreadsListHandler(private val context: Context) : QueryHandler {
    private val TAG = "ThreadsListHandler"

    override fun handle(params: JSONObject): JSONArray {
        val result = JSONArray()
        val threads = mutableMapOf<Long, JSONObject>()

        // Get snippets/dates from SMS messages
        querySmsThreads(threads)

        // Get snippets/dates from MMS messages (overrides SMS if more recent)
        queryMmsThreads(threads)

        // Get canonical participant lists and archived status for all threads
        val (threadAddresses, archivedIds) = queryCanonicalAddresses()

        // Count unread per thread
        val unreadCounts = queryUnreadCounts()

        // Build output sorted by most recent date descending
        val sorted = threads.values.sortedByDescending { it.optLong("snippetDate", 0) }
        for (thread in sorted) {
            val threadId = thread.getLong("threadId")

            // Skip archived (trashed) threads
            if (archivedIds.contains(threadId)) continue

            // Use canonical addresses if available; fall back to the snippet address
            val addresses = threadAddresses[threadId]
                ?: JSONArray().apply {
                    val fallback = thread.optString("_snippetAddress", "")
                    if (fallback.isNotEmpty()) put(fallback)
                }
            thread.put("addresses", addresses)
            thread.remove("_snippetAddress")  // internal field, not sent to desktop

            thread.put("unreadCount", unreadCounts[threadId] ?: 0)
            result.put(thread)
        }

        Log.i(TAG, "threads.list: ${result.length()} threads")
        return result
    }

    /**
     * Query the mms-sms/conversations URI to get canonical participant lists and
     * archived (trashed) status. Returns (addresses map, archived thread IDs set).
     */
    private fun queryCanonicalAddresses(): Pair<Map<Long, JSONArray>, Set<Long>> {
        val addresses = mutableMapOf<Long, JSONArray>()
        val archivedIds = mutableSetOf<Long>()

        val cursor = try {
            context.contentResolver.query(
                Uri.parse("content://mms-sms/conversations?simple=true"),
                arrayOf("_id", "recipient_ids", "archived"),
                null, null, null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query conversations URI: ${e.message}")
            return Pair(addresses, archivedIds)
        } ?: return Pair(addresses, archivedIds)

        cursor.use {
            val idCol = it.getColumnIndex("_id")
            val recipCol = it.getColumnIndex("recipient_ids")
            val archivedCol = it.getColumnIndex("archived")
            if (idCol < 0 || recipCol < 0) {
                Log.w(TAG, "conversations URI missing expected columns")
                return Pair(addresses, archivedIds)
            }

            while (it.moveToNext()) {
                val threadId = it.getLong(idCol)

                // Track archived threads regardless of addresses
                if (archivedCol >= 0 && it.getInt(archivedCol) == 1) {
                    archivedIds.add(threadId)
                    continue
                }

                val recipientIdsStr = it.getString(recipCol) ?: continue
                val recipientIds = recipientIdsStr.trim().split("\\s+".toRegex()).filter { id -> id.isNotEmpty() }

                val addrs = JSONArray()
                for (id in recipientIds) {
                    val addr = resolveCanonicalAddress(id)
                    if (!addr.isNullOrEmpty() && addr != "insert-address-token") {
                        addrs.put(addr)
                    }
                }

                if (addrs.length() > 0) {
                    addresses[threadId] = addrs
                }
            }
        }

        return Pair(addresses, archivedIds)
    }

    private fun resolveCanonicalAddress(id: String): String? {
        return try {
            val cursor = context.contentResolver.query(
                Uri.parse("content://mms-sms/canonical-address/$id"),
                null, null, null, null
            ) ?: return null
            cursor.use {
                if (it.moveToFirst()) {
                    val col = it.getColumnIndex("address")
                    if (col >= 0) it.getString(col) else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve canonical address $id: ${e.message}")
            null
        }
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
                    put("_snippetAddress", address)  // fallback only; canonical addresses preferred
                    put("snippet", body)
                    put("snippetDate", date)
                }
            }
        }
    }

    private fun queryMmsThreads(threads: MutableMap<Long, JSONObject>) {
        val cursor = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf("_id", "thread_id", "date", "read", "msg_box"),
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
                val msgBox = it.getInt(it.getColumnIndexOrThrow("msg_box"))

                // If SMS already has a more recent message for this thread, skip
                val existing = threads[threadId]
                if (existing != null && existing.optLong("snippetDate", 0) >= dateMs) continue

                val body = getMmsBody(mmsId)
                // Fallback address: pick the other party's address from the MMS addr table
                val fallbackAddr = getMmsFallbackAddress(mmsId, msgBox)

                threads[threadId] = JSONObject().apply {
                    put("threadId", threadId)
                    put("_snippetAddress", fallbackAddr)
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

    /**
     * Fallback address for threads where canonical_addresses lookup fails.
     * For sent MMS: TO address (type=151). For received: FROM address (type=137).
     */
    private fun getMmsFallbackAddress(mmsId: Long, msgBox: Int): String {
        val targetType = if (msgBox == 2) 151 else 137

        val cursor = context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            null, null, null
        ) ?: return ""

        var fallback = ""
        cursor.use {
            while (it.moveToNext()) {
                val addr = it.getString(it.getColumnIndexOrThrow("address")) ?: continue
                if (addr == "insert-address-token") continue
                val addrType = it.getInt(it.getColumnIndexOrThrow("type"))
                if (addrType == targetType) return addr
                if (fallback.isEmpty()) fallback = addr
            }
        }
        return fallback
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
