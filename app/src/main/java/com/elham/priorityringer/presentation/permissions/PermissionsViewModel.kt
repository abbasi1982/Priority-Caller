package com.elham.priorityringer.presentation.permissions

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elham.priorityringer.R
import com.elham.priorityringer.domain.model.Capability
import com.elham.priorityringer.domain.model.CapabilityReport
import com.elham.priorityringer.domain.model.CapabilityStatus
import com.elham.priorityringer.domain.usecase.BuildCapabilityReportUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
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

data class PermissionsUiState(
    val isLoading: Boolean = true,
    val report: CapabilityReport? = null,
    /** Walk one grant at a time instead of showing the whole list. */
    val guidedFlowActive: Boolean = false,
    val guidedSkipped: Boolean = false,
) {
    /** Rows to render, in a fixed reading order rather than probe order. */
    val statuses: List<CapabilityStatus>
        get() = report?.statuses.orEmpty()
            .sortedBy { status -> DISPLAY_ORDER.indexOf(status.capability).takeIf { it >= 0 } ?: Int.MAX_VALUE }

    /**
     * The capability the guided flow is currently asking for.
     *
     * Derived rather than stored. Nothing in `presentation` may persist state —
     * `data/` is owned elsewhere — but more importantly a derived step is
     * self-correcting: revoke a grant from system settings and the flow returns
     * to that step on the next resume, which a saved "onboarding done" flag
     * would not.
     */
    val guidedStep: CapabilityStatus?
        get() = report?.let { r ->
            GUIDED_ORDER.firstNotNullOfOrNull { capability ->
                r[capability]?.takeIf { it.isActionable }
            }
        }

    val guidedStepIndex: Int
        get() = guidedStep?.let { GUIDED_ORDER.indexOf(it.capability) + 1 } ?: GUIDED_ORDER.size

    /**
     * Offer the walkthrough whenever something the app genuinely cannot work
     * without is missing (Architecture.md § A.5), unless the user has said
     * "not now" during this session.
     */
    val shouldOfferGuidedFlow: Boolean
        get() = !guidedSkipped &&
            !guidedFlowActive &&
            report?.readiness == CapabilityReport.Readiness.INERT

    companion object {
        /**
         * Grant order for the first-run walkthrough.
         *
         * Required capabilities first, because until both are granted every
         * other grant is decorative — the app cannot identify a caller at all
         * (§ A.5). `VOLUME_ADJUSTABLE` is never a step: it is a device fact, not
         * a permission, and is always RESTRICTED or GRANTED.
         */
        val GUIDED_ORDER = listOf(
            Capability.READ_PHONE_STATE,
            Capability.READ_CALL_LOG,
            Capability.NOTIFICATION_POLICY_ACCESS,
            Capability.POST_NOTIFICATIONS,
            Capability.FULL_SCREEN_INTENT,
            Capability.READ_CONTACTS,
        )

        val DISPLAY_ORDER = GUIDED_ORDER + Capability.VOLUME_ADJUSTABLE
    }
}

sealed interface PermissionsEffect {
    data class Message(@param:StringRes val textRes: Int) : PermissionsEffect
}

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val buildCapabilityReport: BuildCapabilityReportUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PermissionsUiState())
    val uiState: StateFlow<PermissionsUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<PermissionsEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<PermissionsEffect> = _effects.asSharedFlow()

    /**
     * Re-probe. Called on every `ON_RESUME` and after every grant attempt —
     * a Settings deep link returns through resume, and a runtime dialog's
     * result is only trustworthy once re-read from the platform.
     */
    fun refresh() {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { buildCapabilityReport() }
            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    report = report,
                    // Leaving the walkthrough open with nothing left to grant
                    // would be a dead end, so it closes itself.
                    guidedFlowActive = current.guidedFlowActive &&
                        PermissionsUiState(report = report).guidedStep != null,
                )
            }
        }
    }

    fun startGuidedFlow() {
        _uiState.update { it.copy(guidedFlowActive = true, guidedSkipped = false) }
    }

    fun exitGuidedFlow() {
        _uiState.update { it.copy(guidedFlowActive = false, guidedSkipped = true) }
    }

    fun notify(@StringRes textRes: Int) {
        viewModelScope.launch { _effects.emit(PermissionsEffect.Message(textRes)) }
    }

    fun onPermissionResult(granted: Boolean) {
        if (!granted) notify(R.string.permissions_denied_message)
        refresh()
    }
}
