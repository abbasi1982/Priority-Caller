package com.elham.priorityringer.domain.escalation

import com.elham.priorityringer.domain.model.EscalationThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sliding-window escalation (FR5, Architecture.md § 8, § 14).
 *
 * Escalation is the app's loudest action — maximum ring volume plus a
 * full-screen alert over the lock screen. Firing it one call early is intrusive;
 * firing it one call late defeats the feature. The boundaries therefore have to
 * be pinned down exactly, which is why [EscalationPolicy] takes timestamps and
 * "now" as arguments instead of owning a clock.
 */
class EscalationPolicyTest {

    private val policy = EscalationPolicy()

    private val now = 1_700_000_000_000L
    private val defaults = EscalationThresholds()

    private fun minutesAgo(minutes: Long): Long = now - minutes * 60_000L

    // -----------------------------------------------------------------------
    // Window boundaries
    // -----------------------------------------------------------------------

    @Test
    fun `a call landing exactly on the window edge is inside the window`() {
        val exactlyOnEdge = now - defaults.primaryWindowMinutes * 60_000L

        val decision = policy.evaluate(listOf(exactlyOnEdge), now, defaults)

        assertTrue(
            "a call at exactly ${defaults.primaryWindowMinutes} minutes ago must count; " +
                "an exclusive bound would make the threshold silently one call stricter",
            decision.escalate,
        )
    }

    @Test
    fun `a call one millisecond outside the window is excluded`() {
        val justOutside = now - defaults.primaryWindowMinutes * 60_000L - 1

        val decision = policy.evaluate(listOf(justOutside), now, defaults)

        assertFalse(decision.escalate)
    }

    @Test
    fun `a call one millisecond outside the window is not counted in the primary window`() {
        val justOutside = now - defaults.primaryWindowMinutes * 60_000L - 1

        val decision = policy.evaluate(listOf(justOutside), now, defaults)

        assertEquals(
            "only the current call should be counted",
            1,
            decision.countInPrimaryWindow,
        )
    }

    // -----------------------------------------------------------------------
    // The current call counts as one
    // -----------------------------------------------------------------------

    @Test
    fun `the first ever call does not escalate, because two-in-five-minutes needs a prior call`() {
        val decision = policy.evaluate(previousCallTimestampsMs = emptyList(), nowMs = now, thresholds = defaults)

        assertFalse(decision.escalate)
    }

    @Test
    fun `one prior call is enough for a threshold of two, because the current call is the second`() {
        val decision = policy.evaluate(listOf(minutesAgo(2)), now, defaults)

        assertTrue(
            "'2 calls in 5 minutes' must mean this call plus one earlier one, not two earlier ones",
            decision.escalate,
        )
    }

    @Test
    fun `the reported primary count includes the current call`() {
        val decision = policy.evaluate(listOf(minutesAgo(1), minutesAgo(2)), now, defaults)

        assertEquals(3, decision.countInPrimaryWindow)
    }

    // -----------------------------------------------------------------------
    // Trigger precedence
    // -----------------------------------------------------------------------

    @Test
    fun `when both windows are satisfied the primary window is reported as the trigger`() {
        // 3 calls inside 5 minutes satisfies 2-in-5 and 3-in-10 simultaneously.
        val decision = policy.evaluate(listOf(minutesAgo(1), minutesAgo(2)), now, defaults)

        assertEquals(
            "the tighter window is the more specific explanation and is what the audit " +
                "log should name",
            EscalationDecision.Trigger.PRIMARY_WINDOW,
            decision.trigger,
        )
    }

    @Test
    fun `the secondary window can trigger on its own when no call is inside the primary one`() {
        // 6 and 7 minutes ago: outside the 5-minute window, inside the 10-minute one.
        val decision = policy.evaluate(listOf(minutesAgo(6), minutesAgo(7)), now, defaults)

        assertEquals(EscalationDecision.Trigger.SECONDARY_WINDOW, decision.trigger)
    }

    @Test
    fun `a secondary-window trigger still escalates`() {
        val decision = policy.evaluate(listOf(minutesAgo(6), minutesAgo(7)), now, defaults)

        assertTrue(decision.escalate)
    }

    @Test
    fun `no trigger is reported when neither threshold is met`() {
        val decision = policy.evaluate(listOf(minutesAgo(8)), now, defaults)

        assertEquals(EscalationDecision.Trigger.NONE, decision.trigger)
    }

    // -----------------------------------------------------------------------
    // Disabled
    // -----------------------------------------------------------------------

    @Test
    fun `escalation disabled short-circuits even when the thresholds are comfortably exceeded`() {
        val manyCalls = listOf(minutesAgo(1), minutesAgo(2), minutesAgo(3), minutesAgo(4))

        val decision = policy.evaluate(manyCalls, now, defaults.copy(enabled = false))

        assertFalse(decision.escalate)
    }

    @Test
    fun `escalation disabled reports zero counts, proving no window was even evaluated`() {
        val manyCalls = listOf(minutesAgo(1), minutesAgo(2), minutesAgo(3), minutesAgo(4))

        val decision = policy.evaluate(manyCalls, now, defaults.copy(enabled = false))

        assertEquals(EscalationDecision.NOT_ESCALATED, decision)
    }

    // -----------------------------------------------------------------------
    // Clock safety
    // -----------------------------------------------------------------------

    @Test
    fun `future-dated timestamps are ignored, so a backwards clock change cannot manufacture escalation`() {
        val fromTheFuture = listOf(now + 60_000L, now + 120_000L, now + 180_000L)

        val decision = policy.evaluate(fromTheFuture, now, defaults)

        assertFalse(
            "a device whose clock moved backwards (NTP correction, user edit, timezone " +
                "database update) would otherwise see stored timestamps in the future and " +
                "escalate on a first call",
            decision.escalate,
        )
    }

    @Test
    fun `a timestamp exactly equal to now is counted, because it is the boundary of the present`() {
        val decision = policy.evaluate(listOf(now), now, defaults)

        assertEquals(2, decision.countInPrimaryWindow)
    }

    // -----------------------------------------------------------------------
    // Pruning
    // -----------------------------------------------------------------------

    @Test
    fun `pruneBeforeMs uses the widest window so rows the other window still needs survive`() {
        // Deliberately inverted: the "primary" window is the wider one here.
        val inverted = defaults.copy(primaryWindowMinutes = 30, secondaryWindowMinutes = 10)

        assertEquals(
            "pruning to the secondary window would delete rows the 30-minute window needs",
            now - 30 * 60_000L,
            policy.pruneBeforeMs(now, inverted),
        )
    }

    @Test
    fun `pruneBeforeMs uses the secondary window when that is the wider one`() {
        assertEquals(
            now - defaults.secondaryWindowMinutes * 60_000L,
            policy.pruneBeforeMs(now, defaults),
        )
    }

    @Test
    fun `a timestamp at the prune boundary is still inside the widest window`() {
        val cutoff = policy.pruneBeforeMs(now, defaults)

        val decision = policy.evaluate(
            listOf(cutoff, cutoff + 1),
            now,
            defaults.copy(secondaryCallCount = 3),
        )

        assertEquals(
            "a row kept by pruneBeforeMs must still be countable, or pruning and " +
                "counting would disagree at the edge",
            3,
            decision.countInSecondaryWindow,
        )
    }
}
