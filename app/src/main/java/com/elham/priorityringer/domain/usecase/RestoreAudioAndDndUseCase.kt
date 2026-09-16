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
        // **Ringer mode before volume**, mirroring the order apply uses, and
        // the volume write is then skipped unless it can be made without
        // disturbing that mode. See [restoreVolume].
        val ringerOutcome = audio.setRingerMode(snapshot.ringerMode)
        val volumeOutcome = restoreVolume(
            index = snapshot.ringVolume.current,
            restoredMode = snapshot.ringerMode,
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
     * Put the ring volume back — but only when doing so cannot move the ringer
     * mode, because on Android the two are not independent settings.
     *
     * `AudioService.onSetStreamVolume` treats the ring stream's index as the
     * silent/vibrate control. Writing **0** puts the device into VIBRATE (or
     * SILENT, depending on the user's vibrate-when-ringing setting); writing
     * anything **above 0** takes it out of silent into NORMAL. So a volume
     * write is also a ringer-mode write, whether or not it was meant as one.
     *
     * That is a real bug found on a real phone, not a theoretical one: a phone
     * that was on **Silent** came back from a call on **Vibrate**. Restore set
     * the mode to SILENT correctly, then wrote the snapshot's index of 0 — and
     * that write moved the mode to VIBRATE, undoing the line above it. The user
     * ends up with a phone that buzzes at night when they had chosen silence,
     * and the audit log says the restore succeeded, because each step did.
     *
     * So the volume is written only when restoring to NORMAL with an audible
     * index. In every other case the ringer mode already carries the whole
     * state:
     *
     * - **SILENT / VIBRATE.** The platform forces the index to 0 and the phone
     *   is inaudible either way. There is nothing a write could add, and as
     *   above, it actively breaks the mode.
     * - **NORMAL with index 0.** Not a state the platform really holds — index
     *   0 *is* silent — so writing it would flip the mode straight back out of
     *   NORMAL.
     *
     * Skipping is reported as success because it is one: the device is in the
     * state the snapshot describes. Claiming a failure here would be the same
     * dishonesty in the other direction.
     *
     * **Known residue, and it is not fixable from what we captured.** While the
     * phone was silent, `getStreamVolume` reported 0, so 0 is all the snapshot
     * holds — the user's own audible ring level was never visible to us. Apply
     * did set an audible index on the way up, and the system remembers that as
     * the last audible level. If the user later switches the ringer back to
     * Normal by hand, they may find it louder than they left it. Recorded here
     * rather than papered over; fixing it needs apply to capture the audible
     * index before raising it.
     */
    private fun restoreVolume(index: Int, restoredMode: RingerMode): Outcome<Unit> {
        if (audio.isVolumeFixed()) {
            // We never changed it, so there is nothing to put back.
            return Outcome.ok()
        }

        if (restoredMode != RingerMode.NORMAL || index <= 0) {
            Timber.i(
                "Not writing ring index %d: restoring to %s, where the ringer mode " +
                    "carries the state and a volume write would move it",
                index,
                restoredMode,
            )
            return Outcome.ok()
        }

        return audio.setRingVolumeRaw(index).map { }
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
