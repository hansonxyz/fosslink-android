/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink.sms

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import xyz.hanson.fosslink.network.ProtocolMessage

/**
 * Handles attachment download requests from the desktop.
 *
 * When the desktop sends fosslink.sms.request_attachment with { part_id, unique_identifier },
 * this handler reads the MMS part from the content provider and sends it back as base64 data
 * in an fosslink.sms.attachment_file response.
 *
 * Must be called from a background thread (IO dispatcher).
 */
class AttachmentHandler(private val context: Context) {

    companion object {
        private const val TAG = "AttachmentHandler"
        /** Max attachment size to transfer (10 MB) */
        private const val MAX_SIZE = 10 * 1024 * 1024
    }

    fun handleMessage(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        when (msg.type) {
            ProtocolMessage.TYPE_REQUEST_ATTACHMENT -> {
                val partId = msg.body.optLong("part_id", -1)
                val uniqueIdentifier = msg.body.optString("unique_identifier", "")
                if (partId > 0 || uniqueIdentifier.isNotEmpty()) {
                    handleRequestAttachment(partId, uniqueIdentifier, send)
                } else {
                    Log.w(TAG, "Invalid attachment request: missing part_id and unique_identifier")
                }
            }
        }
    }

    private fun handleRequestAttachment(
        partId: Long,
        uniqueIdentifier: String,
        send: (ProtocolMessage) -> Unit
    ) {
        Log.i(TAG, "Attachment requested: partId=$partId, uniqueIdentifier=$uniqueIdentifier")

        try {
            // The unique_identifier from the sync is the MMS part _id (with offset removed for MMS)
            // The part_id matches what we sent during sync
            // Try reading by unique_identifier first (it's the canonical ID from content provider)
            val actualPartId = if (uniqueIdentifier.isNotEmpty()) {
                uniqueIdentifier.toLongOrNull() ?: partId
            } else {
                partId
            }

            val partUri = Uri.parse("content://mms/part/$actualPartId")

            // Get MIME type from the parts table
            val mimeType = getPartMimeType(actualPartId) ?: "application/octet-stream"

            // Read the part data
            val data = context.contentResolver.openInputStream(partUri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                if (bytes.size > MAX_SIZE) {
                    Log.w(TAG, "Attachment too large: ${bytes.size} bytes (max $MAX_SIZE)")
                    return
                }
                bytes
            }

            if (data == null) {
                Log.w(TAG, "Failed to read attachment data for partId=$actualPartId")
                return
            }

            val base64Data = Base64.encodeToString(data, Base64.NO_WRAP)

            // Build the filename from part ID
            val ext = mimeTypeToExtension(mimeType)
            val filename = "${actualPartId}.$ext"

            send(ProtocolMessage(ProtocolMessage.TYPE_ATTACHMENT_FILE, JSONObject().apply {
                put("filename", uniqueIdentifier.ifEmpty { actualPartId.toString() })
                put("payloadSize", data.size)
                put("mimeType", mimeType)
                put("data", base64Data)
            }))

            Log.i(TAG, "Sent attachment: partId=$actualPartId, size=${data.size}, mime=$mimeType")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to read attachment: ${e.message}", e)
        }
    }

    private fun getPartMimeType(partId: Long): String? {
        val uri = Uri.parse("content://mms/part/$partId")
        context.contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.Part.CONTENT_TYPE),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val typeIdx = cursor.getColumnIndex(Telephony.Mms.Part.CONTENT_TYPE)
                if (typeIdx >= 0) {
                    return cursor.getString(typeIdx)
                }
            }
        }
        return null
    }

    private fun mimeTypeToExtension(mimeType: String): String {
        return when {
            mimeType.startsWith("image/jpeg") -> "jpg"
            mimeType.startsWith("image/png") -> "png"
            mimeType.startsWith("image/gif") -> "gif"
            mimeType.startsWith("image/webp") -> "webp"
            mimeType.startsWith("video/mp4") -> "mp4"
            mimeType.startsWith("video/3gp") -> "3gp"
            mimeType.startsWith("audio/") -> "m4a"
            else -> "bin"
        }
    }
}
