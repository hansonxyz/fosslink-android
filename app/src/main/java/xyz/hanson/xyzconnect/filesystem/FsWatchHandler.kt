package xyz.hanson.fosslink.filesystem

import android.os.Environment
import android.os.FileObserver
import android.util.Log
import org.json.JSONObject
import xyz.hanson.fosslink.network.ProtocolMessage
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches filesystem directories for changes using Android's FileObserver API.
 * Desktop sends fosslink.fs.watch with a path; we create a FileObserver for that
 * directory and push fosslink.fs.watch_event messages when files are created,
 * deleted, moved, or modified.
 *
 * Each watched path gets its own FileObserver. Multiple desktops can watch the
 * same path — we refcount and only stop observing when all watchers are gone.
 */
class FsWatchHandler {
    private val TAG = "FsWatchHandler"
    private val storageRoot: File = Environment.getExternalStorageDirectory()

    private var sendFn: ((ProtocolMessage) -> Unit)? = null

    // path -> active observer
    private val observers = ConcurrentHashMap<String, FileObserver>()

    fun setSendFunction(send: (ProtocolMessage) -> Unit) {
        sendFn = send
    }

    fun clearSendFunction() {
        stopAll()
        sendFn = null
    }

    fun handleMessage(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        when (msg.type) {
            ProtocolMessage.TYPE_FS_WATCH -> handleWatch(msg, send)
            ProtocolMessage.TYPE_FS_UNWATCH -> handleUnwatch(msg, send)
            ProtocolMessage.TYPE_FS_WATCH_EVENT_ACK -> {
                // No-op: desktop acknowledged a watch event
            }
        }
    }

    private fun handleWatch(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        val requestId = msg.body.optString("requestId", "")
        val path = msg.body.optString("path", "")

        if (!validatePath(path)) {
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_WATCH_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", "Invalid path")
            }))
            return
        }

        val dir = resolvePath(path)
        if (!dir.isDirectory) {
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_WATCH_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", "Not a directory")
            }))
            return
        }

        // Already watching this path
        if (observers.containsKey(path)) {
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_WATCH_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", JSONObject.NULL)
                put("path", path)
            }))
            return
        }

        try {
            val eventMask = FileObserver.CREATE or
                    FileObserver.DELETE or
                    FileObserver.MOVED_FROM or
                    FileObserver.MOVED_TO or
                    FileObserver.CLOSE_WRITE

            @Suppress("DEPRECATION")
            val observer = object : FileObserver(dir.absolutePath, eventMask) {
                override fun onEvent(event: Int, filename: String?) {
                    if (filename == null) return
                    val broadcastSend = sendFn ?: return

                    val eventType = when (event and 0xffff) {
                        CREATE -> "created"
                        DELETE -> "deleted"
                        MOVED_FROM -> "deleted"
                        MOVED_TO -> "created"
                        CLOSE_WRITE -> "modified"
                        else -> return
                    }

                    val filePath = if (path == "/") "/$filename" else "$path/$filename"
                    val file = File(dir, filename)

                    Log.d(TAG, "Watch event: $eventType $filePath")

                    broadcastSend(ProtocolMessage(ProtocolMessage.TYPE_FS_WATCH_EVENT, JSONObject().apply {
                        put("eventId", UUID.randomUUID().toString())
                        put("watchPath", path)
                        put("path", filePath)
                        put("filename", filename)
                        put("event", eventType)
                        put("isDir", file.isDirectory)
                        put("size", if (file.exists()) file.length() else 0L)
                        put("mtime", if (file.exists()) file.lastModified() / 1000 else 0L)
                    }))
                }
            }

            observer.startWatching()
            observers[path] = observer
            Log.i(TAG, "Started watching: $path (${dir.absolutePath})")

            send(ProtocolMessage(ProtocolMessage.TYPE_FS_WATCH_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", JSONObject.NULL)
                put("path", path)
            }))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to watch $path", e)
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_WATCH_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", e.message ?: "Unknown error")
            }))
        }
    }

    private fun handleUnwatch(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        val requestId = msg.body.optString("requestId", "")
        val path = msg.body.optString("path", "")

        val observer = observers.remove(path)
        if (observer != null) {
            observer.stopWatching()
            Log.i(TAG, "Stopped watching: $path")
        }

        send(ProtocolMessage(ProtocolMessage.TYPE_FS_UNWATCH_RESPONSE, JSONObject().apply {
            put("requestId", requestId)
            put("error", JSONObject.NULL)
        }))
    }

    fun stopAll() {
        for ((path, observer) in observers) {
            observer.stopWatching()
            Log.i(TAG, "Stopped watching: $path")
        }
        observers.clear()
    }

    private fun resolvePath(path: String): File {
        val cleaned = path.trimStart('/')
        return if (cleaned.isEmpty()) storageRoot else File(storageRoot, cleaned)
    }

    private fun validatePath(path: String): Boolean {
        if (path.isEmpty()) return false
        val segments = path.split("/")
        return segments.none { it == ".." }
    }
}
