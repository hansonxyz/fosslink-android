package xyz.hanson.fosslink.query

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Query handler for contacts.list
 *
 * Returns all contacts with their name, phone numbers, and a photo hash
 * (SHA-256 of the thumbnail URI — desktop uses this to detect photo changes).
 */
class ContactsListHandler(private val context: Context) : QueryHandler {
    private val TAG = "ContactsListHandler"

    override fun handle(params: JSONObject): JSONArray {
        val result = JSONArray()

        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.HAS_PHONE_NUMBER,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            ),
            null, null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
        ) ?: return result

        cursor.use {
            while (it.moveToNext()) {
                val contactId = it.getLong(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val lookupKey = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)) ?: continue
                val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)) ?: ""
                val hasPhone = it.getInt(it.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER))
                val photoUri = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI))

                // Get phone numbers
                val phones = JSONArray()
                if (hasPhone > 0) {
                    val phoneCursor = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        arrayOf(contactId.toString()),
                        null
                    )
                    phoneCursor?.use { pc ->
                        while (pc.moveToNext()) {
                            val number = pc.getString(0)
                            if (!number.isNullOrEmpty()) phones.put(number)
                        }
                    }
                }

                // Hash photo URI for change detection
                val photoHash = if (photoUri != null) {
                    val md = MessageDigest.getInstance("SHA-256")
                    md.digest(photoUri.toByteArray()).joinToString("") { "%02x".format(it) }
                } else ""

                result.put(JSONObject().apply {
                    put("uid", lookupKey)
                    put("name", name)
                    put("phones", phones)
                    put("photoHash", photoHash)
                })
            }
        }

        Log.i(TAG, "contacts.list: ${result.length()} contacts")
        return result
    }
}
