package xyz.hanson.fosslink.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

data class DiscoveredDesktop(
    val deviceId: String,
    val deviceName: String,
    val address: String,
    val wsPort: Int,
    val clientVersion: String,
    val lastSeen: Long = System.currentTimeMillis()
)

class DiscoveryListener(
    private val deviceId: String,
    private val deviceName: String
) {
    private val TAG = "DiscoveryListener"
    private val DISCOVERY_PORT = 1716
    private val DEVICE_TIMEOUT_MS = 30_000L
    private val BROADCAST_INTERVAL_MS = 5_000L

    private val _discoveredDevices = MutableStateFlow<Map<String, DiscoveredDesktop>>(emptyMap())
    val discoveredDevices: StateFlow<Map<String, DiscoveredDesktop>> = _discoveredDevices

    /** Fires when a desktop sends a connect request (desktop-initiated pairing) */
    var onConnectRequest: ((DiscoveredDesktop) -> Unit)? = null

    /** Fires when any desktop is discovered (for auto-reconnect when paired) */
    var onDeviceDiscovered: ((DiscoveredDesktop) -> Unit)? = null

    private var scope: CoroutineScope? = null
    private var listenJob: Job? = null
    private var broadcastJob: Job? = null
    private var cleanupJob: Job? = null

    /**
     * Start the passive listener (always on). Listens for desktop broadcasts.
     * Does NOT start broadcasting — call [startBroadcasting] separately.
     */
    fun start(scope: CoroutineScope) {
        stop()
        this.scope = scope

        startListener(scope)

        // Cleanup expired devices periodically
        cleanupJob = scope.launch {
            while (isActive) {
                delay(10_000)
                val now = System.currentTimeMillis()
                val current = _discoveredDevices.value
                val filtered = current.filterValues { now - it.lastSeen < DEVICE_TIMEOUT_MS }
                if (filtered.size != current.size) {
                    _discoveredDevices.value = filtered
                }
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        broadcastJob?.cancel()
        broadcastJob = null
        cleanupJob?.cancel()
        cleanupJob = null
        scope = null
    }

    /**
     * Start broadcasting our presence. Call when the app enters the foreground.
     */
    fun startBroadcasting() {
        val s = scope ?: return
        if (broadcastJob?.isActive == true) return

        Log.i(TAG, "Broadcasting started (foreground)")
        broadcastJob = s.launch(Dispatchers.IO) {
            delay(500)
            while (isActive) {
                try {
                    broadcast()
                } catch (e: Exception) {
                    Log.w(TAG, "Broadcast error", e)
                }
                delay(BROADCAST_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop broadcasting. Call when the app enters the background.
     */
    fun stopBroadcasting() {
        if (broadcastJob?.isActive == true) {
            Log.i(TAG, "Broadcasting stopped (background)")
        }
        broadcastJob?.cancel()
        broadcastJob = null
    }

    /**
     * Restart the UDP listener socket. Call when network connectivity changes
     * (WiFi reconnect, DHCP renewal) since the old socket may be dead.
     */
    fun restartListener() {
        val s = scope ?: return
        Log.i(TAG, "Restarting listener (network change)")
        listenJob?.cancel()
        listenJob = null
        startListener(s)
    }

    private fun startListener(scope: CoroutineScope) {
        listenJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                    broadcast = true
                    soTimeout = 0
                }

                Log.i(TAG, "Listening for discovery on port $DISCOVERY_PORT")
                val buffer = ByteArray(4096)

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)

                        val data = String(packet.data, 0, packet.length)
                        handleDiscoveryPacket(data, packet.address)
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Error receiving discovery packet", e)
                        }
                    }
                }

                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Discovery listener failed to start", e)
            }
        }
    }

    private fun broadcast() {
        val json = JSONObject().apply {
            put("type", "fosslink.discovery")
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("deviceType", "phone")
            put("wsPort", 0)
            put("clientVersion", ProtocolMessage.CLIENT_VERSION)
        }
        val data = json.toString() + "\n"
        val bytes = data.toByteArray()

        val socket = DatagramSocket()
        socket.broadcast = true
        try {
            val packet = DatagramPacket(
                bytes, bytes.size,
                InetAddress.getByName("255.255.255.255"),
                DISCOVERY_PORT
            )
            socket.send(packet)
        } finally {
            socket.close()
        }
    }

    private fun handleDiscoveryPacket(data: String, sender: InetAddress) {
        try {
            val json = JSONObject(data.trim())
            val type = json.optString("type")

            val isConnectRequest = type == "fosslink.connect_request"
            if (type != "fosslink.discovery" && !isConnectRequest) return

            val peerDeviceId = json.optString("deviceId", "")
            val peerDeviceName = json.optString("deviceName", "Unknown")
            val deviceType = json.optString("deviceType", "")
            val wsPort = json.optInt("wsPort", 8716)
            val clientVersion = json.optString("clientVersion", "0.0.0")

            if (peerDeviceId.isEmpty()) return
            if (peerDeviceId == deviceId) return
            if (deviceType == "phone" || deviceType == "tablet") return

            val desktop = DiscoveredDesktop(
                deviceId = peerDeviceId,
                deviceName = peerDeviceName,
                address = sender.hostAddress ?: return,
                wsPort = wsPort,
                clientVersion = clientVersion
            )

            val isNew = !_discoveredDevices.value.containsKey(peerDeviceId)
            _discoveredDevices.value = _discoveredDevices.value + (peerDeviceId to desktop)
            Log.d(TAG, "Discovered: $peerDeviceName at ${desktop.address}:$wsPort (new=$isNew, connectRequest=$isConnectRequest)")

            if (isConnectRequest) {
                Log.i(TAG, "Connect request from $peerDeviceName — triggering auto-connect")
                onConnectRequest?.invoke(desktop)
            }

            if (isNew) {
                onDeviceDiscovered?.invoke(desktop)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse discovery packet", e)
        }
    }
}
