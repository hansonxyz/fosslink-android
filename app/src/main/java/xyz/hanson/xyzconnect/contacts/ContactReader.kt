/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink.contacts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Reads contacts from Android's ContactsContract content provider and
 * builds vCard strings for syncing to the desktop.
 *
 * Clean-room implementation — no external dependencies.
 */
object ContactReader {

    private const val TAG = "ContactReader"

    /** Max thumbnail dimension for contact photos (keeps payload reasonable) */
    private const val PHOTO_MAX_SIZE = 96

    data class ContactInfo(
        val uid: String,
        val vcard: String
    )

    /**
     * Read all contacts and return as vCard strings keyed by lookup key (UID).
     * Photos are embedded as base64 in the PHOTO field.
     */
    fun getAllContacts(context: Context): List<ContactInfo> {
        val contacts = mutableListOf<ContactInfo>()
        val cr = context.contentResolver

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
            ContactsContract.Contacts.PHOTO_URI,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        )

        cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null, null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val lookupIdx = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val hasPhoneIdx = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
            val photoUriIdx = cursor.getColumnIndex(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)

            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(idIdx)
                val lookupKey = cursor.getString(lookupIdx) ?: continue
                val displayName = cursor.getString(nameIdx) ?: continue

                val vcard = buildVcard(
                    context, contactId, lookupKey, displayName,
                    cursor.getInt(hasPhoneIdx) > 0,
                    cursor.getString(photoUriIdx)
                )
                contacts.add(ContactInfo(lookupKey, vcard))
            }
        }

        Log.i(TAG, "Read ${contacts.size} contacts")
        return contacts
    }

    private fun buildVcard(
        context: Context,
        contactId: Long,
        lookupKey: String,
        displayName: String,
        hasPhone: Boolean,
        photoThumbnailUri: String?
    ): String {
        val sb = StringBuilder()
        sb.appendLine("BEGIN:VCARD")
        sb.appendLine("VERSION:3.0")
        sb.appendLine("FN:$displayName")

        val cr = context.contentResolver

        // Phone numbers
        if (hasPhone) {
            cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                ),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null
            )?.use { phoneCursor ->
                val numIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)

                while (phoneCursor.moveToNext()) {
                    val number = phoneCursor.getString(numIdx) ?: continue
                    val type = phoneCursor.getInt(typeIdx)
                    val label = phoneCursor.getString(labelIdx)
                    val typeStr = phoneTypeToVcard(type, label)
                    sb.appendLine("TEL;TYPE=$typeStr:$number")
                }
            }
        }

        // Email addresses
        cr.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.ADDRESS,
                ContactsContract.CommonDataKinds.Email.TYPE,
                ContactsContract.CommonDataKinds.Email.LABEL,
            ),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { emailCursor ->
            val addrIdx = emailCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            val typeIdx = emailCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.TYPE)
            val labelIdx = emailCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.LABEL)

            while (emailCursor.moveToNext()) {
                val addr = emailCursor.getString(addrIdx) ?: continue
                val type = emailCursor.getInt(typeIdx)
                val label = emailCursor.getString(labelIdx)
                val typeStr = emailTypeToVcard(type, label)
                sb.appendLine("EMAIL;TYPE=$typeStr:$addr")
            }
        }

        // Organization
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Organization.COMPANY,
                ContactsContract.CommonDataKinds.Organization.TITLE,
            ),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
            null
        )?.use { orgCursor ->
            val companyIdx = orgCursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY)
            val titleIdx = orgCursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.TITLE)

            if (orgCursor.moveToFirst()) {
                val company = orgCursor.getString(companyIdx)
                val title = orgCursor.getString(titleIdx)
                if (!company.isNullOrBlank()) {
                    sb.appendLine("ORG:$company")
                }
                if (!title.isNullOrBlank()) {
                    sb.appendLine("TITLE:$title")
                }
            }
        }

        // Postal addresses
        cr.query(
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                ContactsContract.CommonDataKinds.StructuredPostal.TYPE,
            ),
            "${ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { addrCursor ->
            val fmtIdx = addrCursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
            val typeIdx = addrCursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.TYPE)

            while (addrCursor.moveToNext()) {
                val formatted = addrCursor.getString(fmtIdx) ?: continue
                val type = addrCursor.getInt(typeIdx)
                val typeStr = addressTypeToVcard(type)
                // ADR format: PO Box;Extended;Street;City;Region;Postal;Country
                // Use formatted address in Street field for simplicity
                val escaped = formatted.replace("\n", "\\n")
                sb.appendLine("ADR;TYPE=$typeStr:;;$escaped;;;;")
            }
        }

        // Nickname
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Nickname.NAME),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE),
            null
        )?.use { nickCursor ->
            val nameFieldIdx = nickCursor.getColumnIndex(ContactsContract.CommonDataKinds.Nickname.NAME)
            if (nickCursor.moveToFirst()) {
                val nickname = nickCursor.getString(nameFieldIdx)
                if (!nickname.isNullOrBlank()) {
                    sb.appendLine("NICKNAME:$nickname")
                }
            }
        }

        // Note
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Note.NOTE),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
            null
        )?.use { noteCursor ->
            val noteIdx = noteCursor.getColumnIndex(ContactsContract.CommonDataKinds.Note.NOTE)
            if (noteCursor.moveToFirst()) {
                val note = noteCursor.getString(noteIdx)
                if (!note.isNullOrBlank()) {
                    sb.appendLine("NOTE:${note.replace("\n", "\\n")}")
                }
            }
        }

        // Birthday (Event type BIRTHDAY)
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Event.START_DATE),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
            arrayOf(
                contactId.toString(),
                ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()
            ),
            null
        )?.use { bdayCursor ->
            val dateIdx = bdayCursor.getColumnIndex(ContactsContract.CommonDataKinds.Event.START_DATE)
            if (bdayCursor.moveToFirst()) {
                val date = bdayCursor.getString(dateIdx)
                if (!date.isNullOrBlank()) {
                    sb.appendLine("BDAY:$date")
                }
            }
        }

        // Photo (thumbnail — already small, but resize to be safe)
        if (photoThumbnailUri != null) {
            try {
                val photoBase64 = loadPhotoAsBase64(context, Uri.parse(photoThumbnailUri))
                if (photoBase64 != null) {
                    sb.appendLine("PHOTO;ENCODING=b;TYPE=JPEG:$photoBase64")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load photo for contact $lookupKey: ${e.message}")
            }
        }

        sb.appendLine("END:VCARD")
        return sb.toString()
    }

    private fun loadPhotoAsBase64(context: Context, photoUri: Uri): String? {
        context.contentResolver.openInputStream(photoUri)?.use { inputStream ->
            val bitmap = BitmapFactory.decodeStream(inputStream) ?: return null
            // Resize if needed
            val scaled = if (bitmap.width > PHOTO_MAX_SIZE || bitmap.height > PHOTO_MAX_SIZE) {
                val scale = PHOTO_MAX_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else {
                bitmap
            }
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }
        return null
    }

    private fun phoneTypeToVcard(type: Int, label: String?): String {
        return when (type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "HOME"
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "CELL"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "WORK"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "WORK,CELL"
            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "HOME,FAX"
            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "WORK,FAX"
            ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "PREF"
            ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "OTHER"
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> label ?: "OTHER"
            else -> "OTHER"
        }
    }

    private fun emailTypeToVcard(type: Int, label: String?): String {
        return when (type) {
            ContactsContract.CommonDataKinds.Email.TYPE_HOME -> "HOME"
            ContactsContract.CommonDataKinds.Email.TYPE_WORK -> "WORK"
            ContactsContract.CommonDataKinds.Email.TYPE_OTHER -> "OTHER"
            ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM -> label ?: "OTHER"
            else -> "OTHER"
        }
    }

    private fun addressTypeToVcard(type: Int): String {
        return when (type) {
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME -> "HOME"
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK -> "WORK"
            else -> "OTHER"
        }
    }
}
