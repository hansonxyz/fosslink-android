package xyz.hanson.fosslink.query

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Query handler for contacts.photos
 *
 * Returns base64-encoded contact photos for the requested UIDs (lookup keys).
 *
 * Params:
 *   uids: string[] (required — list of contact lookup keys)
 */
class ContactsPhotosHandler(private val context: Context) : QueryHandler {
    private val TAG = "ContactsPhotosHandler"
    private val MAX_SIZE = 96

    override fun handle(params: JSONObject): JSONArray {
        val uids = params.optJSONArray("uids") ?: return JSONArray()
        val result = JSONArray()

        for (i in 0 until uids.length()) {
            val uid = uids.optString(i) ?: continue

            val photo = getContactPhoto(uid)
            if (photo != null) {
                result.put(JSONObject().apply {
                    put("uid", uid)
                    put("mimeType", "image/jpeg")
                    put("data", photo)
                })
            }
        }

        Log.i(TAG, "contacts.photos: ${result.length()} photos for ${uids.length()} requested")
        return result
    }

    private fun getContactPhoto(lookupKey: String): String? {
        // Find contact ID from lookup key
        val contactUri = ContactsContract.Contacts.lookupContact(
            context.contentResolver,
            ContactsContract.Contacts.CONTENT_LOOKUP_URI.buildUpon()
                .appendPath(lookupKey)
                .build()
        ) ?: return null

        val cursor = context.contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts._ID),
            null, null, null
        ) ?: return null

        val contactId = cursor.use {
            if (it.moveToFirst()) it.getLong(0) else return null
        }

        // Open photo stream
        val photoUri = android.net.Uri.withAppendedPath(
            android.content.ContentUris.withAppendedId(
                ContactsContract.Contacts.CONTENT_URI, contactId
            ),
            ContactsContract.Contacts.Photo.CONTENT_DIRECTORY
        )

        try {
            val stream = context.contentResolver.openInputStream(photoUri) ?: return null
            stream.use { s ->
                val bitmap = BitmapFactory.decodeStream(s) ?: return null
                val scale = minOf(
                    MAX_SIZE.toFloat() / bitmap.width,
                    MAX_SIZE.toFloat() / bitmap.height,
                    1f
                )
                val thumb = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt(),
                        (bitmap.height * scale).toInt(),
                        true
                    )
                } else bitmap

                val baos = ByteArrayOutputStream()
                thumb.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                val encoded = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                if (thumb !== bitmap) thumb.recycle()
                bitmap.recycle()

                return encoded
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read photo for $lookupKey", e)
            return null
        }
    }
}
