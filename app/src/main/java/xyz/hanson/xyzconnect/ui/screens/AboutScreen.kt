package xyz.hanson.fosslink.ui.screens

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import xyz.hanson.fosslink.BuildConfig
import xyz.hanson.fosslink.R

@Composable
fun AboutScreen() {
    val context = LocalContext.current

    var tapCount by remember { mutableIntStateOf(0) }
    var firstTapMillis by remember { mutableLongStateOf(0L) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // App info card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (firstTapMillis == 0L) firstTapMillis = System.currentTimeMillis()
                    tapCount++
                    if (tapCount >= 3) {
                        if (firstTapMillis >= System.currentTimeMillis() - 500) {
                            // Easter egg — just reset for now
                        }
                        tapCount = 0
                        firstTapMillis = 0L
                    }
                },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Links
        AboutLinkRow(
            iconRes = R.drawable.ic_baseline_gavel_24,
            title = stringResource(R.string.about_license),
            subtitle = stringResource(R.string.about_mit_license),
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, "https://opensource.org/licenses/MIT".toUri()))
            }
        )
        AboutLinkRow(
            iconRes = R.drawable.ic_baseline_code_24,
            title = stringResource(R.string.about_source_code),
            subtitle = "github.com/hansonxyz/fosslink",
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/hansonxyz/fosslink".toUri()))
            }
        )
        AboutLinkRow(
            iconRes = R.drawable.ic_baseline_bug_report_24,
            title = stringResource(R.string.about_report_bug),
            subtitle = stringResource(R.string.about_help_improve),
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/hansonxyz/fosslink/issues".toUri()))
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Credits
        Text(
            text = stringResource(R.string.about_credits),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.about_developed_by),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun AboutLinkRow(@DrawableRes iconRes: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
