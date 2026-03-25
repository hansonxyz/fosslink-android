package xyz.hanson.fosslink.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import xyz.hanson.fosslink.MainActivity
import xyz.hanson.fosslink.R
import xyz.hanson.fosslink.network.*
import xyz.hanson.fosslink.battery.BatteryHandler
import xyz.hanson.fosslink.contacts.ContactMigrationHandler
import xyz.hanson.fosslink.gallery.GalleryEventHandler
import xyz.hanson.fosslink.gallery.GalleryHandler
import xyz.hanson.fosslink.contacts.ContactSyncHandler
import xyz.hanson.fosslink.sms.AttachmentHandler
import xyz.hanson.fosslink.sms.SmsEventHandler
import xyz.hanson.fosslink.sms.SmsSendHandler
import xyz.hanson.fosslink.sms.SmsSyncHandler
import xyz.hanson.fosslink.filesystem.FsWatchHandler
import xyz.hanson.fosslink.findmyphone.FindMyPhoneHandler
import xyz.hanson.fosslink.storage.StorageAnalyzer
import xyz.hanson.fosslink.url.UrlHandler

data class DesktopConnection(
    val deviceId: String,
    val deviceName: String,
    val state: ConnectionState,
    val pairingCode: String? = null
)

class ConnectionService : Service() {
    private val TAG = "ConnectionService"
    private val CHANNEL_ID = "fosslink_connection"
    private val URL_CHANNEL_ID = "fosslink_url"
    private val NOTIFICATION_ID = 1

    companion object {
        var instance: ConnectionService? = null
            private set
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** IO-dispatched scope for message handlers — survives Activity destruction */
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var prefs: android.content.SharedPreferences

    // Multi-desktop: one WebSocketClient per paired desktop
    val wsClients = mutableMapOf<String, WebSocketClient>()
    var ownDeviceId: String = ""
        private set

    var discoveryListener: DiscoveryListener? = null
        private set
    var smsSyncHandler: SmsSyncHandler? = null
        private set
    var contactSyncHandler: ContactSyncHandler? = null
        private set
    var attachmentHandler: AttachmentHandler? = null
        private set
    var smsEventHandler: SmsEventHandler? = null
        private set
    var smsSendHandler: SmsSendHandler? = null
        private set
    var batteryHandler: BatteryHandler? = null
        private set
    var urlHandler: UrlHandler? = null
        private set
    var findMyPhoneHandler: FindMyPhoneHandler? = null
        private set
    var storageAnalyzer: StorageAnalyzer? = null
        private set
    var contactMigrationHandler: ContactMigrationHandler? = null
        private set
    var filesystemHandler: xyz.hanson.fosslink.filesystem.FilesystemHandler? = null
        private set
    var galleryHandler: GalleryHandler? = null
        private set
    var galleryEventHandler: GalleryEventHandler? = null
        private set
    var fsWatchHandler: FsWatchHandler? = null
        private set

    // --- Observable state for ViewModel ---

    private val _desktopConnections = MutableStateFlow<Map<String, DesktopConnection>>(emptyMap())
    val desktopConnections: StateFlow<Map<String, DesktopConnection>> = _desktopConnections

    private val _pairedDeviceIds = MutableStateFlow<Set<String>>(emptySet())
    val pairedDeviceIds: StateFlow<Set<String>> = _pairedDeviceIds

    private val _pairedDeviceNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val pairedDeviceNames: StateFlow<Map<String, String>> = _pairedDeviceNames

    inner class LocalBinder : Binder() {
        fun getService(): ConnectionService = this@ConnectionService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Service created")
        createNotificationChannel()

        prefs = getSharedPreferences("fosslink", MODE_PRIVATE)

        // Generate or load device ID
        var deviceId = prefs.getString("deviceId", null)
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("deviceId", deviceId).apply()
        }
        ownDeviceId = deviceId

        // Load paired devices
        val pairedSet = prefs.getStringSet("paired_devices", null)
        if (pairedSet != null) {
            _pairedDeviceIds.value = pairedSet.toSet()
        } else if (prefs.getBoolean("paired", false)) {
            _pairedDeviceIds.value = setOf("legacy")
            prefs.edit().putStringSet("paired_devices", setOf("legacy")).apply()
        }

        // Load paired device names
        val namesJson = prefs.getString("paired_device_names", null)
        if (namesJson != null) {
            try {
                val json = org.json.JSONObject(namesJson)
                val map = mutableMapOf<String, String>()
                for (key in json.keys()) { map[key] = json.getString(key) }
                _pairedDeviceNames.value = map
            } catch (_: Exception) {}
        }

        smsSyncHandler = SmsSyncHandler(this)
        contactSyncHandler = ContactSyncHandler(this)
        attachmentHandler = AttachmentHandler(this)
        smsEventHandler = SmsEventHandler(this)
        smsSendHandler = SmsSendHandler(this)
        batteryHandler = BatteryHandler(this)
        urlHandler = UrlHandler(this)
        findMyPhoneHandler = FindMyPhoneHandler(this)
        storageAnalyzer = StorageAnalyzer(this)
        contactMigrationHandler = ContactMigrationHandler(this)
        filesystemHandler = xyz.hanson.fosslink.filesystem.FilesystemHandler(this)
        galleryHandler = GalleryHandler(this)
        galleryEventHandler = GalleryEventHandler(this)
        fsWatchHandler = FsWatchHandler()
        discoveryListener = DiscoveryListener(deviceId, Build.MODEL).also {
            // Auto-connect when desktop sends an explicit connect request
            it.onConnectRequest = { desktop ->
                autoConnectTo(desktop, "connect request")
            }
            // Auto-connect to any discovered desktop if we're already paired with it
            it.onDeviceDiscovered = { desktop ->
                if (desktop.deviceId in _pairedDeviceIds.value) {
                    autoConnectTo(desktop, "discovery (paired)")
                }
            }
            it.start(scope)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(getString(R.string.notification_searching))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        storageAnalyzer?.destroy()
        findMyPhoneHandler?.destroy()
        smsEventHandler?.stop()
        batteryHandler?.stop()
        galleryEventHandler?.stop()
        fsWatchHandler?.clearSendFunction()
        wsClients.values.forEach { it.disconnect() }
        wsClients.clear()
        discoveryListener?.stop()
        scope.cancel()
        ioScope.cancel()
        Log.i(TAG, "Service destroyed")
    }

    // --- Multi-client management ---

    fun getAllConnectedClients(): List<WebSocketClient> = wsClients.values.filter {
        it.connectionState.value == ConnectionState.CONNECTED
    }

    fun createClient(deviceId: String): WebSocketClient {
        wsClients[deviceId]?.let { return it }
        val client = WebSocketClient(ownDeviceId, Build.MODEL)
        wsClients[deviceId] = client
        return client
    }

    fun removeClient(deviceId: String) {
        wsClients.remove(deviceId)?.disconnect()
    }

    fun remapClient(oldId: String, newId: String) {
        val client = wsClients.remove(oldId) ?: return
        wsClients[newId] = client
    }

    private fun autoConnectTo(desktop: DiscoveredDesktop, reason: String) {
        val existing = wsClients[desktop.deviceId]
        if (existing != null && existing.connectionState.value != ConnectionState.DISCONNECTED) {
            return // Already connected or connecting
        }
        Log.i(TAG, "Auto-connecting to ${desktop.deviceName} at ${desktop.address}:${desktop.wsPort} ($reason)")
        val client = createClient(desktop.deviceId)
        setupMessageHandlerForClient(desktop.deviceId, client)
        observeClient(desktop.deviceId, client)
        client.connect(desktop.address, desktop.wsPort)
    }

    // --- Message routing ---

    fun setupMessageHandlerForClient(deviceId: String, client: WebSocketClient) {
        client.onMessage { msg ->
            val sendToThis: (ProtocolMessage) -> Unit = { reply -> client.send(reply) }

            when (msg.type) {
                ProtocolMessage.IDENTITY -> {
                    val realId = msg.body.optString("deviceId")
                    if (realId.isNotEmpty() && realId != deviceId && deviceId.startsWith("pending_")) {
                        // Remap temporary ID to real device ID
                        Log.i(TAG, "Remapping $deviceId → $realId")
                        remapClient(deviceId, realId)
                        val conn = _desktopConnections.value[deviceId]
                        if (conn != null) {
                            _desktopConnections.value = (_desktopConnections.value - deviceId) +
                                (realId to conn.copy(deviceId = realId))
                        }
                        // Re-setup with real ID
                        setupMessageHandlerForClient(realId, client)
                        observeClient(realId, client)
                    }
                }
                ProtocolMessage.PAIR_CODE -> {
                    val code = msg.body.optString("code")
                    Log.i(TAG, "Received pairing code from $deviceId: $code")
                    updateDesktopPairingCode(deviceId, code)
                }
                ProtocolMessage.PAIR_ACCEPT -> {
                    Log.i(TAG, "Pairing accepted by $deviceId!")
                    addPairedDevice(deviceId)
                    updateDesktopPairingCode(deviceId, null)
                    startPushHandlers()
                    updateNotificationText()
                }
                ProtocolMessage.PAIR_REJECT -> {
                    Log.i(TAG, "Pairing rejected by $deviceId")
                    updateDesktopPairingCode(deviceId, null)
                }
                ProtocolMessage.UNPAIR -> {
                    Log.i(TAG, "Unpaired by $deviceId")
                    removePairedDevice(deviceId)
                    updateDesktopPairingCode(deviceId, null)
                }
                ProtocolMessage.TYPE_SYNC_START, ProtocolMessage.TYPE_SYNC_ACK -> {
                    acceptImplicitPairing(deviceId)
                    val syncHandler = smsSyncHandler ?: return@onMessage
                    ioScope.launch {
                        syncHandler.handleMessage(msg, sendToThis)
                    }
                }
                ProtocolMessage.TYPE_CONTACTS_SYNC, ProtocolMessage.TYPE_CONTACTS_REQUEST_PHOTO -> {
                    acceptImplicitPairing(deviceId)
                    val contactHandler = contactSyncHandler ?: return@onMessage
                    ioScope.launch {
                        contactHandler.handleMessage(msg, sendToThis)
                    }
                }
                ProtocolMessage.TYPE_REQUEST_ATTACHMENT -> {
                    acceptImplicitPairing(deviceId)
                    val attHandler = attachmentHandler ?: return@onMessage
                    ioScope.launch {
                        attHandler.handleMessage(msg, sendToThis)
                    }
                }
                ProtocolMessage.TYPE_MARK_READ, ProtocolMessage.TYPE_DELETE, ProtocolMessage.TYPE_DELETE_THREAD -> {
                    val eventHandler = smsEventHandler ?: return@onMessage
                    ioScope.launch {
                        eventHandler.handleCommand(msg)
                    }
                }
                ProtocolMessage.TYPE_SMS_EVENT_ACK -> {
                    Log.d(TAG, "Received event_ack from $deviceId (ignored)")
                }
                ProtocolMessage.TYPE_SMS_SEND -> {
                    val sendHandler = smsSendHandler ?: return@onMessage
                    ioScope.launch {
                        sendHandler.handleMessage(msg, sendToThis)
                    }
                }
                ProtocolMessage.TYPE_BATTERY_REQUEST -> {
                    val bHandler = batteryHandler ?: return@onMessage
                    bHandler.handleRequest(sendToThis)
                }
                ProtocolMessage.TYPE_DIAL -> {
                    val number = msg.body.optString("phoneNumber", "")
                    if (number.isNotEmpty()) {
                        Log.i(TAG, "Opening dialer for: $number")
                        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                        val wl = pm.newWakeLock(
                            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "fosslink:dial"
                        )
                        wl.acquire(3000L)
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    }
                }
                ProtocolMessage.TYPE_URL_SHARE -> {
                    urlHandler?.handleMessage(msg)
                }
                ProtocolMessage.TYPE_FINDMYPHONE -> {
                    findMyPhoneHandler?.handleMessage(msg)
                }
                ProtocolMessage.TYPE_STORAGE_REQUEST -> {
                    val analyzer = storageAnalyzer ?: return@onMessage
                    analyzer.handleRequest(sendToThis)
                }
                ProtocolMessage.TYPE_CONTACTS_MIGRATION_SCAN,
                ProtocolMessage.TYPE_CONTACTS_MIGRATION_EXECUTE -> {
                    val migrationHandler = contactMigrationHandler ?: return@onMessage
                    ioScope.launch {
                        migrationHandler.handleMessage(msg, sendToThis)
                    }
                }
                ProtocolMessage.TYPE_GALLERY_SCAN,
                ProtocolMessage.TYPE_GALLERY_THUMBNAIL -> {
                    val gHandler = galleryHandler ?: return@onMessage
                    gHandler.handleMessage(msg, sendToThis)
                }
                ProtocolMessage.TYPE_GALLERY_MEDIA_EVENT_ACK -> {
                    // No-op: desktop acknowledged gallery media event
                }
                ProtocolMessage.TYPE_FS_STAT, ProtocolMessage.TYPE_FS_READDIR,
                ProtocolMessage.TYPE_FS_READ, ProtocolMessage.TYPE_FS_WRITE,
                ProtocolMessage.TYPE_FS_MKDIR, ProtocolMessage.TYPE_FS_DELETE,
                ProtocolMessage.TYPE_FS_RENAME -> {
                    val fsHandler = filesystemHandler ?: return@onMessage
                    fsHandler.handleMessage(msg, sendToThis)
                }
                ProtocolMessage.TYPE_FS_WATCH, ProtocolMessage.TYPE_FS_UNWATCH,
                ProtocolMessage.TYPE_FS_WATCH_EVENT_ACK -> {
                    val watchHandler = fsWatchHandler ?: return@onMessage
                    watchHandler.handleMessage(msg, sendToThis)
                }
            }
        }
    }

    // --- Client connection observation ---

    fun observeClient(deviceId: String, client: WebSocketClient) {
        scope.launch {
            combine(client.connectionState, client.peerDeviceName) { state, name ->
                state to name
            }.collect { (state, name) ->
                val displayName = name ?: deviceId
                val existingCode = _desktopConnections.value[deviceId]?.pairingCode

                if (state == ConnectionState.DISCONNECTED) {
                    // Clean up disconnected client
                    removeClient(deviceId)
                    _desktopConnections.value = _desktopConnections.value - deviceId
                    stopPushHandlersIfEmpty()
                    updateNotificationText()
                } else {
                    _desktopConnections.value = _desktopConnections.value + (deviceId to
                        DesktopConnection(deviceId, displayName, state, existingCode))

                    if (state == ConnectionState.CONNECTED && deviceId in _pairedDeviceIds.value) {
                        startPushHandlers()
                        // Send settings to this specific client
                        smsEventHandler?.sendSettingsTo { msg -> client.send(msg) }
                        // Update persisted device name if we have a real name
                        if (name != null && name != deviceId && _pairedDeviceNames.value[deviceId] != name) {
                            val names = _pairedDeviceNames.value.toMutableMap()
                            names[deviceId] = name
                            _pairedDeviceNames.value = names
                            persistDeviceNames(names)
                        }
                        updateNotificationText()
                    }
                }
            }
        }
    }

    // --- Push handler management ---

    private fun broadcastToAll(): (ProtocolMessage) -> Unit = { msg ->
        getAllConnectedClients().forEach { it.send(msg) }
    }

    private fun startPushHandlers() {
        if (smsEventHandler?.isRunning != true && getAllConnectedClients().isNotEmpty()) {
            val broadcast = broadcastToAll()
            smsEventHandler?.start(broadcast)
            batteryHandler?.start(broadcast)
            galleryEventHandler?.start(broadcast)
            fsWatchHandler?.setSendFunction(broadcast)
        }
    }

    private fun stopPushHandlersIfEmpty() {
        if (getAllConnectedClients().isEmpty()) {
            smsEventHandler?.stop()
            batteryHandler?.stop()
            galleryEventHandler?.stop()
            fsWatchHandler?.clearSendFunction()
        }
    }

    // --- Pairing persistence ---

    fun addPairedDevice(deviceId: String) {
        val current = (_pairedDeviceIds.value - "legacy") + deviceId
        _pairedDeviceIds.value = current
        prefs.edit().putStringSet("paired_devices", current).apply()

        // Persist device name
        val name = _desktopConnections.value[deviceId]?.deviceName ?: deviceId
        val names = _pairedDeviceNames.value.toMutableMap()
        names[deviceId] = name
        _pairedDeviceNames.value = names
        persistDeviceNames(names)
    }

    fun removePairedDevice(deviceId: String) {
        val current = _pairedDeviceIds.value - deviceId
        _pairedDeviceIds.value = current
        prefs.edit().putStringSet("paired_devices", current).apply()

        val names = _pairedDeviceNames.value.toMutableMap()
        names.remove(deviceId)
        _pairedDeviceNames.value = names
        persistDeviceNames(names)
    }

    private fun persistDeviceNames(names: Map<String, String>) {
        val json = org.json.JSONObject()
        for ((k, v) in names) json.put(k, v)
        prefs.edit().putString("paired_device_names", json.toString()).apply()
    }

    private fun updateDesktopPairingCode(deviceId: String, code: String?) {
        val existing = _desktopConnections.value[deviceId] ?: return
        _desktopConnections.value = _desktopConnections.value + (deviceId to existing.copy(pairingCode = code))
        if (code != null) {
            updateNotification(getString(R.string.notification_pairing_request, existing.deviceName))
        }
    }

    private fun acceptImplicitPairing(deviceId: String) {
        if (deviceId !in _pairedDeviceIds.value) {
            Log.i(TAG, "Desktop $deviceId sent protocol message while not paired — accepting implicitly")
            addPairedDevice(deviceId)
            updateDesktopPairingCode(deviceId, null)
            startPushHandlers()
            updateNotificationText()
        }
    }

    // --- Public actions (called by ViewModel) ---

    fun confirmPairing(code: String) {
        val pairingDesktop = _desktopConnections.value.values.firstOrNull { it.pairingCode != null }
        if (pairingDesktop != null) {
            val client = wsClients[pairingDesktop.deviceId]
            client?.send(ProtocolMessage(
                ProtocolMessage.PAIR_CONFIRM,
                org.json.JSONObject().apply { put("code", code) }
            ))
        }
    }

    fun disconnectDesktop(deviceId: String) {
        removeClient(deviceId)
        _desktopConnections.value = _desktopConnections.value - deviceId
        stopPushHandlersIfEmpty()
        updateNotificationText()
    }

    fun forgetDesktop(deviceId: String) {
        disconnectDesktop(deviceId)
        removePairedDevice(deviceId)
    }

    fun forgetAllDesktops() {
        wsClients.keys.toList().forEach { disconnectDesktop(it) }
        _pairedDeviceIds.value = emptySet()
        prefs.edit().putStringSet("paired_devices", emptySet()).apply()
        prefs.edit().putBoolean("setup_complete", false).apply()
    }

    // --- Notification ---

    private fun updateNotificationText() {
        val connections = _desktopConnections.value.values
        val pairing = connections.firstOrNull { it.pairingCode != null }
        val connected = connections.filter { it.state == ConnectionState.CONNECTED }
        val text = when {
            pairing != null -> getString(R.string.notification_pairing_request, pairing.deviceName)
            connected.isEmpty() -> getString(R.string.notification_searching)
            connected.size == 1 -> getString(R.string.notification_connected_to, connected.first().deviceName)
            else -> getString(R.string.notification_connected_multiple, connected.size)
        }
        updateNotification(text)
    }

    fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val connectionChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_connection),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_connection_desc)
        }
        nm.createNotificationChannel(connectionChannel)

        val urlChannel = NotificationChannel(
            URL_CHANNEL_ID,
            getString(R.string.notification_channel_url),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_url_desc)
        }
        nm.createNotificationChannel(urlChannel)
    }
}
