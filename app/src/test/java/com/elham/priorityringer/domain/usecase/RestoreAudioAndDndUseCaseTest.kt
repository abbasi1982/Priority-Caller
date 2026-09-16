package com.elham.priorityringer.domain.usecase

import com.elham.priorityringer.domain.model.AppSettings
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
import com.elham.priorityringer.fake.FakeRingtonePlayerPort
import com.elham.priorityringer.fake.FakeSchedulerPort
import com.elham.priorityringer.fake.FakeSettingsRepository
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
    private val ringtonePlayer = FakeRingtonePlayerPort(recorder)
    private val scheduler = FakeSchedulerPort(recorder)
    private val restoreRepository = FakeRestoreRepository(recorder)
    private val settings = FakeSettingsRepository()
    private val audit = FakeAuditRepository()
    private val clock = FakeClock()

    private val useCase = RestoreAudioAndDndUseCase(
        audio = audio,
        dnd = dnd,
        alert = alert,
        ringtonePlayer = ringtonePlayer,
        scheduler = scheduler,
        restoreRepository = restoreRepository,
        settings = settings,
        audit = audit,
        clock = clock,
    )

    /**
     * The device as it looks mid-priority-call: loud, ringing, DND relaxed —
     * with a snapshot on disk remembering the vibrate/quiet/DND state it had.
     */
    private fun givenMutatedPhoneWithPendingSnapshot(
        snapshotMode: RingerMode = RingerMode.VIBRATE,
        snapshotVolumeIndex: Int = 0,
    ) {
        audio.ringerMode = RingerMode.NORMAL
        audio.currentVolumeIndex = 15
        audio.maxVolumeIndex = 15
        dnd.policyAccess = true
        dnd.filter = InterruptionFilter.ALL
        restoreRepository.seed(
            testSnapshot(
                ringerMode = snapshotMode,
                ringVolume = VolumeSnapshot(current = snapshotVolumeIndex, max = 15),
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
            // NORMAL, because that is the only mode where a volume write is
            // made at all — see RestoreAudioAndDndUseCase.restoreVolume.
            givenMutatedPhoneWithPendingSnapshot(
                snapshotMode = RingerMode.NORMAL,
                snapshotVolumeIndex = 4,
            )

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
        givenMutatedPhoneWithPendingSnapshot(
            snapshotMode = RingerMode.NORMAL,
            snapshotVolumeIndex = 4,
        )
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

    // ------------------------------------------------ field-level reporting
    //
    // Found on a real phone: "Restore was incomplete (trigger: CALL_ENDED):
    // VERIFICATION_FAILED." — which names neither the field that failed nor
    // what the device reported, so it tells the user to check settings without
    // saying which, and tells the next diagnosis nothing at all.

    @Test
    fun `a restore failure names the field that failed`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()
        dnd.restoreResult = Outcome.Failure(
            reason = FailureReason.VERIFICATION_FAILED,
            detail = "Wanted PRIORITY, device reports ALL",
        )

        useCase(RestoreTrigger.CALL_ENDED)

        val message = audit.lastOf(AuditEventType.RESTORATION_FAILED)?.message.orEmpty()
        assertTrue(
            "the entry must say which of ringer/volume/DND failed: $message",
            message.contains("DND VERIFICATION_FAILED"),
        )
    }

    @Test
    fun `a restore failure carries the detail the device reported`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()
        dnd.restoreResult = Outcome.Failure(
            reason = FailureReason.VERIFICATION_FAILED,
            detail = "Wanted PRIORITY, device reports ALL",
        )

        useCase(RestoreTrigger.CALL_ENDED)

        val message = audit.lastOf(AuditEventType.RESTORATION_FAILED)?.message.orEmpty()
        assertTrue(
            "the detail is the whole diagnosis and must survive into the log: $message",
            message.contains("Wanted PRIORITY, device reports ALL"),
        )
    }

    // ------------------------------------------------------ ordering + volume

    @Test
    fun `ringer mode is restored before volume, mirroring apply`() = runTest {
        // NORMAL, so a volume write actually happens and the order is
        // observable at all.
        givenMutatedPhoneWithPendingSnapshot(
            snapshotMode = RingerMode.NORMAL,
            snapshotVolumeIndex = 4,
        )

        useCase(RestoreTrigger.CALL_ENDED)

        val ringerAt = recorder.calls.indexOf(CallRecorder.SET_RINGER)
        val volumeAt = recorder.calls.indexOf(CallRecorder.SET_VOLUME_RAW)
        assertTrue("ringer must be set first, got ${recorder.calls}", ringerAt in 0 until volumeAt)
    }

    @Test
    fun `restoring SILENT leaves the phone SILENT, not VIBRATE`() = runTest {
        // The real-device bug. The phone was on Silent; after the call it was
        // on Vibrate. Restore set SILENT correctly and then wrote the
        // snapshot's ring index of 0, and on Android that write *is* a ringer
        // mode change — straight back out of SILENT into VIBRATE.
        givenMutatedPhoneWithPendingSnapshot(
            snapshotMode = RingerMode.SILENT,
            snapshotVolumeIndex = 0,
        )

        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(
            "a phone left on Silent must come back on Silent",
            RingerMode.SILENT,
            audio.ringerMode,
        )
    }

    @Test
    fun `restoring VIBRATE leaves the phone VIBRATE`() = runTest {
        givenMutatedPhoneWithPendingSnapshot(
            snapshotMode = RingerMode.VIBRATE,
            snapshotVolumeIndex = 0,
        )

        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(RingerMode.VIBRATE, audio.ringerMode)
    }

    @Test
    fun `no volume is written when restoring to a silent mode`() = runTest {
        givenMutatedPhoneWithPendingSnapshot(
            snapshotMode = RingerMode.SILENT,
            snapshotVolumeIndex = 0,
        )

        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(
            "the ringer mode carries the whole state; a write can only break it",
            emptyList<Int>(),
            audio.volumeRawRequests,
        )
    }

    @Test
    fun `skipping the volume write is reported as success, because it is one`() = runTest {
        givenMutatedPhoneWithPendingSnapshot(
            snapshotMode = RingerMode.SILENT,
            snapshotVolumeIndex = 0,
        )

        val outcome = useCase(RestoreTrigger.CALL_ENDED)

        assertTrue("$outcome", outcome.isSuccess)
        assertFalse(audit.hasType(AuditEventType.RESTORATION_FAILED))
    }

    @Test
    fun `volume IS written when restoring to NORMAL with an audible level`() = runTest {
        givenMutatedPhoneWithPendingSnapshot(
            snapshotMode = RingerMode.NORMAL,
            snapshotVolumeIndex = 4,
        )

        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(listOf(4), audio.volumeRawRequests)
        assertEquals(4, audio.currentVolumeIndex)
        assertEquals(RingerMode.NORMAL, audio.ringerMode)
    }

    @Test
    fun `a NORMAL snapshot with index zero does not write, which would undo NORMAL`() = runTest {
        // Not a state the platform really holds — index 0 *is* silent — but if
        // a device ever reports it, writing it back would flip the mode.
        givenMutatedPhoneWithPendingSnapshot(
            snapshotMode = RingerMode.NORMAL,
            snapshotVolumeIndex = 0,
        )

        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(emptyList<Int>(), audio.volumeRawRequests)
        assertEquals(RingerMode.NORMAL, audio.ringerMode)
    }

    @Test
    fun `a volume failure when restoring to NORMAL is reported`() = runTest {
        givenMutatedPhoneWithPendingSnapshot(
            snapshotMode = RingerMode.NORMAL,
            snapshotVolumeIndex = 4,
        )
        audio.setRingVolumeRawResult = Outcome.Failure(
            reason = FailureReason.NOTIFICATION_POLICY_ACCESS_DENIED,
            detail = "setStreamVolume(4) denied while DND active",
        )

        useCase(RestoreTrigger.CALL_ENDED)

        val message = audit.lastOf(AuditEventType.RESTORATION_FAILED)?.message.orEmpty()
        assertTrue(
            "a refused write in the one mode that writes must be reported: $message",
            message.contains("volume NOTIFICATION_POLICY_ACCESS_DENIED"),
        )
    }

    // ------------------------------------------------- the audible ring level
    //
    // A phone that lives on Silent has an audible level the user chose, which
    // Android will not show us while it is silent (getStreamVolume reports 0,
    // and the platform's own "last audible" value is @hide). Apply overwrites
    // it on the way up. Without putting it back, the user finds the app's
    // volume days later when they unsilence the phone by hand.

    @Test
    fun `the remembered audible level is written back before the phone is silenced`() = runTest {
        givenMutatedPhoneWithPendingSnapshot(
            snapshotMode = RingerMode.SILENT,
            snapshotVolumeIndex = 0,
        )
        settings.set(AppSettings(lastAudibleRingIndex = 6))

        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(
            "the user's own level, not the snapshot's 0 and not the app's raised level",
            listOf(6),
            audio.volumeRawRequests,
        )
        assertEquals(
            "and the phone still ends up silent",
            RingerMode.SILENT,
            audio.ringerMode,
        )
    }

    @Test
    fun `the level is written before the mode, because after is impossible`() = runTest {
        givenMutatedPhoneWithPendingSnapshot(
            snapshotMode = RingerMode.SILENT,
            snapshotVolumeIndex = 0,
        )
        settings.set(AppSettings(lastAudibleRingIndex = 6))

        useCase(RestoreTrigger.CALL_ENDED)

        val volumeAt = recorder.calls.indexOf(CallRecorder.SET_VOLUME_RAW)
        val ringerAt = recorder.calls.indexOf(CallRecorder.SET_RINGER)
        assertTrue(
            "once the phone is silent any non-zero write would unsilence it: ${recorder.calls}",
            volumeAt in 0 until ringerAt,
        )
    }

    @Test
    fun `nothing is written when the app has never seen this phone audible`() = runTest {
        givenMutatedPhoneWithPendingSnapshot(
            snapshotMode = RingerMode.SILENT,
            snapshotVolumeIndex = 0,
        )
        settings.set(AppSettings(lastAudibleRingIndex = null))

        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(
            "unknown is not a volume; a guess would be worse than leaving it",
            emptyList<Int>(),
            audio.volumeRawRequests,
        )
        assertEquals(RingerMode.SILENT, audio.ringerMode)
    }

    @Test
    fun `no level is written if the phone is already silent, which would unsilence it`() = runTest {
        givenMutatedPhoneWithPendingSnapshot(
            snapshotMode = RingerMode.SILENT,
            snapshotVolumeIndex = 0,
        )
        settings.set(AppSettings(lastAudibleRingIndex = 6))
        // A previous restore already put the phone back, or the user did.
        audio.ringerMode = RingerMode.SILENT

        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(
            "writing a non-zero index here would take the phone OUT of silent — " +
                "the loud-bedroom failure this subsystem exists to prevent",
            emptyList<Int>(),
            audio.volumeRawRequests,
        )
        assertEquals(RingerMode.SILENT, audio.ringerMode)
    }

    // -----------------------------------------------------------------------
    // Stopping the alarm-stream alert
    //
    // Every trigger that reaches restore means the ringing is over, so the
    // alert must stop unconditionally — before the snapshot is even looked at,
    // and whatever else goes wrong. A phone that will not go quiet is the one
    // failure worse than a phone that never rang.
    // -----------------------------------------------------------------------

    @Test
    fun `the alarm-stream alert is stopped even when there is no snapshot to restore`() = runTest {
        useCase(RestoreTrigger.CALL_ENDED)

        assertEquals(
            "a fallback alert leaves no snapshot behind — it changes nothing to " +
                "restore. Gating the stop on a pending snapshot would leave it " +
                "playing forever",
            1,
            ringtonePlayer.stopCount,
        )
    }

    /**
     * The durable bound.
     *
     * The player's own timer is a main-thread Handler, and a device probe
     * showed the process frozen as a cached app for 32 seconds after the
     * broadcast returned — audio still playing, no main-thread callback
     * running. A frozen Handler cannot stop anything. WorkManager survives
     * that, so the watchdog is what actually bounds a stuck alert, and this
     * test is what keeps that path wired up.
     */
    @Test
    fun `the watchdog stops the alarm-stream alert, since the player's own timer can be frozen`() =
        runTest {
            givenMutatedPhoneWithPendingSnapshot()

            useCase(RestoreTrigger.WATCHDOG_TIMEOUT)

            assertFalse(ringtonePlayer.playing)
            assertEquals(1, ringtonePlayer.stopCount)
        }

    @Test
    fun `the alarm-stream alert is stopped even when the restore itself fails`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()
        audio.setRingerModeResult = Outcome.Failure(FailureReason.VERIFICATION_FAILED)
        dnd.restoreResult = Outcome.Failure(FailureReason.VERIFICATION_FAILED)

        useCase(RestoreTrigger.CALL_ENDED)

        assertFalse(
            "a device that refuses to be put back is no reason to keep sounding",
            ringtonePlayer.playing,
        )
    }

    @Test
    fun `the alarm-stream alert is stopped before the snapshot is read`() = runTest {
        givenMutatedPhoneWithPendingSnapshot()

        useCase(RestoreTrigger.CALL_ENDED)

        assertTrue(
            "the stop runs outside the restore mutex, so it cannot queue behind " +
                "an in-flight restore and keep sounding in the ear of someone " +
                "who has just answered. Recorded: ${recorder.calls}",
            recorder.indexOf(CallRecorder.STOP_ALARM_ALERT) <
                recorder.indexOf(CallRecorder.CLEAR_SNAPSHOT),
        )
    }
}
