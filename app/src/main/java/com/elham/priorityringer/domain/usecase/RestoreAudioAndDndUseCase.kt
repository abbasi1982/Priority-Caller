package com.elham.priorityringer.domain.usecase

import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.FailureReason
import com.elham.priorityringer.domain.model.InterruptionFilter
import com.elham.priorityringer.domain.model.Outcome
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
        val volumeOutcome = restoreVolume(snapshot.ringVolume.current)
        val ringerOutcome = audio.setRingerMode(snapshot.ringerMode)
        val dndOutcome = restoreDnd(snapshot.interruptionFilter, snapshot.zenRuleId)

        val failures = listOfNotNull(
            volumeOutcome.failureOrNull(),
            ringerOutcome.failureOrNull(),
            dndOutcome.failureOrNull(),
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
                message = "Restore was incomplete (trigger: $trigger): " +
                    failures.joinToString { it.reason.name } +
                    ". Check your ringer and Do Not Disturb settings.",
                recoverable = false,
            )
            failures.first()
        }
    }

    private fun restoreVolume(index: Int): Outcome<Unit> =
        if (audio.isVolumeFixed()) {
            // We never changed it, so there is nothing to put back.
            Outcome.ok()
        } else {
            audio.setRingVolumeRaw(index).map { }
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
