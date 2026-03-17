package xyz.hanson.fosslink.network

import org.json.JSONObject

data class ProtocolMessage(
    val type: String,
    val body: JSONObject = JSONObject()
) {
    fun serialize(): String = JSONObject().apply {
        put("type", type)
        put("body", body)
    }.toString()

    companion object {
        const val IDENTITY = "fosslink.identity"
        const val PAIR_CODE = "fosslink.pair_code"
        const val PAIR_CONFIRM = "fosslink.pair_confirm"
        const val PAIR_ACCEPT = "fosslink.pair_accept"
        const val PAIR_REJECT = "fosslink.pair_reject"
        const val PAIR_REQUEST = "fosslink.pair_request"
        const val UNPAIR = "fosslink.unpair"

        // SMS sync
        const val TYPE_SYNC_START = "fosslink.sms.sync_start"
        const val TYPE_SYNC_BATCH = "fosslink.sms.sync_batch"
        const val TYPE_SYNC_COMPLETE = "fosslink.sms.sync_complete"
        const val TYPE_SYNC_ACK = "fosslink.sms.sync_ack"

        // Contacts
        const val TYPE_CONTACTS_SYNC = "fosslink.contacts.sync"
        const val TYPE_CONTACTS_REQUEST_PHOTO = "fosslink.contacts.request_photo"
        const val TYPE_CONTACTS_UIDS_RESPONSE = "fosslink.contacts.uids_response"
        const val TYPE_CONTACTS_VCARDS_RESPONSE = "fosslink.contacts.vcards_response"

        // Attachments
        const val TYPE_REQUEST_ATTACHMENT = "fosslink.sms.request_attachment"
        const val TYPE_ATTACHMENT_FILE = "fosslink.sms.attachment_file"

        // Real-time events
        const val TYPE_SMS_EVENT = "fosslink.sms.event"
        const val TYPE_SMS_EVENT_ACK = "fosslink.sms.event_ack"

        // Desktop → Phone commands
        const val TYPE_MARK_READ = "fosslink.sms.mark_read"
        const val TYPE_DELETE = "fosslink.sms.delete"
        const val TYPE_DELETE_THREAD = "fosslink.sms.delete_thread"

        // Send SMS
        const val TYPE_SMS_SEND = "fosslink.sms.send"
        const val TYPE_SMS_SEND_STATUS = "fosslink.sms.send_status"

        // Battery
        const val TYPE_BATTERY = "fosslink.battery"
        const val TYPE_BATTERY_REQUEST = "fosslink.battery.request"

        // Telephony (dial)
        const val TYPE_DIAL = "fosslink.telephony.dial"

        // Find my phone
        const val TYPE_FINDMYPHONE = "fosslink.findmyphone"

        // URL sharing
        const val TYPE_URL_SHARE = "fosslink.url.share"

        // Settings
        const val TYPE_SETTINGS = "fosslink.settings"

        // Storage analysis
        const val TYPE_STORAGE_REQUEST = "fosslink.storage.request"
        const val TYPE_STORAGE_ANALYSIS = "fosslink.storage.analysis"

        // Contact migration
        const val TYPE_CONTACTS_MIGRATION_SCAN = "fosslink.contacts.migration_scan"
        const val TYPE_CONTACTS_MIGRATION_SCAN_RESPONSE = "fosslink.contacts.migration_scan_response"
        const val TYPE_CONTACTS_MIGRATION_EXECUTE = "fosslink.contacts.migration_execute"
        const val TYPE_CONTACTS_MIGRATION_EXECUTE_RESPONSE = "fosslink.contacts.migration_execute_response"

        // Gallery
        const val TYPE_GALLERY_SCAN = "fosslink.gallery.scan"
        const val TYPE_GALLERY_SCAN_RESPONSE = "fosslink.gallery.scan_response"
        const val TYPE_GALLERY_THUMBNAIL = "fosslink.gallery.thumbnail"
        const val TYPE_GALLERY_THUMBNAIL_RESPONSE = "fosslink.gallery.thumbnail_response"
        const val TYPE_GALLERY_MEDIA_EVENT = "fosslink.gallery.media_event"
        const val TYPE_GALLERY_MEDIA_EVENT_ACK = "fosslink.gallery.media_event_ack"

        // Filesystem (WebDAV bridge)
        const val TYPE_FS_STAT = "fosslink.fs.stat"
        const val TYPE_FS_STAT_RESPONSE = "fosslink.fs.stat_response"
        const val TYPE_FS_READDIR = "fosslink.fs.readdir"
        const val TYPE_FS_READDIR_RESPONSE = "fosslink.fs.readdir_response"
        const val TYPE_FS_READ = "fosslink.fs.read"
        const val TYPE_FS_READ_RESPONSE = "fosslink.fs.read_response"
        const val TYPE_FS_WRITE = "fosslink.fs.write"
        const val TYPE_FS_WRITE_RESPONSE = "fosslink.fs.write_response"
        const val TYPE_FS_MKDIR = "fosslink.fs.mkdir"
        const val TYPE_FS_MKDIR_RESPONSE = "fosslink.fs.mkdir_response"
        const val TYPE_FS_DELETE = "fosslink.fs.delete"
        const val TYPE_FS_DELETE_RESPONSE = "fosslink.fs.delete_response"
        const val TYPE_FS_RENAME = "fosslink.fs.rename"
        const val TYPE_FS_RENAME_RESPONSE = "fosslink.fs.rename_response"
        const val TYPE_FS_WATCH = "fosslink.fs.watch"
        const val TYPE_FS_WATCH_RESPONSE = "fosslink.fs.watch_response"
        const val TYPE_FS_UNWATCH = "fosslink.fs.unwatch"
        const val TYPE_FS_UNWATCH_RESPONSE = "fosslink.fs.unwatch_response"
        const val TYPE_FS_WATCH_EVENT = "fosslink.fs.watch_event"
        const val TYPE_FS_WATCH_EVENT_ACK = "fosslink.fs.watch_event_ack"

        const val CLIENT_VERSION = "0.2.0"

        fun parse(data: String): ProtocolMessage {
            val json = JSONObject(data)
            return ProtocolMessage(
                type = json.getString("type"),
                body = json.getJSONObject("body")
            )
        }

        fun createIdentity(deviceId: String, deviceName: String): ProtocolMessage {
            return ProtocolMessage(IDENTITY, JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("deviceType", "phone")
                put("clientType", "fosslink")
                put("clientVersion", CLIENT_VERSION)
            })
        }
    }
}
