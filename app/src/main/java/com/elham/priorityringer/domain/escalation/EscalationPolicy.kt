package com.elham.priorityringer.domain.escalation

import com.elham.priorityringer.domain.model.EscalationThresholds
import kotlin.math.max

/**
 * Decision on whether a repeat caller has earned the louder treatment (FR5).
 */
data class EscalationDecision(
    val escalate: Boolean,
    /** Calls seen inside the shorter (primary) window, including the current one. */
    val countInPrimaryWindow: Int,
    /** Calls seen inside the longer (secondary) window, including the current one. */
    val countInSecondaryWindow: Int,
    val trigger: Trigger,
) {
    enum class Trigger { NONE, PRIMARY_WINDOW, SECONDARY_WINDOW }

    companion object {
        val NOT_ESCALATED = EscalationDecision(false, 0, 0, Trigger.NONE)
    }
}

/**
 * Pure sliding-window escalation rule.
 *
 * Deliberately has no repository, no clock, and no Android dependency: it takes
 * the timestamps it should consider and the current time as arguments. That is
 * what makes the boundary conditions (exact-edge timestamps, window eviction,
 * per-number isolation) testable without a device or a fake database —
 * Architecture.md § 14.
 *
 * Persistence of the timestamps themselves is `EscalationEventEntity`'s job
 * (§ 10); survival across process death is a storage concern, not a policy one.
 */
class EscalationPolicy {

    /**
     * @param previousCallTimestampsMs timestamps of earlier *matched* calls from
     *   this one number. Order irrelevant; entries outside every window are
     *   ignored, so callers may pass a generously wide slice.
     * @param nowMs the current call's timestamp. Counted as one of the calls —
     *   "2 calls within 5 minutes" means this one plus one earlier one.
     */
    fun evaluate(
        previousCallTimestampsMs: List<Long>,
        nowMs: Long,
        thresholds: EscalationThresholds,
    ): EscalationDecision {
        if (!thresholds.enabled) return EscalationDecision.NOT_ESCALATED

        val primaryCutoff = nowMs - thresholds.primaryWindowMinutes * MILLIS_PER_MINUTE
        val secondaryCutoff = nowMs - thresholds.secondaryWindowMinutes * MILLIS_PER_MINUTE

        // `>=` on the cutoff: a call at exactly the window edge is inside it.
        // Future-dated entries are ignored — a clock change backwards must not
        // manufacture an escalation.
        val inPrimary = previousCallTimestampsMs.count { it in primaryCutoff..nowMs } + 1
        val inSecondary = previousCallTimestampsMs.count { it in secondaryCutoff..nowMs } + 1

        val primaryHit = inPrimary >= thresholds.primaryCallCount
        val secondaryHit = inSecondary >= thresholds.secondaryCallCount

        val trigger = when {
            primaryHit -> EscalationDecision.Trigger.PRIMARY_WINDOW
            secondaryHit -> EscalationDecision.Trigger.SECONDARY_WINDOW
            else -> EscalationDecision.Trigger.NONE
        }

        return EscalationDecision(
            escalate = primaryHit || secondaryHit,
            countInPrimaryWindow = inPrimary,
            countInSecondaryWindow = inSecondary,
            trigger = trigger,
        )
    }

    /**
     * Timestamps older than this can be deleted — nothing can ever need them
     * again. Used to keep `escalation_events` from growing without bound.
     */
    fun pruneBeforeMs(nowMs: Long, thresholds: EscalationThresholds): Long {
        val widestWindowMinutes = max(
            thresholds.primaryWindowMinutes,
            thresholds.secondaryWindowMinutes,
        )
        return nowMs - widestWindowMinutes * MILLIS_PER_MINUTE
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
