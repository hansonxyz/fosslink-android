package xyz.hanson.fosslink.query

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages the hide/filter list — phone numbers whose threads are hidden
 * from the desktop conversation list. Stored in a local SQLite database
 * on the phone (source of truth). Synced to desktops on connect.
 *
 * Handles two query resources:
 *   filter.list — returns all filtered phone numbers
 *   filter.set  — add or remove a number from the filter list
 */
class FilterListHandler(context: Context) {
    private val TAG = "FilterListHandler"
    private val db: FilterListDb = FilterListDb(context)

    /** Query handler for filter.list — returns all filtered numbers */
    val listHandler = object : QueryHandler {
        override fun handle(params: JSONObject): JSONArray {
            val numbers = db.getAll()
            Log.i(TAG, "filter.list: ${numbers.size} numbers")
            val result = JSONArray()
            for (num in numbers) {
                result.put(JSONObject().apply { put("number", num) })
            }
            return result
        }
    }

    /** Query handler for filter.set — add or remove a number */
    val setHandler = object : QueryHandler {
        override fun handle(params: JSONObject): JSONArray {
            val number = params.optString("number", "")
            val filtered = params.optBoolean("filtered", true)

            if (number.isEmpty()) {
                Log.w(TAG, "filter.set: missing number param")
                return JSONArray()
            }

            if (filtered) {
                db.add(number)
                Log.i(TAG, "filter.set: added $number")
            } else {
                db.remove(number)
                Log.i(TAG, "filter.set: removed $number")
            }

            return JSONArray().put(JSONObject().apply {
                put("number", number)
                put("filtered", filtered)
            })
        }
    }

    fun isFiltered(number: String): Boolean = db.contains(number)
    fun getAll(): List<String> = db.getAll()
}

private class FilterListDb(context: Context) : SQLiteOpenHelper(context, "filter_list.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS filter_list (number TEXT PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun getAll(): List<String> {
        val list = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT number FROM filter_list", null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.getString(0))
            }
        }
        return list
    }

    fun add(number: String) {
        writableDatabase.insertWithOnConflict(
            "filter_list",
            null,
            ContentValues().apply { put("number", number) },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun remove(number: String) {
        writableDatabase.delete("filter_list", "number = ?", arrayOf(number))
    }

    fun contains(number: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM filter_list WHERE number = ?", arrayOf(number)
        ).use { return it.count > 0 }
    }
}
