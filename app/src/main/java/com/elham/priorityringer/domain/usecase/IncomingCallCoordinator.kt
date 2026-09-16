package com.elham.priorityringer.domain.usecase

import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.CallMatchResult
import com.elham.priorityringer.domain.model.CallState
import com.elham.priorityringer.domain.model.IncomingCallEvent
import com.elham.priorityringer.domain.repository.AuditRepository
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
 * 4. `IDLE` after ringing → restore.
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
) {
    private val mutex = Mutex()

    private val _lastResult = MutableStateFlow<ApplyResult?>(null)

    /** Most recent apply attempt, for Dashboard and Test Mode. */
    val lastResult: StateFlow<ApplyResult?> = _lastResult.asStateFlow()

    /** Have we applied changes for a call that has not yet ended? */
    @Volatile
    private var ringingHandled: Boolean = false

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
                ringingHandled = true
            }
        }
    }

    /**
     * Drive restore from call state (§ 5.2, § A.3 trigger 1).
     *
     * Only [CallState.IDLE] restores. `OFFHOOK` means the call was answered and
     * is in progress — restoring then would drop ring volume mid-conversation
     * for no benefit, and the call could still be rejected and re-ring.
     */
    suspend fun onCallStateChanged(state: CallState) {
        if (state != CallState.IDLE) return
        if (!ringingHandled) return

        ringingHandled = false
        restore(RestoreTrigger.CALL_ENDED)
    }

    /**
     * Trigger 3 — cold-start reconciliation. Called from `Application.onCreate`
     * and from the receiver, so a snapshot stranded by a process kill is always
     * found. A no-op when nothing is pending.
     */
    suspend fun reconcileStaleRestore() {
        restore(RestoreTrigger.COLD_START_RECONCILIATION)
    }
}
