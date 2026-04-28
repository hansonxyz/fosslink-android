package xyz.hanson.fosslink.network

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import xyz.hanson.fosslink.query.QueryHandler
import java.util.UUID

/**
 * Query Server
 *
 * Receives fosslink.query requests from desktop clients, dispatches to
 * registered resource handlers, paginates the results, and sends them
 * back with ACK-based flow control (sliding window of 20 pages).
 *
 * Each query is processed sequentially — one at a time per connection.
 * The phone holds no state between queries.
 *
 * Cancellation: the active query's coroutine job is tracked so it can be
 * cancelled mid-execution by:
 *   - the desktop sending fosslink.query.cancel { queryId }, or
 *   - the WebSocket disconnecting (cancelActive() called by ConnectionService).
 * Cancelling stops the handler.handle() walk and prevents further pages from
 * being sent. Without this, a slow handler keeps walking storage / cursors
 * after the desktop has already given up on the query, wasting phone work.
 */
class QueryServer {
    private val TAG = "QueryServer"
    private val PAGE_SIZE = 20
    private val MAX_IN_FLIGHT = 20

    private val handlers = mutableMapOf<String, QueryHandler>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Active query state (one at a time)
    private var activeQueryId: String? = null
    private var activeJob: Job? = null
    private var activePages: List<JSONArray> = emptyList()
    private var activeNextPage = 0   // next page index to send (0-based)
    private var activeSentCount = 0  // pages sent but not yet ACKed
    private var activeSend: ((ProtocolMessage) -> Unit)? = null
    private var activeQueryTimestamp: Long = 0

    /** Register a resource handler. */
    fun registerHandler(resource: String, handler: QueryHandler) {
        handlers[resource] = handler
        Log.i(TAG, "Registered handler: $resource")
    }

    /**
     * Handle an incoming fosslink.query request.
     * Dispatches to the appropriate handler, paginates, and starts sending.
     * The handler runs inside a cancellable coroutine so we can abort it
     * mid-walk via fosslink.query.cancel or on disconnect.
     */
    fun handleQuery(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        val queryId = msg.body.optString("queryId", "")
        val resource = msg.body.optString("resource", "")
        val params = msg.body.optJSONObject("params") ?: JSONObject()

        if (queryId.isEmpty() || resource.isEmpty()) {
            Log.w(TAG, "Invalid query: missing queryId or resource")
            return
        }

        val handler = handlers[resource]
        if (handler == null) {
            Log.w(TAG, "No handler for resource: $resource")
            // Send empty result
            val body = JSONObject().apply {
                put("queryId", queryId)
                put("pageId", UUID.randomUUID().toString())
                put("page", 1)
                put("totalPages", 1)
                put("data", JSONArray())
            }
            send(ProtocolMessage(ProtocolMessage.TYPE_QUERY_RESULT, body))
            return
        }

        // A new query implicitly cancels any previous one — the desktop only
        // tracks one active query at a time, so anything still running is
        // unwanted work.
        activeJob?.cancel()

        Log.i(TAG, "Executing query: $resource (queryId=$queryId)")

        // Record timestamp before query executes — used by desktop for delete detection
        val queryTimestamp = System.currentTimeMillis()

        activeQueryId = queryId
        activeQueryTimestamp = queryTimestamp
        activeSend = send

        activeJob = scope.launch {
            // Execute the full query (cancellable — will throw CancellationException
            // if cancel() is called between cooperative yield points)
            val result: JSONArray = try {
                handler.handle(params)
            } catch (e: CancellationException) {
                Log.i(TAG, "Query $resource ($queryId) cancelled during handler")
                if (activeQueryId == queryId) clearActiveQuery()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Query handler error for $resource", e)
                val body = JSONObject().apply {
                    put("queryId", queryId)
                    put("pageId", UUID.randomUUID().toString())
                    put("page", 1)
                    put("totalPages", 1)
                    put("data", JSONArray())
                }
                send(ProtocolMessage(ProtocolMessage.TYPE_QUERY_RESULT, body))
                if (activeQueryId == queryId) clearActiveQuery()
                return@launch
            }

            // If this query was cancelled while we were waiting (or replaced),
            // bail before paginating.
            if (!isActive || activeQueryId != queryId) {
                Log.i(TAG, "Query $resource ($queryId) cancelled before pagination")
                return@launch
            }

            // Paginate — use pageSize from params if specified, otherwise default
            val pageSize = params.optInt("pageSize", PAGE_SIZE)
            val pages = mutableListOf<JSONArray>()
            if (result.length() == 0) {
                pages.add(JSONArray())
            } else {
                var i = 0
                while (i < result.length()) {
                    val page = JSONArray()
                    val end = minOf(i + pageSize, result.length())
                    for (j in i until end) {
                        page.put(result.get(j))
                    }
                    pages.add(page)
                    i = end
                }
            }

            Log.i(TAG, "Query $resource: ${result.length()} items in ${pages.size} pages")

            activePages = pages
            activeNextPage = 0
            activeSentCount = 0

            // Send initial burst (up to MAX_IN_FLIGHT pages)
            sendPages()
        }
    }

    /**
     * Handle an incoming fosslink.query.ack from the desktop.
     * Sends the next page if available.
     */
    fun handleAck(msg: ProtocolMessage) {
        val queryId = msg.body.optString("queryId", "")
        if (queryId != activeQueryId) {
            Log.w(TAG, "ACK for unknown query: $queryId")
            return
        }

        activeSentCount--
        sendPages()

        // Check if query is complete
        if (activeNextPage >= activePages.size && activeSentCount <= 0) {
            Log.i(TAG, "Query $queryId complete (all pages sent and ACKed)")
            clearActiveQuery()
        }
    }

    /**
     * Handle an incoming fosslink.query.cancel from the desktop.
     * Cancels the active handler coroutine and clears the slot.
     */
    fun handleCancel(msg: ProtocolMessage) {
        val queryId = msg.body.optString("queryId", "")
        if (queryId.isEmpty() || queryId != activeQueryId) {
            Log.w(TAG, "Cancel for unknown/inactive query: $queryId")
            return
        }
        Log.i(TAG, "Cancelling active query $queryId on desktop request")
        activeJob?.cancel()
        clearActiveQuery()
    }

    /**
     * Cancel any in-flight query (e.g. when the connection drops). Idempotent.
     */
    fun cancelActive() {
        if (activeJob != null) {
            Log.i(TAG, "Cancelling active query (connection drop or shutdown)")
            activeJob?.cancel()
            clearActiveQuery()
        }
    }

    /** Clear active query state. */
    fun clearActiveQuery() {
        activeQueryId = null
        activeJob = null
        activePages = emptyList()
        activeNextPage = 0
        activeSentCount = 0
        activeSend = null
    }

    // --- Internal ---

    private fun sendPages() {
        val send = activeSend ?: return
        val queryId = activeQueryId ?: return
        val totalPages = activePages.size

        while (activeNextPage < totalPages && activeSentCount < MAX_IN_FLIGHT) {
            val pageIndex = activeNextPage
            val pageData = activePages[pageIndex]
            val pageId = UUID.randomUUID().toString()

            val body = JSONObject().apply {
                put("queryId", queryId)
                put("pageId", pageId)
                put("page", pageIndex + 1)       // 1-based page number
                put("totalPages", totalPages)
                put("queryTimestamp", activeQueryTimestamp)
                put("data", pageData)
            }

            send(ProtocolMessage(ProtocolMessage.TYPE_QUERY_RESULT, body))
            activeNextPage++
            activeSentCount++
        }
    }
}
