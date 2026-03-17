/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink.gallery

import android.content.Context
import android.database.ContentObserver
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import xyz.hanson.fosslink.network.ProtocolMessage
import java.io.File
import java.util.UUID

/**
 * Observes MediaStore for new images and videos, pushing real-time events
 * to the desktop. Follows the same pattern as SmsEventHandler.
 *
 * No queue, no acks. WebSocket handles delivery while connected.
 * On disconnect, the desktop does a full resync on reconnect.
 */
class GalleryEventHandler(private val context: Context) {
    private val TAG = "GalleryEventHandler"
    private val storageRoot: File = Environment.getExternalStorageDirectory()

    private var imageObserver: ContentObserver? = null
    private var videoObserver: ContentObserver? = null
    private var sendFn: ((ProtocolMessage) -> Unit)? = null
    private var lastKnownTimestamp: Long = 0

    val isRunning: Boolean get() = imageObserver != null

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heif", "heic")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v")
    }

    /**
     * Start observing MediaStore for new media and pushing events via [send].
     */
    fun start(send: (ProtocolMessage) -> Unit) {
        if (imageObserver != null) {
            Log.w(TAG, "Already started, stopping first")
            stop()
        }

        sendFn = send
        lastKnownTimestamp = getCurrentMaxTimestamp()
        Log.i(TAG, "Starting gallery event handler, lastKnownTimestamp=$lastKnownTimestamp")

        val handler = Handler(Looper.getMainLooper())

        imageObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                detectNewMedia()
            }
        }
        videoObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                detectNewMedia()
            }
        }

        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, imageObserver!!
        )
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, videoObserver!!
        )
    }

    /**
     * Stop observing and clean up state.
     */
    fun stop() {
        imageObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        videoObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        imageObserver = null
        videoObserver = null
        sendFn = null
        lastKnownTimestamp = 0
        Log.i(TAG, "Gallery event handler stopped")
    }

    private fun getCurrentMaxTimestamp(): Long {
        var maxTimestamp = 0L

        // Check images
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf("MAX(${MediaStore.Images.Media.DATE_MODIFIED})"),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    maxTimestamp = maxOf(maxTimestamp, cursor.getLong(0))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query max image timestamp", e)
        }

        // Check videos
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf("MAX(${MediaStore.Video.Media.DATE_MODIFIED})"),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    maxTimestamp = maxOf(maxTimestamp, cursor.getLong(0))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query max video timestamp", e)
        }

        return maxTimestamp
    }

    private fun detectNewMedia() {
        val send = sendFn ?: return

        val newItems = JSONArray()
        val mimeTypeMap = MimeTypeMap.getSingleton()

        // Query new images
        queryNewMedia(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.SIZE,
            mimeTypeMap,
            newItems
        )

        // Query new videos
        queryNewMedia(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            mimeTypeMap,
            newItems
        )

        if (newItems.length() == 0) return

        // Update timestamp
        var newestTimestamp = lastKnownTimestamp
        for (i in 0 until newItems.length()) {
            val mtime = newItems.getJSONObject(i).optLong("mtime", 0)
            if (mtime > newestTimestamp) newestTimestamp = mtime
        }
        lastKnownTimestamp = newestTimestamp

        Log.i(TAG, "Pushing ${newItems.length()} new media item(s)")
        send(ProtocolMessage(ProtocolMessage.TYPE_GALLERY_MEDIA_EVENT, JSONObject().apply {
            put("event", "added")
            put("eventId", UUID.randomUUID().toString())
            put("items", newItems)
        }))
    }

    @Suppress("DEPRECATION")
    private fun queryNewMedia(
        uri: android.net.Uri,
        dateModifiedColumn: String,
        dataColumn: String,
        sizeColumn: String,
        mimeTypeMap: MimeTypeMap,
        outItems: JSONArray
    ) {
        try {
            context.contentResolver.query(
                uri,
                arrayOf(dataColumn, dateModifiedColumn, sizeColumn),
                "$dateModifiedColumn > ?",
                arrayOf(lastKnownTimestamp.toString()),
                "$dateModifiedColumn DESC"
            )?.use { cursor ->
                val dataIdx = cursor.getColumnIndexOrThrow(dataColumn)
                val dateIdx = cursor.getColumnIndexOrThrow(dateModifiedColumn)
                val sizeIdx = cursor.getColumnIndexOrThrow(sizeColumn)

                while (cursor.moveToNext()) {
                    val absolutePath = cursor.getString(dataIdx) ?: continue
                    val file = File(absolutePath)
                    if (!file.exists()) continue

                    val mtime = cursor.getLong(dateIdx)
                    val size = cursor.getLong(sizeIdx)

                    val relativePath = try {
                        file.relativeTo(storageRoot).path
                    } catch (_: IllegalArgumentException) {
                        continue
                    }

                    val folder = file.parentFile?.let {
                        try { it.relativeTo(storageRoot).path } catch (_: IllegalArgumentException) { "" }
                    } ?: ""

                    val ext = file.extension.lowercase()
                    val mimeType = mimeTypeMap.getMimeTypeFromExtension(ext) ?: ""
                    val kind = if (ext in IMAGE_EXTENSIONS) "image" else "video"
                    val isHidden = relativePath.split(File.separator).any { it.startsWith(".") }

                    outItems.put(JSONObject().apply {
                        put("path", "/$relativePath")
                        put("filename", file.name)
                        put("folder", folder)
                        put("mtime", mtime)
                        put("size", size)
                        put("mimeType", mimeType)
                        put("isHidden", isHidden)
                        put("kind", kind)
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query new media from $uri", e)
        }
    }
}
