package com.elham.priorityringer.presentation.testmode

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
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneCallback
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.elham.priorityringer.R
import com.elham.priorityringer.domain.model.CapabilityReport
import com.elham.priorityringer.domain.model.CapabilityStatus
import com.elham.priorityringer.domain.model.Outcome
import com.elham.priorityringer.domain.model.RingerMode
import com.elham.priorityringer.domain.usecase.ApplyResult
import com.elham.priorityringer.presentation.common.LabeledValueRow
import com.elham.priorityringer.presentation.common.SectionCard
import com.elham.priorityringer.presentation.common.labelRes
import com.elham.priorityringer.presentation.theme.PriorityRingerTheme
import kotlinx.coroutines.delay

/** How often the live ringer/filter readout is re-probed while visible. */
private const val LIVE_POLL_INTERVAL_MS = 2_000L

@Composable
fun TestModeScreen(
    snackbarHostState: SnackbarHostState,
    viewModel: TestModeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Architecture.md § 9 wants a live ringer/interruption-filter readout. The
    // capability seam is a synchronous probe with no change callback, so this
    // polls — but only while RESUMED, so a backgrounded screen costs nothing.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refresh()
                delay(LIVE_POLL_INTERVAL_MS)
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TestModeEffect.Message ->
                    snackbarHostState.showSnackbar(context.getString(effect.textRes))
            }
        }
    }

    TestModeContent(
        state = state,
        onSelectContact = viewModel::selectContact,
        onSimulateCall = viewModel::simulateCall,
        onSimulateEscalation = viewModel::simulateEscalation,
        onRestoreNow = viewModel::restoreNow,
        onRefresh = viewModel::refresh,
    )
}

@Composable
private fun TestModeContent(
    state: TestModeUiState,
    onSelectContact: (Long) -> Unit,
    onSimulateCall: () -> Unit,
    onSimulateEscalation: () -> Unit,
    onRestoreNow: () -> Unit,
    onRefresh: () -> Unit,
) {
    if (state.isLoading) return
    val report = state.report ?: return
    val palette = PriorityRingerTheme.status

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Architecture.md § 9: "Never grant fake permission checkmarks." This
        // card exists so nobody reads a green result here as a simulation
        // artefact — it is the real pipeline, on the real device, with the real
        // permissions currently granted.
        SectionCard(title = stringResource(R.string.test_honesty_title)) {
            Text(
                stringResource(R.string.test_honesty_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.test_honesty_restore),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SectionCard(title = stringResource(R.string.test_live_state_title)) {
            LabeledValueRow(
                label = stringResource(R.string.label_ringer_mode),
                value = stringResource(report.currentRingerMode.labelRes),
            )
            LabeledValueRow(
                label = stringResource(R.string.label_interruption_filter),
                value = stringResource(report.currentInterruptionFilter.labelRes),
                valueColor = if (report.currentInterruptionFilter.maySuppressCalls) {
                    palette.degraded
                } else {
                    null
                },
            )
            LabeledValueRow(
                label = stringResource(R.string.label_ring_volume),
                value = stringResource(
                    R.string.value_volume_of_max,
                    report.currentRingVolume.current,
                    report.currentRingVolume.max,
                    report.currentRingVolume.percent,
                ),
            )
            LabeledValueRow(
                label = stringResource(R.string.label_volume_fixed),
                value = stringResource(
                    if (report.isVolumeFixed) R.string.value_yes else R.string.value_no,
                ),
            )
            LabeledValueRow(
                label = stringResource(R.string.label_api_level),
                value = stringResource(R.string.value_api, report.sdkInt, report.targetSdk),
            )
            OutlinedButton(onClick = onRefresh) {
                Text(stringResource(R.string.test_refresh))
            }
        }

        CapabilitySummary(report)

        SectionCard(title = stringResource(R.string.test_simulate_title)) {
            if (state.enabledContacts.isEmpty()) {
                Text(
                    stringResource(R.string.test_no_contacts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.degraded,
                )
            } else {
                Text(
                    stringResource(R.string.test_select_contact),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(Modifier.selectableGroup()) {
                    state.enabledContacts.forEach { contact ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.selectedContact?.id == contact.id,
                                onClick = { onSelectContact(contact.id) },
                            )
                            Text(
                                text = stringResource(
                                    R.string.test_contact_row,
                                    contact.displayName,
                                    contact.redactedNumber,
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }

            // Shown only when it applies.
            //
            // A simulated call runs the real apply path, so on a phone that is
            // not already audible it can start the alarm-stream backup alert —
            // and there is no real call to end, so nothing arrives to stop it.
            // Only "Restore now", the auto-restore timer, or the player's own
            // guard will. A warning that appeared on every visit would be read
            // as decoration; this one is only here when there is something to
            // warn about.
            if (report.currentRingerMode != RingerMode.NORMAL) {
                Text(
                    stringResource(R.string.test_simulate_may_sound),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.degraded,
                )
            }

            Button(
                onClick = onSimulateCall,
                enabled = !state.isRunning && state.selectedContact != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PhoneCallback, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.test_simulate_call))
            }

            OutlinedButton(
                onClick = onSimulateEscalation,
                enabled = !state.isRunning &&
                    state.selectedContact != null &&
                    state.settings.escalation.enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.NotificationsActive, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.test_simulate_escalation))
            }

            Text(
                stringResource(
                    R.string.test_escalation_explainer,
                    state.settings.escalation.primaryCallCount,
                    state.settings.escalation.primaryWindowMinutes,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResource(R.string.test_restore_title)) {
            Text(
                stringResource(R.string.test_restore_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.restorePending) {
                Text(
                    stringResource(R.string.test_restore_pending),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.degraded,
                )
            }
            Button(onClick = onRestoreNow, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Restore, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.test_restore_now))
            }
        }

        state.lastApply?.let { ResultCard(it) }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CapabilitySummary(report: CapabilityReport) {
    SectionCard(title = stringResource(R.string.test_capabilities_title)) {
        report.statuses.forEach { status ->
            LabeledValueRow(
                label = stringResource(status.capability.labelRes),
                value = stringResource(status.state.labelRes),
                valueColor = with(PriorityRingerTheme.status) {
                    when (status.state) {
                        CapabilityStatus.State.GRANTED -> armed
                        CapabilityStatus.State.DENIED -> inert
                        CapabilityStatus.State.RESTRICTED -> degraded
                        CapabilityStatus.State.NOT_REQUIRED -> null
                    }
                },
            )
            // § 12 again: never a bare mark. Even this compact readout carries
            // the consequence whenever the state is not GRANTED.
            if (status.state != CapabilityStatus.State.GRANTED &&
                status.state != CapabilityStatus.State.NOT_REQUIRED
            ) {
                Text(
                    status.consequence,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        report.notes.forEach { note ->
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultCard(apply: ApplyResult) {
    SectionCard(title = stringResource(R.string.test_result_title)) {
        LabeledValueRow(
            label = stringResource(R.string.label_contact),
            value = apply.contact.displayName,
        )
        LabeledValueRow(
            label = stringResource(R.string.label_escalated),
            value = stringResource(apply.escalation.level.labelRes),
        )
        LabeledValueRow(
            label = stringResource(R.string.label_snapshot_saved),
            value = stringResource(
                if (apply.snapshotSaved) R.string.value_yes else R.string.value_no_already_pending,
            ),
        )
        OutcomeRow(stringResource(R.string.label_do_not_disturb), apply.dnd)
        OutcomeRow(stringResource(R.string.label_ringer_mode), apply.ringer)
        OutcomeRow(stringResource(R.string.label_ring_volume), apply.volume)
        OutcomeRow(stringResource(R.string.label_full_screen_alert), apply.alert)

        if (apply.allAttemptsFailed) {
            Text(
                stringResource(R.string.test_all_failed),
                style = MaterialTheme.typography.titleMedium,
                color = PriorityRingerTheme.status.inert,
            )
        }
    }
}

@Composable
private fun OutcomeRow(label: String, outcome: Outcome<*>?) {
    val palette = PriorityRingerTheme.status
    when {
        outcome == null -> LabeledValueRow(label, stringResource(R.string.value_not_attempted))

        outcome.isSuccess -> LabeledValueRow(
            label = label,
            value = stringResource(R.string.value_applied),
            valueColor = palette.armed,
        )

        else -> LabeledValueRow(
            label = label,
            value = stringResource(outcome.failureOrNull()!!.reason.labelRes),
            valueColor = palette.inert,
        )
    }
}
