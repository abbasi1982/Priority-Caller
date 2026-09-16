package com.elham.priorityringer.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.AuditLogEntry
import com.elham.priorityringer.domain.model.CapabilityReport
import com.elham.priorityringer.domain.usecase.ApplyResult
import com.elham.priorityringer.domain.usecase.BuildCapabilityReportUseCase
import com.elham.priorityringer.domain.usecase.IncomingCallCoordinator
import com.elham.priorityringer.domain.usecase.ObserveAuditLogUseCase
import com.elham.priorityringer.domain.usecase.ObservePriorityContactsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One of the two persistent banners Architecture.md § 4 rule 5 and § 6.3
 * require. Identified by the audit entry that caused it so that dismissal
 * applies to *that* failure and not to every future one.
 */
data class DashboardBanner(
    val entryId: Long,
    val kind: Kind,
    val message: String,
    val timestampEpochMs: Long,
) {
    enum class Kind { RESTORE_FAILED, DND_INEFFECTIVE }
}

data class DashboardUiState(
    val isLoading: Boolean = true,
    val report: CapabilityReport? = null,
    val totalContacts: Int = 0,
    val enabledContacts: Int = 0,
    val lastEvent: AuditLogEntry? = null,
    val banners: List<DashboardBanner> = emptyList(),
    val lastApply: ApplyResult? = null,
) {
    val readiness: CapabilityReport.Readiness?
        get() = report?.readiness
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val buildCapabilityReport: BuildCapabilityReportUseCase,
    observeAuditLog: ObserveAuditLogUseCase,
    observeContacts: ObservePriorityContactsUseCase,
    coordinator: IncomingCallCoordinator,
) : ViewModel() {

    /**
     * Written by [refresh] only. The report is never cached across a resume —
     * these grants are revocable from outside the app and a stale ✅ on this
     * screen would be the most consequential lie the UI could tell.
     */
    private val report = MutableStateFlow<CapabilityReport?>(null)

    private val dismissedBannerIds = MutableStateFlow<Set<Long>>(emptySet())

    val uiState: StateFlow<DashboardUiState> = combine(
        report,
        observeAuditLog(limit = AUDIT_SCAN_DEPTH),
        observeContacts(),
        dismissedBannerIds,
        coordinator.lastResult,
    ) { capabilityReport, auditEntries, contacts, dismissed, lastApply ->
        DashboardUiState(
            isLoading = capabilityReport == null,
            report = capabilityReport,
            totalContacts = contacts.size,
            enabledContacts = contacts.count { it.enabled },
            lastEvent = auditEntries.firstOrNull(),
            banners = auditEntries.activeBanners().filterNot { it.entryId in dismissed },
            lastApply = lastApply,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DashboardUiState(),
    )

    /**
     * Called from `ON_RESUME`. The probe is a synchronous set of binder calls
     * into `AudioManager`/`NotificationManager`, so it is moved off the main
     * thread even though each one is individually cheap.
     */
    fun refresh() {
        viewModelScope.launch {
            report.value = withContext(Dispatchers.IO) { buildCapabilityReport() }
        }
    }

    fun dismissBanner(entryId: Long) {
        dismissedBannerIds.value = dismissedBannerIds.value + entryId
    }

    private companion object {
        /**
         * Deep enough to find the outcome that resolved (or did not resolve) a
         * failure, shallow enough that the scan stays trivial.
         */
        const val AUDIT_SCAN_DEPTH = 60
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Architecture.md § 6.3: the banner persists "until the user dismisses **or**
 * restore succeeds".
 *
 * So a failure is not enough on its own — what matters is whether anything
 * newer resolved it. Each pair is scanned newest-first and the first entry of
 * either kind decides, which means a banner disappears by itself once the
 * device is verifiably back to normal instead of outliving the problem.
 *
 * @receiver audit entries, newest first.
 */
internal fun List<AuditLogEntry>.activeBanners(): List<DashboardBanner> = listOfNotNull(
    unresolvedFailure(
        failure = AuditEventType.RESTORATION_FAILED,
        resolvedBy = setOf(AuditEventType.RESTORATION_COMPLETED),
        kind = DashboardBanner.Kind.RESTORE_FAILED,
    ),
    unresolvedFailure(
        failure = AuditEventType.DND_BYPASS_INEFFECTIVE,
        // A later bypass attempt that did *not* produce an INEFFECTIVE entry is
        // the only evidence available that DND is answering us again.
        resolvedBy = setOf(AuditEventType.DND_BYPASS_ATTEMPTED),
        kind = DashboardBanner.Kind.DND_INEFFECTIVE,
    ),
)

private fun List<AuditLogEntry>.unresolvedFailure(
    failure: AuditEventType,
    resolvedBy: Set<AuditEventType>,
    kind: DashboardBanner.Kind,
): DashboardBanner? {
    val decisive = firstOrNull { it.type == failure || it.type in resolvedBy } ?: return null
    if (decisive.type != failure) return null

    return DashboardBanner(
        entryId = decisive.id,
        kind = kind,
        message = decisive.message,
        timestampEpochMs = decisive.timestampEpochMs,
    )
}
