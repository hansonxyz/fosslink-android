package xyz.hanson.fosslink.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import xyz.hanson.fosslink.network.*
import xyz.hanson.fosslink.service.ConnectionService
import xyz.hanson.fosslink.service.DesktopConnection

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val TAG = "MainViewModel"

    private val _service = MutableStateFlow<ConnectionService?>(null)
    private var bound = false
    private val prefs = app.getSharedPreferences("fosslink", Context.MODE_PRIVATE)

    // --- UI State ---

    private val _appState = MutableStateFlow<AppConnectionState>(AppConnectionState.Disconnected)
    val appState: StateFlow<AppConnectionState> = _appState

    private val _permissions = MutableStateFlow<List<PermissionItem>>(emptyList())
    val permissions: StateFlow<List<PermissionItem>> = _permissions

    private val _isSetupComplete = MutableStateFlow(false)
    val isSetupComplete: StateFlow<Boolean> = _isSetupComplete

    // Discovery
    private val _discoveredDevices = MutableStateFlow<Map<String, DiscoveredDesktop>>(emptyMap())

    // Delegating flows — expose service state for UI consumption
    val desktopConnections: StateFlow<Map<String, DesktopConnection>> =
        _service.flatMapLatest { svc ->
            svc?.desktopConnections ?: MutableStateFlow(emptyMap())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val pairedDeviceIds: StateFlow<Set<String>> =
        _service.flatMapLatest { svc ->
            svc?.pairedDeviceIds ?: MutableStateFlow(emptySet())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val isPaired: StateFlow<Boolean> =
        pairedDeviceIds.map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** All paired devices with names, for Settings screen (shows offline devices too) */
    val pairedDevices: StateFlow<List<DesktopInfo>> =
        _service.flatMapLatest { svc ->
            if (svc == null) flowOf(emptyList())
            else combine(svc.pairedDeviceIds, svc.pairedDeviceNames) { ids, names ->
                ids.filter { it != "legacy" }.map { id -> DesktopInfo(id, names[id] ?: id) }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as ConnectionService.LocalBinder).getService()
            _service.value = svc
            bound = true
            Log.i(TAG, "Service bound")
            setupService(svc)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _service.value = null
            bound = false
        }
    }

    init {
        _isSetupComplete.value = prefs.getBoolean("setup_complete", false)
        refreshPermissions()

        val context = getApplication<Application>()
        val intent = Intent(context, ConnectionService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Derive AppConnectionState from combined flows
        viewModelScope.launch {
            combine(
                desktopConnections,
                _discoveredDevices,
                pairedDeviceIds,
            ) { connections, devices, _ ->
                deriveAppState(connections, devices)
            }.collect { state ->
                _appState.value = state
            }
        }
    }

    private fun setupService(svc: ConnectionService) {
        // Forward discovery flow
        viewModelScope.launch {
            svc.discoveryListener?.discoveredDevices?.collect { devices ->
                _discoveredDevices.value = devices
            }
        }
    }

    private fun deriveAppState(
        connections: Map<String, DesktopConnection>,
        devices: Map<String, DiscoveredDesktop>,
    ): AppConnectionState {
        val pairedIds = pairedDeviceIds.value
        // Only paired + connected desktops count as "connected"
        val connectedPaired = connections.values.filter {
            it.state == ConnectionState.CONNECTED && it.deviceId in pairedIds
        }
        // Any desktop with a pairing code (paired or not) is a pending pairing
        val pairingDesktop = connections.values.firstOrNull { it.pairingCode != null }
        val pendingPairing = pairingDesktop?.let {
            PendingPairing(it.deviceId, it.deviceName, it.pairingCode!!)
        }

        return when {
            connectedPaired.isNotEmpty() -> AppConnectionState.Connected(
                desktops = connectedPaired.map { DesktopInfo(it.deviceId, it.deviceName) },
                pendingPairing = pendingPairing
            )
            pairingDesktop != null -> AppConnectionState.PairingRequested(
                pairingCode = pairingDesktop.pairingCode!!,
                desktopName = pairingDesktop.deviceName
            )
            connections.values.any { it.state == ConnectionState.CONNECTING } ->
                AppConnectionState.Connecting(
                    desktopName = connections.values.first { it.state == ConnectionState.CONNECTING }.deviceName
                )
            devices.isNotEmpty() -> AppConnectionState.Discovering(devices.values.toList())
            else -> AppConnectionState.Disconnected
        }
    }

    // --- Public actions ---

    fun connectToDesktop(address: String, port: Int = 8716) {
        val svc = _service.value ?: return
        val tempId = "pending_${address}_$port"
        val client = svc.createClient(tempId)
        svc.setupMessageHandlerForClient(tempId, client)
        svc.observeClient(tempId, client)
        client.connect(address, port)
    }

    fun confirmPairingCode(code: String) {
        _service.value?.confirmPairing(code)
    }

    fun disconnectDesktop(deviceId: String) {
        _service.value?.disconnectDesktop(deviceId)
    }

    fun forgetDesktop(deviceId: String) {
        _service.value?.forgetDesktop(deviceId)
    }

    fun forgetAllDesktops() {
        _service.value?.forgetAllDesktops()
        _isSetupComplete.value = false
    }

    fun refreshPermissions() {
        val items = mutableListOf(
            PermissionItem(
                name = "SMS",
                permission = Manifest.permission.READ_SMS,
                granted = isPermGranted(Manifest.permission.READ_SMS) &&
                          isPermGranted(Manifest.permission.SEND_SMS),
                description = "Read and send text messages",
                relatedPermissions = listOf(Manifest.permission.SEND_SMS)
            ),
            PermissionItem(
                name = "Contacts",
                permission = Manifest.permission.READ_CONTACTS,
                granted = isPermGranted(Manifest.permission.READ_CONTACTS) &&
                          isPermGranted(Manifest.permission.WRITE_CONTACTS),
                description = "Show contact names in conversations",
                relatedPermissions = listOf(Manifest.permission.WRITE_CONTACTS)
            ),
            PermissionItem(
                name = "Phone",
                permission = Manifest.permission.READ_PHONE_STATE,
                granted = isPermGranted(Manifest.permission.READ_PHONE_STATE),
                description = "Detect incoming calls"
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            items.add(
                PermissionItem(
                    name = "Notifications",
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    granted = isPermGranted(Manifest.permission.POST_NOTIFICATIONS),
                    description = "Show connection and pairing notifications"
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            items.add(
                PermissionItem(
                    name = "Files Access",
                    permission = Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                    granted = Environment.isExternalStorageManager(),
                    description = "Browse phone gallery and files from desktop",
                    isSpecialAccess = true,
                    specialAction = SpecialAction.FILES_ACCESS
                )
            )
        }
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        items.add(
            PermissionItem(
                name = "Battery",
                permission = "battery_optimization",
                granted = pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName),
                description = "Prevent Android from throttling connections",
                isSpecialAccess = true,
                specialAction = SpecialAction.BATTERY_OPTIMIZATION
            )
        )
        items.add(
            PermissionItem(
                name = "Autostart",
                permission = "autostart",
                granted = true,
                description = "Start automatically after phone reboots"
            )
        )
        _permissions.value = items
    }

    fun completeSetup() {
        prefs.edit().putBoolean("setup_complete", true).apply()
        _isSetupComplete.value = true
    }

    // --- Private helpers ---

    private fun isPermGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            getApplication(), permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCleared() {
        super.onCleared()
        if (bound) {
            getApplication<Application>().unbindService(serviceConnection)
            bound = false
        }
    }
}
