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

    private var listenJob: Job? = null
    private var broadcastJob: Job? = null
    private var cleanupJob: Job? = null

    fun start(scope: CoroutineScope) {
        stop()

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

        // Broadcast our presence periodically
        broadcastJob = scope.launch(Dispatchers.IO) {
            delay(1000) // Initial delay to let socket bind
            while (isActive) {
                try {
                    broadcast()
                } catch (e: Exception) {
                    Log.w(TAG, "Broadcast error", e)
                }
                delay(BROADCAST_INTERVAL_MS)
            }
        }

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
    }

    private fun broadcast() {
        val json = JSONObject().apply {
            put("type", "fosslink.discovery")
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("deviceType", "phone")
            put("wsPort", 0) // Phone doesn't have a WS server
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

            // Discovery packets are flat JSON (fields at top level, no "body" wrapper)
            val peerDeviceId = json.optString("deviceId", "")
            val peerDeviceName = json.optString("deviceName", "Unknown")
            val deviceType = json.optString("deviceType", "")
            val wsPort = json.optInt("wsPort", 8716)
            val clientVersion = json.optString("clientVersion", "0.0.0")

            if (peerDeviceId.isEmpty()) return

            // Ignore own broadcasts
            if (peerDeviceId == deviceId) return

            // Phone only discovers desktops/laptops (ignore other phones)
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

            // Auto-connect when desktop explicitly requests it
            if (isConnectRequest) {
                Log.i(TAG, "Connect request from $peerDeviceName — triggering auto-connect")
                onConnectRequest?.invoke(desktop)
            }

            // Notify about any discovered desktop (for paired auto-reconnect)
            if (isNew) {
                onDeviceDiscovered?.invoke(desktop)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse discovery packet", e)
        }
    }
}
