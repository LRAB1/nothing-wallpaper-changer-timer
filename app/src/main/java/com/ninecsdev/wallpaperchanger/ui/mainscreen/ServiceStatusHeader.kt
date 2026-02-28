package com.ninecsdev.wallpaperchanger.ui.mainscreen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.ui.theme.NothingGray
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Header for showing the app status.
 */
@Composable
fun ServiceStatusHeader(
    state: ServiceState,
    modifier: Modifier = Modifier
) {
    val (color, label) = getVisualsForState(state)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "APP STATUS",
            style = MaterialTheme.typography.labelSmall,
            color = NothingWhite.copy(alpha = 0.4f),
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLed(
                color = color,
                isPulsing = state is ServiceState.Loading
            )

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = NothingWhite,
                letterSpacing = 1.sp
            )
        }
    }
}

private fun getVisualsForState(state: ServiceState): Pair<Color, String> {
    return when (state) {
        is ServiceState.Running -> Color.Green to "ACTIVE"
        is ServiceState.Stopped -> NothingRed to "INACTIVE"
        is ServiceState.Paused -> Color(0xFFFFA500) to "PAUSED (BATTERY)"
        is ServiceState.DisabledPowerSave -> Color(0xFFFFA500) to "POWER SAVE ON"
        is ServiceState.DisabledNoCollection -> NothingGray to "SETUP NEEDED"
        is ServiceState.Loading -> NothingWhite to "INITIALIZING"
    }
}

@Composable
private fun StatusLed(
    color: Color,
    isPulsing: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "PulseTransition")

    val alpha by if (isPulsing) {
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "PulseAlpha"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    Box(
        modifier = modifier
            .size(14.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

@Preview(name = "Running", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewHeaderRunning() {
    MaterialTheme {
        Box(Modifier.padding(24.dp)) {
            ServiceStatusHeader(state = ServiceState.Running)
        }
    }
}

@Preview(name = "Power Save", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewHeaderLoading() {
    MaterialTheme {
        Box(Modifier.padding(24.dp)) {
            ServiceStatusHeader(
                state = ServiceState.Loading,
            )
        }
    }
}

@Preview(name = "Stopped", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewHeaderStopped() {
    MaterialTheme {
        Box(Modifier.padding(24.dp)) {
            ServiceStatusHeader(state = ServiceState.Stopped)
        }
    }
}

@Preview(name = "No Collection", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewHeaderNoCollection() {
    MaterialTheme {
        Box(Modifier.padding(24.dp)) {
            ServiceStatusHeader(state = ServiceState.DisabledNoCollection)
        }
    }
}

@Preview(name = "Power Save", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewHeaderPowerSave() {
    MaterialTheme {
        Box(Modifier.padding(24.dp)) {
            ServiceStatusHeader(
                state = ServiceState.DisabledPowerSave,
            )
        }
    }
}