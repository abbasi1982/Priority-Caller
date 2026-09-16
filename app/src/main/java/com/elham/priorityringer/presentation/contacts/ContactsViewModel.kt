package com.elham.priorityringer.presentation.contacts

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elham.priorityringer.R
import com.elham.priorityringer.domain.model.PriorityContact
import com.elham.priorityringer.domain.usecase.ObservePriorityContactsUseCase
import com.elham.priorityringer.domain.usecase.RemoveContactUseCase
import com.elham.priorityringer.domain.usecase.ToggleContactUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContactsUiState(
    val isLoading: Boolean = true,
    val contacts: List<PriorityContact> = emptyList(),
    /** Set while the delete confirmation dialog is open. */
    val pendingDeletion: PriorityContact? = null,
) {
    val isEmpty: Boolean get() = !isLoading && contacts.isEmpty()
}

/** One-off events. Anything that should not survive a rotation lives here. */
sealed interface ContactsEffect {
    data class Message(@param:StringRes val textRes: Int, val formatArg: String? = null) :
        ContactsEffect
}

@HiltViewModel
class ContactsViewModel @Inject constructor(
    observeContacts: ObservePriorityContactsUseCase,
    private val toggleContact: ToggleContactUseCase,
    private val removeContact: RemoveContactUseCase,
) : ViewModel() {

    private val pendingDeletion = MutableStateFlow<PriorityContact?>(null)

    private val _effects = MutableSharedFlow<ContactsEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<ContactsEffect> = _effects.asSharedFlow()

    val uiState: StateFlow<ContactsUiState> =
        combine(observeContacts(), pendingDeletion) { contacts, pending ->
            ContactsUiState(
                isLoading = false,
                // Newest first: the contact just added is the one being checked.
                contacts = contacts.sortedByDescending { it.createdAtEpochMs },
                pendingDeletion = pending,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = ContactsUiState(),
        )

    fun setEnabled(contact: PriorityContact, enabled: Boolean) {
        viewModelScope.launch {
            toggleContact(contact.id, enabled)
            _effects.emit(
                ContactsEffect.Message(
                    textRes = if (enabled) {
                        R.string.contacts_enabled_message
                    } else {
                        R.string.contacts_disabled_message
                    },
                    formatArg = contact.displayName,
                ),
            )
        }
    }

    fun requestDelete(contact: PriorityContact) {
        pendingDeletion.value = contact
    }

    fun cancelDelete() {
        pendingDeletion.value = null
    }

    fun confirmDelete() {
        val contact = pendingDeletion.value ?: return
        pendingDeletion.value = null
        viewModelScope.launch {
            removeContact(contact.id)
            _effects.emit(
                ContactsEffect.Message(R.string.contacts_removed_message, contact.displayName),
            )
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
