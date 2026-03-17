package xyz.hanson.fosslink.filesystem

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import xyz.hanson.fosslink.network.ProtocolMessage
import java.io.File
import java.io.RandomAccessFile

/**
 * Handles phone filesystem operations using Java File APIs, responding to
 * fosslink.fs.* protocol messages from the desktop.
 *
 * All operations are rooted at /storage/emulated/0 (the shared/external storage).
 * Requires MANAGE_EXTERNAL_STORAGE permission on Android 11+.
 * No root access needed.
 *
 * Paths from the desktop are relative to the storage root. The path "/" maps to
 * /storage/emulated/0, and "/DCIM/photo.jpg" maps to /storage/emulated/0/DCIM/photo.jpg.
 */
class FilesystemHandler(private val context: Context) {
    private val TAG = "FilesystemHandler"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storageRoot: File = Environment.getExternalStorageDirectory()

    fun handleMessage(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        when (msg.type) {
            ProtocolMessage.TYPE_FS_STAT -> scope.launch { handleStat(msg, send) }
            ProtocolMessage.TYPE_FS_READDIR -> scope.launch { handleReaddir(msg, send) }
            ProtocolMessage.TYPE_FS_READ -> scope.launch { handleRead(msg, send) }
            ProtocolMessage.TYPE_FS_WRITE -> scope.launch { handleWrite(msg, send) }
            ProtocolMessage.TYPE_FS_MKDIR -> scope.launch { handleMkdir(msg, send) }
            ProtocolMessage.TYPE_FS_DELETE -> scope.launch { handleDelete(msg, send) }
            ProtocolMessage.TYPE_FS_RENAME -> scope.launch { handleRename(msg, send) }
        }
    }

    // ---- stat ----

    private fun handleStat(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        val requestId = msg.body.optString("requestId", "")
        val path = msg.body.optString("path", "")

        if (!validatePath(path)) {
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_STAT_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", "Invalid path")
            }))
            return
        }

        try {
            val file = resolvePath(path)

            if (!file.exists()) {
                send(ProtocolMessage(ProtocolMessage.TYPE_FS_STAT_RESPONSE, JSONObject().apply {
                    put("requestId", requestId)
                    put("exists", false)
                }))
                return
            }

            send(ProtocolMessage(ProtocolMessage.TYPE_FS_STAT_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("exists", true)
                put("isDir", file.isDirectory)
                put("isFile", file.isFile)
                put("size", file.length())
                put("mtime", file.lastModified() / 1000) // Convert ms to seconds
                put("permissions", buildPermissionString(file))
            }))
        } catch (e: Exception) {
            Log.e(TAG, "stat failed for $path", e)
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_STAT_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", e.message ?: "Unknown error")
            }))
        }
    }

    // ---- readdir ----

    private fun handleReaddir(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        val requestId = msg.body.optString("requestId", "")
        val path = msg.body.optString("path", "")

        if (!validatePath(path)) {
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_READDIR_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", "Invalid path")
            }))
            return
        }

        try {
            val dir = resolvePath(path)
            val entries = JSONArray()

            val files = dir.listFiles()
            if (files != null) {
                for (f in files) {
                    entries.put(JSONObject().apply {
                        put("name", f.name)
                        put("isDir", f.isDirectory)
                        put("size", f.length())
                        put("mtime", f.lastModified() / 1000)
                    })
                }
            }

            send(ProtocolMessage(ProtocolMessage.TYPE_FS_READDIR_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("entries", entries)
            }))
        } catch (e: Exception) {
            Log.e(TAG, "readdir failed for $path", e)
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_READDIR_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", e.message ?: "Unknown error")
            }))
        }
    }

    // ---- read ----

    private fun handleRead(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        val requestId = msg.body.optString("requestId", "")
        val path = msg.body.optString("path", "")
        val offset = msg.body.optLong("offset", 0L)
        val length = msg.body.optLong("length", 65536L)

        if (!validatePath(path)) {
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_READ_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", "Invalid path")
            }))
            return
        }

        try {
            val file = resolvePath(path)
            val buffer = ByteArray(length.toInt().coerceAtMost(1_048_576)) // Cap at 1MB
            var bytesRead = 0

            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                bytesRead = raf.read(buffer)
                if (bytesRead < 0) bytesRead = 0
            }

            val actualData = if (bytesRead < buffer.size) buffer.copyOf(bytesRead) else buffer
            val b64 = Base64.encodeToString(actualData, Base64.NO_WRAP)
            val eof = bytesRead < length

            send(ProtocolMessage(ProtocolMessage.TYPE_FS_READ_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("data", b64)
                put("bytesRead", bytesRead.toLong())
                put("eof", eof)
            }))
        } catch (e: Exception) {
            Log.e(TAG, "read failed for $path", e)
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_READ_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", e.message ?: "Unknown error")
            }))
        }
    }

    // ---- write ----

    private fun handleWrite(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        val requestId = msg.body.optString("requestId", "")
        val path = msg.body.optString("path", "")
        val data = msg.body.optString("data", "")
        val offset = msg.body.optLong("offset", 0L)
        val truncate = msg.body.optBoolean("truncate", false)

        if (!validatePath(path)) {
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_WRITE_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", "Invalid path")
            }))
            return
        }

        try {
            val bytes = Base64.decode(data, Base64.NO_WRAP)
            val file = resolvePath(path)

            // Ensure parent directory exists
            file.parentFile?.mkdirs()

            RandomAccessFile(file, "rw").use { raf ->
                if (truncate) {
                    raf.setLength(0)
                    raf.seek(0)
                } else {
                    raf.seek(offset)
                }
                raf.write(bytes)
            }

            send(ProtocolMessage(ProtocolMessage.TYPE_FS_WRITE_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("bytesWritten", bytes.size.toLong())
            }))
        } catch (e: Exception) {
            Log.e(TAG, "write failed for $path", e)
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_WRITE_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", e.message ?: "Unknown error")
            }))
        }
    }

    // ---- mkdir ----

    private fun handleMkdir(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        val requestId = msg.body.optString("requestId", "")
        val path = msg.body.optString("path", "")

        if (!validatePath(path)) {
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_MKDIR_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", "Invalid path")
            }))
            return
        }

        try {
            val dir = resolvePath(path)
            val success = dir.mkdirs()

            if (!success && !dir.isDirectory) {
                send(ProtocolMessage(ProtocolMessage.TYPE_FS_MKDIR_RESPONSE, JSONObject().apply {
                    put("requestId", requestId)
                    put("error", "Failed to create directory")
                }))
                return
            }

            send(ProtocolMessage(ProtocolMessage.TYPE_FS_MKDIR_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", JSONObject.NULL)
            }))
        } catch (e: Exception) {
            Log.e(TAG, "mkdir failed for $path", e)
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_MKDIR_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", e.message ?: "Unknown error")
            }))
        }
    }

    // ---- delete ----

    private fun handleDelete(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        val requestId = msg.body.optString("requestId", "")
        val path = msg.body.optString("path", "")
        val recursive = msg.body.optBoolean("recursive", false)

        if (!validatePath(path)) {
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_DELETE_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", "Invalid path")
            }))
            return
        }

        try {
            val file = resolvePath(path)
            val success = if (recursive) {
                file.deleteRecursively()
            } else {
                file.delete()
            }

            if (!success && file.exists()) {
                send(ProtocolMessage(ProtocolMessage.TYPE_FS_DELETE_RESPONSE, JSONObject().apply {
                    put("requestId", requestId)
                    put("error", "Failed to delete")
                }))
                return
            }

            send(ProtocolMessage(ProtocolMessage.TYPE_FS_DELETE_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", JSONObject.NULL)
            }))
        } catch (e: Exception) {
            Log.e(TAG, "delete failed for $path", e)
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_DELETE_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", e.message ?: "Unknown error")
            }))
        }
    }

    // ---- rename ----

    private fun handleRename(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        val requestId = msg.body.optString("requestId", "")
        val from = msg.body.optString("from", "")
        val to = msg.body.optString("to", "")

        if (!validatePath(from) || !validatePath(to)) {
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_RENAME_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", "Invalid path")
            }))
            return
        }

        try {
            val fromFile = resolvePath(from)
            val toFile = resolvePath(to)

            // Ensure target parent exists
            toFile.parentFile?.mkdirs()

            val success = fromFile.renameTo(toFile)
            if (!success) {
                send(ProtocolMessage(ProtocolMessage.TYPE_FS_RENAME_RESPONSE, JSONObject().apply {
                    put("requestId", requestId)
                    put("error", "Failed to rename")
                }))
                return
            }

            send(ProtocolMessage(ProtocolMessage.TYPE_FS_RENAME_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", JSONObject.NULL)
            }))
        } catch (e: Exception) {
            Log.e(TAG, "rename failed for $from -> $to", e)
            send(ProtocolMessage(ProtocolMessage.TYPE_FS_RENAME_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", e.message ?: "Unknown error")
            }))
        }
    }

    // ---- utilities ----

    /**
     * Resolve a protocol path to an absolute File.
     * Paths are relative to the external storage root (/storage/emulated/0).
     * "/" maps to the root, "/DCIM" maps to /storage/emulated/0/DCIM, etc.
     */
    private fun resolvePath(path: String): File {
        val cleaned = path.trimStart('/')
        return if (cleaned.isEmpty()) storageRoot else File(storageRoot, cleaned)
    }

    /**
     * Validate a filesystem path. Rejects empty paths and paths containing ".."
     * segments to prevent path traversal attacks.
     */
    private fun validatePath(path: String): Boolean {
        if (path.isEmpty()) return false
        val segments = path.split("/")
        return segments.none { it == ".." }
    }

    /**
     * Build a Unix-style permission string from File properties.
     */
    private fun buildPermissionString(file: File): String {
        val d = if (file.isDirectory) "d" else "-"
        val r = if (file.canRead()) "r" else "-"
        val w = if (file.canWrite()) "w" else "-"
        val x = if (file.canExecute()) "x" else "-"
        return "$d$r$w$x$r-$x$r-$x"
    }

    fun destroy() {
        scope.cancel()
    }
}
