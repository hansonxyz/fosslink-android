/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink.findmyphone

import android.app.KeyguardManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.hanson.fosslink.R
import xyz.hanson.fosslink.ui.theme.FossLinkTheme

class FindMyPhoneActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show on lock screen and turn screen on
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val keyguardManager = getSystemService(KeyguardManager::class.java)
        keyguardManager?.requestDismissKeyguard(this, null)

        setContent {
            FossLinkTheme {
                FindMyPhoneScreen(onDismiss = {
                    FindMyPhoneHandler.instance?.stopPlaying()
                    finish()
                })
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // If the user navigates away, stop the alarm
        FindMyPhoneHandler.instance?.stopPlaying()
    }
}

@Composable
fun FindMyPhoneScreen(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PulsingRings()
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                stringResource(R.string.findphone_ringing),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.findphone_tap_to_stop),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PulsingRings() {
    val infiniteTransition = rememberInfiniteTransition(label = "rings")
    val ringColor = MaterialTheme.colorScheme.primary

    val ring1 = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Restart),
        label = "ring1"
    )
    val ring2 = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, delayMillis = 666), RepeatMode.Restart),
        label = "ring2"
    )
    val ring3 = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, delayMillis = 1333), RepeatMode.Restart),
        label = "ring3"
    )

    Canvas(modifier = Modifier.size(200.dp)) {
        val center = this.center
        val maxRadius = size.minDimension / 2f

        for (progress in listOf(ring1.value, ring2.value, ring3.value)) {
            val radius = maxRadius * progress
            val alpha = (1f - progress) * 0.4f
            drawCircle(
                color = ringColor.copy(alpha = alpha),
                radius = radius,
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )
        }

        // Center filled circle
        drawCircle(
            color = ringColor,
            radius = 28.dp.toPx(),
            center = center
        )

        // Phone icon drawn as simple path (handset shape)
        drawPhoneIcon(center, ringColor)
    }
}

private fun DrawScope.drawPhoneIcon(center: Offset, color: Color) {
    val iconColor = Color.White
    val iconSize = 20.dp.toPx()
    val halfSize = iconSize / 2f

    // Simple phone handset shape using a path
    val path = Path().apply {
        // Left earpiece
        moveTo(center.x - halfSize * 0.7f, center.y - halfSize * 0.8f)
        cubicTo(
            center.x - halfSize * 0.9f, center.y - halfSize * 0.4f,
            center.x - halfSize * 0.9f, center.y + halfSize * 0.4f,
            center.x - halfSize * 0.5f, center.y + halfSize * 0.5f
        )
        // Bottom curve
        lineTo(center.x - halfSize * 0.2f, center.y + halfSize * 0.8f)
        cubicTo(
            center.x - halfSize * 0.1f, center.y + halfSize * 0.9f,
            center.x + halfSize * 0.1f, center.y + halfSize * 0.9f,
            center.x + halfSize * 0.2f, center.y + halfSize * 0.8f
        )
        // Right earpiece
        lineTo(center.x + halfSize * 0.5f, center.y + halfSize * 0.5f)
        cubicTo(
            center.x + halfSize * 0.9f, center.y + halfSize * 0.4f,
            center.x + halfSize * 0.9f, center.y - halfSize * 0.4f,
            center.x + halfSize * 0.7f, center.y - halfSize * 0.8f
        )
        // Top curve
        lineTo(center.x + halfSize * 0.2f, center.y - halfSize * 0.8f)
        cubicTo(
            center.x + halfSize * 0.1f, center.y - halfSize * 0.9f,
            center.x - halfSize * 0.1f, center.y - halfSize * 0.9f,
            center.x - halfSize * 0.2f, center.y - halfSize * 0.8f
        )
        close()
    }

    drawPath(path, color = iconColor)
}
