package com.elham.priorityringer.domain.usecase

import app.cash.turbine.test
import com.elham.priorityringer.domain.escalation.EscalationPolicy
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.CallState
import com.elham.priorityringer.domain.model.FailureReason
import com.elham.priorityringer.domain.model.IncomingCallEvent
import com.elham.priorityringer.domain.model.InterruptionFilter
import com.elham.priorityringer.domain.model.RingerMode
import com.elham.priorityringer.domain.phone.NormalizedNumber
import com.elham.priorityringer.domain.phone.PhoneNumberNormalizer
import com.elham.priorityringer.fake.CallRecorder
import com.elham.priorityringer.fake.FakeAlertPort
import com.elham.priorityringer.fake.FakeAudioPort
import com.elham.priorityringer.fake.FakeAuditRepository
import com.elham.priorityringer.fake.FakeClock
import com.elham.priorityringer.fake.FakeContactRepository
import com.elham.priorityringer.fake.FakeDndPort
import com.elham.priorityringer.fake.FakeEscalationRepository
import com.elham.priorityringer.fake.FakeRestoreRepository
import com.elham.priorityringer.fake.FakeSchedulerPort
import com.elham.priorityringer.fake.FakeSettingsRepository
import com.elham.priorityringer.fake.FakeTelephonyPort
import com.elham.priorityringer.fake.testContact
import com.elham.priorityringer.fake.testSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end orchestration of one incoming call (Architecture.md § 4).
 *
 * Two rules here are absolute rather than best-effort:
 *
 *  - **A non-priority call must leave the device completely untouched.** Not
 *    "restored afterwards" — untouched. Anything else means the app changes how
 *    the phone behaves for calls the user never asked it to handle.
 *  - **Nothing may escape this method.** It runs inside a `goAsync()` broadcast
 *    window (§ A.2); an exception thrown here kills the app *during an incoming
 *    call*, which the user would experience as the phone crashing when someone
 *    rings them.
 *
 * The coordinator is wired up here with the real use cases and fake ports, so
 * these assertions cover the whole path rather than the coordinator alone.
 */
class IncomingCallCoordinatorTest {

    private val recorder = CallRecorder()
    private val audio = FakeAudioPort(recorder)
    private val dnd = FakeDndPort(recorder)
    private val alert = FakeAlertPort(recorder)
    private val scheduler = FakeSchedulerPort(recorder)
    private val restoreRepository = FakeRestoreRepository(recorder)
    private val escalationRepository = FakeEscalationRepository()
    private val audit = FakeAuditRepository()
    private val clock = FakeClock()

    private val contacts = FakeContactRepository()
    private val telephony = FakeTelephonyPort(countryIso = "US")
    private val normalizer = PhoneNumberNormalizer()
    private val settings = FakeSettingsRepository()

    private val mum = testContact(id = 1L, displayName = "Mum", originalInput = "+15551234567")

    private val applyPriorityRing = ApplyPriorityRingUseCase(
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

    private val restore = RestoreAudioAndDndUseCase(
        audio = audio,
        dnd = dnd,
        alert = alert,
        scheduler = scheduler,
        restoreRepository = restoreRepository,
        audit = audit,
        clock = clock,
    )

    private val coordinator = IncomingCallCoordinator(
        evaluateCall = EvaluateIncomingCallUseCase(contacts, normalizer, telephony),
        applyPriorityRing = applyPriorityRing,
        restore = restore,
        settings = settings,
        audit = audit,
        telephony = telephony,
        restoreRepository = restoreRepository,
        clock = clock,
    )

    private fun callFrom(raw: String?) = IncomingCallEvent(
        number = normalizer.normalize(raw, telephony.defaultCountryIso()),
        timestampEpochMs = clock.now,
    )

    private fun givenSuppressedPhoneAndOneContact() {
        contacts.seed(mum)
        audio.ringerMode = RingerMode.VIBRATE
        audio.currentVolumeIndex = 3
        dnd.policyAccess = true
        dnd.filter = InterruptionFilter.NONE
    }

    // -----------------------------------------------------------------------
    // Rule 2 — a non-priority call changes nothing
    // -----------------------------------------------------------------------

    @Test
    fun `a call from a number that is not a priority contact mutates nothing at all`() = runTest {
        givenSuppressedPhoneAndOneContact()

        coordinator.onIncomingCall(callFrom("+15559998888"))

        assertEquals(
            "not one port, repository or scheduler call is permitted for an ordinary " +
                "call; recorded: ${recorder.calls}",
            emptyList<String>(),
            recorder.calls,
        )
    }

    @Test
    fun `a call from a number that is not a priority contact leaves the ringer on vibrate`() =
        runTest {
            givenSuppressedPhoneAndOneContact()

            coordinator.onIncomingCall(callFrom("+15559998888"))

            assertEquals(RingerMode.VIBRATE, audio.ringerMode)
        }

    @Test
    fun `an ordinary call writes no audit row, because that would be logging the call history`() =
        runTest {
            givenSuppressedPhoneAndOneContact()

            coordinator.onIncomingCall(callFrom("+15559998888"))

            assertTrue(
                "500 entries of every call the user receives is a privacy problem, not a log",
                audit.entries.isEmpty(),
            )
        }

    @Test
    fun `a call from a disabled priority contact mutates nothing`() = runTest {
        contacts.seed(mum.copy(enabled = false))
        audio.ringerMode = RingerMode.VIBRATE

        coordinator.onIncomingCall(callFrom("5551234567"))

        assertEquals(emptyList<String>(), recorder.calls)
    }

    // -----------------------------------------------------------------------
    // Rule 1 — unusable number
    // -----------------------------------------------------------------------

    @Test
    fun `a call with no number is audited as NUMBER_UNAVAILABLE`() = runTest {
        givenSuppressedPhoneAndOneContact()

        coordinator.onIncomingCall(
            IncomingCallEvent(number = NormalizedNumber.UNKNOWN, timestampEpochMs = clock.now),
        )

        assertTrue(audit.hasType(AuditEventType.NUMBER_UNAVAILABLE))
    }

    @Test
    fun `a call with no number mutates nothing, because no caller could be identified`() = runTest {
        givenSuppressedPhoneAndOneContact()

        coordinator.onIncomingCall(
            IncomingCallEvent(number = NormalizedNumber.UNKNOWN, timestampEpochMs = clock.now),
        )

        assertEquals(emptyList<String>(), recorder.calls)
    }

    // -----------------------------------------------------------------------
    // Rule 3 — a priority call is handled
    // -----------------------------------------------------------------------

    @Test
    fun `a call from an enabled priority contact raises the ringer`() = runTest {
        givenSuppressedPhoneAndOneContact()

        coordinator.onIncomingCall(callFrom("5551234567"))

        assertEquals(RingerMode.NORMAL, audio.ringerMode)
    }

    @Test
    fun `a call from an enabled priority contact leaves a pending snapshot for restore`() = runTest {
        givenSuppressedPhoneAndOneContact()

        coordinator.onIncomingCall(callFrom("5551234567"))

        assertNotNull(restoreRepository.pending)
    }

    @Test
    fun `the apply result is published so the dashboard can show what actually happened`() =
        runTest {
            givenSuppressedPhoneAndOneContact()

            coordinator.lastResult.test {
                assertNull("nothing has happened yet", awaitItem())

                coordinator.onIncomingCall(callFrom("5551234567"))

                assertEquals(mum, awaitItem()?.contact)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // -----------------------------------------------------------------------
    // Rule 5 — nothing escapes into the broadcast window
    // -----------------------------------------------------------------------

    @Test
    fun `an exception thrown deep in the apply path does not propagate out of the receiver window`() =
        runTest {
            givenSuppressedPhoneAndOneContact()
            escalationRepository.failOnTimestampsSince = true

            // No assertion needed beyond "this line is reached": an escaping
            // exception here would kill the app mid-call (§ A.2).
            coordinator.onIncomingCall(callFrom("5551234567"))
        }

    @Test
    fun `an exception thrown deep in the apply path is audited as an unrecoverable ERROR`() =
        runTest {
            givenSuppressedPhoneAndOneContact()
            escalationRepository.failOnTimestampsSince = true

            coordinator.onIncomingCall(callFrom("5551234567"))

            assertEquals(
                false,
                audit.lastOf(AuditEventType.ERROR)?.recoverable,
            )
        }

    @Test
    fun `an exception before the snapshot is written leaves the device untouched`() = runTest {
        givenSuppressedPhoneAndOneContact()
        escalationRepository.failOnTimestampsSince = true

        coordinator.onIncomingCall(callFrom("5551234567"))

        assertTrue(recorder.deviceMutations.isEmpty())
        assertNull(restoreRepository.pending)
    }

    @Test
    fun `an audit repository that itself fails still does not crash the receiver`() = runTest {
        givenSuppressedPhoneAndOneContact()
        audit.failOnLog = true

        coordinator.onIncomingCall(callFrom("5551234567"))
    }

    // -----------------------------------------------------------------------
    // Rule 4 — restore on call state
    // -----------------------------------------------------------------------

    @Test
    fun `IDLE after a handled priority ring restores the device`() = runTest {
        givenSuppressedPhoneAndOneContact()
        coordinator.onIncomingCall(callFrom("5551234567"))

        coordinator.onCallStateChanged(CallState.IDLE)

        assertEquals(RingerMode.VIBRATE, audio.ringerMode)
    }

    @Test
    fun `IDLE after a handled priority ring clears the pending snapshot`() = runTest {
        givenSuppressedPhoneAndOneContact()
        coordinator.onIncomingCall(callFrom("5551234567"))

        coordinator.onCallStateChanged(CallState.IDLE)

        assertNull(restoreRepository.pending)
    }

    @Test
    fun `IDLE after a non-priority call does not change ringer or DND`() = runTest {
        givenSuppressedPhoneAndOneContact()
        coordinator.onIncomingCall(callFrom("+15559998888"))

        coordinator.onCallStateChanged(CallState.IDLE)

        assertEquals(
            "restore still runs (idempotent, no in-memory handled-ring flag) but " +
                "must not mutate the device when nothing is pending",
            emptyList<String>(),
            recorder.deviceMutations,
        )
    }

    /**
     * The snapshot, not an in-memory flag, is what makes the second call a
     * no-op. That distinction is the point: a flag would also suppress the
     * restore after a process restart, when the snapshot is the only thing left.
     */
    @Test
    fun `a repeated IDLE does not touch the device again, because nothing is pending`() = runTest {
        givenSuppressedPhoneAndOneContact()
        coordinator.onIncomingCall(callFrom("5551234567"))
        coordinator.onCallStateChanged(CallState.IDLE)
        recorder.reset()

        coordinator.onCallStateChanged(CallState.IDLE)

        assertEquals(emptyList<String>(), recorder.deviceMutations)
    }

    @Test
    fun `OFFHOOK restores, because answering means the ringtone has done its job`() = runTest {
        givenSuppressedPhoneAndOneContact()
        coordinator.onIncomingCall(callFrom("5551234567"))

        coordinator.onCallStateChanged(CallState.OFFHOOK)

        assertNull(
            "the ring stream is not the in-call voice stream, so restoring at the " +
                "moment of answering is inaudible to the conversation - and waiting " +
                "would leave the watchdog to fire mid-call instead",
            restoreRepository.pending,
        )
        assertEquals(RingerMode.VIBRATE, audio.ringerMode)
    }

    @Test
    fun `a second restore after OFFHOOK is a harmless no-op`() = runTest {
        givenSuppressedPhoneAndOneContact()
        coordinator.onIncomingCall(callFrom("5551234567"))
        coordinator.onCallStateChanged(CallState.OFFHOOK)

        coordinator.onCallStateChanged(CallState.IDLE)

        assertNull(restoreRepository.pending)
        assertEquals(RingerMode.VIBRATE, audio.ringerMode)
    }

    @Test
    fun `IDLE restores a snapshot left by a process that died before this one started`() =
        runTest {
            // No onIncomingCall in THIS process: the snapshot is on disk from a
            // process that has since been killed. A restore gated on an
            // in-memory "we handled a ring" flag would skip this entirely.
            givenSuppressedPhoneAndOneContact()
            restoreRepository.seed(testSnapshot(ringerMode = RingerMode.VIBRATE))

            coordinator.onCallStateChanged(CallState.IDLE)

            assertNull(restoreRepository.pending)
        }

    @Test
    fun `RINGING as a state change never restores`() = runTest {
        givenSuppressedPhoneAndOneContact()
        coordinator.onIncomingCall(callFrom("5551234567"))

        coordinator.onCallStateChanged(CallState.RINGING)

        assertNotNull(restoreRepository.pending)
    }

    // -----------------------------------------------------------------------
    // Trigger 3 — cold start
    // -----------------------------------------------------------------------

    @Test
    fun `cold-start reconciliation restores a snapshot stranded by a process kill`() = runTest {
        givenSuppressedPhoneAndOneContact()
        coordinator.onIncomingCall(callFrom("5551234567"))
        // The process dies here: no IDLE callback ever arrives. By the time it
        // restarts the call is long over, telephony is idle, and the snapshot
        // is past its own auto-restore deadline.
        telephony.currentState = CallState.IDLE
        clock.advanceSeconds(120)

        coordinator.reconcileStaleRestore()

        assertEquals(RingerMode.VIBRATE, audio.ringerMode)
        assertTrue(audit.hasType(AuditEventType.STALE_RESTORE_RECOVERED))
    }

    @Test
    fun `cold-start reconciliation does NOT restore while a call is still ringing`() = runTest {
        givenSuppressedPhoneAndOneContact()
        coordinator.onIncomingCall(callFrom("5551234567"))
        recorder.reset()

        // The killed process is restarted BY the ringing call itself, so
        // onCreate runs while that call is still ringing. Restoring now would
        // put the phone back to vibrate and re-arm DND mid-ring - silencing the
        // very call the app exists to make audible.
        //
        // The clock is aged past the snapshot deadline deliberately, so the
        // expiry gate is satisfied and this test genuinely exercises the
        // call-state gate rather than passing for the wrong reason.
        clock.advanceSeconds(120)
        telephony.currentState = CallState.RINGING

        coordinator.reconcileStaleRestore()

        assertNotNull(
            "the snapshot must stay on disk for the watchdog or the call-ended " +
                "trigger to use once the call is actually over",
            restoreRepository.pending,
        )
        assertEquals(emptyList<String>(), recorder.deviceMutations)
    }

    @Test
    fun `cold-start reconciliation does NOT restore during an answered call`() = runTest {
        givenSuppressedPhoneAndOneContact()
        coordinator.onIncomingCall(callFrom("5551234567"))
        recorder.reset()
        clock.advanceSeconds(120)
        telephony.currentState = CallState.OFFHOOK

        coordinator.reconcileStaleRestore()

        assertNotNull(restoreRepository.pending)
        assertEquals(emptyList<String>(), recorder.deviceMutations)
    }

    /**
     * The case the call-state read cannot catch.
     *
     * `getCallState()` reads the default subscription, which the platform warns
     * may disagree with the broadcast — on a dual-SIM phone it can report IDLE
     * while the *other* SIM is ringing. Missing permission and read failures
     * also fail open to IDLE by design, since never restoring is worse. So the
     * snapshot's own deadline, not telephony, is what actually protects a live
     * call here.
     */
    @Test
    fun `cold-start reconciliation defers on a fresh snapshot even when telephony claims idle`() =
        runTest {
            givenSuppressedPhoneAndOneContact()
            coordinator.onIncomingCall(callFrom("5551234567"))
            recorder.reset()

            // Telephony lying, or reading the wrong SIM. The snapshot was
            // written seconds ago, so the call cannot be over.
            telephony.currentState = CallState.IDLE

            coordinator.reconcileStaleRestore()

            assertNotNull(
                "a snapshot younger than its own auto-restore deadline belongs to a " +
                    "live call, whatever telephony reports",
                restoreRepository.pending,
            )
            assertEquals(emptyList<String>(), recorder.deviceMutations)
        }

    // -----------------------------------------------------------------------
    // restoreNow — the guarded entry point for the watchdog and Test Mode
    // -----------------------------------------------------------------------

    @Test
    fun `restoreNow puts the device back and clears the snapshot`() = runTest {
        givenSuppressedPhoneAndOneContact()
        coordinator.onIncomingCall(callFrom("5551234567"))

        val outcome = coordinator.restoreNow(RestoreTrigger.MANUAL)

        assertTrue(outcome.isSuccess)
        assertNull(restoreRepository.pending)
        assertEquals(RingerMode.VIBRATE, audio.ringerMode)
    }

    /**
     * Unlike the cold-start path, restoreNow has no expiry gate — the watchdog
     * fires precisely because the deadline passed, and a user tapping "Restore
     * now" is asking for it unconditionally.
     */
    @Test
    fun `restoreNow ignores the snapshot deadline that gates cold start`() = runTest {
        givenSuppressedPhoneAndOneContact()
        coordinator.onIncomingCall(callFrom("5551234567"))
        telephony.currentState = CallState.RINGING

        coordinator.restoreNow(RestoreTrigger.WATCHDOG_TIMEOUT)

        assertNull(restoreRepository.pending)
    }

    @Test
    fun `restoreNow with nothing pending reports NOTHING_TO_DO rather than failing loudly`() =
        runTest {
            val outcome = coordinator.restoreNow(RestoreTrigger.MANUAL)

            assertEquals(FailureReason.NOTHING_TO_DO, outcome.failureOrNull()?.reason)
            assertTrue(recorder.deviceMutations.isEmpty())
        }

    @Test
    fun `cold-start reconciliation with nothing pending is a silent no-op`() = runTest {
        coordinator.reconcileStaleRestore()

        assertTrue(audit.entries.isEmpty())
        assertTrue(recorder.deviceMutations.isEmpty())
    }
}
