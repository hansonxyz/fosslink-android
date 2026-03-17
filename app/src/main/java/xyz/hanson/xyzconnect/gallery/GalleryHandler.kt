package xyz.hanson.fosslink.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import xyz.hanson.fosslink.network.ProtocolMessage
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Handles gallery operations: scanning for media files and generating thumbnails.
 * Responds to fosslink.gallery.* protocol messages from the desktop.
 *
 * All operations are rooted at /storage/emulated/0 (the shared/external storage).
 * Recursively walks the entire storage to find image and video files.
 * Requires MANAGE_EXTERNAL_STORAGE permission on Android 11+.
 */
class GalleryHandler(private val context: Context) {
    private val TAG = "GalleryHandler"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storageRoot: File = Environment.getExternalStorageDirectory()

    companion object {
        private const val THUMBNAIL_MAX_SIZE = 256
        private const val THUMBNAIL_QUALITY = 75

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heif", "heic")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v")
        private val ALL_MEDIA_EXTENSIONS = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS
    }

    fun handleMessage(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        when (msg.type) {
            ProtocolMessage.TYPE_GALLERY_SCAN -> scope.launch { handleScan(msg, send) }
            ProtocolMessage.TYPE_GALLERY_THUMBNAIL -> scope.launch { handleThumbnail(msg, send) }
        }
    }

    // ---- scan ----

    private fun handleScan(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        val requestId = msg.body.optString("requestId", "")
        try {
            val items = JSONArray()
            val mimeTypeMap = MimeTypeMap.getSingleton()

            storageRoot.walk()
                .filter { it.isFile }
                .filter { file ->
                    val ext = file.extension.lowercase()
                    ext in ALL_MEDIA_EXTENSIONS
                }
                .forEach { file ->
                    val relativePath = file.relativeTo(storageRoot).path
                    val folder = file.parentFile?.relativeTo(storageRoot)?.path ?: ""
                    val ext = file.extension.lowercase()
                    val mimeType = mimeTypeMap.getMimeTypeFromExtension(ext) ?: ""
                    val kind = if (ext in IMAGE_EXTENSIONS) "image" else "video"

                    // Check if any path component starts with "."
                    val isHidden = relativePath.split(File.separator).any { it.startsWith(".") }

                    items.put(JSONObject().apply {
                        put("path", "/$relativePath")
                        put("filename", file.name)
                        put("folder", folder)
                        put("mtime", file.lastModified() / 1000)
                        put("size", file.length())
                        put("mimeType", mimeType)
                        put("isHidden", isHidden)
                        put("kind", kind)
                    })
                }

            // Sort by mtime descending (newest first)
            val sortedItems = JSONArray()
            val itemList = mutableListOf<JSONObject>()
            for (i in 0 until items.length()) {
                itemList.add(items.getJSONObject(i))
            }
            itemList.sortByDescending { it.optLong("mtime", 0L) }
            for (item in itemList) {
                sortedItems.put(item)
            }

            Log.d(TAG, "Gallery scan found ${sortedItems.length()} media files")

            send(ProtocolMessage(ProtocolMessage.TYPE_GALLERY_SCAN_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("items", sortedItems)
            }))
        } catch (e: Exception) {
            Log.e(TAG, "Gallery scan failed", e)
            send(ProtocolMessage(ProtocolMessage.TYPE_GALLERY_SCAN_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("error", e.message ?: "Unknown error")
            }))
        }
    }

    // ---- thumbnail ----

    private fun handleThumbnail(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        val requestId = msg.body.optString("requestId", "")
        val path = msg.body.optString("path", "")

        if (!validatePath(path)) {
            send(ProtocolMessage(ProtocolMessage.TYPE_GALLERY_THUMBNAIL_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("path", path)
                put("thumbnailFailed", true)
            }))
            return
        }

        try {
            val file = resolvePath(path)

            if (!file.exists() || !file.isFile) {
                send(ProtocolMessage(ProtocolMessage.TYPE_GALLERY_THUMBNAIL_RESPONSE, JSONObject().apply {
                    put("requestId", requestId)
                    put("path", path)
                    put("thumbnailFailed", true)
                }))
                return
            }

            val ext = file.extension.lowercase()
            val isVideo = ext in VIDEO_EXTENSIONS

            if (isVideo) {
                handleVideoThumbnail(file, requestId, path, send)
            } else {
                handleImageThumbnail(file, requestId, path, send)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail generation failed for $path", e)
            send(ProtocolMessage(ProtocolMessage.TYPE_GALLERY_THUMBNAIL_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("path", path)
                put("thumbnailFailed", true)
            }))
        }
    }

    private fun handleImageThumbnail(
        file: File,
        requestId: String,
        path: String,
        send: (ProtocolMessage) -> Unit
    ) {
        // First pass: decode bounds only
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)

        val origWidth = options.outWidth
        val origHeight = options.outHeight

        if (origWidth <= 0 || origHeight <= 0) {
            send(ProtocolMessage(ProtocolMessage.TYPE_GALLERY_THUMBNAIL_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("path", path)
                put("thumbnailFailed", true)
            }))
            return
        }

        // Calculate inSampleSize to downsample to ~256px max dimension
        options.inSampleSize = calculateInSampleSize(origWidth, origHeight, THUMBNAIL_MAX_SIZE)
        options.inJustDecodeBounds = false

        val sampled = BitmapFactory.decodeFile(file.absolutePath, options)
        if (sampled == null) {
            send(ProtocolMessage(ProtocolMessage.TYPE_GALLERY_THUMBNAIL_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("path", path)
                put("thumbnailFailed", true)
            }))
            return
        }

        // Scale to fit within THUMBNAIL_MAX_SIZE x THUMBNAIL_MAX_SIZE
        val scaled = scaleBitmap(sampled, THUMBNAIL_MAX_SIZE)
        if (scaled !== sampled) {
            sampled.recycle()
        }

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, baos)
        val thumbWidth = scaled.width
        val thumbHeight = scaled.height
        scaled.recycle()

        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        send(ProtocolMessage(ProtocolMessage.TYPE_GALLERY_THUMBNAIL_RESPONSE, JSONObject().apply {
            put("requestId", requestId)
            put("path", path)
            put("data", b64)
            put("width", thumbWidth)
            put("height", thumbHeight)
        }))
    }

    private fun handleVideoThumbnail(
        file: File,
        requestId: String,
        path: String,
        send: (ProtocolMessage) -> Unit
    ) {
        var bitmap: Bitmap? = null
        try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                bitmap = retriever.getFrameAtTime(
                    500000L, // 0.5 seconds in microseconds
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever failed for $path", e)
        }

        if (bitmap == null) {
            send(ProtocolMessage(ProtocolMessage.TYPE_GALLERY_THUMBNAIL_RESPONSE, JSONObject().apply {
                put("requestId", requestId)
                put("path", path)
                put("thumbnailFailed", true)
            }))
            return
        }

        val scaled = scaleBitmap(bitmap!!, THUMBNAIL_MAX_SIZE)
        if (scaled !== bitmap) {
            bitmap!!.recycle()
        }

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, baos)
        val thumbWidth = scaled.width
        val thumbHeight = scaled.height
        scaled.recycle()

        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        send(ProtocolMessage(ProtocolMessage.TYPE_GALLERY_THUMBNAIL_RESPONSE, JSONObject().apply {
            put("requestId", requestId)
            put("path", path)
            put("data", b64)
            put("width", thumbWidth)
            put("height", thumbHeight)
        }))
    }

    // ---- utilities ----

    /**
     * Calculate the largest inSampleSize value that is a power of 2 and keeps
     * the resulting dimensions >= maxSize.
     */
    private fun calculateInSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var inSampleSize = 1
        val larger = maxOf(width, height)
        while (larger / (inSampleSize * 2) >= maxSize) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    /**
     * Scale a bitmap to fit within maxSize x maxSize while maintaining aspect ratio.
     */
    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxSize && height <= maxSize) return bitmap

        val ratio = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val newWidth = (width * ratio).toInt().coerceAtLeast(1)
        val newHeight = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

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

    fun destroy() {
        scope.cancel()
    }
}
