/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink.contacts

import android.content.Context
import android.util.Log
import org.json.JSONObject
import xyz.hanson.fosslink.network.ProtocolMessage

/**
 * Handles contact sync protocol.
 *
 * When the desktop sends fosslink.contacts.sync, this handler:
 * 1. Reads all contacts from ContactsContract
 * 2. Sends them as vCards in batches of BATCH_SIZE
 * 3. Sends contacts.complete with total count
 *
 * The desktop's ContactsHandler parses the vCards and extracts photos.
 *
 * Must be called from a background thread (IO dispatcher).
 */
class ContactSyncHandler(private val context: Context) {

    companion object {
        private const val TAG = "ContactSyncHandler"
        private const val BATCH_SIZE = 50
    }

    fun handleMessage(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        when (msg.type) {
            ProtocolMessage.TYPE_CONTACTS_SYNC -> {
                handleContactsSync(send)
            }
            ProtocolMessage.TYPE_CONTACTS_REQUEST_PHOTO -> {
                val uidsArray = msg.body.optJSONArray("uids")
                if (uidsArray != null) {
                    val uids = mutableListOf<String>()
                    for (i in 0 until uidsArray.length()) {
                        uids.add(uidsArray.getString(i))
                    }
                    handleRequestPhoto(uids, send)
                }
            }
        }
    }

    /**
     * Handle fosslink.contacts.sync — send all contact UIDs to desktop.
     * Desktop will then request vCards for the UIDs it needs.
     */
    private fun handleContactsSync(send: (ProtocolMessage) -> Unit) {
        Log.i(TAG, "Contact sync requested")

        val contacts = ContactReader.getAllContacts(context)

        // Send UIDs in object format: { uid1: timestamp, uid2: timestamp, ... }
        // Desktop's handleUidsResponse supports both array and object formats
        val body = JSONObject()
        for (contact in contacts) {
            body.put(contact.uid, System.currentTimeMillis())
        }

        send(ProtocolMessage(ProtocolMessage.TYPE_CONTACTS_UIDS_RESPONSE, body))

        Log.i(TAG, "Sent ${contacts.size} contact UIDs")
    }

    /**
     * Handle fosslink.contacts.request_photo — send vCards for requested UIDs.
     * The desktop requests specific UIDs after receiving the UIDs list.
     */
    private fun handleRequestPhoto(uids: List<String>, send: (ProtocolMessage) -> Unit) {
        Log.i(TAG, "vCards requested for ${uids.size} contacts")

        val allContacts = ContactReader.getAllContacts(context)
        val uidSet = uids.toSet()
        val matching = allContacts.filter { it.uid in uidSet }

        // Send vCards in batches — each batch is a JSON object with UID → vCard string
        var sent = 0
        for (chunk in matching.chunked(BATCH_SIZE)) {
            val body = JSONObject()
            for (contact in chunk) {
                body.put(contact.uid, contact.vcard)
            }
            send(ProtocolMessage(ProtocolMessage.TYPE_CONTACTS_VCARDS_RESPONSE, body))
            sent += chunk.size
            Log.d(TAG, "Sent vCard batch: $sent/${matching.size}")
        }

        Log.i(TAG, "Contact vCards sync complete: $sent contacts")
    }
}
