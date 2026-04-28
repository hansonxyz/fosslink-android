/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.hanson.fosslink.R

/** Mode controls the dismissal semantics. */
enum class SamsungScreenMode {
    /** Inside the new-install wizard. Continue is gated on tapping "Open
     *  Device Care" first. Continue advances to the next wizard step and
     *  records the user's acknowledgment. */
    Wizard,

    /** Auto-launched on app start when an existing-setup Samsung user has
     *  not yet acknowledged. Same gating as Wizard. Continue clears the
     *  forced-redirect by recording acknowledgment. */
    Forced,

    /** Re-entered from the settings screen by user choice. Close is always
     *  enabled. Acknowledgment is NOT changed (settings visits are
     *  reference-only). */
    Settings,
}

@Composable
fun SamsungDeepSleepScreen(
    mode: SamsungScreenMode,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    var deviceCareOpened by rememberSaveable { mutableStateOf(false) }
    val continueEnabled = mode == SamsungScreenMode.Settings || deviceCareOpened

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.samsung_screen_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = stringResource(R.string.samsung_screen_intro),
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = stringResource(R.string.samsung_screen_what_to_do),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.samsung_screen_step1), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.samsung_screen_step2), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.samsung_screen_step3), style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                openSamsungDeviceCare(context)
                deviceCareOpened = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.samsung_screen_open_btn))
        }

        if (mode != SamsungScreenMode.Settings && !deviceCareOpened) {
            Text(
                text = stringResource(R.string.samsung_screen_continue_disabled_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onContinue,
            enabled = continueEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
        ) {
            Text(
                text = if (mode == SamsungScreenMode.Settings) {
                    stringResource(R.string.samsung_screen_close)
                } else {
                    stringResource(R.string.samsung_screen_continue)
                }
            )
        }
    }
}

/** Best-effort intent to land on the Samsung Device Care battery page. */
private fun openSamsungDeviceCare(context: Context) {
    val attempts = listOf(
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
    for (intent in attempts) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) { /* try next */ }
    }
}
