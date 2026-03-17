package xyz.hanson.fosslink.ui.viewmodel

import xyz.hanson.fosslink.network.DiscoveredDesktop

data class DesktopInfo(val deviceId: String, val name: String)
data class PendingPairing(val deviceId: String, val desktopName: String, val pairingCode: String)

sealed class AppConnectionState {
    object Disconnected : AppConnectionState()
    data class Discovering(val devices: List<DiscoveredDesktop>) : AppConnectionState()
    data class Connecting(val desktopName: String) : AppConnectionState()
    data class PairingRequested(val pairingCode: String, val desktopName: String) : AppConnectionState()
    data class Connected(
        val desktops: List<DesktopInfo>,
        val pendingPairing: PendingPairing? = null
    ) : AppConnectionState()
}
