package com.elham.priorityringer.domain.usecase

import com.elham.priorityringer.domain.escalation.EscalationDecision
import com.elham.priorityringer.domain.escalation.EscalationPolicy
import com.elham.priorityringer.domain.model.AppSettings
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.CallSnapshot
import com.elham.priorityringer.domain.model.FailureReason
import com.elham.priorityringer.domain.model.InterruptionFilter
import com.elham.priorityringer.domain.model.Outcome
import com.elham.priorityringer.domain.model.PriorityContact
import com.elham.priorityringer.domain.model.RingerMode
import com.elham.priorityringer.domain.model.VolumeSnapshot
import com.elham.priorityringer.domain.port.AlertPort
import com.elham.priorityringer.domain.port.AudioPort
import com.elham.priorityringer.domain.port.Clock
import com.elham.priorityringer.domain.port.DndPort
import com.elham.priorityringer.domain.port.RingtonePlayerPort
import com.elham.priorityringer.domain.port.SchedulerPort
import com.elham.priorityringer.domain.repository.AuditRepository
import com.elham.priorityringer.domain.repository.EscalationRepository
import com.elham.priorityringer.domain.repository.RestoreRepository
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * What the app managed to do for one priority call. Rendered by Dashboard and
 * Test Mode; every field corresponds to something the user can be told.
 */
data class ApplyResult(
    val contact: PriorityContact,
    val escalation: EscalationDecision,
    val snapshotSaved: Boolean,
    val dnd: Outcome<InterruptionFilter>?,
    val ringer: Outcome<RingerMode>?,
    val volume: Outcome<VolumeSnapshot>?,
    val alert: Outcome<AlertPort.AlertMode>?,

    /**
     * The alarm-stream fallback. `null` means it was never needed: the phone
     * was verified audible without it, which is the ordinary case.
     */
    val alarmAlert: Outcome<RingtonePlayerPort.AlertDelivery>? = null,
) {
    /**
     * True if *nothing* we attempted actually worked.
     *
     * The alarm-stream alert counts. It is the one attempt that can succeed
     * when every mutation failed — that is the entire reason it exists — and
     * telling the user "priority ringing had no effect" while their phone is
     * audibly ringing would be the worst kind of wrong report.
     */
    val allAttemptsFailed: Boolean
        get() = listOfNotNull(dnd, ringer, volume, alarmAlert).let { attempts ->
            attempts.isNotEmpty() && attempts.none { it.isSuccess }
        }

    val failures: List<Outcome.Failure>
        get() = listOfNotNull(dnd, ringer, volume, alert, alarmAlert)
            .mapNotNull { it.failureOrNull() }
}

/**
 * Apply the priority-ringing treatment for one matched call (FR3–FR5).
 *
 * Architecture.md § 4 requires this to **never throw out of process** — it runs
 * inside a `BroadcastReceiver.goAsync()` window (§ A.2), where an escaping
 * exception would kill the app during an incoming call.
 *
 * The ordering is the safety-critical part (§ A.3):
 *
 * ```
 * capture → PERSIST → schedule watchdog → mutate
 * ```
 *
 * If the process dies before the mutation, the persisted snapshot matches
 * reality and restoring is a harmless no-op. If it dies after, the snapshot is
 * on disk and one of the three restore triggers will find it. The window in
 * which a crash can strand the device is therefore empty.
 *
 * Steps 3–5 are **independently failable**: a denied DND permission does not
 * prevent the volume change from being attempted. That is "degrade gracefully"
 * made mechanical rather than aspirational.
 */
@Singleton
class ApplyPriorityRingUseCase @Inject constructor(
    private val audio: AudioPort,
    private val dnd: DndPort,
    private val alert: AlertPort,
    private val ringtonePlayer: RingtonePlayerPort,
    private val scheduler: SchedulerPort,
    private val restoreRepository: RestoreRepository,
    private val escalationRepository: EscalationRepository,
    private val audit: AuditRepository,
    private val escalationPolicy: EscalationPolicy,
    private val recordAudibleRingIndex: RecordAudibleRingIndexUseCase,
    private val clock: Clock,
) {

    suspend operator fun invoke(
        contact: PriorityContact,
        settings: AppSettings,
        isSimulated: Boolean = false,
    ): ApplyResult {
        val now = clock.nowEpochMs()

        audit.log(
            type = AuditEventType.PRIORITY_CALL_DETECTED,
            message = buildString {
                append("Priority call from ${contact.displayName} (${contact.redactedNumber})")
                if (isSimulated) append(" [simulated]")
            },
            relatedContactId = contact.id,
        )

        // ---- 1. Escalation (FR5) ------------------------------------------
        val escalation = evaluateEscalation(contact, settings, now)

        // ---- 2. Capture → persist → schedule, BEFORE touching anything ----
        //
        // The audible level is sampled here, before any mutation, because this
        // is the last moment it is the user's own. If the phone is audible
        // right now, this is their level; if it is silent, the reading is
        // worthless and the use case declines to record it, leaving whatever
        // was learned earlier.
        recordAudibleRingIndex()

        val snapshot = captureSnapshot(now, settings)
        val snapshotSaved = restoreRepository.saveIfAbsent(snapshot)

        if (snapshotSaved) {
            scheduler.scheduleRestoreWatchdog(settings.autoRestoreTimeoutSeconds)
        } else {
            // A snapshot from an earlier overlapping call is still pending.
            // Keeping it is correct: it holds the genuine pre-mutation state,
            // whereas what we just captured is already partly our own doing.
            Timber.i("Restore already pending; keeping original snapshot")
        }

        // ---- 3. DND (FR3) -------------------------------------------------
        val dndOutcome = applyDnd(settings)

        // ---- 4. Ringer mode (FR4) -----------------------------------------
        val ringerOutcome = applyRingerMode(snapshot.ringerMode)

        // ---- 5. Volume (FR4/FR5) ------------------------------------------
        val targetPercent = if (escalation.escalate) 100 else settings.ringtoneVolumePercent
        val volumeOutcome = applyVolume(targetPercent, escalation.escalate)

        // ---- 5b. Alarm-stream fallback, only if still inaudible ------------
        val alarmAlertOutcome = if (stillInaudible(dndOutcome)) {
            playAlarmStreamAlert(contact)
        } else {
            null
        }

        // ---- 6. Full-screen alert, escalation only (FR5) -------------------
        val alertOutcome = if (escalation.escalate) showAlert(contact) else null

        return ApplyResult(
            contact = contact,
            escalation = escalation,
            snapshotSaved = snapshotSaved,
            dnd = dndOutcome,
            ringer = ringerOutcome,
            volume = volumeOutcome,
            alert = alertOutcome,
            alarmAlert = alarmAlertOutcome,
        ).also { result ->
            if (result.allAttemptsFailed) {
                audit.log(
                    type = AuditEventType.ERROR,
                    message = "Priority ringing had no effect — every attempted " +
                        "change was blocked by the system or the device.",
                    relatedContactId = contact.id,
                    recoverable = true,
                )
            }
        }
    }

    /**
     * Is the phone *still* not going to ring, after everything above?
     *
     * This is a **re-read**, not an inspection of the outcomes, and the
     * distinction is the whole correctness of the feature. Neither outcome can
     * answer the question on its own:
     *
     *  - [applyRingerMode] returns `null` when the phone was already NORMAL.
     *    That is a success — nothing needed doing — but it is indistinguishable
     *    from "not attempted" in the `Outcome`.
     *  - A volume write can succeed at an index the user set to 0.
     *
     * Reading the device back is the only honest answer, and it is the same
     * discipline every mutation in this app already follows (§ 6.2).
     *
     * DND is the exception: there is nothing to re-read that distinguishes
     * "PRIORITY, with calls allowed by the bypass we just applied" from
     * "PRIORITY, suppressing this call" — [InterruptionFilter.maySuppressCalls]
     * is true for both, so using it here would fire the fallback on ordinary
     * successful calls and ring the phone twice, a few hundred milliseconds out
     * of phase. The port already made that judgement when it read the filter
     * back, so the failure reason is what is trusted.
     */
    private fun stillInaudible(dndOutcome: Outcome<InterruptionFilter>?): Boolean {
        val ringerSilent = audio.currentRingerMode() != RingerMode.NORMAL
        val volumeZero = audio.currentRingVolume().current == 0
        val dndBlocking = dndOutcome?.failureOrNull()?.reason in BLOCKING_DND_REASONS

        if (ringerSilent || volumeZero || dndBlocking) {
            Timber.i(
                "Phone still inaudible after apply (silent=%b, volume0=%b, dnd=%b); " +
                    "falling back to the alarm stream",
                ringerSilent,
                volumeZero,
                dndBlocking,
            )
            return true
        }
        return false
    }

    /**
     * The fallback: play the user's ringtone on the alarm stream.
     *
     * The audibility prediction is logged **before** the attempt, so a phone in
     * Total Silence produces an audit entry explaining why it stayed quiet
     * rather than an entry claiming an alert that nobody heard.
     */
    private suspend fun playAlarmStreamAlert(
        contact: PriorityContact,
    ): Outcome<RingtonePlayerPort.AlertDelivery> {
        val audibility = ringtonePlayer.alarmAudibility()

        when (audibility) {
            RingtonePlayerPort.AlarmAudibility.MUTED_BY_DND -> audit.log(
                type = AuditEventType.ALARM_STREAM_ALERT_LIKELY_INAUDIBLE,
                message = "Do Not Disturb on this phone is set to silence alarms " +
                    "too, so the backup alert probably cannot be heard. " +
                    "Allowing alarms in Do Not Disturb would let it through.",
                relatedContactId = contact.id,
                recoverable = true,
            )

            RingtonePlayerPort.AlarmAudibility.VOLUME_ZERO -> audit.log(
                type = AuditEventType.ALARM_STREAM_ALERT_LIKELY_INAUDIBLE,
                message = "The alarm volume on this phone is zero, so the backup " +
                    "alert probably cannot be heard. Raising the alarm volume " +
                    "would let it through.",
                relatedContactId = contact.id,
                recoverable = true,
            )

            RingtonePlayerPort.AlarmAudibility.AUDIBLE,
            RingtonePlayerPort.AlarmAudibility.UNKNOWN,
            -> Unit
        }

        // Attempted regardless of the prediction. The prediction reads settings;
        // it does not decide the outcome, and declining to try on the strength
        // of it would turn a guess into a silence.
        val outcome = ringtonePlayer.startAlarmStreamAlert()

        when (outcome) {
            is Outcome.Success -> audit.log(
                type = AuditEventType.ALARM_STREAM_ALERT_STARTED,
                message = "The ringer could not be made audible, so your ringtone " +
                    "is playing as an alarm instead" +
                    if (outcome.value == RingtonePlayerPort.AlertDelivery.IN_PROCESS) {
                        " (it may stop early if the system closes the app)."
                    } else {
                        "."
                    },
                relatedContactId = contact.id,
            )

            is Outcome.Failure -> audit.log(
                type = AuditEventType.ALARM_STREAM_ALERT_FAILED,
                message = "The backup alarm alert could not be started: " +
                    outcome.reason.name + (outcome.detail?.let { " ($it)" } ?: ""),
                relatedContactId = contact.id,
                recoverable = true,
            )
        }

        return outcome
    }

    private suspend fun evaluateEscalation(
        contact: PriorityContact,
        settings: AppSettings,
        now: Long,
    ): EscalationDecision {
        val since = escalationPolicy.pruneBeforeMs(now, settings.escalation)
        val previous = escalationRepository.timestampsSince(contact.matchKey, since)

        val decision = escalationPolicy.evaluate(previous, now, settings.escalation)

        // Record *after* evaluating so the current call isn't double-counted —
        // EscalationPolicy already counts it via the `+ 1`.
        escalationRepository.record(contact.matchKey, now)
        escalationRepository.pruneBefore(since)

        if (decision.escalate) {
            audit.log(
                type = AuditEventType.ESCALATION_TRIGGERED,
                message = "Repeat caller: ${decision.countInPrimaryWindow} calls in " +
                    "${settings.escalation.primaryWindowMinutes} min " +
                    "(${decision.trigger}). Raising to maximum volume.",
                relatedContactId = contact.id,
            )
        }
        return decision
    }

    private fun captureSnapshot(now: Long, settings: AppSettings) = CallSnapshot(
        ringerMode = audio.currentRingerMode(),
        ringVolume = audio.currentRingVolume(),
        interruptionFilter = dnd.currentFilter(),
        zenRuleId = dnd.activeZenRuleId(),
        capturedAtEpochMs = now,
        expiresAtEpochMs = now + settings.autoRestoreTimeoutSeconds * 1000L,
    )

    private suspend fun applyDnd(settings: AppSettings): Outcome<InterruptionFilter> {
        if (!dnd.hasPolicyAccess()) {
            val failure = Outcome.Failure(
                reason = FailureReason.NOTIFICATION_POLICY_ACCESS_DENIED,
                detail = "Notification policy access not granted",
            )
            audit.log(
                type = AuditEventType.PERMISSION_DENIED,
                message = "Could not adjust Do Not Disturb — notification policy " +
                    "access is not granted. Grant it in Permissions.",
            )
            return failure
        }

        val before = dnd.currentFilter()
        if (!before.maySuppressCalls) {
            // Nothing to bypass. Returning success here (rather than a no-op
            // failure) is correct: the desired end state already holds.
            return Outcome.Success(before)
        }

        val outcome = dnd.applyBypass(settings.dndBypassStrategy)

        audit.log(
            type = AuditEventType.DND_BYPASS_ATTEMPTED,
            message = "DND bypass (${settings.dndBypassStrategy}): $before → " +
                when (outcome) {
                    is Outcome.Success -> outcome.value.toString()
                    is Outcome.Failure -> "FAILED (${outcome.reason})"
                },
        )

        if (outcome is Outcome.Failure && outcome.reason == FailureReason.DND_BYPASS_INEFFECTIVE) {
            audit.log(
                type = AuditEventType.DND_BYPASS_INEFFECTIVE,
                message = "Do Not Disturb is still suppressing calls. On Android 15+ " +
                    "a stricter Do Not Disturb set by you or the system takes " +
                    "precedence and cannot be overridden by an app.",
            )
        }
        return outcome
    }

    private suspend fun applyRingerMode(currentMode: RingerMode): Outcome<RingerMode>? {
        if (currentMode == RingerMode.NORMAL) return null

        val outcome = audio.setRingerMode(RingerMode.NORMAL)

        when (outcome) {
            is Outcome.Success -> audit.log(
                type = AuditEventType.RINGER_MODE_CHANGED,
                message = "Ringer $currentMode → ${outcome.value}",
            )

            is Outcome.Failure -> {
                // Architecture.md § 7.3 — silent is a distinct, honest failure.
                val type = if (outcome.reason == FailureReason.SILENT_NOT_OVERRIDDEN) {
                    AuditEventType.SILENT_NOT_OVERRIDDEN
                } else {
                    AuditEventType.RINGER_CHANGE_FAILED
                }
                audit.log(
                    type = type,
                    message = when (outcome.reason) {
                        FailureReason.SILENT_NOT_OVERRIDDEN ->
                            "Phone is in Silent mode and stayed silent. Silent is " +
                                "controlled by you and the device manufacturer; an " +
                                "app cannot reliably override it."

                        else -> "Could not switch ringer out of $currentMode " +
                            "(${outcome.reason})."
                    },
                    recoverable = true,
                )
            }
        }
        return outcome
    }

    private suspend fun applyVolume(
        targetPercent: Int,
        escalated: Boolean,
    ): Outcome<VolumeSnapshot> {
        // § 7.1 — check before attempting, so the audit says "impossible on
        // this device" rather than a misleading generic failure.
        if (audio.isVolumeFixed()) {
            audit.log(
                type = AuditEventType.VOLUME_FIXED,
                message = "This device reports a fixed output volume, so ring " +
                    "volume cannot be changed. Ringer mode was still adjusted.",
            )
            return Outcome.Failure(
                reason = FailureReason.VOLUME_FIXED,
                detail = "AudioManager.isVolumeFixed() == true",
            )
        }

        val outcome = audio.setRingVolumePercent(targetPercent)

        when (outcome) {
            is Outcome.Success -> audit.log(
                type = AuditEventType.VOLUME_CHANGED,
                message = "Ring volume → ${outcome.value.percent}%" +
                    if (escalated) " (escalated to maximum)" else "",
            )

            is Outcome.Failure -> audit.log(
                type = AuditEventType.VOLUME_CHANGE_FAILED,
                message = "Could not set ring volume to $targetPercent% " +
                    "(${outcome.reason}).",
            )
        }
        return outcome
    }

    private suspend fun showAlert(contact: PriorityContact): Outcome<AlertPort.AlertMode> {
        val outcome = alert.showPriorityAlert(contact)

        when (outcome) {
            is Outcome.Success -> audit.log(
                type = if (outcome.value == AlertPort.AlertMode.FULL_SCREEN) {
                    AuditEventType.FULL_SCREEN_ALERT_SHOWN
                } else {
                    AuditEventType.FULL_SCREEN_ALERT_FALLBACK
                },
                message = when (outcome.value) {
                    AlertPort.AlertMode.FULL_SCREEN ->
                        "Full-screen alert shown for ${contact.displayName}."

                    AlertPort.AlertMode.HEADS_UP_FALLBACK ->
                        "Full-screen notifications are not allowed for this app, " +
                            "so a heads-up notification was shown instead."
                },
                relatedContactId = contact.id,
            )

            is Outcome.Failure -> audit.log(
                type = AuditEventType.ERROR,
                message = "Could not show priority alert (${outcome.reason}).",
                relatedContactId = contact.id,
            )
        }
        return outcome
    }

    private companion object {
        /**
         * DND failures that mean the call will not be heard.
         *
         * `DND_BYPASS_INEFFECTIVE` is the Android 15 most-restrictive-wins case
         * (§ A.1); without notification-policy access the app cannot relax DND
         * at all. Every other failure reason leaves the filter untouched, which
         * may still be perfectly audible.
         */
        val BLOCKING_DND_REASONS = setOf(
            FailureReason.DND_BYPASS_INEFFECTIVE,
            FailureReason.NOTIFICATION_POLICY_ACCESS_DENIED,
        )
    }
}
