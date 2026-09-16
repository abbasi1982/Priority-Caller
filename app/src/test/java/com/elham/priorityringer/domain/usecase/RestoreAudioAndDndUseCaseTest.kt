package com.elham.priorityringer.domain.usecase

import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.FailureReason
import com.elham.priorityringer.domain.model.InterruptionFilter
import com.elham.priorityringer.domain.model.Outcome
import com.elham.priorityringer.domain.model.RingerMode
import com.elham.priorityringer.domain.model.VolumeSnapshot
import com.elham.priorityringer.fake.CallRecorder
import com.elham.priorityringer.fake.FakeAlertPort
import com.elham.priorityringer.fake.FakeAudioPort
import com.elham.priorityringer.fake.FakeAuditRepository
import com.elham.priorityringer.fake.FakeClock
import com.elham.priorityringer.fake.FakeDndPort
import com.elham.priorityringer.fake.FakeRestoreRepository
import com.elham.priorityringer.fake.FakeSchedulerPort
import com.elham.priorityringer.fake.testSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restore (FR3, FR4, Architecture.md § 6.3, § 17.5, § A.3).
 *
 * The failure this code exists to prevent is not a quiet ring. It is a phone
 * left permanently off Do Not Disturb at maximum volume because the call-end
 * callback never arrived or the process was killed — strictly worse than the app
 * not working at all.
 *
 * Three triggers all route here, so the tests below are mostly about one
 * property: **running restore more times than necessary must be harmless, and
 * running it once must be enough.**
 */
class RestoreAudioAndDndUseCaseTest {

    private val recorder = CallRecorder()
    private val audio = FakeAudioPort(recorder)
    private val dnd = FakeDndPort(recorder)
    private val alert = FakeAlertPort(recorder)
    private val scheduler = FakeSchedulerPort(recorder)
    private val restoreRepository = FakeRestoreRepository(recorder)
    private val audit = FakeAuditRepository()
    private val clock = FakeClock()

    private val useCase = RestoreAudioAndDndUseCase(
        audio = audio,
        dnd = dnd,
        alert = alert,
        scheduler = scheduler,
        restoreRepository = restoreRepository,
        audit = audit,
        clock = clock,
    )

    /**
     * The device as it looks mid-priority-call: loud, ringing, DND relaxed —
     * with a snapshot on disk remembering the vibrate/quiet/DND state it had.
     */
    private fun givenMutatedPhoneWithPendingSnapshot() {
        audio.ringerMode = RingerMode.NORMAL
        audio.currentVolumeIndex = 15
        audio.maxVolumeIndex = 15
        dnd.policyAccess = true
        dnd.filter = InterruptionFilter.ALL
        restoreRepository.seed(
            testSnapshot(
                ringerMode = RingerMode.VIBRATE,
                ringVolume = VolumeSnapshot(current = 4, max = 15),
                interruptionFilter = InterruptionFilter.PRIORITY,
                zenRuleId = "rule-7",
                capturedAtEpochMs = clock.now,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Nothing to do
    // -----------------------------------------------------------------------

    @Test
    fun `with no pending snapshot the restore reports NOTHING_TO_DO`() = runTest {
        val outcome = useCase(RestoreTrigger.WATCHDOG_TIMEOUT)

        assertEquals(
            FailureReason.NOTHING_TO_DO,
            (outcome as Outcome.Failure).reason,
        )
    }

    @Test
    fun `with no pending snapshot nothing on the device is touched`() = runTest {
        useCase(RestoreTrigger.WATCHDOG_TIMEOUT)

        assertTrue(
            "the watchdog routinely fires after a normal restore has already run; " +
                "it must not re-apply anything. Recorded: ${recorder.calls}",
            recorder.deviceMutations.isEmpty(),
        )
    }

    @Test
    fun `with no pending snapshot the watchdog is still cancelled, because it has nothing left to do`() =
        runTest {
            useCase(RestoreTrigger.WATCHDOG_TIMEOUT)

            assertEquals(1, scheduler.cancelCount)
        }

    @Test
    fun `a routine watchdog with nothing pending writes no audit row, so the log stays useful`() =
        runTest {
            useCase(RestoreTrigger.WATCHDOG_TIMEOUT)

            assertTrue(
                "this happens after every single call; logging it would fill the " +
                    "500-entry cap with noise",
                audit.entries.isEmpty(),
            )
        }

    // -----------------------------------------------------------------------
    // Idempotency (§ A.3)
    // -----------------------------------------------------------------------

    @Test
    fun `the first restore succeeds`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()

        val outcome = useCase(RestoreTrigger.CALL_ENDED)

        assertTrue(outcome.isSuccess)
    }

    @Test
    fun `running restore a second time is safe and reports NOTHING_TO_DO`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()
        useCase(RestoreTrigger.CALL_ENDED)

        val second = useCase(RestoreTrigger.WATCHDOG_TIMEOUT)

        assertEquals(
            "three independent triggers mean double restores are normal, not an error",
            FailureReason.NOTHING_TO_DO,
            (second as Outcome.Failure).reason,
        )
    }

    @Test
    fun `a second restore does not re-apply the snapshot over changes the user has since made`() =
        runTest {
            givenMutatedPhoneWithPendingSnapshot()
            useCase(RestoreTrigger.CALL_ENDED)
            audio.ringerMode = RingerMode.SILENT
            recorder.reset()

            useCase(RestoreTrigger.WATCHDOG_TIMEOUT)

            assertEquals(
                "the user put the phone on silent after the call; restore must not undo that",
                RingerMode.SILENT,
                audio.ringerMode,
            )
            assertTrue(recorder.deviceMutations.isEmpty())
        }

    // -----------------------------------------------------------------------
    // What a successful restore puts back
    // -----------------------------------------------------------------------

    @Test
    fun `the ringer mode is put back to the snapshot value`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()

        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(RingerMode.VIBRATE, audio.ringerMode)
    }

    @Test
    fun `the volume is put back by raw index, not by percent, so rounding cannot drift it`() =
        runTest {
            givenMutatedPhoneWithPendingSnapshot()

            useCase(RestoreTrigger.CALL_ENDED)

            assertEquals(listOf(4), audio.volumeRawRequests)
            assertTrue(audio.volumePercentRequests.isEmpty())
        }

    @Test
    fun `the interruption filter and zen rule id are both handed back to the DND port`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()

        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(listOf(InterruptionFilter.PRIORITY to "rule-7"), dnd.restoreRequests)
    }

    @Test
    fun `the escalation alert is dismissed, so it cannot outlive the call`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()

        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(1, alert.dismissCount)
    }

    @Test
    fun `a successful restore is audited as RESTORATION_COMPLETED`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()

        useCase(RestoreTrigger.CALL_ENDED)

        assertTrue(audit.hasType(AuditEventType.RESTORATION_COMPLETED))
    }

    @Test
    fun `a successful restore cancels the watchdog`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()

        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(1, scheduler.cancelCount)
    }

    @Test
    fun `a successful restore clears the pending snapshot`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()

        useCase(RestoreTrigger.CALL_ENDED)

        assertNull(restoreRepository.pending)
    }

    // -----------------------------------------------------------------------
    // Partial failure (§ 6.3)
    // -----------------------------------------------------------------------

    @Test
    fun `a failed DND restore does not prevent the volume from being restored`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()
        dnd.restoreResult = Outcome.Failure(FailureReason.SECURITY_EXCEPTION)

        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(
            "leaving the phone loud because DND could not be put back would compound " +
                "the problem rather than contain it",
            4,
            audio.currentVolumeIndex,
        )
    }

    @Test
    fun `a failed DND restore does not prevent the ringer mode from being restored`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()
        dnd.restoreResult = Outcome.Failure(FailureReason.SECURITY_EXCEPTION)

        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(RingerMode.VIBRATE, audio.ringerMode)
    }

    @Test
    fun `a partial restore failure is audited as RESTORATION_FAILED`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()
        dnd.restoreResult = Outcome.Failure(FailureReason.SECURITY_EXCEPTION)

        useCase(RestoreTrigger.CALL_ENDED)

        assertTrue(audit.hasType(AuditEventType.RESTORATION_FAILED))
        assertFalse(audit.hasType(AuditEventType.RESTORATION_COMPLETED))
    }

    @Test
    fun `a partial restore failure is marked unrecoverable, which is what keeps the dashboard banner up`() =
        runTest {
            givenMutatedPhoneWithPendingSnapshot()
            dnd.restoreResult = Outcome.Failure(FailureReason.SECURITY_EXCEPTION)

            useCase(RestoreTrigger.CALL_ENDED)

            assertEquals(
                false,
                audit.lastOf(AuditEventType.RESTORATION_FAILED)?.recoverable,
            )
        }

    @Test
    fun `the snapshot is cleared even when the restore only partly succeeded`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()
        dnd.restoreResult = Outcome.Failure(FailureReason.SECURITY_EXCEPTION)

        useCase(RestoreTrigger.CALL_ENDED)

        assertNull(
            "a retained snapshot would be re-applied on the next trigger and could " +
                "stomp on changes the user has since made by hand",
            restoreRepository.pending,
        )
    }

    @Test
    fun `the watchdog is cancelled even when the restore only partly succeeded`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()
        dnd.restoreResult = Outcome.Failure(FailureReason.SECURITY_EXCEPTION)

        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(1, scheduler.cancelCount)
    }

    @Test
    fun `a partial restore failure is reported to the caller, not swallowed`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()
        dnd.restoreResult = Outcome.Failure(FailureReason.SECURITY_EXCEPTION)

        val outcome = useCase(RestoreTrigger.CALL_ENDED)

        assertFalse(outcome.isSuccess)
    }

    // -----------------------------------------------------------------------
    // Things that are skipped, not failed
    // -----------------------------------------------------------------------

    @Test
    fun `volume restore is skipped on a fixed-volume device, because it was never changed`() =
        runTest {
            givenMutatedPhoneWithPendingSnapshot()
            audio.volumeFixed = true

            useCase(RestoreTrigger.CALL_ENDED)

            assertTrue(audio.volumeRawRequests.isEmpty())
        }

    @Test
    fun `a skipped volume restore is not counted as a restore failure`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()
        audio.volumeFixed = true

        val outcome = useCase(RestoreTrigger.CALL_ENDED)

        assertTrue(outcome.isSuccess)
        assertTrue(audit.hasType(AuditEventType.RESTORATION_COMPLETED))
    }

    @Test
    fun `DND restore is skipped without policy access, because DND was never changed either`() =
        runTest {
            givenMutatedPhoneWithPendingSnapshot()
            dnd.policyAccess = false

            useCase(RestoreTrigger.CALL_ENDED)

            assertTrue(dnd.restoreRequests.isEmpty())
        }

    @Test
    fun `a skipped DND restore is not counted as a restore failure`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()
        dnd.policyAccess = false

        val outcome = useCase(RestoreTrigger.CALL_ENDED)

        assertTrue(
            "reporting a failure here would show the user a permanent 'restore failed' " +
                "banner for a permission they deliberately withheld",
            outcome.isSuccess,
        )
    }

    // -----------------------------------------------------------------------
    // Cold-start reconciliation (§ A.3 trigger 3)
    // -----------------------------------------------------------------------

    @Test
    fun `a cold-start reconciliation logs STALE_RESTORE_RECOVERED, because the process had been killed`() =
        runTest {
            givenMutatedPhoneWithPendingSnapshot()
            clock.advanceSeconds(300)

            useCase(RestoreTrigger.COLD_START_RECONCILIATION)

            assertTrue(audit.hasType(AuditEventType.STALE_RESTORE_RECOVERED))
        }

    @Test
    fun `a cold-start reconciliation still restores the device`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()
        clock.advanceSeconds(300)

        useCase(RestoreTrigger.COLD_START_RECONCILIATION)

        assertEquals(RingerMode.VIBRATE, audio.ringerMode)
    }

    @Test
    fun `an ordinary call-end restore does not claim anything was stale`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()

        useCase(RestoreTrigger.CALL_ENDED)

        assertFalse(audit.hasType(AuditEventType.STALE_RESTORE_RECOVERED))
    }

    @Test
    fun `a cold start with nothing pending logs nothing, because that is the normal case`() =
        runTest {
            useCase(RestoreTrigger.COLD_START_RECONCILIATION)

            assertFalse(audit.hasType(AuditEventType.STALE_RESTORE_RECOVERED))
        }
}
