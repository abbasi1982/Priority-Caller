package com.elham.priorityringer.domain.usecase

import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.CallMatchResult
import com.elham.priorityringer.domain.model.CallState
import com.elham.priorityringer.domain.model.IncomingCallEvent
import com.elham.priorityringer.domain.model.Outcome
import com.elham.priorityringer.domain.port.Clock
import com.elham.priorityringer.domain.port.TelephonyPort
import com.elham.priorityringer.domain.repository.AuditRepository
import com.elham.priorityringer.domain.repository.RestoreRepository
import com.elham.priorityringer.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Orchestrates a real (or simulated) incoming call.
 *
 * Architecture.md § 4 fixes the required behaviour:
 *
 * 1. Ignore empty numbers → log `NUMBER_UNAVAILABLE`.
 * 2. No match → do **nothing** to audio or DND.
 * 3. Match → snapshot → apply → schedule timeout restore.
 * 4. Call answered (`OFFHOOK`) or ended (`IDLE`) → restore.
 * 5. Apply failure → log and surface a persistent "last failure" on Dashboard.
 *
 * Point 2 is a hard rule, not an optimisation: a non-priority call must leave
 * the device untouched. Anything else would make the app change the phone's
 * behaviour for calls the user never asked it to.
 */
@Singleton
class IncomingCallCoordinator @Inject constructor(
    private val evaluateCall: EvaluateIncomingCallUseCase,
    private val applyPriorityRing: ApplyPriorityRingUseCase,
    private val restore: RestoreAudioAndDndUseCase,
    private val settings: SettingsRepository,
    private val audit: AuditRepository,
    private val telephony: TelephonyPort,
    private val restoreRepository: RestoreRepository,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    private val _lastResult = MutableStateFlow<ApplyResult?>(null)

    /** Most recent apply attempt, for Dashboard and Test Mode. */
    val lastResult: StateFlow<ApplyResult?> = _lastResult.asStateFlow()

    /**
     * Handle a `PHONE_STATE` RINGING broadcast.
     *
     * Runs inside the receiver's `goAsync()` window (§ A.2) and must never
     * throw — an exception escaping here kills the app during an incoming call.
     */
    suspend fun onIncomingCall(event: IncomingCallEvent) {
        try {
            mutex.withLock { handleIncoming(event) }
        } catch (t: Throwable) {
            Timber.e(t, "Unhandled error while handling incoming call")
            runCatching {
                audit.log(
                    type = AuditEventType.ERROR,
                    message = "Unexpected error handling the incoming call: " +
                        "${t.javaClass.simpleName}. Audio settings were not changed, " +
                        "or will be restored automatically.",
                    recoverable = false,
                )
            }
        }
    }

    private suspend fun handleIncoming(event: IncomingCallEvent) {
        when (val match = evaluateCall(event)) {
            is CallMatchResult.NumberUnavailable -> {
                audit.log(
                    type = AuditEventType.NUMBER_UNAVAILABLE,
                    message = "An incoming call arrived without a caller number. " +
                        "This normally means the Call Log permission is not " +
                        "granted, so no caller can be identified.",
                )
            }

            is CallMatchResult.Miss -> {
                // Rule 2. Deliberately silent — not even an audit entry, which
                // would fill the 500-entry log with every ordinary call and
                // amount to logging the user's entire call history.
                Timber.d("Incoming call %s: not a priority contact", event.number.redacted)
            }

            is CallMatchResult.DisabledPriority -> {
                Timber.d("Priority contact %s is disabled; ignoring", match.contact.displayName)
            }

            is CallMatchResult.EnabledPriority -> {
                val result = applyPriorityRing(
                    contact = match.contact,
                    settings = settings.get(),
                    isSimulated = event.isSimulated,
                )
                _lastResult.value = result
            }
        }
    }

    /**
     * Drive restore from call state (§ 5.2, § A.3 trigger 1).
     *
     * **Both `OFFHOOK` and `IDLE` restore.** An earlier version restored only on
     * `IDLE`, reasoning that restoring mid-conversation dropped ring volume for
     * no benefit. That was wrong on two counts:
     *
     * 1. Once a call is answered the ringtone has stopped, so the raised volume
     *    and relaxed DND have already served their entire purpose. Ring-stream
     *    volume is not the in-call voice stream, so restoring it changes nothing
     *    the person on the call can hear.
     * 2. Not restoring on `OFFHOOK` left the watchdog scheduled during the call.
     *    On any call longer than `autoRestoreTimeoutSeconds` (90s by default)
     *    the watchdog would fire and restore *anyway*, mid-conversation — the
     *    exact outcome the original reasoning was trying to avoid, just at an
     *    arbitrary moment instead of a chosen one.
     *
     * Restoring at the moment of answering is the earliest point at which
     * restoring is harmless, and it retires the watchdog before it can fire.
     *
     * There is deliberately no "did we apply anything?" flag guarding this.
     * Such a flag lives in memory, so after a process restart it reads `false`
     * even though a snapshot is pending on disk — and would suppress the very
     * restore that matters most. [restore] is idempotent and cheap when nothing
     * is pending, so it is simply always called.
     */
    suspend fun onCallStateChanged(state: CallState) {
        if (state == CallState.RINGING) return

        // Same lock as onIncomingCall, and that is the whole point.
        //
        // RestoreAudioAndDnd has its own internal mutex, but that only
        // serialises restores against each other — it does nothing to stop a
        // restore interleaving with an *apply*. The failure that allows: the
        // user answers fast, so OFFHOOK arrives while the RINGING apply is
        // still inside its goAsync window. Restore then reads the snapshot,
        // puts the device back, clears the row and cancels the watchdog —
        // while apply is still part-way through raising the volume. The phone
        // is left modified with no pending snapshot and no watchdog, which is
        // exactly the stranded state this whole subsystem exists to prevent.
        //
        // Lock ordering is always coordinator -> restore, never the reverse,
        // so there is no inversion to deadlock on.
        mutex.withLock { restore(RestoreTrigger.CALL_ENDED) }
    }

    /**
     * Restore under the coordinator's lock, for callers outside the call path:
     * the WorkManager watchdog and Test Mode's "Restore now".
     *
     * Exists so the invariant is "**every** restore holds the coordinator
     * mutex" rather than "every restore except two". The watchdog happens to be
     * protected by timing — it fires on the same deadline the expiry gate uses,
     * so it should not overlap an apply — but that is an argument from
     * scheduling, and scheduling arguments stop being true when someone changes
     * a timeout. Test Mode has no such protection at all: tapping *Simulate*
     * and *Restore now* together interleaves an apply and a restore directly.
     *
     * Routing both here costs one uncontended lock acquisition and removes the
     * need to reason about either case.
     *
     * @return the restore outcome, so Test Mode can report what actually
     *   happened rather than assuming success.
     */
    suspend fun restoreNow(trigger: RestoreTrigger): Outcome<Unit> =
        mutex.withLock { restore(trigger) }

    /**
     * Trigger 3 — cold-start reconciliation (§ A.3).
     *
     * **Must not restore while a call is in progress.** The dangerous sequence:
     * the process is killed mid-ring, the next `PHONE_STATE` broadcast restarts
     * it, `Application.onCreate` runs — and an unconditional restore here would
     * put the phone back to vibrate and re-arm DND *while the priority call is
     * still ringing*, silencing the call this app exists to make audible. The
     * same race can also have a cold-start restore interleave with the apply for
     * a second call.
     *
     * So when telephony reports anything other than idle, reconciliation defers.
     * Nothing is lost by waiting: the pending snapshot stays on disk, the
     * WorkManager watchdog survived the process death, and
     * [onCallStateChanged] will restore as soon as the call is answered or ends.
     */
    suspend fun reconcileStaleRestore() = mutex.withLock {
        val snapshot = restoreRepository.getPending() ?: return@withLock

        // Gate 1 — is the snapshot actually *stale*?
        //
        // This trigger is named for recovering snapshots stranded by a process
        // kill, and a snapshot that has not yet reached its own auto-restore
        // deadline is not stranded: it is a live call being handled normally.
        //
        // This gate exists because gate 2 can lie. `getCallState()` /
        // `callStateForSubscription` read the default subscription, which the
        // platform itself warns may disagree with the broadcast — on a dual-SIM
        // phone the default subscription can look idle while the *other* SIM is
        // ringing. Missing permission and read failures also fail open to IDLE,
        // deliberately, because never restoring is the worse failure.
        //
        // So the expiry check carries the safety, not the state read: during a
        // live call the snapshot is by definition younger than its own deadline,
        // no matter what telephony claims. Waiting costs nothing — the watchdog
        // survived the process death, and OFFHOOK/IDLE from the receiver will
        // restore as soon as the call is genuinely over.
        val now = clock.nowEpochMs()
        if (now < snapshot.expiresAtEpochMs) {
            Timber.i(
                "Deferring cold-start restore: snapshot is %dms from its deadline",
                snapshot.expiresAtEpochMs - now,
            )
            return@withLock
        }

        // Gate 2 — belt and braces. Cheap, and catches the ordinary case where
        // telephony *is* reporting accurately.
        val state = telephony.currentCallState()
        if (state != CallState.IDLE) {
            Timber.i("Deferring cold-start restore: call state is %s", state)
            return@withLock
        }

        restore(RestoreTrigger.COLD_START_RECONCILIATION)
        Unit
    }
}
