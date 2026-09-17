package com.elham.priorityringer.data.local.mapper

import com.elham.priorityringer.data.local.entity.AppSettingsEntity
import com.elham.priorityringer.data.local.entity.AuditLogEntryEntity
import com.elham.priorityringer.data.local.entity.EscalationEventEntity
import com.elham.priorityringer.data.local.entity.PendingRestoreEntity
import com.elham.priorityringer.data.local.entity.PriorityContactEntity
import com.elham.priorityringer.data.local.entity.SETTINGS_ROW_ID
import com.elham.priorityringer.domain.model.AppSettings
import com.elham.priorityringer.domain.model.AuditLogEntry
import com.elham.priorityringer.domain.model.CallSnapshot
import com.elham.priorityringer.domain.model.EscalationThresholds
import com.elham.priorityringer.domain.model.PriorityContact
import com.elham.priorityringer.domain.model.VolumeSnapshot

/**
 * Entity ↔ domain mapping (Architecture.md § 10: "Mappers in `data`; domain
 * never imports Room").
 *
 * Pure extension functions with no injected collaborators, so they are testable
 * on the JVM and cannot accidentally acquire a dependency on Android or on the
 * database itself.
 */

// ---------------------------------------------------------------------------
// Contacts
// ---------------------------------------------------------------------------

fun PriorityContactEntity.toDomain(): PriorityContact = PriorityContact(
    id = id,
    displayName = displayName,
    originalInput = originalInput,
    matchKey = matchKey,
    e164 = e164,
    enabled = enabled,
    createdAtEpochMs = createdAtEpochMs,
)

/**
 * An `id` of `0` is passed straight through: Room's `autoGenerate` treats it as
 * "assign one", which is the same convention `PriorityContact`'s default uses.
 */
fun PriorityContact.toEntity(): PriorityContactEntity = PriorityContactEntity(
    id = id,
    displayName = displayName,
    originalInput = originalInput,
    matchKey = matchKey,
    e164 = e164,
    enabled = enabled,
    createdAtEpochMs = createdAtEpochMs,
)

fun List<PriorityContactEntity>.toContactDomain(): List<PriorityContact> = map { it.toDomain() }

// ---------------------------------------------------------------------------
// Audit log
// ---------------------------------------------------------------------------

fun AuditLogEntryEntity.toDomain(): AuditLogEntry = AuditLogEntry(
    id = id,
    timestampEpochMs = timestampEpochMs,
    type = type,
    message = message,
    relatedContactId = relatedContactId,
    recoverable = recoverable,
)

fun AuditLogEntry.toEntity(): AuditLogEntryEntity = AuditLogEntryEntity(
    id = id,
    timestampEpochMs = timestampEpochMs,
    type = type,
    message = message,
    relatedContactId = relatedContactId,
    recoverable = recoverable,
)

fun List<AuditLogEntryEntity>.toAuditDomain(): List<AuditLogEntry> = map { it.toDomain() }

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

fun AppSettingsEntity.toDomain(): AppSettings = AppSettings(
    ringtoneVolumePercent = ringtoneVolumePercent,
    escalation = EscalationThresholds(
        enabled = escalationEnabled,
        primaryCallCount = escalationPrimaryCallCount,
        primaryWindowMinutes = escalationPrimaryWindowMinutes,
        secondaryCallCount = escalationSecondaryCallCount,
        secondaryWindowMinutes = escalationSecondaryWindowMinutes,
        alarmCallCount = escalationAlarmCallCount,
        alarmWindowMinutes = escalationAlarmWindowMinutes,
    ),
    autoRestoreTimeoutSeconds = autoRestoreTimeoutSeconds,
    loggingEnabled = loggingEnabled,
    dndBypassStrategy = dndBypassStrategy,
    lastAudibleRingIndex = lastAudibleRingIndex,
)

fun AppSettings.toEntity(): AppSettingsEntity = AppSettingsEntity(
    id = SETTINGS_ROW_ID,
    ringtoneVolumePercent = ringtoneVolumePercent,
    escalationEnabled = escalation.enabled,
    escalationPrimaryCallCount = escalation.primaryCallCount,
    escalationPrimaryWindowMinutes = escalation.primaryWindowMinutes,
    escalationSecondaryCallCount = escalation.secondaryCallCount,
    escalationSecondaryWindowMinutes = escalation.secondaryWindowMinutes,
    escalationAlarmCallCount = escalation.alarmCallCount,
    escalationAlarmWindowMinutes = escalation.alarmWindowMinutes,
    autoRestoreTimeoutSeconds = autoRestoreTimeoutSeconds,
    loggingEnabled = loggingEnabled,
    dndBypassStrategy = dndBypassStrategy,
    // Carried explicitly, and it must stay that way.
    //
    // `SettingsRepository.update` writes through `@Upsert`, which replaces the
    // whole row. Omitting this field here did not leave it untouched — it took
    // the entity's `= null` default, so every settings edit silently wiped the
    // learned audible ring level and the next Silent restore had nothing to
    // put back. It failed honestly (restore declines on a null index and
    // leaves the volume alone), which is exactly why nobody noticed.
    lastAudibleRingIndex = lastAudibleRingIndex,
)

// ---------------------------------------------------------------------------
// Pending restore (A.3)
// ---------------------------------------------------------------------------

fun PendingRestoreEntity.toDomain(): CallSnapshot = CallSnapshot(
    ringerMode = ringerMode,
    ringVolume = VolumeSnapshot(current = ringVolumeCurrent, max = ringVolumeMax),
    interruptionFilter = interruptionFilter,
    zenRuleId = zenRuleId,
    capturedAtEpochMs = capturedAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
)

fun CallSnapshot.toEntity(): PendingRestoreEntity = PendingRestoreEntity(
    id = SETTINGS_ROW_ID,
    ringerMode = ringerMode,
    ringVolumeCurrent = ringVolume.current,
    ringVolumeMax = ringVolume.max,
    interruptionFilter = interruptionFilter,
    zenRuleId = zenRuleId,
    capturedAtEpochMs = capturedAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
)

// ---------------------------------------------------------------------------
// Escalation
// ---------------------------------------------------------------------------

/**
 * Escalation has no domain type of its own — `EscalationRepository` speaks in
 * `matchKey` and raw timestamps, and `EscalationPolicy` takes a `List<Long>`.
 * Only the write direction needs a mapper.
 */
fun escalationEventEntity(matchKey: String, timestampEpochMs: Long): EscalationEventEntity =
    EscalationEventEntity(matchKey = matchKey, timestampEpochMs = timestampEpochMs)
