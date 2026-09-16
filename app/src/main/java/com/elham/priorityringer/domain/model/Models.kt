package com.elham.priorityringer.domain.model

import com.elham.priorityringer.domain.phone.NormalizedNumber

/**
 * Domain models. Architecture.md § 3 and § 10: these carry no Room annotations
 * and no `android.*` imports; mapping to entities happens in `data`.
 */

// ---------------------------------------------------------------------------
// FR1 — Priority contacts
// ---------------------------------------------------------------------------

data class PriorityContact(
    val id: Long = 0L,
    val displayName: String,
    /** Exactly what the user picked or typed. Shown in the UI. */
    val originalInput: String,
    /** Normalised form used for matching (Architecture.md § 10 unique index). */
    val matchKey: String,
    val e164: String?,
    val enabled: Boolean = true,
    val createdAtEpochMs: Long,
) {
    val redactedNumber: String
        get() = if (matchKey.length >= 4) "•••${matchKey.takeLast(4)}" else "•••"
}

/** How a contact came to be in the list — affects nothing but the UI label. */
enum class ContactSource { DEVICE_PICKER, MANUAL_ENTRY }

// ---------------------------------------------------------------------------
// FR2 — Incoming calls
// ---------------------------------------------------------------------------

data class IncomingCallEvent(
    val number: NormalizedNumber,
    val timestampEpochMs: Long,
    val isSimulated: Boolean = false,
)

/** Outcome of comparing an incoming call against the configured list. */
sealed interface CallMatchResult {
    /** Not a priority number. Audio and DND must be left completely alone. */
    data object Miss : CallMatchResult

    /** Matched, but the user has toggled this contact off (FR1). */
    data class DisabledPriority(val contact: PriorityContact) : CallMatchResult

    data class EnabledPriority(val contact: PriorityContact) : CallMatchResult

    /**
     * `PHONE_STATE` arrived without a usable number. Architecture.md § A.5 —
     * with `READ_CALL_LOG` denied this is the *only* result the app can ever
     * produce, which is why that state is surfaced as "inert", not "reduced".
     */
    data object NumberUnavailable : CallMatchResult
}

enum class CallState { RINGING, OFFHOOK, IDLE }

// ---------------------------------------------------------------------------
// Audio / DND state
// ---------------------------------------------------------------------------

enum class RingerMode { SILENT, VIBRATE, NORMAL, UNKNOWN }

enum class InterruptionFilter {
    /** No DND — everything comes through. */
    ALL,
    PRIORITY,
    ALARMS,
    NONE,
    UNKNOWN,
    ;

    /**
     * Might this filter suppress a normal incoming call ring?
     *
     * Deliberately **conservative**, and deliberately not a verdict.
     * `PRIORITY` is included because whether it lets calls through depends on
     * the user's notification *policy*, which this enum cannot see. Treating it
     * as possibly-suppressing means the app attempts a bypass rather than
     * assuming it is unnecessary.
     *
     * This is the right test for "should I try?". It is the **wrong** test for
     * "did it work?" — answering that requires reading the policy, which is why
     * the effectiveness check lives in `AndroidDndPort` where the policy is
     * available. Using this property for the read-back would classify the
     * default strategy's own intended end state as a failure.
     */
    val maySuppressCalls: Boolean
        get() = this == ALARMS || this == NONE || this == PRIORITY || this == UNKNOWN
}

data class VolumeSnapshot(
    val current: Int,
    val max: Int,
) {
    val percent: Int get() = if (max <= 0) 0 else (current * 100) / max

    companion object {
        val UNKNOWN = VolumeSnapshot(0, 0)
    }
}

/**
 * Device audio + DND state captured immediately before any mutation.
 *
 * Architecture.md § 3 defines this as in-memory. § A.3 adds durable backing via
 * `PendingRestoreEntity` — the type is unchanged, it is simply also written to
 * disk before mutation so that a process death cannot strand the device in a
 * modified state.
 */
data class CallSnapshot(
    val ringerMode: RingerMode,
    val ringVolume: VolumeSnapshot,
    val interruptionFilter: InterruptionFilter,
    /** Id of an `AutomaticZenRule` we created (target 35+ branch, § A.1). */
    val zenRuleId: String? = null,
    val capturedAtEpochMs: Long,
    /** When the watchdog should force restore even with no call-end signal. */
    val expiresAtEpochMs: Long,
)

// ---------------------------------------------------------------------------
// FR7 — Audit log
// ---------------------------------------------------------------------------

/**
 * Architecture.md § 13. Names are taken verbatim from the contract, including
 * the reviewer's additions beyond the original FR7 list.
 */
enum class AuditEventType {
    PRIORITY_CALL_DETECTED,
    DND_BYPASS_ATTEMPTED,
    RINGER_MODE_CHANGED,
    VOLUME_CHANGED,
    RESTORATION_COMPLETED,
    ERROR,

    // Reviewer additions — the cases that make failures legible.
    NUMBER_UNAVAILABLE,
    DND_BYPASS_INEFFECTIVE,
    SILENT_NOT_OVERRIDDEN,
    VOLUME_FIXED,

    // Implementation additions, all covering states the UI must explain.
    RINGER_CHANGE_FAILED,
    VOLUME_CHANGE_FAILED,
    RESTORATION_FAILED,
    ESCALATION_TRIGGERED,
    FULL_SCREEN_ALERT_SHOWN,
    FULL_SCREEN_ALERT_FALLBACK,
    PERMISSION_DENIED,
    STALE_RESTORE_RECOVERED,
    SIMULATION_RUN,
    CONTACT_ADDED,
    CONTACT_REMOVED,
    ;

    val severity: AuditSeverity
        get() = when (this) {
            ERROR, RESTORATION_FAILED, RINGER_CHANGE_FAILED, VOLUME_CHANGE_FAILED ->
                AuditSeverity.ERROR

            DND_BYPASS_INEFFECTIVE, SILENT_NOT_OVERRIDDEN, VOLUME_FIXED,
            NUMBER_UNAVAILABLE, PERMISSION_DENIED, FULL_SCREEN_ALERT_FALLBACK,
            STALE_RESTORE_RECOVERED,
            -> AuditSeverity.WARNING

            else -> AuditSeverity.INFO
        }

    /**
     * Should this event raise the persistent Dashboard banner?
     *
     * Architecture.md § 4 (rule 5) and § 6.3 require a *persistent* surface for
     * "restore failed" and "DND ineffective". Severity alone is the wrong test:
     * `DND_BYPASS_INEFFECTIVE` and `SILENT_NOT_OVERRIDDEN` are only WARNING —
     * they are expected platform limits rather than malfunctions — yet they are
     * exactly the outcomes the user must be told about, because they mean a
     * priority call did not ring the way they were expecting.
     */
    val raisesPersistentBanner: Boolean
        get() = severity == AuditSeverity.ERROR ||
            this == DND_BYPASS_INEFFECTIVE ||
            this == SILENT_NOT_OVERRIDDEN ||
            this == STALE_RESTORE_RECOVERED
}

enum class AuditSeverity { INFO, WARNING, ERROR }

data class AuditLogEntry(
    val id: Long = 0L,
    val timestampEpochMs: Long,
    val type: AuditEventType,
    /** Human-readable. Never contains a full phone number (§ 13). */
    val message: String,
    val relatedContactId: Long? = null,
    /**
     * Architecture.md § 3: separates a platform restriction the user can
     * understand and possibly fix from an unexpected crash.
     */
    val recoverable: Boolean = true,
) {
    val severity: AuditSeverity get() = type.severity
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

data class EscalationThresholds(
    val enabled: Boolean = true,
    val primaryCallCount: Int = 2,
    val primaryWindowMinutes: Int = 5,
    val secondaryCallCount: Int = 3,
    val secondaryWindowMinutes: Int = 10,
)

/**
 * Single-row settings (Architecture.md § 3, id = 1).
 */
data class AppSettings(
    val ringtoneVolumePercent: Int = 80,
    val escalation: EscalationThresholds = EscalationThresholds(),
    val autoRestoreTimeoutSeconds: Int = 90,
    /**
     * When false, informational events are dropped — but **errors are still
     * persisted**, per the reviewer's recommendation in § 3. A user who turns
     * logging off still needs to be able to find out why the app failed.
     */
    val loggingEnabled: Boolean = true,
    val dndBypassStrategy: DndBypassStrategy = DndBypassStrategy.PRIORITY_ALLOW_CALLS,
) {
    fun validated(): AppSettings = copy(
        ringtoneVolumePercent = ringtoneVolumePercent.coerceIn(MIN_VOLUME_PERCENT, 100),
        autoRestoreTimeoutSeconds = autoRestoreTimeoutSeconds
            .coerceIn(MIN_RESTORE_TIMEOUT_SECONDS, MAX_RESTORE_TIMEOUT_SECONDS),
        escalation = escalation.copy(
            primaryCallCount = escalation.primaryCallCount.coerceIn(2, 10),
            primaryWindowMinutes = escalation.primaryWindowMinutes.coerceIn(1, 60),
            secondaryCallCount = escalation.secondaryCallCount.coerceIn(2, 10),
            secondaryWindowMinutes = escalation.secondaryWindowMinutes.coerceIn(1, 120),
        ),
    )

    companion object {
        /**
         * Not 0. A "priority" ringer set to silent is a contradiction, and
         * allowing it would let the user configure the app into doing nothing
         * while appearing armed.
         */
        const val MIN_VOLUME_PERCENT = 10
        const val MIN_RESTORE_TIMEOUT_SECONDS = 30
        const val MAX_RESTORE_TIMEOUT_SECONDS = 600
    }
}

/**
 * How to relax DND. Architecture.md § 6.2 prefers allowing calls through over
 * disabling DND outright, as the less invasive change to someone else's phone.
 */
enum class DndBypassStrategy {
    /** `INTERRUPTION_FILTER_PRIORITY` + policy permitting calls. Default. */
    PRIORITY_ALLOW_CALLS,

    /** `INTERRUPTION_FILTER_ALL` — turns DND fully off for the call's duration. */
    DISABLE_DND_TEMPORARILY,
}
