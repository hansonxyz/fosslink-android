package xyz.hanson.fosslink.query

import android.os.Environment
import android.util.Log
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Query handler for gallery.scan
 *
 * Returns all image and video files from external storage, sorted by
 * modification time (newest first). The QueryServer paginates the
 * results automatically so the desktop receives items progressively.
 *
 * Params:
 *   scope (optional, default "all"):
 *     - "dcim"        — only files under DCIM/
 *     - "screenshots" — only files under Pictures/Screenshots/
 *     - "all"         — entire external storage
 */
class GalleryScanHandler : QueryHandler {
    private val TAG = "GalleryScanHandler"

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heif", "heic")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v")
        private val ALL_MEDIA_EXTENSIONS = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS
    }

    override fun handle(params: JSONObject): JSONArray {
        val storageRoot = Environment.getExternalStorageDirectory()
        val scope = params.optString("scope", "all")
        val roots = when (scope) {
            "dcim" -> listOf(File(storageRoot, "DCIM"))
            "screenshots" -> listOf(File(storageRoot, "Pictures/Screenshots"))
            else -> listOf(storageRoot)
        }

        val mimeTypeMap = MimeTypeMap.getSingleton()
        val itemList = mutableListOf<JSONObject>()

        for (root in roots) {
            if (!root.exists()) continue
            root.walk()
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
                    val isHidden = relativePath.split(File.separator).any { it.startsWith(".") }

                    itemList.add(JSONObject().apply {
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
        }

        // Sort by mtime descending (newest first)
        itemList.sortByDescending { it.optLong("mtime", 0L) }

        Log.i(TAG, "gallery.scan ($scope): ${itemList.size} media files")

        val result = JSONArray()
        for (item in itemList) result.put(item)
        return result
    }
}
