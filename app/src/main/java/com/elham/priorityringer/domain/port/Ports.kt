package com.elham.priorityringer.domain.port

import com.elham.priorityringer.domain.model.CallState
import com.elham.priorityringer.domain.model.CapabilityReport
import com.elham.priorityringer.domain.model.DndBypassStrategy
import com.elham.priorityringer.domain.model.InterruptionFilter
import com.elham.priorityringer.domain.model.Outcome
import com.elham.priorityringer.domain.model.PriorityContact
import com.elham.priorityringer.domain.model.RingerMode
import com.elham.priorityringer.domain.model.VolumeSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * The Android seam (Architecture.md § A.4, § 14).
 *
 * Everything touching `AudioManager`, `NotificationManager`, `TelephonyManager`
 * or `RoleManager` lives behind these interfaces. Implementations are in
 * `data/platform`; the use cases see only this file, which is what lets the
 * mutate-then-restore logic — the most dangerous code in the app — be tested
 * with fakes on a machine with no device attached.
 *
 * Every mutating method returns [Outcome] and never throws.
 */

/** Injectable time source. Architecture.md § 14 requires this for window tests. */
interface Clock {
    fun nowEpochMs(): Long
}

/**
 * Ringer mode and ring-stream volume (FR4).
 *
 * Implementations must follow the § 7 sequence: check [isVolumeFixed] first,
 * attempt the change, then **read the value back** and report
 * [com.elham.priorityringer.domain.model.FailureReason.VERIFICATION_FAILED] if
 * the device did not comply. Architecture.md § 6.2 is explicit: claiming
 * success without reading back is forbidden.
 */
interface AudioPort {
    fun currentRingerMode(): RingerMode
    fun currentRingVolume(): VolumeSnapshot

    /** § 7.1 — when true, volume changes are impossible and must be skipped. */
    fun isVolumeFixed(): Boolean

    /**
     * @return [Outcome.Success] only if a read-back confirms the new mode.
     *   Attempting SILENT → NORMAL may legitimately fail with
     *   `SILENT_NOT_OVERRIDDEN`; that is a supported result, not a bug.
     */
    fun setRingerMode(mode: RingerMode): Outcome<RingerMode>

    /** @param percent 0–100 of the ring stream maximum. Verified by read-back. */
    fun setRingVolumePercent(percent: Int): Outcome<VolumeSnapshot>

    fun setRingVolumeRaw(index: Int): Outcome<VolumeSnapshot>
}

/**
 * Do Not Disturb (FR3).
 *
 * Two implementations exist per Architecture.md § A.1, selected at runtime from
 * `targetSdkVersion`: the legacy global-filter path (< 35) and the
 * `AutomaticZenRule` path (35+). Callers cannot tell them apart.
 */
interface DndPort {
    fun hasPolicyAccess(): Boolean
    fun currentFilter(): InterruptionFilter

    /**
     * Relax DND for an incoming priority call.
     *
     * Must re-read the filter afterwards. If it is unchanged and still
     * suppressing, return `DND_BYPASS_INEFFECTIVE` rather than success — on
     * Android 15+ a stricter manual DND wins under most-restrictive-wins
     * semantics and the app genuinely cannot override it (§ 6.2).
     */
    fun applyBypass(strategy: DndBypassStrategy): Outcome<InterruptionFilter>

    /** Idempotent. Safe to call with no bypass active. */
    fun restore(filter: InterruptionFilter, zenRuleId: String?): Outcome<Unit>

    /** Id of the zen rule created by [applyBypass], on the 35+ branch only. */
    fun activeZenRuleId(): String?
}

/**
 * Call state for restore (Architecture.md § 5.2).
 *
 * `TelephonyCallback.CallStateListener` carries no caller number — AOSP omits
 * it deliberately — so this port is state-only. Number identification comes
 * from the `PHONE_STATE` broadcast instead.
 */
interface TelephonyPort {
    fun callState(): Flow<CallState>

    /**
     * Call state *right now*, without waiting for an emission.
     *
     * Needed by cold-start reconciliation: a process that has just been
     * restarted has no [callState] history yet, and must not restore the
     * device's audio while a call is actively ringing. Returns
     * [CallState.IDLE] when the state cannot be read — the conservative answer
     * would be to block restore forever, which is worse than restoring early.
     */
    fun currentCallState(): CallState

    /** ISO-3166 alpha-2 from network, falling back to SIM then locale (§ 5.3). */
    fun defaultCountryIso(): String?

    /**
     * Platform second-opinion on number equality, layered *after* the pure
     * matcher. May only turn a non-match into a match (see
     * `PhoneNumberNormalizer` kdoc).
     */
    fun platformNumbersMatch(a: String, b: String): Boolean
}

/** Full-screen escalation alert (FR5). */
interface AlertPort {
    fun showPriorityAlert(contact: PriorityContact): Outcome<AlertMode>
    fun dismissAlert()

    enum class AlertMode {
        FULL_SCREEN,

        /**
         * API 34+ withheld full-screen intent permission, so a high-priority
         * heads-up notification was posted instead. Degraded, not failed.
         */
        HEADS_UP_FALLBACK,
    }
}

/**
 * The last-resort alert: the user's ringtone, played by this app on the **alarm
 * stream** (FR4 fallback).
 *
 * Everything else in this app works by mutating the device and putting it back.
 * Two cases defeat that, both documented and neither a bug: Silent may refuse to
 * become audible (`SILENT_NOT_OVERRIDDEN`), and on Android 15+ a Do Not Disturb
 * mode the user set themselves wins under most-restrictive-wins
 * (`DND_BYPASS_INEFFECTIVE`). In those cases the app has, until now, only been
 * able to explain itself.
 *
 * This port takes the other route: it changes nothing and plays the sound
 * itself, with `AudioAttributes.USAGE_ALARM`. Ringer mode does not silence the
 * alarm stream, so Silent stops mattering; ordinary DND does not filter it, so
 * most-restrictive-wins stops mattering.
 *
 * **It is not a universal bypass, and must never be described as one.** The
 * platform reference is explicit that when a Do Not Disturb policy disallows
 * alarms, "the alarm stream will be muted when DND is active" — and Total
 * Silence disallows them. That is what [alarmAudibility] exists to say *before*
 * the attempt, so the audit log records a prediction rather than a surprise.
 *
 * Implementations must never throw, and [stopAlarmStreamAlert] must be
 * idempotent and safe from any thread: it is on the path that runs when the user
 * answers the phone.
 */
interface RingtonePlayerPort {

    /**
     * Whether an alarm-stream alert would actually be heard right now.
     *
     * Read *before* playing. `UNKNOWN` is an honest answer — without
     * notification-policy access the app cannot read the DND policy, and
     * guessing would defeat the purpose.
     */
    fun alarmAudibility(): AlarmAudibility

    /**
     * Begin looping the user's ringtone on the alarm stream.
     *
     * Returns success only if playback is confirmed to have started, per the
     * read-back rule in Architecture.md § 6.2. Any previous alert is stopped
     * first, so two can never overlap.
     */
    fun startAlarmStreamAlert(): Outcome<AlertDelivery>

    /** Idempotent, synchronous, callable from any thread. */
    fun stopAlarmStreamAlert()

    enum class AlarmAudibility {
        AUDIBLE,

        /** Total Silence, or a DND policy that disallows alarms. */
        MUTED_BY_DND,

        /** The user's alarm volume is zero or the stream is muted. */
        VOLUME_ZERO,

        /** No notification-policy access, so the policy cannot be read. */
        UNKNOWN,
    }

    enum class AlertDelivery {
        /** Held up by a foreground service; survives the broadcast window. */
        FOREGROUND_SERVICE,

        /**
         * Playing from the app process with nothing holding it up. The system
         * may reclaim the process and cut the alert short. Degraded, not
         * failed — and the audit log says so.
         */
        IN_PROCESS,
    }
}

/**
 * Watchdog scheduling (Architecture.md § A.2, § A.3).
 *
 * Backed by WorkManager so the restore deadline survives process death — the
 * whole point of the third restore trigger.
 */
interface SchedulerPort {
    fun scheduleRestoreWatchdog(afterSeconds: Int)
    fun cancelRestoreWatchdog()
}

/** Builds the live [CapabilityReport] for Dashboard, Permissions and Test Mode. */
interface CapabilityPort {
    fun report(): CapabilityReport
}
