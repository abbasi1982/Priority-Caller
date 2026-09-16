package com.elham.priorityringer.domain.usecase

import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.FailureReason
import com.elham.priorityringer.domain.model.InterruptionFilter
import com.elham.priorityringer.domain.model.Outcome
import com.elham.priorityringer.domain.model.RingerMode
import com.elham.priorityringer.domain.model.map
import com.elham.priorityringer.domain.port.AlertPort
import com.elham.priorityringer.domain.port.AudioPort
import com.elham.priorityringer.domain.port.Clock
import com.elham.priorityringer.domain.port.DndPort
import com.elham.priorityringer.domain.port.SchedulerPort
import com.elham.priorityringer.domain.repository.AuditRepository
import com.elham.priorityringer.domain.repository.RestoreRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Which trigger invoked a restore. Recorded so the audit log explains itself. */
enum class RestoreTrigger {
    /** `CallStateListener` saw IDLE — the normal path. */
    CALL_ENDED,

    /** WorkManager watchdog fired; the call-end signal never arrived. */
    WATCHDOG_TIMEOUT,

    /** A pending snapshot was found at start-up — the process had been killed. */
    COLD_START_RECONCILIATION,

    /** User pressed "Restore now" in Test Mode (FR6). */
    MANUAL,
}

/**
 * Put the device back exactly as it was (FR3, FR4).
 *
 * This is the most important use case in the app. The failure it exists to
 * prevent is not "the phone didn't ring loudly" — it is:
 *
 * > the phone is left permanently off Do-Not-Disturb at maximum ring volume,
 * > because the call-end callback never arrived or the process was killed.
 *
 * which is strictly worse than the app not working at all.
 *
 * Three properties make that impossible rather than unlikely:
 *
 * 1. **Durable.** The snapshot lives in Room, written before any mutation
 *    (Architecture.md § A.3). A killed process loses nothing.
 * 2. **Idempotent.** Running it three times is harmless; running it zero times
 *    is the only unacceptable outcome. Hence three independent triggers
 *    ([RestoreTrigger]) all routed here.
 * 3. **Serialised.** A [Mutex] keeps concurrent triggers from interleaving —
 *    the in-process ordering guarantee Architecture.md § 4 asks for.
 *
 * Restoration is best-effort per field: failing to restore DND must not prevent
 * restoring volume.
 */
@Singleton
class RestoreAudioAndDndUseCase @Inject constructor(
    private val audio: AudioPort,
    private val dnd: DndPort,
    private val alert: AlertPort,
    private val scheduler: SchedulerPort,
    private val restoreRepository: RestoreRepository,
    private val audit: AuditRepository,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    suspend operator fun invoke(trigger: RestoreTrigger): Outcome<Unit> = mutex.withLock {
        val snapshot = restoreRepository.getPending()
            ?: return@withLock Outcome.Failure(
                reason = FailureReason.NOTHING_TO_DO,
                detail = "No pending snapshot",
            ).also {
                // Entirely expected: the watchdog routinely fires after a normal
                // call-end restore has already cleaned up. Not worth an audit row.
                Timber.d("Restore (%s): nothing pending", trigger)
                scheduler.cancelRestoreWatchdog()
            }

        if (trigger == RestoreTrigger.COLD_START_RECONCILIATION) {
            val ageSeconds = (clock.nowEpochMs() - snapshot.capturedAtEpochMs) / 1000
            audit.log(
                type = AuditEventType.STALE_RESTORE_RECOVERED,
                message = "Found audio settings left modified ${ageSeconds}s ago " +
                    "(the app was stopped mid-call). Restoring them now.",
            )
        }

        alert.dismissAlert()

        // Each restore is attempted regardless of the others' outcomes.
        //
        // **Ringer mode before volume**, mirroring the order apply uses. The
        // reverse order verifies the ring index in the wrong ringer context:
        // restoring a SILENT phone means writing index 0, and on AOSP index 0
        // on the ring stream *is* a ringer-mode change
        // (`AudioService.onSetStreamVolume`), so the write races the mode it is
        // about to be corrected by. Settling the mode first means the index is
        // written against the state it belongs to.
        val ringerOutcome = audio.setRingerMode(snapshot.ringerMode)
        val volumeOutcome = restoreVolume(
            index = snapshot.ringVolume.current,
            restoredMode = snapshot.ringerMode,
            ringerRestored = ringerOutcome.isSuccess,
        )
        val dndOutcome = restoreDnd(snapshot.interruptionFilter, snapshot.zenRuleId)

        val failures = listOfNotNull(
            ringerOutcome.failureOrNull()?.let { "ringer" to it },
            volumeOutcome.failureOrNull()?.let { "volume" to it },
            dndOutcome.failureOrNull()?.let { "DND" to it },
        )

        // Clear unconditionally. A retained snapshot would be re-applied on the
        // next trigger and could stomp on changes the user has since made by
        // hand — worse than leaving one field unrestored, which is already
        // recorded below and shown as a persistent Dashboard banner (§ 6.3).
        restoreRepository.clear()
        scheduler.cancelRestoreWatchdog()

        if (failures.isEmpty()) {
            audit.log(
                type = AuditEventType.RESTORATION_COMPLETED,
                message = "Restored ringer ${snapshot.ringerMode}, volume " +
                    "${snapshot.ringVolume.percent}%, DND " +
                    "${snapshot.interruptionFilter} (trigger: $trigger).",
            )
            Outcome.ok()
        } else {
            audit.log(
                type = AuditEventType.RESTORATION_FAILED,
                // Name the field and carry the detail. "VERIFICATION_FAILED"
                // alone does not say which of three fields failed or what the
                // device actually reported, which leaves the user with nothing
                // to act on and the next diagnosis with nothing to go on —
                // in an app whose whole promise is saying plainly what did not
                // work.
                message = "Restore was incomplete (trigger: $trigger): " +
                    failures.joinToString("; ") { (field, failure) ->
                        "$field ${failure.reason.name}" +
                            (failure.detail?.let { " ($it)" } ?: "")
                    } +
                    ". Check your ringer and Do Not Disturb settings.",
                recoverable = false,
            )
            failures.first().second
        }
    }

    /**
     * @param restoredMode the ringer mode this restore is putting back.
     * @param ringerRestored whether that mode was actually re-established.
     *
     * The ring-stream index is not user-observable in SILENT or VIBRATE — the
     * platform forces it to 0 and the phone is silent either way. Devices
     * disagree about what `getStreamVolume` then reports (0, or a retained
     * non-zero index), so a read-back mismatch there describes a difference
     * nobody can hear.
     *
     * Reporting it as a failure would tell the user to go and check settings
     * that are already correct, which is worse than saying nothing: it spends
     * the credibility the honest failures need. So the mismatch is downgraded —
     * but **only** when the ringer mode itself was verifiably restored. If the
     * mode did not go back, the phone may really be sitting at NORMAL with the
     * wrong volume, and that is a genuine failure the user must hear about.
     *
     * The write is always attempted. Only the verification is relaxed.
     */
    private fun restoreVolume(
        index: Int,
        restoredMode: RingerMode,
        ringerRestored: Boolean,
    ): Outcome<Unit> {
        if (audio.isVolumeFixed()) {
            // We never changed it, so there is nothing to put back.
            return Outcome.ok()
        }

        val outcome = audio.setRingVolumeRaw(index).map { }
        val failure = outcome.failureOrNull() ?: return outcome

        val inaudibleMode = restoredMode == RingerMode.SILENT || restoredMode == RingerMode.VIBRATE
        return if (
            failure.reason == FailureReason.VERIFICATION_FAILED &&
            inaudibleMode &&
            ringerRestored
        ) {
            Timber.i(
                "Ring index read-back mismatch in %s (%s); ringer mode restored, so not a failure",
                restoredMode,
                failure.detail,
            )
            Outcome.ok()
        } else {
            outcome
        }
    }

    private fun restoreDnd(filter: InterruptionFilter, zenRuleId: String?): Outcome<Unit> =
        if (!dnd.hasPolicyAccess()) {
            // Without access we could not have changed DND either, so this is
            // not a real failure.
            Outcome.ok()
        } else {
            dnd.restore(filter, zenRuleId)
        }
}
