package xyz.hanson.fosslink.ui.screens.wizard

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.hanson.fosslink.R
import xyz.hanson.fosslink.ui.viewmodel.PermissionItem
import xyz.hanson.fosslink.ui.viewmodel.SpecialAction

@Composable
fun PermissionsStep(
    permissions: List<PermissionItem>,
    onRequestPermission: (String) -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current

    // Refresh permissions when returning from system settings screens
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRequestPermission("")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.permissions_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.permissions_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        permissions.forEach { perm ->
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { _ ->
                onRequestPermission(perm.permission)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (perm.granted) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = perm.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = perm.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (perm.granted) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.permissions_granted),
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(24.dp)
                        )
                    } else if (perm.isSpecialAccess) {
                        TextButton(onClick = {
                            launchSpecialAction(context, perm.specialAction)
                            onRequestPermission(perm.permission)
                        }) {
                            Text(stringResource(R.string.permissions_grant))
                        }
                    } else {
                        TextButton(onClick = {
                            val allPerms = listOf(perm.permission) + perm.relatedPermissions
                            permissionLauncher.launch(allPerms.toTypedArray())
                        }) {
                            Text(stringResource(R.string.permissions_grant))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Text(stringResource(R.string.permissions_continue))
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onNext) {
            Text(stringResource(R.string.permissions_skip))
        }
    }
}

/** Launch the OS-level intent for a special-access permission. Best-effort —
 *  Samsung intents are firmware-version specific and may need fallback. */
private fun launchSpecialAction(
    context: android.content.Context,
    action: SpecialAction?,
) {
    val attempts: List<Intent> = when (action) {
        SpecialAction.BATTERY_OPTIMIZATION -> listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
        SpecialAction.SAMSUNG_DEVICE_CARE -> listOf(
            // Samsung Device Care → Battery → Background usage limits page.
            // Component name varies across firmware versions; try the most
            // common ones in order, fall back to app-info if all fail.
            Intent().setClassName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity"
            ),
            Intent().setClassName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            ),
            Intent().setClassName(
                "com.samsung.android.sm",
                "com.samsung.android.sm.battery.ui.BatteryActivity"
            ),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
        SpecialAction.FILES_ACCESS, null -> listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
    }

    for (intent in attempts) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) { /* try next */ }
    }
}
