package com.elham.priorityringer.presentation.testmode

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elham.priorityringer.R
import com.elham.priorityringer.domain.model.AppSettings
import com.elham.priorityringer.domain.model.CapabilityReport
import com.elham.priorityringer.domain.model.FailureReason
import com.elham.priorityringer.domain.model.Outcome
import com.elham.priorityringer.domain.model.PriorityContact
import com.elham.priorityringer.domain.usecase.ApplyResult
import com.elham.priorityringer.domain.usecase.BuildCapabilityReportUseCase
import com.elham.priorityringer.domain.usecase.IncomingCallCoordinator
import com.elham.priorityringer.domain.usecase.ObservePriorityContactsUseCase
import com.elham.priorityringer.domain.usecase.ObserveSettingsUseCase
import com.elham.priorityringer.domain.usecase.RestoreAudioAndDndUseCase
import com.elham.priorityringer.domain.usecase.RestoreTrigger
import com.elham.priorityringer.domain.usecase.SeedEscalationHistoryUseCase
import com.elham.priorityringer.domain.usecase.SimulatePriorityCallUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TestModeUiState(
    val isLoading: Boolean = true,
    val report: CapabilityReport? = null,
    val settings: AppSettings = AppSettings(),
    val contacts: List<PriorityContact> = emptyList(),
    val selectedContactId: Long? = null,
    val isRunning: Boolean = false,
    val lastApply: ApplyResult? = null,
    /** True once a simulation has run and no restore has been seen since. */
    val restorePending: Boolean = false,
) {
    val enabledContacts: List<PriorityContact> get() = contacts.filter { it.enabled }

    val selectedContact: PriorityContact?
        get() = enabledContacts.firstOrNull { it.id == selectedContactId }
            ?: enabledContacts.firstOrNull()
}

sealed interface TestModeEffect {
    data class Message(@param:StringRes val textRes: Int) : TestModeEffect
}

/**
 * FR6 / Architecture.md § 9.
 *
 * Every action here goes through the same use cases a real `PHONE_STATE`
 * broadcast does. Nothing is stubbed and no permission result is faked: if
 * `READ_CALL_LOG` is denied, the simulation reports exactly the failure a real
 * call would. A test mode that passed while the real path would fail is worse
 * than having no test mode at all.
 */
@HiltViewModel
class TestModeViewModel @Inject constructor(
    private val buildCapabilityReport: BuildCapabilityReportUseCase,
    private val simulatePriorityCall: SimulatePriorityCallUseCase,
    private val restoreAudioAndDnd: RestoreAudioAndDndUseCase,
    private val seedEscalationHistory: SeedEscalationHistoryUseCase,
    private val observeSettings: ObserveSettingsUseCase,
    observeContacts: ObservePriorityContactsUseCase,
    coordinator: IncomingCallCoordinator,
) : ViewModel() {

    private val report = MutableStateFlow<CapabilityReport?>(null)
    private val selectedContactId = MutableStateFlow<Long?>(null)
    private val isRunning = MutableStateFlow(false)
    private val restorePending = MutableStateFlow(false)

    private val _effects = MutableSharedFlow<TestModeEffect>(extraBufferCapacity = 2)
    val effects: SharedFlow<TestModeEffect> = _effects.asSharedFlow()

    val uiState: StateFlow<TestModeUiState> = combine(
        report,
        observeContacts(),
        observeSettings(),
        combine(selectedContactId, isRunning, restorePending) { id, running, pending ->
            Triple(id, running, pending)
        },
        coordinator.lastResult,
    ) { capabilityReport, contacts, settings, (selectedId, running, pendingRestore), lastApply ->
        TestModeUiState(
            isLoading = capabilityReport == null,
            report = capabilityReport,
            settings = settings,
            contacts = contacts,
            selectedContactId = selectedId,
            isRunning = running,
            lastApply = lastApply,
            restorePending = pendingRestore,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = TestModeUiState(),
    )

    /**
     * Re-probe. Architecture.md § 9 asks for a live ringer/filter readout; the
     * only seam available is the synchronous [BuildCapabilityReportUseCase], so
     * the screen polls this while it is resumed rather than inventing a port.
     */
    fun refresh() {
        viewModelScope.launch {
            report.value = withContext(Dispatchers.IO) { buildCapabilityReport() }
        }
    }

    fun selectContact(id: Long) {
        selectedContactId.value = id
    }

    fun simulateCall() {
        val contact = uiState.value.selectedContact
        if (contact == null) {
            emit(R.string.test_no_contacts)
            return
        }
        runSimulation(contact)
    }

    /**
     * FR6 "simulate escalation": seed enough synthetic timestamps that the real
     * [com.elham.priorityringer.domain.escalation.EscalationPolicy] decides to
     * escalate, then run an ordinary simulated call.
     *
     * `EscalationPolicy` counts the current call itself, so one fewer than the
     * threshold is seeded. The timestamps are past-dated: the policy only
     * counts entries in `cutoff..now`, so anything stamped at or after the
     * call's own instant would be discarded and the escalation would not fire.
     */
    fun simulateEscalation() {
        val contact = uiState.value.selectedContact
        if (contact == null) {
            emit(R.string.test_no_contacts)
            return
        }

        viewModelScope.launch {
            val settings = observeSettings().first()
            if (!settings.escalation.enabled) {
                emit(R.string.test_escalation_disabled)
                return@launch
            }

            seedEscalationHistory(contact, settings)
            emit(R.string.test_escalation_seeded)
            runSimulation(contact)
        }
    }

    private fun runSimulation(contact: PriorityContact) {
        if (isRunning.value) return
        isRunning.value = true

        viewModelScope.launch {
            try {
                when (simulatePriorityCall(contact.originalInput)) {
                    SimulatePriorityCallUseCase.SimulationResult.Ran -> {
                        restorePending.value = true
                        emit(R.string.test_simulation_ran)
                    }

                    SimulatePriorityCallUseCase.SimulationResult.NoContactsConfigured ->
                        emit(R.string.test_no_contacts)
                }
            } finally {
                isRunning.value = false
                refresh()
            }
        }
    }

    /**
     * Architecture.md § 9 requires an explicit, always-available "Restore now".
     * It routes through the same idempotent use case as the three automatic
     * triggers, so pressing it when nothing is pending is a safe no-op.
     */
    fun restoreNow() {
        viewModelScope.launch {
            val outcome = restoreAudioAndDnd(RestoreTrigger.MANUAL)
            restorePending.value = false
            refresh()
            emit(
                when {
                    outcome is Outcome.Failure &&
                        outcome.reason == FailureReason.NOTHING_TO_DO ->
                        R.string.test_restore_nothing

                    outcome.isSuccess -> R.string.test_restore_done
                    else -> R.string.test_restore_incomplete
                },
            )
        }
    }

    private fun emit(@StringRes textRes: Int) {
        viewModelScope.launch { _effects.emit(TestModeEffect.Message(textRes)) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
