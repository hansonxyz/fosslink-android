package xyz.hanson.fosslink.query

import org.json.JSONArray
import org.json.JSONObject

/**
 * Test handler — returns the "items" array from params as the result.
 * Used for testing the query transport layer.
 *
 * Usage: query echo { "items": [1, 2, 3, ...] }
 */
class EchoHandler : QueryHandler {
    override fun handle(params: JSONObject): JSONArray {
        return params.optJSONArray("items") ?: JSONArray()
    }
}
