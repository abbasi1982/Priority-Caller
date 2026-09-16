package com.elham.priorityringer.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elham.priorityringer.R
import com.elham.priorityringer.domain.model.AppSettings
import com.elham.priorityringer.domain.model.DndBypassStrategy
import com.elham.priorityringer.presentation.common.SectionCard
import com.elham.priorityringer.presentation.common.descriptionRes
import com.elham.priorityringer.presentation.common.labelRes
import kotlin.math.roundToInt

/**
 * Every range here mirrors [AppSettings.validated] exactly.
 *
 * If the UI let a value outside those bounds be chosen, the repository would
 * clamp it on write and the number would appear to change by itself. The
 * volume floor in particular is deliberate: `MIN_VOLUME_PERCENT` exists so the
 * app cannot be configured into looking armed while ringing silently.
 */
private object Ranges {
    val VOLUME = AppSettings.MIN_VOLUME_PERCENT..100
    const val VOLUME_STEP = 5

    val RESTORE_SECONDS =
        AppSettings.MIN_RESTORE_TIMEOUT_SECONDS..AppSettings.MAX_RESTORE_TIMEOUT_SECONDS
    const val RESTORE_STEP = 30

    val CALL_COUNT = 2..10
    val PRIMARY_WINDOW_MINUTES = 1..60
    val SECONDARY_WINDOW_MINUTES = 1..120
}

@Composable
fun SettingsScreen(
    onOpenTestMode: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsContent(
        state = state,
        onEdit = viewModel::edit,
        onEditAndCommit = viewModel::editAndCommit,
        onCommit = viewModel::commit,
        onOpenTestMode = onOpenTestMode,
    )
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onEdit: ((AppSettings) -> AppSettings) -> Unit,
    onEditAndCommit: ((AppSettings) -> AppSettings) -> Unit,
    onCommit: () -> Unit,
    onOpenTestMode: () -> Unit,
) {
    if (state.isLoading) return
    val settings = state.settings

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(title = stringResource(R.string.settings_volume_title)) {
            Text(
                stringResource(R.string.settings_volume_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.value_percent, settings.ringtoneVolumePercent),
                style = MaterialTheme.typography.headlineSmall,
            )
            Slider(
                value = settings.ringtoneVolumePercent.toFloat(),
                onValueChange = { raw ->
                    val snapped = (raw / Ranges.VOLUME_STEP).roundToInt() * Ranges.VOLUME_STEP
                    onEdit { it.copy(ringtoneVolumePercent = snapped.coerceIn(Ranges.VOLUME)) }
                },
                onValueChangeFinished = onCommit,
                valueRange = Ranges.VOLUME.first.toFloat()..Ranges.VOLUME.last.toFloat(),
                // `steps` counts the points *between* the endpoints.
                steps = (Ranges.VOLUME.last - Ranges.VOLUME.first) / Ranges.VOLUME_STEP - 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.settings_volume_floor, AppSettings.MIN_VOLUME_PERCENT),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResource(R.string.settings_escalation_title)) {
            Text(
                stringResource(R.string.settings_escalation_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            SwitchRow(
                label = stringResource(R.string.settings_escalation_enabled),
                checked = settings.escalation.enabled,
                onCheckedChange = { enabled ->
                    onEditAndCommit { it.copy(escalation = it.escalation.copy(enabled = enabled)) }
                },
            )

            if (settings.escalation.enabled) {
                Text(
                    stringResource(R.string.settings_escalation_window_a),
                    style = MaterialTheme.typography.titleMedium,
                )
                StepperRow(
                    label = stringResource(R.string.settings_escalation_calls),
                    value = settings.escalation.primaryCallCount,
                    range = Ranges.CALL_COUNT,
                    onValueChange = { v ->
                        onEditAndCommit {
                            it.copy(escalation = it.escalation.copy(primaryCallCount = v))
                        }
                    },
                )
                StepperRow(
                    label = stringResource(R.string.settings_escalation_minutes),
                    value = settings.escalation.primaryWindowMinutes,
                    range = Ranges.PRIMARY_WINDOW_MINUTES,
                    onValueChange = { v ->
                        onEditAndCommit {
                            it.copy(escalation = it.escalation.copy(primaryWindowMinutes = v))
                        }
                    },
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_escalation_window_b),
                    style = MaterialTheme.typography.titleMedium,
                )
                StepperRow(
                    label = stringResource(R.string.settings_escalation_calls),
                    value = settings.escalation.secondaryCallCount,
                    range = Ranges.CALL_COUNT,
                    onValueChange = { v ->
                        onEditAndCommit {
                            it.copy(escalation = it.escalation.copy(secondaryCallCount = v))
                        }
                    },
                )
                StepperRow(
                    label = stringResource(R.string.settings_escalation_minutes),
                    value = settings.escalation.secondaryWindowMinutes,
                    range = Ranges.SECONDARY_WINDOW_MINUTES,
                    onValueChange = { v ->
                        onEditAndCommit {
                            it.copy(escalation = it.escalation.copy(secondaryWindowMinutes = v))
                        }
                    },
                )

                Text(
                    stringResource(
                        R.string.settings_escalation_summary,
                        settings.escalation.primaryCallCount,
                        settings.escalation.primaryWindowMinutes,
                        settings.escalation.secondaryCallCount,
                        settings.escalation.secondaryWindowMinutes,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(title = stringResource(R.string.settings_restore_title)) {
            Text(
                stringResource(R.string.settings_restore_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.value_seconds, settings.autoRestoreTimeoutSeconds),
                style = MaterialTheme.typography.headlineSmall,
            )
            Slider(
                value = settings.autoRestoreTimeoutSeconds.toFloat(),
                onValueChange = { raw ->
                    val snapped = (raw / Ranges.RESTORE_STEP).roundToInt() * Ranges.RESTORE_STEP
                    onEdit {
                        it.copy(autoRestoreTimeoutSeconds = snapped.coerceIn(Ranges.RESTORE_SECONDS))
                    }
                },
                onValueChangeFinished = onCommit,
                valueRange = Ranges.RESTORE_SECONDS.first.toFloat()..
                    Ranges.RESTORE_SECONDS.last.toFloat(),
                steps = (Ranges.RESTORE_SECONDS.last - Ranges.RESTORE_SECONDS.first) /
                    Ranges.RESTORE_STEP - 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard(title = stringResource(R.string.settings_dnd_title)) {
            Text(
                stringResource(R.string.settings_dnd_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(Modifier.selectableGroup()) {
                DndBypassStrategy.entries.forEach { strategy ->
                    StrategyRow(
                        strategy = strategy,
                        selected = settings.dndBypassStrategy == strategy,
                        onSelect = { onEditAndCommit { it.copy(dndBypassStrategy = strategy) } },
                    )
                }
            }
        }

        // Architecture.md § 12 and § 17.3: the UI must never claim calls always
        // ring through. This card is the standing statement of what the app
        // cannot promise, kept next to the controls that sound like it could.
        SectionCard(title = stringResource(R.string.settings_limits_title)) {
            Text(
                stringResource(R.string.settings_limits_silent),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_limits_dnd),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_limits_oem),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_limits_timing),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SectionCard(title = stringResource(R.string.settings_logging_title)) {
            SwitchRow(
                label = stringResource(R.string.settings_logging_enabled),
                checked = settings.loggingEnabled,
                onCheckedChange = { enabled ->
                    onEditAndCommit { it.copy(loggingEnabled = enabled) }
                },
            )
            Text(
                stringResource(R.string.settings_logging_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(onClick = onOpenTestMode, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Science, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_open_test_mode))
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Stepper rather than a slider for the escalation numbers: the ranges are small
 * and the exact value matters ("2 calls" vs "3 calls" changes behaviour), which
 * is precisely where a drag control is the wrong instrument.
 */
@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(
            onClick = { onValueChange((value - 1).coerceIn(range)) },
            enabled = value > range.first,
        ) {
            Icon(
                Icons.Filled.Remove,
                contentDescription = stringResource(R.string.action_decrease, label),
            )
        }
        Text(value.toString(), style = MaterialTheme.typography.titleLarge)
        IconButton(
            onClick = { onValueChange((value + 1).coerceIn(range)) },
            enabled = value < range.last,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.action_increase, label),
            )
        }
    }
}

@Composable
private fun StrategyRow(
    strategy: DndBypassStrategy,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 8.dp)) {
            Text(stringResource(strategy.labelRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(strategy.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
