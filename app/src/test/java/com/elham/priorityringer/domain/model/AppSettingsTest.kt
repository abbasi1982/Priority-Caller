package com.elham.priorityringer.domain.model

import com.elham.priorityringer.domain.model.AppSettings.Companion.MAX_RESTORE_TIMEOUT_SECONDS
import com.elham.priorityringer.domain.model.AppSettings.Companion.MIN_RESTORE_TIMEOUT_SECONDS
import com.elham.priorityringer.domain.model.AppSettings.Companion.MIN_VOLUME_PERCENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings clamping (Architecture.md § 3, § 4 "Validate ranges", § 14).
 *
 * `validated()` is the only thing standing between a slider, a restored backup
 * or a future migration and a configuration that makes the app quietly useless
 * — a 0% "priority" ring volume, or a restore timeout so long the phone stays
 * loud for an hour after the call ends.
 */
class AppSettingsTest {

    // -----------------------------------------------------------------------
    // Ring volume
    // -----------------------------------------------------------------------

    @Test
    fun `volume percent cannot be clamped below the minimum, because a silent priority ring is a contradiction`() {
        val validated = AppSettings(ringtoneVolumePercent = 0).validated()

        assertEquals(
            "0% would let the user configure the app into appearing armed while doing nothing",
            MIN_VOLUME_PERCENT,
            validated.ringtoneVolumePercent,
        )
    }

    @Test
    fun `a negative volume percent is raised to the minimum rather than wrapping`() {
        assertEquals(
            MIN_VOLUME_PERCENT,
            AppSettings(ringtoneVolumePercent = -25).validated().ringtoneVolumePercent,
        )
    }

    @Test
    fun `volume percent exactly at the minimum is left alone`() {
        assertEquals(
            MIN_VOLUME_PERCENT,
            AppSettings(ringtoneVolumePercent = MIN_VOLUME_PERCENT).validated().ringtoneVolumePercent,
        )
    }

    @Test
    fun `volume percent one below the minimum is clamped up`() {
        assertEquals(
            MIN_VOLUME_PERCENT,
            AppSettings(ringtoneVolumePercent = MIN_VOLUME_PERCENT - 1)
                .validated().ringtoneVolumePercent,
        )
    }

    @Test
    fun `volume percent of exactly 100 is left alone`() {
        assertEquals(100, AppSettings(ringtoneVolumePercent = 100).validated().ringtoneVolumePercent)
    }

    @Test
    fun `volume percent above 100 is clamped down to 100`() {
        assertEquals(100, AppSettings(ringtoneVolumePercent = 400).validated().ringtoneVolumePercent)
    }

    // -----------------------------------------------------------------------
    // Restore timeout
    // -----------------------------------------------------------------------

    @Test
    fun `a restore timeout below the floor is raised, so the watchdog cannot fire mid-ring`() {
        assertEquals(
            MIN_RESTORE_TIMEOUT_SECONDS,
            AppSettings(autoRestoreTimeoutSeconds = 1).validated().autoRestoreTimeoutSeconds,
        )
    }

    @Test
    fun `a restore timeout exactly at the floor is left alone`() {
        assertEquals(
            MIN_RESTORE_TIMEOUT_SECONDS,
            AppSettings(autoRestoreTimeoutSeconds = MIN_RESTORE_TIMEOUT_SECONDS)
                .validated().autoRestoreTimeoutSeconds,
        )
    }

    @Test
    fun `a restore timeout exactly at the ceiling is left alone`() {
        assertEquals(
            MAX_RESTORE_TIMEOUT_SECONDS,
            AppSettings(autoRestoreTimeoutSeconds = MAX_RESTORE_TIMEOUT_SECONDS)
                .validated().autoRestoreTimeoutSeconds,
        )
    }

    @Test
    fun `a restore timeout above the ceiling is clamped, so the device cannot stay modified for hours`() {
        assertEquals(
            MAX_RESTORE_TIMEOUT_SECONDS,
            AppSettings(autoRestoreTimeoutSeconds = 86_400).validated().autoRestoreTimeoutSeconds,
        )
    }

    // -----------------------------------------------------------------------
    // Escalation thresholds
    // -----------------------------------------------------------------------

    /**
     * The invariant that keeps the ladder a ladder.
     *
     * The alarm tier is evaluated first, so anything that satisfies it also
     * satisfies the tier below. Set it at or under that tier and the quieter
     * response becomes unreachable — the user would have configured a rung they
     * can never land on.
     */
    @Test
    fun `an alarm call count at or below the primary one is raised above it`() {
        val validated = AppSettings(
            escalation = EscalationThresholds(primaryCallCount = 4, alarmCallCount = 4),
        ).validated()

        assertEquals(5, validated.escalation.alarmCallCount)
    }

    @Test
    fun `an alarm call count below the primary one is raised above it`() {
        val validated = AppSettings(
            escalation = EscalationThresholds(primaryCallCount = 6, alarmCallCount = 2),
        ).validated()

        assertEquals(7, validated.escalation.alarmCallCount)
    }

    @Test
    fun `an alarm call count comfortably above the primary one is left alone`() {
        val validated = AppSettings(
            escalation = EscalationThresholds(primaryCallCount = 2, alarmCallCount = 5),
        ).validated()

        assertEquals(5, validated.escalation.alarmCallCount)
    }

    @Test
    fun `an alarm window below one minute is raised`() {
        val validated = AppSettings(
            escalation = EscalationThresholds(alarmWindowMinutes = 0),
        ).validated()

        assertEquals(1, validated.escalation.alarmWindowMinutes)
    }

    @Test
    fun `an alarm window above sixty minutes is clamped down`() {
        val validated = AppSettings(
            escalation = EscalationThresholds(alarmWindowMinutes = 900),
        ).validated()

        assertEquals(60, validated.escalation.alarmWindowMinutes)
    }

    @Test
    fun `a primary call count below two is raised, because one call can never be a repeat call`() {
        val validated = AppSettings(
            escalation = EscalationThresholds(primaryCallCount = 1),
        ).validated()

        assertEquals(2, validated.escalation.primaryCallCount)
    }

    @Test
    fun `a primary call count above ten is clamped down`() {
        val validated = AppSettings(
            escalation = EscalationThresholds(primaryCallCount = 99),
        ).validated()

        assertEquals(10, validated.escalation.primaryCallCount)
    }

    @Test
    fun `a zero-minute primary window is raised to one minute`() {
        val validated = AppSettings(
            escalation = EscalationThresholds(primaryWindowMinutes = 0),
        ).validated()

        assertEquals(1, validated.escalation.primaryWindowMinutes)
    }

    @Test
    fun `a primary window above sixty minutes is clamped down`() {
        val validated = AppSettings(
            escalation = EscalationThresholds(primaryWindowMinutes = 600),
        ).validated()

        assertEquals(60, validated.escalation.primaryWindowMinutes)
    }

    @Test
    fun `a secondary call count below two is raised`() {
        val validated = AppSettings(
            escalation = EscalationThresholds(secondaryCallCount = 0),
        ).validated()

        assertEquals(2, validated.escalation.secondaryCallCount)
    }

    @Test
    fun `a secondary call count above ten is clamped down`() {
        val validated = AppSettings(
            escalation = EscalationThresholds(secondaryCallCount = 50),
        ).validated()

        assertEquals(10, validated.escalation.secondaryCallCount)
    }

    @Test
    fun `a zero-minute secondary window is raised to one minute`() {
        val validated = AppSettings(
            escalation = EscalationThresholds(secondaryWindowMinutes = 0),
        ).validated()

        assertEquals(1, validated.escalation.secondaryWindowMinutes)
    }

    @Test
    fun `a secondary window above one hundred and twenty minutes is clamped down`() {
        val validated = AppSettings(
            escalation = EscalationThresholds(secondaryWindowMinutes = 10_000),
        ).validated()

        assertEquals(120, validated.escalation.secondaryWindowMinutes)
    }

    // -----------------------------------------------------------------------
    // Non-numeric fields and idempotency
    // -----------------------------------------------------------------------

    @Test
    fun `validation never flips the escalation enabled flag, which is a user choice not a range`() {
        assertFalse(
            AppSettings(escalation = EscalationThresholds(enabled = false))
                .validated().escalation.enabled,
        )
    }

    @Test
    fun `validation never flips the logging flag`() {
        assertFalse(AppSettings(loggingEnabled = false).validated().loggingEnabled)
    }

    @Test
    fun `validation never rewrites the chosen DND strategy`() {
        assertEquals(
            DndBypassStrategy.DISABLE_DND_TEMPORARILY,
            AppSettings(dndBypassStrategy = DndBypassStrategy.DISABLE_DND_TEMPORARILY)
                .validated().dndBypassStrategy,
        )
    }

    @Test
    fun `the defaults are already valid, so a fresh install needs no correction`() {
        val defaults = AppSettings()

        assertEquals(defaults, defaults.validated())
    }

    @Test
    fun `validation is idempotent, so repeated saves cannot drift`() {
        val wild = AppSettings(
            ringtoneVolumePercent = -5,
            autoRestoreTimeoutSeconds = 100_000,
            escalation = EscalationThresholds(
                primaryCallCount = 0,
                primaryWindowMinutes = 0,
                secondaryCallCount = 900,
                secondaryWindowMinutes = 900,
            ),
        )

        val once = wild.validated()

        assertEquals(once, once.validated())
    }

    @Test
    fun `every clamped field of a wildly invalid settings object lands inside its range`() {
        val validated = AppSettings(
            ringtoneVolumePercent = -5,
            autoRestoreTimeoutSeconds = 100_000,
            escalation = EscalationThresholds(
                primaryCallCount = 0,
                primaryWindowMinutes = 0,
                secondaryCallCount = 900,
                secondaryWindowMinutes = 900,
            ),
        ).validated()

        assertTrue(validated.ringtoneVolumePercent in MIN_VOLUME_PERCENT..100)
        assertTrue(
            validated.autoRestoreTimeoutSeconds in
                MIN_RESTORE_TIMEOUT_SECONDS..MAX_RESTORE_TIMEOUT_SECONDS,
        )
        assertTrue(validated.escalation.primaryCallCount in 2..10)
        assertTrue(validated.escalation.primaryWindowMinutes in 1..60)
        assertTrue(validated.escalation.secondaryCallCount in 2..10)
        assertTrue(validated.escalation.secondaryWindowMinutes in 1..120)
    }
}
