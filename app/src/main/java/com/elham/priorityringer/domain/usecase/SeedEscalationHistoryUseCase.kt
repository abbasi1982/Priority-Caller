package com.elham.priorityringer.domain.usecase

import com.elham.priorityringer.domain.model.AppSettings
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.PriorityContact
import com.elham.priorityringer.domain.port.Clock
import com.elham.priorityringer.domain.repository.AuditRepository
import com.elham.priorityringer.domain.repository.EscalationRepository
import javax.inject.Inject

/**
 * Plant enough recent call history that the *next* call from [contact] escalates
 * (FR6, Architecture.md § 9 "simulate escalation").
 *
 * Exists so Test Mode does not have to reach for [EscalationRepository]
 * directly. Architecture.md § 2 routes presentation through use cases; a
 * ViewModel writing to a repository compiles and respects the dependency rule,
 * but it puts a write path in the UI layer that production code never uses, and
 * that is exactly the sort of divergence that makes a test feature stop
 * reflecting reality.
 *
 * It deliberately seeds **real** rows through the ordinary repository rather
 * than faking a decision: § 9 requires simulation to exercise the same logic a
 * real call does. `EscalationPolicy` then evaluates genuinely — the simulation
 * can still decline to escalate if the thresholds say so, which is the honest
 * outcome and a real test of the configuration.
 */
class SeedEscalationHistoryUseCase @Inject constructor(
    private val escalationRepository: EscalationRepository,
    private val audit: AuditRepository,
    private val clock: Clock,
) {
    /**
     * Seeds one fewer call than the primary threshold, because
     * `EscalationPolicy` counts the call currently arriving as one of them.
     *
     * Timestamps are spaced backwards from now and kept comfortably inside the
     * primary window, so the seeded history is genuinely eligible rather than
     * sitting on a boundary where a slow test run could evict it.
     */
    suspend operator fun invoke(contact: PriorityContact, settings: AppSettings): Int {
        val thresholds = settings.escalation
        val needed = (thresholds.primaryCallCount - 1).coerceAtLeast(0)
        if (needed == 0) return 0

        val now = clock.nowEpochMs()
        val windowMs = thresholds.primaryWindowMinutes * 60_000L
        // Spread across the first half of the window: recent enough to count,
        // spaced enough to look like distinct calls.
        val spacing = (windowMs / 2) / needed

        repeat(needed) { index ->
            escalationRepository.record(contact.matchKey, now - (index + 1) * spacing)
        }

        audit.log(
            type = AuditEventType.SIMULATION_RUN,
            message = "Test Mode seeded $needed earlier call(s) from " +
                "${contact.displayName} so the next simulated call meets the " +
                "escalation threshold.",
            relatedContactId = contact.id,
        )
        return needed
    }
}
