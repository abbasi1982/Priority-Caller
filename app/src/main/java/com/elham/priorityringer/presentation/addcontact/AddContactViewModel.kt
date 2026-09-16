package com.elham.priorityringer.presentation.addcontact

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elham.priorityringer.R
import com.elham.priorityringer.domain.model.Capability
import com.elham.priorityringer.domain.phone.PhoneNumberNormalizer
import com.elham.priorityringer.domain.usecase.AddContactResult
import com.elham.priorityringer.domain.usecase.AddPriorityContactUseCase
import com.elham.priorityringer.domain.usecase.BuildCapabilityReportUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AddContactUiState(
    val displayName: String = "",
    val number: String = "",
    /** Inline, live validation message under the number field. */
    @param:StringRes val numberErrorRes: Int? = null,
    val isSaving: Boolean = false,
    /**
     * Set when the device picker returned a contact whose number we could not
     * read. Carries the probe's own `consequence` copy for `READ_CONTACTS` so
     * the explanation matches the Permissions screen exactly.
     */
    val pickerConsequence: String? = null,
) {
    val canSave: Boolean
        get() = !isSaving && number.isNotBlank() && numberErrorRes == null
}

sealed interface AddContactEffect {
    data object Saved : AddContactEffect
    data class Message(@param:StringRes val textRes: Int) : AddContactEffect
}

@HiltViewModel
class AddContactViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val addContact: AddPriorityContactUseCase,
    private val normalizer: PhoneNumberNormalizer,
    private val buildCapabilityReport: BuildCapabilityReportUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddContactUiState())
    val uiState: StateFlow<AddContactUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<AddContactEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<AddContactEffect> = _effects.asSharedFlow()

    fun onNameChanged(value: String) {
        _uiState.update { it.copy(displayName = value) }
    }

    /**
     * Validation runs on every keystroke but only *reports* once there is
     * something to report — flagging "too short" while the user is still on the
     * third digit would be noise, so a non-empty-but-implausible number is the
     * only inline error.
     *
     * The country ISO is deliberately not passed: it only affects E.164
     * promotion, not the digit count `isPlausible` actually tests, so the
     * ViewModel needs no `TelephonyPort`.
     */
    fun onNumberChanged(value: String) {
        _uiState.update { current ->
            current.copy(
                number = value,
                numberErrorRes = when {
                    value.isBlank() -> null
                    !normalizer.isPlausible(value) -> R.string.add_contact_error_invalid
                    else -> null
                },
                pickerConsequence = null,
            )
        }
    }

    /** Result of `ActivityResultContracts.PickContact`. */
    fun onContactPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            // ContentResolver on the main thread would jank the picker's return
            // animation and can block on a slow provider.
            when (val picked = withContext(Dispatchers.IO) {
                ContactUriResolver.resolve(context, uri)
            }) {
                is PickedContact.Resolved -> _uiState.update { current ->
                    current.copy(
                        displayName = picked.displayName.ifBlank { current.displayName },
                        number = picked.number,
                        numberErrorRes = if (normalizer.isPlausible(picked.number)) {
                            null
                        } else {
                            R.string.add_contact_error_invalid
                        },
                        pickerConsequence = null,
                    )
                }

                PickedContact.NumberUnreadable -> _uiState.update {
                    it.copy(pickerConsequence = readContactsConsequence())
                }
            }
        }
    }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) return

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            when (addContact(displayName = state.displayName, rawNumber = state.number)) {
                is AddContactResult.Added -> {
                    _uiState.update { it.copy(isSaving = false) }
                    _effects.emit(AddContactEffect.Saved)
                }

                AddContactResult.InvalidNumber -> _uiState.update {
                    it.copy(isSaving = false, numberErrorRes = R.string.add_contact_error_invalid)
                }

                // Not an error state: the contact the user wanted is already
                // protected, so the message says so rather than accusing them.
                AddContactResult.AlreadyExists -> _uiState.update {
                    it.copy(isSaving = false, numberErrorRes = R.string.add_contact_error_exists)
                }
            }
        }
    }

    fun dismissPickerMessage() {
        _uiState.update { it.copy(pickerConsequence = null) }
    }

    private fun readContactsConsequence(): String =
        buildCapabilityReport()[Capability.READ_CONTACTS]?.consequence
            ?: context.getString(R.string.add_contact_picker_unreadable_fallback)
}
