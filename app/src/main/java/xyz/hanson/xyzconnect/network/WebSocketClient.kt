package xyz.hanson.fosslink.network

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import java.security.cert.X509Certificate

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

class WebSocketClient(
    private val deviceId: String,
    private val deviceName: String
) {
    private val TAG = "WebSocketClient"

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _peerDeviceId = MutableStateFlow<String?>(null)
    val peerDeviceId: StateFlow<String?> = _peerDeviceId

    private val _peerDeviceName = MutableStateFlow<String?>(null)
    val peerDeviceName: StateFlow<String?> = _peerDeviceName

    private var webSocket: WebSocket? = null
    private var messageListener: ((ProtocolMessage) -> Unit)? = null

    private val client: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, java.security.SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // No read timeout for WebSocket
            .build()
    }

    fun onMessage(listener: (ProtocolMessage) -> Unit) {
        messageListener = listener
    }

    fun connect(address: String, port: Int = 8716) {
        if (_connectionState.value == ConnectionState.CONNECTING) return
        _connectionState.value = ConnectionState.CONNECTING

        val url = "wss://$address:$port"
        Log.i(TAG, "Connecting to $url (deviceId=$deviceId, deviceName=$deviceName)")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket onOpen fired, response=${response.code}")
                try {
                    val identity = ProtocolMessage.createIdentity(deviceId, deviceName)
                    val serialized = identity.serialize()
                    Log.i(TAG, "Sending identity: $serialized")
                    val sent = ws.send(serialized)
                    Log.i(TAG, "Identity send result: $sent")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send identity", e)
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.i(TAG, "onMessage received: ${text.take(200)}")
                try {
                    val msg = ProtocolMessage.parse(text)

                    if (msg.type == ProtocolMessage.IDENTITY) {
                        _peerDeviceId.value = msg.body.optString("deviceId")
                        _peerDeviceName.value = msg.body.optString("deviceName")
                        _connectionState.value = ConnectionState.CONNECTED
                        Log.i(TAG, "Identity received: ${_peerDeviceName.value} (${_peerDeviceId.value})")
                    }

                    messageListener?.invoke(msg)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message", e)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                _peerDeviceId.value = null
                _peerDeviceName.value = null
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}, response=${response?.code}", t)
                _connectionState.value = ConnectionState.DISCONNECTED
                _peerDeviceId.value = null
                _peerDeviceName.value = null
            }
        })
    }

    fun send(msg: ProtocolMessage) {
        webSocket?.send(msg.serialize())
    }

    fun disconnect() {
        webSocket?.close(1000, "Disconnect requested")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}
