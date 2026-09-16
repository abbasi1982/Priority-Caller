package com.elham.priorityringer.presentation.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elham.priorityringer.R
import com.elham.priorityringer.domain.model.Capability
import com.elham.priorityringer.domain.model.CapabilityStatus
import com.elham.priorityringer.presentation.common.CapabilityRow
import com.elham.priorityringer.presentation.common.SettingsLinks
import com.elham.priorityringer.presentation.common.actionLabelRes
import com.elham.priorityringer.presentation.common.labelRes
import com.elham.priorityringer.presentation.common.purposeRes
import com.elham.priorityringer.presentation.theme.PriorityRingerTheme

@Composable
fun PermissionsScreen(
    snackbarHostState: SnackbarHostState,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Architecture.md § 12 — grants change outside the app (system Settings,
    // an admin policy, an OS update), so the probe is repeated on every resume
    // rather than cached. Returning from a Settings deep link also lands here.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PermissionsEffect.Message ->
                    snackbarHostState.showSnackbar(context.getString(effect.textRes))
            }
        }
    }

    var pendingCapability by remember { mutableStateOf<Capability?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val capability = pendingCapability
        pendingCapability = null

        val permission = capability?.let { SettingsLinks.runtimePermission(it) }
        val activity = SettingsLinks.findActivity(context)

        // A denial with no rationale left means the system will no longer show
        // the dialog: the only remaining route is App info, so send them there
        // instead of leaving a button that appears to do nothing.
        val permanentlyDenied = !granted &&
            permission != null &&
            activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

        if (permanentlyDenied) {
            if (SettingsLinks.launch(context, SettingsLinks.appDetails(context))) {
                viewModel.notify(R.string.permissions_opened_app_settings)
            } else {
                viewModel.notify(R.string.permissions_no_settings_screen)
            }
        }
        viewModel.onPermissionResult(granted)
    }

    val onAction: (Capability) -> Unit = { capability ->
        val permission = SettingsLinks.runtimePermission(capability)
        if (permission != null) {
            pendingCapability = capability
            permissionLauncher.launch(permission)
        } else {
            val intent = SettingsLinks.settingsIntent(context, capability)
            if (intent == null || !SettingsLinks.launch(context, intent)) {
                viewModel.notify(R.string.permissions_no_settings_screen)
            }
        }
    }

    PermissionsContent(
        state = state,
        onAction = onAction,
        onStartGuidedFlow = viewModel::startGuidedFlow,
        onExitGuidedFlow = viewModel::exitGuidedFlow,
    )
}

@Composable
private fun PermissionsContent(
    state: PermissionsUiState,
    onAction: (Capability) -> Unit,
    onStartGuidedFlow: () -> Unit,
    onExitGuidedFlow: () -> Unit,
) {
    if (state.isLoading) return

    val guidedStep = state.guidedStep

    if (state.guidedFlowActive && guidedStep != null) {
        GuidedStep(
            status = guidedStep,
            stepNumber = state.guidedStepIndex,
            totalSteps = PermissionsUiState.GUIDED_ORDER.size,
            onAction = { onAction(guidedStep.capability) },
            onExit = onExitGuidedFlow,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.permissions_intro),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        if (state.shouldOfferGuidedFlow) {
            item { GuidedFlowPrompt(onStart = onStartGuidedFlow, onSkip = onExitGuidedFlow) }
        } else if (guidedStep != null) {
            item {
                OutlinedButton(onClick = onStartGuidedFlow, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.permissions_resume_setup))
                }
            }
        } else {
            item { AllGrantedCard() }
        }

        // Iterating the report's own list, not Capability.entries: the probe
        // decides what applies on this API level, and a capability it omits
        // must not appear as a mysterious blank row.
        items(state.statuses, key = { it.capability.name }) { status ->
            CapabilityRow(status = status, onAction = { onAction(status.capability) })
        }

        item { Spacer(Modifier.height(8.dp)) }
        item {
            Text(
                stringResource(R.string.permissions_footer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GuidedFlowPrompt(onStart: () -> Unit, onSkip: () -> Unit) {
    val palette = PriorityRingerTheme.status
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = palette.inertContainer,
            contentColor = palette.onInertContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.permissions_setup_prompt_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                stringResource(R.string.permissions_setup_prompt_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart) {
                    Text(stringResource(R.string.permissions_setup_start))
                }
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.permissions_setup_skip))
                }
            }
        }
    }
}

/**
 * One grant at a time (Architecture.md § 12 / Addendum).
 *
 * A flat status list is a reference, not an onboarding: it asks the user to
 * work out which of six permissions to grant first, in an app where two of them
 * are load-bearing and the rest are not. The walkthrough removes that decision.
 */
@Composable
private fun GuidedStep(
    status: CapabilityStatus,
    stepNumber: Int,
    totalSteps: Int,
    onAction: () -> Unit,
    onExit: () -> Unit,
) {
    val palette = PriorityRingerTheme.status

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.permissions_step_counter, stepNumber, totalSteps),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { stepNumber.toFloat() / totalSteps.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            stringResource(status.capability.labelRes),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(status.capability.purposeRes),
            style = MaterialTheme.typography.bodyLarge,
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (status.capability.isRequired) {
                    palette.inertContainer
                } else {
                    palette.degradedContainer
                },
                contentColor = if (status.capability.isRequired) {
                    palette.onInertContainer
                } else {
                    palette.onDegradedContainer
                },
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(
                        if (status.capability.isRequired) {
                            R.string.permissions_step_without_this_required
                        } else {
                            R.string.permissions_step_without_this_optional
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(status.consequence, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(4.dp))

        Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(status.capability.actionLabelRes))
        }
        TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.permissions_step_show_all))
        }
    }
}

/** Shown in place of the walkthrough once nothing is left to grant. */
@Composable
internal fun AllGrantedCard() {
    val palette = PriorityRingerTheme.status
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = palette.armed,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.permissions_all_granted))
    }
}
