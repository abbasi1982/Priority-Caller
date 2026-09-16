package com.elham.priorityringer.presentation.audit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elham.priorityringer.R
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.AuditLogEntry
import com.elham.priorityringer.domain.model.AuditSeverity
import com.elham.priorityringer.presentation.common.ConfirmDialog
import com.elham.priorityringer.presentation.common.EmptyState
import com.elham.priorityringer.presentation.common.TimeFormatting
import com.elham.priorityringer.presentation.common.containerColor
import com.elham.priorityringer.presentation.common.icon
import com.elham.priorityringer.presentation.common.labelRes
import com.elham.priorityringer.presentation.common.tint

@Composable
fun AuditScreen(
    snackbarHostState: SnackbarHostState,
    viewModel: AuditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AuditContent(
        state = state,
        onSetFilter = viewModel::setFilter,
        onRequestClear = viewModel::requestClear,
    )

    if (state.showClearConfirmation) {
        ConfirmDialog(
            title = stringResource(R.string.audit_clear_title),
            message = stringResource(R.string.audit_clear_message),
            confirmLabel = stringResource(R.string.audit_clear_confirm),
            onConfirm = viewModel::confirmClear,
            onDismiss = viewModel::cancelClear,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuditContent(
    state: AuditUiState,
    onSetFilter: (AuditEventType?) -> Unit,
    onRequestClear: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.audit_entry_count, state.totalCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRequestClear, enabled = state.totalCount > 0) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.audit_clear))
            }
        }

        if (state.availableTypes.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.filter == null,
                    onClick = { onSetFilter(null) },
                    label = { Text(stringResource(R.string.audit_filter_all)) },
                )
                state.availableTypes.forEach { type ->
                    FilterChip(
                        selected = state.filter == type,
                        onClick = { onSetFilter(if (state.filter == type) null else type) },
                        label = { Text(stringResource(type.labelRes)) },
                    )
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                state.isLoading -> Unit

                state.isFilteredEmpty -> EmptyState(
                    icon = Icons.Filled.HistoryToggleOff,
                    title = stringResource(R.string.audit_empty_filtered_title),
                    message = stringResource(R.string.audit_empty_filtered_message),
                    actionLabel = stringResource(R.string.audit_filter_all),
                    onAction = { onSetFilter(null) },
                    modifier = Modifier.align(Alignment.Center),
                )

                state.isEmpty -> EmptyState(
                    icon = Icons.Filled.HistoryToggleOff,
                    title = stringResource(R.string.audit_empty_title),
                    message = stringResource(R.string.audit_empty_message),
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.entries, key = { it.id }) { entry -> AuditRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun AuditRow(entry: AuditLogEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = entry.severity.containerColor()),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                entry.severity.icon(),
                contentDescription = stringResource(entry.severity.contentDescriptionRes()),
                tint = entry.severity.tint(),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    stringResource(entry.type.labelRes),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        TimeFormatting.timestamp(entry.timestampEpochMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!entry.recoverable) {
                        // Architecture.md § 3 separates "the platform said no"
                        // from "something went wrong we did not anticipate".
                        Text(
                            stringResource(R.string.audit_unexpected),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun AuditSeverity.contentDescriptionRes(): Int = when (this) {
    AuditSeverity.INFO -> R.string.severity_info
    AuditSeverity.WARNING -> R.string.severity_warning
    AuditSeverity.ERROR -> R.string.severity_error
}
