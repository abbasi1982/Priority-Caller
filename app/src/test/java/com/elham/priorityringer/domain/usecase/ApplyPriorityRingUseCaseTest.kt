package com.elham.priorityringer.domain.usecase

import com.elham.priorityringer.domain.escalation.EscalationPolicy
import com.elham.priorityringer.domain.model.AppSettings
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.FailureReason
import com.elham.priorityringer.domain.model.InterruptionFilter
import com.elham.priorityringer.domain.model.Outcome
import com.elham.priorityringer.domain.model.RingerMode
import com.elham.priorityringer.domain.port.AlertPort
import com.elham.priorityringer.fake.CallRecorder
import com.elham.priorityringer.fake.FakeAlertPort
import com.elham.priorityringer.fake.FakeAudioPort
import com.elham.priorityringer.fake.FakeAuditRepository
import com.elham.priorityringer.fake.FakeClock
import com.elham.priorityringer.fake.FakeDndPort
import com.elham.priorityringer.fake.FakeEscalationRepository
import com.elham.priorityringer.fake.FakeRestoreRepository
import com.elham.priorityringer.fake.FakeSchedulerPort
import com.elham.priorityringer.fake.testContact
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The apply path (FR3–FR5, Architecture.md § 6.2, § 7, § 8, § A.3).
 *
 * This is the only code in the app that changes the device, and it runs inside a
 * `goAsync()` window during an incoming call. Two properties matter more than
 * anything it actually achieves:
 *
 *  - **Write-ahead.** The pre-mutation snapshot reaches storage before the first
 *    mutation, so no crash window can strand the phone off Do Not Disturb at
 *    full volume (§ A.3).
 *  - **Independent failability.** A denied permission degrades one step and no
 *    more; it never cancels the steps that would still have worked (§ 7).
 *
 * Everything below is reachable with no device attached, which is the point of
 * the ports in § A.4.
 */
class ApplyPriorityRingUseCaseTest {

    private val recorder = CallRecorder()
    private val audio = FakeAudioPort(recorder)
    private val dnd = FakeDndPort(recorder)
    private val alert = FakeAlertPort(recorder)
    private val scheduler = FakeSchedulerPort(recorder)
    private val restoreRepository = FakeRestoreRepository(recorder)
    private val escalationRepository = FakeEscalationRepository()
    private val audit = FakeAuditRepository()
    private val clock = FakeClock()

    private val contact = testContact()
    private val settings = AppSettings()

    private val useCase = ApplyPriorityRingUseCase(
        audio = audio,
        dnd = dnd,
        alert = alert,
        scheduler = scheduler,
        restoreRepository = restoreRepository,
        escalationRepository = escalationRepository,
        audit = audit,
        escalationPolicy = EscalationPolicy(),
        clock = clock,
    )

    /** A phone in the state the app exists to fix: vibrate, DND suppressing calls. */
    private fun givenSuppressedPhone() {
        audio.ringerMode = RingerMode.VIBRATE
        audio.currentVolumeIndex = 3
        audio.maxVolumeIndex = 15
        dnd.policyAccess = true
        dnd.filter = InterruptionFilter.NONE
    }

    // -----------------------------------------------------------------------
    // Write-ahead ordering (§ A.3) — the safety-critical property
    // -----------------------------------------------------------------------

    @Test
    fun `the snapshot is persisted before the first device mutation, so a crash can never strand the phone`() =
        runTest {
            givenSuppressedPhone()

            useCase(contact, settings)

            val firstMutation = recorder.firstDeviceMutationIndex
            assertTrue("expected at least one device mutation to have happened", firstMutation >= 0)
            assertTrue(
                "saveIfAbsent must precede every device mutation; recorded order was " +
                    "${recorder.calls}",
                recorder.indexOf(CallRecorder.SAVE_SNAPSHOT) < firstMutation,
            )
        }

    @Test
    fun `the restore watchdog is enqueued before the first device mutation`() = runTest {
        givenSuppressedPhone()

        useCase(contact, settings)

        assertTrue(
            "if the process dies between mutation and enqueue, nothing would ever " +
                "restore; recorded order was ${recorder.calls}",
            recorder.indexOf(CallRecorder.SCHEDULE_WATCHDOG) < recorder.firstDeviceMutationIndex,
        )
    }

    @Test
    fun `the persisted snapshot holds the pre-mutation ringer mode, not the one we just set`() =
        runTest {
            givenSuppressedPhone()

            useCase(contact, settings)

            assertEquals(RingerMode.VIBRATE, restoreRepository.pending?.ringerMode)
        }

    @Test
    fun `the persisted snapshot holds the pre-mutation volume index`() = runTest {
        givenSuppressedPhone()

        useCase(contact, settings)

        assertEquals(3, restoreRepository.pending?.ringVolume?.current)
    }

    @Test
    fun `the snapshot expiry is the watchdog deadline, so restore and watchdog agree`() = runTest {
        givenSuppressedPhone()

        useCase(contact, settings)

        assertEquals(
            clock.now + settings.autoRestoreTimeoutSeconds * 1000L,
            restoreRepository.pending?.expiresAtEpochMs,
        )
    }

    @Test
    fun `the watchdog is scheduled with the configured timeout`() = runTest {
        givenSuppressedPhone()

        useCase(contact, settings)

        assertEquals(listOf(settings.autoRestoreTimeoutSeconds), scheduler.scheduledSeconds)
    }

    @Test
    fun `the call is announced in the audit log before anything is attempted`() = runTest {
        givenSuppressedPhone()

        useCase(contact, settings)

        assertEquals(AuditEventType.PRIORITY_CALL_DETECTED, audit.entries.first().type)
    }

    @Test
    fun `the audit log never contains the full number, only the redacted form`() = runTest {
        givenSuppressedPhone()

        useCase(contact, settings)

        assertFalse(
            "Architecture.md § 13 forbids full numbers in logs",
            audit.entries.any { it.message.contains(contact.originalInput) },
        )
    }

    // -----------------------------------------------------------------------
    // Independent failability (§ 7)
    // -----------------------------------------------------------------------

    @Test
    fun `a denied notification-policy grant does not stop the volume change from being attempted`() =
        runTest {
            givenSuppressedPhone()
            dnd.policyAccess = false

            val result = useCase(contact, settings)

            assertTrue(
                "volume is independent of DND permission; refusing to try would " +
                    "throw away the one thing that still works",
                result.volume?.isSuccess == true,
            )
        }

    @Test
    fun `a denied notification-policy grant is reported as a typed failure, not an exception`() =
        runTest {
            givenSuppressedPhone()
            dnd.policyAccess = false

            val result = useCase(contact, settings)

            assertEquals(
                FailureReason.NOTIFICATION_POLICY_ACCESS_DENIED,
                result.dnd?.failureOrNull()?.reason,
            )
        }

    @Test
    fun `a denied notification-policy grant is audited as PERMISSION_DENIED so the UI can offer the grant`() =
        runTest {
            givenSuppressedPhone()
            dnd.policyAccess = false

            useCase(contact, settings)

            assertTrue(audit.hasType(AuditEventType.PERMISSION_DENIED))
        }

    @Test
    fun `DND is never even attempted without policy access`() = runTest {
        givenSuppressedPhone()
        dnd.policyAccess = false

        useCase(contact, settings)

        assertTrue(dnd.applyBypassRequests.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Fixed volume (§ 7.1)
    // -----------------------------------------------------------------------

    @Test
    fun `a fixed-volume device has no volume change attempted at all`() = runTest {
        givenSuppressedPhone()
        audio.volumeFixed = true

        useCase(contact, settings)

        assertTrue(
            "attempting it would produce a misleading generic failure instead of " +
                "'impossible on this device'",
            audio.volumePercentRequests.isEmpty(),
        )
    }

    @Test
    fun `a fixed-volume device audits VOLUME_FIXED rather than a generic volume failure`() =
        runTest {
            givenSuppressedPhone()
            audio.volumeFixed = true

            useCase(contact, settings)

            assertTrue(audit.hasType(AuditEventType.VOLUME_FIXED))
            assertFalse(audit.hasType(AuditEventType.VOLUME_CHANGE_FAILED))
        }

    @Test
    fun `a fixed-volume device still has its ringer mode changed`() = runTest {
        givenSuppressedPhone()
        audio.volumeFixed = true

        useCase(contact, settings)

        assertEquals(
            "vibrate to normal is the one thing still available on a fixed-volume route",
            listOf(RingerMode.NORMAL),
            audio.ringerModeRequests,
        )
    }

    @Test
    fun `a fixed-volume device reports VOLUME_FIXED as the volume outcome`() = runTest {
        givenSuppressedPhone()
        audio.volumeFixed = true

        val result = useCase(contact, settings)

        assertEquals(FailureReason.VOLUME_FIXED, result.volume?.failureOrNull()?.reason)
    }

    // -----------------------------------------------------------------------
    // Ringer mode (§ 7.2, § 7.3)
    // -----------------------------------------------------------------------

    @Test
    fun `a phone already in normal mode has its ringer left alone`() = runTest {
        audio.ringerMode = RingerMode.NORMAL

        val result = useCase(contact, settings)

        assertNull("there is nothing to change, so nothing should be attempted", result.ringer)
        assertTrue(audio.ringerModeRequests.isEmpty())
    }

    @Test
    fun `a silent phone that stays silent is audited honestly as SILENT_NOT_OVERRIDDEN`() = runTest {
        givenSuppressedPhone()
        audio.ringerMode = RingerMode.SILENT
        audio.setRingerModeResult = Outcome.Failure(FailureReason.SILENT_NOT_OVERRIDDEN)

        useCase(contact, settings)

        assertTrue(audit.hasType(AuditEventType.SILENT_NOT_OVERRIDDEN))
    }

    @Test
    fun `a silent phone that stays silent is not reported as a generic ringer failure`() = runTest {
        givenSuppressedPhone()
        audio.ringerMode = RingerMode.SILENT
        audio.setRingerModeResult = Outcome.Failure(FailureReason.SILENT_NOT_OVERRIDDEN)

        useCase(contact, settings)

        assertFalse(
            "§ 7.3 — Silent is user- and OEM-controlled. Calling it a generic failure " +
                "would hide the one explanation the user can act on.",
            audit.hasType(AuditEventType.RINGER_CHANGE_FAILED),
        )
    }

    @Test
    fun `an OEM that silently ignores the ringer change is audited as RINGER_CHANGE_FAILED`() =
        runTest {
            givenSuppressedPhone()
            audio.setRingerModeResult = Outcome.Failure(FailureReason.VERIFICATION_FAILED)

            useCase(contact, settings)

            assertTrue(audit.hasType(AuditEventType.RINGER_CHANGE_FAILED))
            assertFalse(audit.hasType(AuditEventType.SILENT_NOT_OVERRIDDEN))
        }

    @Test
    fun `a successful vibrate to normal transition is audited as RINGER_MODE_CHANGED`() = runTest {
        givenSuppressedPhone()

        useCase(contact, settings)

        assertTrue(audit.hasType(AuditEventType.RINGER_MODE_CHANGED))
    }

    // -----------------------------------------------------------------------
    // DND (§ 6.2)
    // -----------------------------------------------------------------------

    @Test
    fun `a filter that already allows everything succeeds without touching DND`() = runTest {
        audio.ringerMode = RingerMode.VIBRATE
        dnd.policyAccess = true
        dnd.filter = InterruptionFilter.ALL

        val result = useCase(contact, settings)

        assertTrue(
            "the desired end state already holds, so this is success, not a no-op failure",
            result.dnd?.isSuccess == true,
        )
        assertTrue(
            "mutating DND we did not need to change would be an unnecessary change to " +
                "someone else's phone",
            dnd.applyBypassRequests.isEmpty(),
        )
    }

    @Test
    fun `a suppressing filter is bypassed with the configured strategy`() = runTest {
        givenSuppressedPhone()

        useCase(contact, settings)

        assertEquals(listOf(settings.dndBypassStrategy), dnd.applyBypassRequests)
    }

    @Test
    fun `every DND attempt is audited with the before and after filter`() = runTest {
        givenSuppressedPhone()

        useCase(contact, settings)

        assertTrue(audit.hasType(AuditEventType.DND_BYPASS_ATTEMPTED))
    }

    @Test
    fun `an ineffective DND bypass gets its own audit event, distinct from the attempt`() = runTest {
        givenSuppressedPhone()
        dnd.applyBypassResult = Outcome.Failure(FailureReason.DND_BYPASS_INEFFECTIVE)

        useCase(contact, settings)

        assertTrue(
            "§ 6.2 — on Android 15+ a stricter manual DND wins and the app genuinely " +
                "cannot override it. The user needs to be told that specifically.",
            audit.hasType(AuditEventType.DND_BYPASS_INEFFECTIVE),
        )
    }

    @Test
    fun `an ineffective DND bypass is still recorded as an attempt, so the log shows what was tried`() =
        runTest {
            givenSuppressedPhone()
            dnd.applyBypassResult = Outcome.Failure(FailureReason.DND_BYPASS_INEFFECTIVE)

            useCase(contact, settings)

            assertTrue(audit.hasType(AuditEventType.DND_BYPASS_ATTEMPTED))
        }

    @Test
    fun `an ineffective DND bypass does not stop the ringer and volume changes`() = runTest {
        givenSuppressedPhone()
        dnd.applyBypassResult = Outcome.Failure(FailureReason.DND_BYPASS_INEFFECTIVE)

        val result = useCase(contact, settings)

        assertTrue(result.ringer?.isSuccess == true)
        assertTrue(result.volume?.isSuccess == true)
    }

    // -----------------------------------------------------------------------
    // Escalation (FR5)
    // -----------------------------------------------------------------------

    @Test
    fun `an escalated call targets maximum volume rather than the configured percent`() = runTest {
        givenSuppressedPhone()
        escalationRepository.seed(contact.matchKey, clock.now - 60_000L)

        useCase(contact, settings)

        assertEquals(listOf(100), audio.volumePercentRequests)
    }

    @Test
    fun `an escalated call shows the full-screen alert`() = runTest {
        givenSuppressedPhone()
        escalationRepository.seed(contact.matchKey, clock.now - 60_000L)

        useCase(contact, settings)

        assertEquals(listOf(contact), alert.shownFor)
    }

    @Test
    fun `an escalated call is announced in the audit log`() = runTest {
        givenSuppressedPhone()
        escalationRepository.seed(contact.matchKey, clock.now - 60_000L)

        useCase(contact, settings)

        assertTrue(audit.hasType(AuditEventType.ESCALATION_TRIGGERED))
    }

    @Test
    fun `a heads-up fallback alert is audited as a fallback, not as a success or a failure`() =
        runTest {
            givenSuppressedPhone()
            escalationRepository.seed(contact.matchKey, clock.now - 60_000L)
            alert.showResult = Outcome.Success(AlertPort.AlertMode.HEADS_UP_FALLBACK)

            useCase(contact, settings)

            assertTrue(audit.hasType(AuditEventType.FULL_SCREEN_ALERT_FALLBACK))
            assertFalse(audit.hasType(AuditEventType.FULL_SCREEN_ALERT_SHOWN))
        }

    @Test
    fun `a first call targets the configured volume percent`() = runTest {
        givenSuppressedPhone()

        useCase(contact, settings)

        assertEquals(listOf(settings.ringtoneVolumePercent), audio.volumePercentRequests)
    }

    @Test
    fun `a first call shows no full-screen alert, which would be intrusive for a single call`() =
        runTest {
            givenSuppressedPhone()

            val result = useCase(contact, settings)

            assertTrue(alert.shownFor.isEmpty())
            assertNull(result.alert)
        }

    @Test
    fun `the current call is recorded for escalation exactly once`() = runTest {
        givenSuppressedPhone()

        useCase(contact, settings)

        assertEquals(
            "double-recording would make every second call escalate",
            listOf(contact.matchKey to clock.now),
            escalationRepository.recorded,
        )
    }

    // -----------------------------------------------------------------------
    // Total failure
    // -----------------------------------------------------------------------

    @Test
    fun `when every attempt fails the result says so`() = runTest {
        dnd.policyAccess = false
        audio.ringerMode = RingerMode.SILENT
        audio.setRingerModeResult = Outcome.Failure(FailureReason.SILENT_NOT_OVERRIDDEN)
        audio.volumeFixed = true

        val result = useCase(contact, settings)

        assertTrue(result.allAttemptsFailed)
    }

    @Test
    fun `when every attempt fails an ERROR is audited, because the user must not believe it worked`() =
        runTest {
            dnd.policyAccess = false
            audio.ringerMode = RingerMode.SILENT
            audio.setRingerModeResult = Outcome.Failure(FailureReason.SILENT_NOT_OVERRIDDEN)
            audio.volumeFixed = true

            useCase(contact, settings)

            assertTrue(audit.hasType(AuditEventType.ERROR))
        }

    @Test
    fun `a single success means the attempt did not wholly fail`() = runTest {
        givenSuppressedPhone()
        dnd.policyAccess = false
        audio.setRingerModeResult = Outcome.Failure(FailureReason.VERIFICATION_FAILED)

        val result = useCase(contact, settings)

        assertFalse("the volume change succeeded", result.allAttemptsFailed)
        assertFalse(audit.hasType(AuditEventType.ERROR))
    }

    // -----------------------------------------------------------------------
    // Overlapping calls (§ A.3 step 2)
    // -----------------------------------------------------------------------

    @Test
    fun `a second overlapping call does not save a new snapshot`() = runTest {
        givenSuppressedPhone()
        useCase(contact, settings)

        val second = useCase(contact, settings)

        assertFalse(second.snapshotSaved)
    }

    @Test
    fun `a second overlapping call leaves the original pre-mutation ringer mode in storage`() =
        runTest {
            givenSuppressedPhone()
            useCase(contact, settings)
            assertEquals(
                "precondition: the first call really did change the device",
                RingerMode.NORMAL,
                audio.ringerMode,
            )

            useCase(contact, settings)

            assertEquals(
                "overwriting would persist NORMAL — a value we set ourselves — and " +
                    "restore would then never put the phone back on vibrate",
                RingerMode.VIBRATE,
                restoreRepository.pending?.ringerMode,
            )
        }

    @Test
    fun `a second overlapping call leaves the original pre-mutation volume in storage`() = runTest {
        givenSuppressedPhone()
        useCase(contact, settings)

        useCase(contact, settings)

        assertEquals(3, restoreRepository.pending?.ringVolume?.current)
    }

    @Test
    fun `a second overlapping call does not enqueue a second watchdog`() = runTest {
        givenSuppressedPhone()
        useCase(contact, settings)

        useCase(contact, settings)

        assertEquals(1, scheduler.scheduleCount)
    }

    @Test
    fun `a second overlapping call still applies the ringing treatment`() = runTest {
        givenSuppressedPhone()
        useCase(contact, settings)
        audio.ringerMode = RingerMode.VIBRATE

        val second = useCase(contact, settings)

        assertNotNull(
            "declining to snapshot again must not mean declining to ring",
            second.ringer,
        )
    }
}
