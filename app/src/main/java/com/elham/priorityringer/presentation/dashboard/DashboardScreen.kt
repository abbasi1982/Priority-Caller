package com.elham.priorityringer.presentation.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elham.priorityringer.R
import com.elham.priorityringer.domain.model.AuditSeverity
import com.elham.priorityringer.domain.model.CapabilityReport
import com.elham.priorityringer.domain.usecase.ApplyResult
import com.elham.priorityringer.presentation.common.LabeledValueRow
import com.elham.priorityringer.presentation.common.PersistentBanner
import com.elham.priorityringer.presentation.common.SectionCard
import com.elham.priorityringer.presentation.common.TimeFormatting
import com.elham.priorityringer.presentation.common.icon
import com.elham.priorityringer.presentation.common.labelRes
import com.elham.priorityringer.presentation.common.tint
import com.elham.priorityringer.presentation.theme.PriorityRingerTheme

@Composable
fun DashboardScreen(
    onOpenPermissions: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenAudit: () -> Unit,
    onOpenTestMode: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Architecture.md § 12 — permissions change outside the app, so the report
    // is rebuilt every time this screen becomes visible rather than once.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    DashboardContent(
        state = state,
        onDismissBanner = viewModel::dismissBanner,
        onOpenPermissions = onOpenPermissions,
        onOpenContacts = onOpenContacts,
        onOpenAudit = onOpenAudit,
        onOpenTestMode = onOpenTestMode,
    )
}

@Composable
private fun DashboardContent(
    state: DashboardUiState,
    onDismissBanner: (Long) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenAudit: () -> Unit,
    onOpenTestMode: () -> Unit,
) {
    if (state.isLoading) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val report = state.report ?: return

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(state.banners, key = { it.entryId }) { banner ->
            PersistentBanner(
                title = stringResource(
                    when (banner.kind) {
                        DashboardBanner.Kind.RESTORE_FAILED -> R.string.banner_restore_failed_title
                        DashboardBanner.Kind.DND_INEFFECTIVE -> R.string.banner_dnd_ineffective_title
                        DashboardBanner.Kind.SILENT_NOT_OVERRIDDEN ->
                            R.string.banner_silent_not_overridden_title
                    },
                ),
                message = banner.message,
                severity = when (banner.kind) {
                    DashboardBanner.Kind.RESTORE_FAILED -> AuditSeverity.ERROR
                    DashboardBanner.Kind.DND_INEFFECTIVE -> AuditSeverity.WARNING
                    DashboardBanner.Kind.SILENT_NOT_OVERRIDDEN -> AuditSeverity.WARNING
                },
                onDismiss = { onDismissBanner(banner.entryId) },
                actionLabel = stringResource(R.string.action_view_audit_log),
                onAction = onOpenAudit,
            )
        }

        item { ReadinessCard(report.readiness, onOpenPermissions) }

        item {
            SectionCard(title = stringResource(R.string.dashboard_contacts_title)) {
                LabeledValueRow(
                    label = stringResource(R.string.dashboard_contacts_enabled),
                    value = stringResource(
                        R.string.dashboard_contacts_count,
                        state.enabledContacts,
                        state.totalContacts,
                    ),
                )
                if (state.totalContacts == 0) {
                    Text(
                        stringResource(R.string.dashboard_contacts_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (state.enabledContacts == 0) {
                    Text(
                        stringResource(R.string.dashboard_contacts_all_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PriorityRingerTheme.status.degraded,
                    )
                }
                TextButton(onClick = onOpenContacts) {
                    Text(stringResource(R.string.dashboard_manage_contacts))
                }
            }
        }

        item { DeviceStateCard(report) }

        item {
            SectionCard(title = stringResource(R.string.dashboard_last_event_title)) {
                val event = state.lastEvent
                if (event == null) {
                    Text(
                        stringResource(R.string.dashboard_last_event_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            event.severity.icon(),
                            contentDescription = null,
                            tint = event.severity.tint(),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                stringResource(event.type.labelRes),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                event.message,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                TimeFormatting.timestamp(event.timestampEpochMs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                TextButton(onClick = onOpenAudit) {
                    Text(stringResource(R.string.dashboard_view_full_log))
                }
            }
        }

        state.lastApply?.let { apply ->
            item { LastAttemptCard(apply) }
        }

        item {
            OutlinedButton(onClick = onOpenTestMode, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Science, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.dashboard_open_test_mode))
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

/**
 * The single most important piece of copy in the app.
 *
 * Architecture.md Addendum A.5 is explicit that INERT must not be presented as
 * a softer version of DEGRADED: without a caller number **nothing at all**
 * happens, and describing that as partial functionality would let a family set
 * up the app, see something reassuring, and be unreachable in an emergency.
 * The three states therefore differ in colour, icon and — more importantly — in
 * what they claim.
 */
@Composable
private fun ReadinessCard(
    readiness: CapabilityReport.Readiness,
    onOpenPermissions: () -> Unit,
) {
    val palette = PriorityRingerTheme.status

    data class Look(
        val container: Color,
        val onContainer: Color,
        val icon: ImageVector,
        val titleRes: Int,
        val bodyRes: Int,
        val actionRes: Int?,
    )

    val look = when (readiness) {
        CapabilityReport.Readiness.ARMED -> Look(
            container = palette.armedContainer,
            onContainer = palette.onArmedContainer,
            icon = Icons.Filled.CheckCircle,
            titleRes = R.string.readiness_armed_title,
            bodyRes = R.string.readiness_armed_body,
            actionRes = null,
        )

        CapabilityReport.Readiness.DEGRADED -> Look(
            container = palette.degradedContainer,
            onContainer = palette.onDegradedContainer,
            icon = Icons.Filled.Warning,
            titleRes = R.string.readiness_degraded_title,
            bodyRes = R.string.readiness_degraded_body,
            actionRes = R.string.readiness_degraded_action,
        )

        CapabilityReport.Readiness.INERT -> Look(
            container = palette.inertContainer,
            onContainer = palette.onInertContainer,
            icon = Icons.Filled.Block,
            titleRes = R.string.readiness_inert_title,
            bodyRes = R.string.readiness_inert_body,
            actionRes = R.string.readiness_inert_action,
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = look.container,
            contentColor = look.onContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(look.icon, contentDescription = null, modifier = Modifier.size(40.dp))
            Text(stringResource(look.titleRes), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(look.bodyRes), style = MaterialTheme.typography.bodyLarge)

            if (readiness == CapabilityReport.Readiness.INERT) {
                Text(
                    stringResource(R.string.readiness_inert_emphasis),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            // Shown precisely when the app looks healthiest. Test Mode makes
            // the app feel instant because it skips telephony entirely, so an
            // armed Dashboard is the one place a user could reasonably form the
            // belief that volume is raised before the phone starts ringing.
            // It is not, and that gap is a property of this detection path
            // rather than a bug to be fixed later.
            if (readiness != CapabilityReport.Readiness.INERT) {
                Text(
                    stringResource(R.string.readiness_timing_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            look.actionRes?.let { actionRes ->
                OutlinedButton(onClick = onOpenPermissions) { Text(stringResource(actionRes)) }
            }
        }
    }
}

@Composable
private fun DeviceStateCard(report: CapabilityReport) {
    SectionCard(title = stringResource(R.string.dashboard_device_state_title)) {
        LabeledValueRow(
            label = stringResource(R.string.label_ringer_mode),
            value = stringResource(report.currentRingerMode.labelRes),
        )
        LabeledValueRow(
            label = stringResource(R.string.label_interruption_filter),
            value = stringResource(report.currentInterruptionFilter.labelRes),
        )
        LabeledValueRow(
            label = stringResource(R.string.label_ring_volume),
            value = stringResource(R.string.value_percent, report.currentRingVolume.percent),
        )
        if (report.isVolumeFixed) {
            Text(
                stringResource(R.string.dashboard_volume_fixed),
                style = MaterialTheme.typography.bodyMedium,
                color = PriorityRingerTheme.status.degraded,
            )
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

/** Per-step outcome of the most recent apply (real or simulated). */
@Composable
private fun LastAttemptCard(apply: ApplyResult) {
    SectionCard(title = stringResource(R.string.dashboard_last_attempt_title)) {
        LabeledValueRow(
            label = stringResource(R.string.label_contact),
            value = apply.contact.displayName,
        )
        LabeledValueRow(
            label = stringResource(R.string.label_escalated),
            value = stringResource(
                if (apply.escalation.escalate) R.string.value_yes else R.string.value_no,
            ),
        )
        OutcomeRow(stringResource(R.string.label_do_not_disturb), apply.dnd)
        OutcomeRow(stringResource(R.string.label_ringer_mode), apply.ringer)
        // `volume` is Outcome<*>; only success/failure is readable through the
        // star projection, which is all this row needs.
        OutcomeRow(stringResource(R.string.label_ring_volume), apply.volume)
        OutcomeRow(stringResource(R.string.label_full_screen_alert), apply.alert)
    }
}

@Composable
private fun OutcomeRow(
    label: String,
    outcome: com.elham.priorityringer.domain.model.Outcome<*>?,
) {
    val palette = PriorityRingerTheme.status
    when {
        outcome == null -> LabeledValueRow(
            label = label,
            value = stringResource(R.string.value_not_attempted),
        )

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
