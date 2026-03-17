package xyz.hanson.fosslink.contacts

import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import xyz.hanson.fosslink.network.ProtocolMessage

/**
 * Handles contact migration protocol messages. Scans for device-only contacts
 * (not backed by a Google account) and migrates selected contacts to a Google
 * account by copying all Data rows into a new RawContact.
 *
 * Source raw contacts are NOT deleted after migration.
 */
class ContactMigrationHandler(private val context: Context) {
    private val TAG = "ContactMigration"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Device-local account types that indicate a contact is not cloud-synced. */
    private val DEVICE_LOCAL_TYPES = setOf(
        null, "", "phone", "local",
        "vnd.sec.contact.phone",       // Samsung
        "com.samsung.android.lool",     // Samsung device account
        "com.oppo.contacts.device",     // Oppo
        "com.bbk.account",              // Vivo
        "com.huawei.android.internal.contacts", // Huawei
    )

    /** Data columns to copy from source to destination raw contact. */
    private val DATA_COLUMNS = arrayOf(
        ContactsContract.Data.MIMETYPE,
        ContactsContract.Data.DATA1,
        ContactsContract.Data.DATA2,
        ContactsContract.Data.DATA3,
        ContactsContract.Data.DATA4,
        ContactsContract.Data.DATA5,
        ContactsContract.Data.DATA6,
        ContactsContract.Data.DATA7,
        ContactsContract.Data.DATA8,
        ContactsContract.Data.DATA9,
        ContactsContract.Data.DATA10,
        ContactsContract.Data.DATA11,
        ContactsContract.Data.DATA12,
        ContactsContract.Data.DATA13,
        ContactsContract.Data.DATA14,
        ContactsContract.Data.DATA15,
        ContactsContract.Data.IS_PRIMARY,
        ContactsContract.Data.IS_SUPER_PRIMARY,
    )

    fun handleMessage(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        when (msg.type) {
            ProtocolMessage.TYPE_CONTACTS_MIGRATION_SCAN -> scope.launch { handleScan(send) }
            ProtocolMessage.TYPE_CONTACTS_MIGRATION_EXECUTE -> scope.launch { handleExecute(msg, send) }
        }
    }

    // ---- scan ----

    private fun handleScan(send: (ProtocolMessage) -> Unit) {
        try {
            val resolver = context.contentResolver

            // 1. Query all raw contacts with account info
            val projection = arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.CONTACT_ID,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY,
            )

            data class RawContactInfo(
                val rawContactId: Long,
                val contactId: Long,
                val accountType: String?,
                val accountName: String?,
                val displayName: String?,
            )

            val rawContacts = mutableListOf<RawContactInfo>()
            resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                projection,
                "${ContactsContract.RawContacts.DELETED} = 0",
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
                val contactIdIdx = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)
                val typeIdx = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_NAME)
                val displayIdx = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY)

                while (cursor.moveToNext()) {
                    rawContacts.add(RawContactInfo(
                        rawContactId = cursor.getLong(idIdx),
                        contactId = cursor.getLong(contactIdIdx),
                        accountType = cursor.getString(typeIdx),
                        accountName = cursor.getString(nameIdx),
                        displayName = cursor.getString(displayIdx),
                    ))
                }
            }

            // 2. Group by CONTACT_ID
            val groupedByContact = rawContacts.groupBy { it.contactId }

            // 3. A contact is "device-only" if NONE of its raw contacts have
            //    ACCOUNT_TYPE = "com.google"
            val deviceOnlyContacts = groupedByContact.filter { (_, raws) ->
                raws.none { it.accountType == "com.google" }
            }

            // 4. Build response entries: one per device-only contact, pick first raw contact
            val contactsArray = JSONArray()
            for ((contactId, raws) in deviceOnlyContacts) {
                val primary = raws.first()
                val displayName = primary.displayName ?: ""
                if (displayName.isBlank()) continue // Skip unnamed contacts

                // Read first phone number
                val phoneNumber = readFirstPhoneNumber(resolver, contactId)

                contactsArray.put(JSONObject().apply {
                    put("contactId", contactId)
                    put("rawContactId", primary.rawContactId)
                    put("displayName", displayName)
                    put("phoneNumber", phoneNumber ?: "")
                    put("accountType", primary.accountType ?: "local")
                    put("accountName", primary.accountName ?: "Phone")
                })
            }

            // 5. Get available Google accounts
            val accountManager = AccountManager.get(context)
            val googleAccounts = accountManager.getAccountsByType("com.google")
            val accountsArray = JSONArray()
            for (account in googleAccounts) {
                accountsArray.put(account.name)
            }

            val response = JSONObject().apply {
                put("contacts", contactsArray)
                put("googleAccounts", accountsArray)
                put("totalDeviceOnly", deviceOnlyContacts.size)
            }

            send(ProtocolMessage(ProtocolMessage.TYPE_CONTACTS_MIGRATION_SCAN_RESPONSE, response))
            Log.i(TAG, "Scan complete: ${deviceOnlyContacts.size} device-only contacts, ${googleAccounts.size} Google accounts")
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            send(ProtocolMessage(ProtocolMessage.TYPE_CONTACTS_MIGRATION_SCAN_RESPONSE, JSONObject().apply {
                put("contacts", JSONArray())
                put("googleAccounts", JSONArray())
                put("totalDeviceOnly", 0)
                put("error", e.message ?: "Unknown error")
            }))
        }
    }

    private fun readFirstPhoneNumber(
        resolver: android.content.ContentResolver,
        contactId: Long,
    ): String? {
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return null
    }

    // ---- execute ----

    private fun handleExecute(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        // Check WRITE_CONTACTS permission
        if (context.checkSelfPermission("android.permission.WRITE_CONTACTS")
            != PackageManager.PERMISSION_GRANTED
        ) {
            send(ProtocolMessage(ProtocolMessage.TYPE_CONTACTS_MIGRATION_EXECUTE_RESPONSE, JSONObject().apply {
                put("migrated", 0)
                put("failed", 0)
                put("errors", JSONArray().put("WRITE_CONTACTS permission not granted"))
            }))
            return
        }

        val rawContactIds = msg.body.optJSONArray("rawContactIds") ?: JSONArray()
        val targetAccount = msg.body.optString("targetAccount", "")

        if (targetAccount.isBlank()) {
            send(ProtocolMessage(ProtocolMessage.TYPE_CONTACTS_MIGRATION_EXECUTE_RESPONSE, JSONObject().apply {
                put("migrated", 0)
                put("failed", rawContactIds.length())
                put("errors", JSONArray().put("No target Google account specified"))
            }))
            return
        }

        val resolver = context.contentResolver
        var migrated = 0
        var failed = 0
        val errors = JSONArray()

        for (i in 0 until rawContactIds.length()) {
            val rawContactId = rawContactIds.getLong(i)
            try {
                migrateRawContact(resolver, rawContactId, targetAccount)
                migrated++
            } catch (e: Exception) {
                failed++
                // Try to get the display name for a meaningful error
                val name = getDisplayNameForRawContact(resolver, rawContactId) ?: "ID $rawContactId"
                val errorMsg = "Contact '$name' failed: ${e.message ?: "Unknown error"}"
                errors.put(errorMsg)
                Log.e(TAG, errorMsg, e)
            }
        }

        val response = JSONObject().apply {
            put("migrated", migrated)
            put("failed", failed)
            put("errors", errors)
        }

        send(ProtocolMessage(ProtocolMessage.TYPE_CONTACTS_MIGRATION_EXECUTE_RESPONSE, response))
        Log.i(TAG, "Migration complete: $migrated migrated, $failed failed")
    }

    /**
     * Migrate a single raw contact to a Google account by copying all its Data rows
     * into a new RawContact under the target account.
     *
     * The source raw contact is NOT deleted.
     */
    private fun migrateRawContact(
        resolver: android.content.ContentResolver,
        rawContactId: Long,
        targetAccount: String,
    ) {
        // 1. Read all Data rows for this raw contact
        val dataRows = mutableListOf<Map<String, String?>>()

        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            DATA_COLUMNS,
            "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawContactId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val row = mutableMapOf<String, String?>()
                for (col in DATA_COLUMNS) {
                    val idx = cursor.getColumnIndex(col)
                    if (idx >= 0) {
                        row[col] = cursor.getString(idx)
                    }
                }
                dataRows.add(row)
            }
        }

        if (dataRows.isEmpty()) {
            throw IllegalStateException("No data rows found for raw contact $rawContactId")
        }

        // 2. Build ContentProviderOperation batch
        val ops = ArrayList<ContentProviderOperation>()

        // Operation 0: Insert new RawContact under Google account
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, "com.google")
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, targetAccount)
                .build()
        )

        // Operations 1..N: Insert Data rows with back-reference to the new RawContact
        for (row in dataRows) {
            val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)

            for ((col, value) in row) {
                if (col == ContactsContract.Data.RAW_CONTACT_ID) continue
                if (value != null) {
                    builder.withValue(col, value)
                }
            }

            ops.add(builder.build())
        }

        // 3. Apply batch — this is per-contact, so a failure here only affects one contact
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun getDisplayNameForRawContact(
        resolver: android.content.ContentResolver,
        rawContactId: Long,
    ): String? {
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.RawContacts._ID} = ?",
            arrayOf(rawContactId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return null
    }

    fun destroy() {
        scope.cancel()
    }
}
