package com.elham.priorityringer.presentation.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAddAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elham.priorityringer.R
import com.elham.priorityringer.domain.model.PriorityContact
import com.elham.priorityringer.presentation.common.ConfirmDialog
import com.elham.priorityringer.presentation.common.EmptyState

@Composable
fun ContactsScreen(
    snackbarHostState: SnackbarHostState,
    onAddContact: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ContactsEffect.Message -> snackbarHostState.showSnackbar(
                    effect.formatArg
                        ?.let { context.getString(effect.textRes, it) }
                        ?: context.getString(effect.textRes),
                )
            }
        }
    }

    ContactsContent(
        state = state,
        onAddContact = onAddContact,
        onToggle = viewModel::setEnabled,
        onRequestDelete = viewModel::requestDelete,
    )

    state.pendingDeletion?.let { contact ->
        ConfirmDialog(
            title = stringResource(R.string.contacts_delete_title),
            message = stringResource(R.string.contacts_delete_message, contact.displayName),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

@Composable
private fun ContactsContent(
    state: ContactsUiState,
    onAddContact: () -> Unit,
    onToggle: (PriorityContact, Boolean) -> Unit,
    onRequestDelete: (PriorityContact) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        when {
            state.isLoading -> Unit

            state.isEmpty -> EmptyState(
                icon = Icons.Filled.PersonAddAlt,
                title = stringResource(R.string.contacts_empty_title),
                message = stringResource(R.string.contacts_empty_message),
                actionLabel = stringResource(R.string.contacts_add),
                onAction = onAddContact,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Bottom padding clears the FAB so the last row stays reachable.
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.contacts, key = { it.id }) { contact ->
                    ContactRow(
                        contact = contact,
                        onToggle = { enabled -> onToggle(contact, enabled) },
                        onDelete = { onRequestDelete(contact) },
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onAddContact,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.contacts_add)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun ContactRow(
    contact: PriorityContact,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            headlineContent = { Text(contact.displayName) },
            supportingContent = {
                Text(
                    text = if (contact.enabled) {
                        stringResource(R.string.contacts_row_enabled, contact.originalInput)
                    } else {
                        stringResource(R.string.contacts_row_disabled, contact.originalInput)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = contact.enabled,
                        onCheckedChange = onToggle,
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(
                                R.string.contacts_delete_cd,
                                contact.displayName,
                            ),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    }
}
