package xyz.hanson.fosslink.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.hanson.fosslink.R
import xyz.hanson.fosslink.ui.viewmodel.AppConnectionState
import xyz.hanson.fosslink.ui.viewmodel.DesktopInfo

@Composable
fun SettingsScreen(
    appState: AppConnectionState,
    pairedDevices: List<DesktopInfo>,
    onDisconnect: (String) -> Unit,
    onForgetDesktop: (String) -> Unit,
    onForgetAll: () -> Unit
) {
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }
    var forgetDeviceId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Paired desktops section (shows all paired, online or offline)
        SectionHeader("Paired Desktops")
        val connectedIds = if (appState is AppConnectionState.Connected) {
            appState.desktops.map { it.deviceId }.toSet()
        } else emptySet()

        if (pairedDevices.isEmpty()) {
            Text(
                text = "No paired desktops",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            pairedDevices.forEach { desktop ->
                val isOnline = desktop.deviceId in connectedIds
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_device_desktop_32dp),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = if (isOnline) Color(0xFF4CAF50)
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = desktop.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (isOnline) "Connected" else "Offline",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isOnline) Color(0xFF4CAF50)
                                           else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row {
                            if (isOnline) {
                                OutlinedButton(
                                    onClick = { onDisconnect(desktop.deviceId) },
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text("Disconnect", style = MaterialTheme.typography.labelMedium)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            OutlinedButton(
                                onClick = { forgetDeviceId = desktop.deviceId },
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("Forget", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { showResetDialog = true }) {
            Text("Reset Setup")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Root integration
        SectionHeader("Advanced")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val prefs = context.getSharedPreferences("fosslink", Context.MODE_PRIVATE)
            var rootEnabled by remember { mutableStateOf(prefs.getBoolean("root_integration", false)) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Root Integration",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Enable privileged operations (open URLs directly, sync read status)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = rootEnabled,
                    onCheckedChange = { enabled ->
                        rootEnabled = enabled
                        prefs.edit().putBoolean("root_integration", enabled).apply()
                    }
                )
            }
        }
    }

    // Forget single desktop confirmation
    forgetDeviceId?.let { deviceId ->
        AlertDialog(
            onDismissRequest = { forgetDeviceId = null },
            title = { Text("Forget Desktop") },
            text = { Text("This will disconnect and unpair this desktop. You'll need to pair again.") },
            confirmButton = {
                TextButton(onClick = {
                    forgetDeviceId = null
                    onForgetDesktop(deviceId)
                }) {
                    Text("Forget", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { forgetDeviceId = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Reset all confirmation
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Setup") },
            text = { Text("This will disconnect and forget all desktops. You'll need to pair again.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    onForgetAll()
                }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun BatteryOptimizationSection(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isIgnoring = pm.isIgnoringBatteryOptimizations(context.packageName)

    if (!isIgnoring) {
        SectionHeader("Battery")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)
                },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Battery optimization is enabled",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "This may prevent FossLink from staying connected. Tap to disable battery optimization for this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
