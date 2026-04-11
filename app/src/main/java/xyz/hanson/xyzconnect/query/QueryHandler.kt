package xyz.hanson.fosslink.query

import org.json.JSONArray
import org.json.JSONObject

/**
 * Interface for query resource handlers.
 * Each handler executes a query and returns the full result set.
 * Pagination is handled by QueryServer — handlers don't need to know about it.
 */
interface QueryHandler {
    /** Execute the query with the given params and return all result items. */
    fun handle(params: JSONObject): JSONArray
}
