package com.elham.priorityringer.domain.escalation

import com.elham.priorityringer.domain.model.EscalationThresholds
import kotlin.math.max

/**
 * Decision on whether a repeat caller has earned the louder treatment (FR5).
 */
data class EscalationDecision(
    val level: Level,
    /** Calls seen inside the shorter (primary) window, including the current one. */
    val countInPrimaryWindow: Int,
    /** Calls seen inside the longer (secondary) window, including the current one. */
    val countInSecondaryWindow: Int,
    /** Calls seen inside the alarm window, including the current one. */
    val countInAlarmWindow: Int,
    val trigger: Trigger,
) {
    /**
     * How hard the app should try, not merely whether.
     *
     * The distinction the app exists to make is between "someone is calling"
     * and "someone is trying really hard to reach me". [RAISE] is the former
     * taken seriously — maximum volume and a full-screen alert. [ALARM] is the
     * latter: it additionally plays the ringtone on the alarm stream *even when
     * the ringer is working perfectly well*, which is deliberately not what a
     * phone call sounds like.
     */
    enum class Level { NONE, RAISE, ALARM }

    enum class Trigger { NONE, PRIMARY_WINDOW, SECONDARY_WINDOW, ALARM_WINDOW }

    /** Kept so the many call sites that only ask "did anything escalate?" still read well. */
    val escalate: Boolean get() = level != Level.NONE

    companion object {
        val NOT_ESCALATED = EscalationDecision(Level.NONE, 0, 0, 0, Trigger.NONE)
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
        val alarmCutoff = nowMs - thresholds.alarmWindowMinutes * MILLIS_PER_MINUTE

        // `>=` on the cutoff: a call at exactly the window edge is inside it.
        // Future-dated entries are ignored — a clock change backwards must not
        // manufacture an escalation.
        val inPrimary = previousCallTimestampsMs.count { it in primaryCutoff..nowMs } + 1
        val inSecondary = previousCallTimestampsMs.count { it in secondaryCutoff..nowMs } + 1
        val inAlarm = previousCallTimestampsMs.count { it in alarmCutoff..nowMs } + 1

        val alarmHit = inAlarm >= thresholds.alarmCallCount
        val primaryHit = inPrimary >= thresholds.primaryCallCount
        val secondaryHit = inSecondary >= thresholds.secondaryCallCount

        // **Strongest first.** The order here is the whole ladder.
        //
        // The original two tiers were written `primary else secondary`, which
        // makes SECONDARY the tier that fires only when PRIMARY did not — so a
        // burst of calls reports PRIMARY while the same number spread out
        // reports SECONDARY. That reads backwards for a ladder, and it is
        // exactly why the alarm tier could not simply reuse the secondary one.
        // Checking the loudest response first is what stops the same mistake
        // being made one rung up: if alarm were checked last it would be
        // unreachable, because any input that satisfies it also satisfies
        // primary.
        val trigger = when {
            alarmHit -> EscalationDecision.Trigger.ALARM_WINDOW
            primaryHit -> EscalationDecision.Trigger.PRIMARY_WINDOW
            secondaryHit -> EscalationDecision.Trigger.SECONDARY_WINDOW
            else -> EscalationDecision.Trigger.NONE
        }

        val level = when {
            alarmHit -> EscalationDecision.Level.ALARM
            primaryHit || secondaryHit -> EscalationDecision.Level.RAISE
            else -> EscalationDecision.Level.NONE
        }

        return EscalationDecision(
            level = level,
            countInPrimaryWindow = inPrimary,
            countInSecondaryWindow = inSecondary,
            countInAlarmWindow = inAlarm,
            trigger = trigger,
        )
    }

    /** The count and window that actually produced [decision], for reporting. */
    fun reportedCountAndWindow(
        decision: EscalationDecision,
        thresholds: EscalationThresholds,
    ): Pair<Int, Int> = when (decision.trigger) {
        EscalationDecision.Trigger.ALARM_WINDOW ->
            decision.countInAlarmWindow to thresholds.alarmWindowMinutes

        EscalationDecision.Trigger.SECONDARY_WINDOW ->
            decision.countInSecondaryWindow to thresholds.secondaryWindowMinutes

        EscalationDecision.Trigger.PRIMARY_WINDOW,
        EscalationDecision.Trigger.NONE,
        -> decision.countInPrimaryWindow to thresholds.primaryWindowMinutes
    }

    /**
     * Timestamps older than this can be deleted — nothing can ever need them
     * again. Used to keep `escalation_events` from growing without bound.
     */
    fun pruneBeforeMs(nowMs: Long, thresholds: EscalationThresholds): Long {
        // Every window, not just the two obvious ones.
        //
        // `ApplyPriorityRingUseCase` uses this value both to *fetch* the
        // timestamps it evaluates and to *delete* the ones it no longer needs.
        // A window missing from this max would have its own history pruned out
        // from under it on every single call, and the tier would simply never
        // fire — no error, no audit row, nothing to notice.
        val widestWindowMinutes = max(
            thresholds.alarmWindowMinutes,
            max(thresholds.primaryWindowMinutes, thresholds.secondaryWindowMinutes),
        )
        return nowMs - widestWindowMinutes * MILLIS_PER_MINUTE
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
