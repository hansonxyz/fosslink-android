package xyz.hanson.fosslink.query

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages desktop read-state overrides — threads that a desktop has marked
 * as read. Stored in a local SQLite database on the phone (source of truth).
 * Synced to desktops on connect so all desktops share the same read state.
 *
 * Handles two query resources:
 *   read.list — returns all read overrides (threadId → timestamp)
 *   read.set  — set or clear a read override for a thread
 */
class ReadOverridesHandler(context: Context) {
    private val TAG = "ReadOverridesHandler"
    private val db: ReadOverridesDb = ReadOverridesDb(context)

    /** Query handler for read.list — returns all overrides */
    val listHandler = object : QueryHandler {
        override fun handle(params: JSONObject): JSONArray {
            val overrides = db.getAll()
            Log.i(TAG, "read.list: ${overrides.size} overrides")
            val result = JSONArray()
            for ((threadId, timestamp) in overrides) {
                result.put(JSONObject().apply {
                    put("threadId", threadId)
                    put("readAt", timestamp)
                })
            }
            return result
        }
    }

    /** Query handler for read.set — set a read override */
    val setHandler = object : QueryHandler {
        override fun handle(params: JSONObject): JSONArray {
            val threadId = params.optLong("threadId", -1)
            val readAt = params.optLong("readAt", 0)

            if (threadId < 0) {
                Log.w(TAG, "read.set: missing threadId param")
                return JSONArray()
            }

            if (readAt > 0) {
                db.set(threadId, readAt)
                Log.i(TAG, "read.set: thread $threadId read at $readAt")
            } else {
                db.remove(threadId)
                Log.i(TAG, "read.set: thread $threadId cleared")
            }

            return JSONArray().put(JSONObject().apply {
                put("threadId", threadId)
                put("readAt", readAt)
            })
        }
    }
}

private class ReadOverridesDb(context: Context) : SQLiteOpenHelper(context, "read_overrides.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS read_overrides (thread_id INTEGER PRIMARY KEY, read_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun getAll(): List<Pair<Long, Long>> {
        val list = mutableListOf<Pair<Long, Long>>()
        readableDatabase.rawQuery("SELECT thread_id, read_at FROM read_overrides", null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.getLong(0) to cursor.getLong(1))
            }
        }
        return list
    }

    fun set(threadId: Long, readAt: Long) {
        writableDatabase.insertWithOnConflict(
            "read_overrides",
            null,
            ContentValues().apply {
                put("thread_id", threadId)
                put("read_at", readAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun remove(threadId: Long) {
        writableDatabase.delete("read_overrides", "thread_id = ?", arrayOf(threadId.toString()))
    }
}
