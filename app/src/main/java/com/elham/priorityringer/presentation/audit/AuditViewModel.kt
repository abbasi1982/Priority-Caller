package com.elham.priorityringer.presentation.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.AuditLogEntry
import com.elham.priorityringer.domain.usecase.ClearAuditLogUseCase
import com.elham.priorityringer.domain.usecase.ObserveAuditLogUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AuditUiState(
    val isLoading: Boolean = true,
    /** Already newest-first: the repository returns them in that order. */
    val entries: List<AuditLogEntry> = emptyList(),
    /** `null` means "all types". */
    val filter: AuditEventType? = null,
    /** Only the types actually present, so the filter never offers a dead end. */
    val availableTypes: List<AuditEventType> = emptyList(),
    val showClearConfirmation: Boolean = false,
    val totalCount: Int = 0,
) {
    val isEmpty: Boolean get() = !isLoading && entries.isEmpty()
    val isFilteredEmpty: Boolean get() = isEmpty && filter != null
}

@HiltViewModel
class AuditViewModel @Inject constructor(
    observeAuditLog: ObserveAuditLogUseCase,
    private val clearAuditLog: ClearAuditLogUseCase,
) : ViewModel() {

    private val filter = MutableStateFlow<AuditEventType?>(null)
    private val showClearConfirmation = MutableStateFlow(false)

    val uiState: StateFlow<AuditUiState> =
        combine(observeAuditLog(), filter, showClearConfirmation) { entries, type, confirming ->
            AuditUiState(
                isLoading = false,
                entries = if (type == null) entries else entries.filter { it.type == type },
                filter = type,
                availableTypes = entries.map { it.type }.distinct().sortedBy { it.name },
                showClearConfirmation = confirming,
                totalCount = entries.size,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = AuditUiState(),
        )

    fun setFilter(type: AuditEventType?) {
        filter.value = type
    }

    fun requestClear() {
        showClearConfirmation.value = true
    }

    fun cancelClear() {
        showClearConfirmation.value = false
    }

    fun confirmClear() {
        showClearConfirmation.value = false
        viewModelScope.launch { clearAuditLog() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
