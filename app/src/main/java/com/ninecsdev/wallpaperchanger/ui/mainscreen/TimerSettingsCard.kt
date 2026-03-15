package com.ninecsdev.wallpaperchanger.ui.mainscreen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninecsdev.wallpaperchanger.model.RotationTrigger
import com.ninecsdev.wallpaperchanger.model.TimerInterval
import com.ninecsdev.wallpaperchanger.ui.theme.CardCornerRadius
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

/**
 * Card for configuring the wallpaper rotation trigger (per-lock vs. timed).
 * When timed mode is enabled, a dropdown lets the user pick the interval.
 * Also contains the "Follow focus mode" opt-in toggle.
 */
@Composable
fun TimerSettingsCard(
    rotationTrigger: RotationTrigger,
    timerInterval: TimerInterval,
    followFocusMode: Boolean,
    onToggleTimedMode: (Boolean) -> Unit,
    onIntervalSelected: (TimerInterval) -> Unit,
    onToggleFollowFocusMode: (Boolean) -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        colors = CardDefaults.outlinedCardColors(
            containerColor = NothingBlack,
            contentColor = NothingWhite
        ),
        border = BorderStroke(2.dp, NothingWhite.copy(alpha = 0.5f))
    ) {
        Column {
            TimerCardHeader(
                isTimedEnabled = rotationTrigger == RotationTrigger.TIMED,
                onToggle = onToggleTimedMode
            )

            if (rotationTrigger == RotationTrigger.TIMED) {
                HorizontalDivider(
                    color = NothingWhite.copy(alpha = 0.5f),
                    thickness = 2.dp
                )
                TimerCardIntervalSelector(
                    selected = timerInterval,
                    onSelected = onIntervalSelected
                )
            }

            HorizontalDivider(
                color = NothingWhite.copy(alpha = 0.5f),
                thickness = 2.dp
            )
            FollowFocusModeRow(
                enabled = followFocusMode,
                onToggle = onToggleFollowFocusMode
            )
        }
    }
}

@Composable
private fun TimerCardHeader(
    isTimedEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "ROTATION TIMER",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = if (isTimedEnabled) "TIMED — CHANGES ON SCHEDULE" else "PER LOCK — CHANGES ON EACH LOCK",
                style = MaterialTheme.typography.labelSmall,
                color = NothingWhite.copy(alpha = 0.4f),
                letterSpacing = 0.5.sp
            )
        }

        Switch(
            checked = isTimedEnabled,
            onCheckedChange = onToggle,
            modifier = Modifier.scale(0.8f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = NothingBlack,
                checkedTrackColor = NothingWhite,
                uncheckedThumbColor = NothingWhite,
                uncheckedTrackColor = NothingBlack,
                uncheckedBorderColor = NothingWhite.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun TimerCardIntervalSelector(
    selected: TimerInterval,
    onSelected: (TimerInterval) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "INTERVAL",
                style = MaterialTheme.typography.labelSmall,
                color = NothingWhite.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = selected.displayName.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Box {
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = "CHANGE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = NothingWhite
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = NothingBlack
            ) {
                TimerInterval.entries.forEach { interval ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = interval.displayName.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (interval == selected) FontWeight.Black else FontWeight.Normal,
                                letterSpacing = 0.5.sp
                            )
                        },
                        onClick = {
                            onSelected(interval)
                            expanded = false
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = NothingWhite,
                            disabledTextColor = NothingWhite.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun FollowFocusModeRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "FOLLOW FOCUS MODE",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = if (enabled) "DAILY ROTATION WHILE IN DND / FOCUS" else "CHANGES NORMALLY IN DND / FOCUS",
                style = MaterialTheme.typography.labelSmall,
                color = NothingWhite.copy(alpha = 0.4f),
                letterSpacing = 0.5.sp
            )
        }

        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.scale(0.8f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = NothingBlack,
                checkedTrackColor = NothingWhite,
                uncheckedThumbColor = NothingWhite,
                uncheckedTrackColor = NothingBlack,
                uncheckedBorderColor = NothingWhite.copy(alpha = 0.5f)
            )
        )
    }
}

@Preview(name = "Per Lock (default)", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewTimerCardPerLock() {
    MaterialTheme {
        Box(Modifier.padding(16.dp)) {
            TimerSettingsCard(
                rotationTrigger = RotationTrigger.ON_LOCK,
                timerInterval = TimerInterval.DAILY,
                followFocusMode = false,
                onToggleTimedMode = {},
                onIntervalSelected = {},
                onToggleFollowFocusMode = {}
            )
        }
    }
}

@Preview(name = "Timed mode", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewTimerCardTimed() {
    MaterialTheme {
        Box(Modifier.padding(16.dp)) {
            TimerSettingsCard(
                rotationTrigger = RotationTrigger.TIMED,
                timerInterval = TimerInterval.SIX_HOURS,
                followFocusMode = false,
                onToggleTimedMode = {},
                onIntervalSelected = {},
                onToggleFollowFocusMode = {}
            )
        }
    }
}

@Preview(name = "Follow Focus Mode on", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewTimerCardFollowFocus() {
    MaterialTheme {
        Box(Modifier.padding(16.dp)) {
            TimerSettingsCard(
                rotationTrigger = RotationTrigger.ON_LOCK,
                timerInterval = TimerInterval.DAILY,
                followFocusMode = true,
                onToggleTimedMode = {},
                onIntervalSelected = {},
                onToggleFollowFocusMode = {}
            )
        }
    }
}
