package com.elham.priorityringer.presentation.addcontact

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elham.priorityringer.R
import com.elham.priorityringer.domain.model.AuditSeverity
import com.elham.priorityringer.presentation.common.PersistentBanner
import com.elham.priorityringer.presentation.common.SectionCard

@Composable
fun AddContactScreen(
    snackbarHostState: SnackbarHostState,
    onSaved: () -> Unit,
    viewModel: AddContactViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickContact = rememberLauncherForActivityResult(
        ActivityResultContracts.PickContact(),
    ) { uri -> viewModel.onContactPicked(uri) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                AddContactEffect.Saved -> {
                    snackbarHostState.showSnackbar(context.getString(R.string.add_contact_saved))
                    onSaved()
                }

                is AddContactEffect.Message ->
                    snackbarHostState.showSnackbar(context.getString(effect.textRes))
            }
        }
    }

    AddContactContent(
        state = state,
        onPickContact = { pickContact.launch(null) },
        onNameChanged = viewModel::onNameChanged,
        onNumberChanged = viewModel::onNumberChanged,
        onDismissPickerMessage = viewModel::dismissPickerMessage,
        onSave = viewModel::save,
    )
}

@Composable
private fun AddContactContent(
    state: AddContactUiState,
    onPickContact: () -> Unit,
    onNameChanged: (String) -> Unit,
    onNumberChanged: (String) -> Unit,
    onDismissPickerMessage: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(title = stringResource(R.string.add_contact_picker_title)) {
            Text(
                stringResource(R.string.add_contact_picker_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onPickContact, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Contacts, contentDescription = null)
                Text(
                    stringResource(R.string.add_contact_pick_from_device),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        // Shown instead of failing silently when the picker returns a contact
        // whose number needs READ_CONTACTS. Manual entry below still works.
        state.pickerConsequence?.let { consequence ->
            PersistentBanner(
                title = stringResource(R.string.add_contact_picker_unreadable_title),
                message = consequence,
                severity = AuditSeverity.WARNING,
                onDismiss = onDismissPickerMessage,
            )
        }

        SectionCard(title = stringResource(R.string.add_contact_manual_title)) {
            OutlinedTextField(
                value = state.displayName,
                onValueChange = onNameChanged,
                label = { Text(stringResource(R.string.add_contact_name_label)) },
                supportingText = { Text(stringResource(R.string.add_contact_name_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.number,
                onValueChange = onNumberChanged,
                label = { Text(stringResource(R.string.add_contact_number_label)) },
                isError = state.numberErrorRes != null,
                supportingText = {
                    Text(
                        text = state.numberErrorRes
                            ?.let { stringResource(it) }
                            ?: stringResource(R.string.add_contact_number_help),
                        color = if (state.numberErrorRes != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(R.string.add_contact_matching_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = onSave,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp).size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(stringResource(R.string.add_contact_save))
        }
    }
}
