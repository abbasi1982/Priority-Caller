package com.elham.priorityringer.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.DndBypassStrategy
import com.elham.priorityringer.domain.model.InterruptionFilter
import com.elham.priorityringer.domain.model.RingerMode

/**
 * Shared setup for the Room DAO tests (Architecture.md § 10, § 14).
 *
 * These tests were written in parallel with the `data/local` layer, against the
 * contracts rather than the implementation. They have since been reconciled
 * against the real layer: the database is `PriorityRingerDatabase`, and
 * `AppSettingsEntity` prefixes its escalation columns (`escalationPrimaryCallCount`
 * and friends). Entity names and every other property name matched the contract
 * as written.
 *
 * Still unverified, because no compiler has ever run over this tree: DAO method
 * names. If one does not resolve, the assertion above it is still the behaviour
 * the contract requires — rename the call, do not delete the test.
 *
 * Hilt is deliberately not used: these tests exercise SQL, not the DI graph,
 * and building the database directly keeps them independent of
 * `DatabaseModule`.
 */
object DaoTestSupport {

    /**
     * @param prepopulateSettings attach the production `SettingsPrepopulateCallback`.
     *
     * Off by default so the DAO tests exercise SQL against an empty table. Turn
     * it on for the settings-prepopulation test: without the real callback a
     * bare in-memory builder has no row id = 1, and asserting on one the test
     * inserted itself would only be testing the test.
     */
    fun inMemoryDatabase(prepopulateSettings: Boolean = false): PriorityRingerDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PriorityRingerDatabase::class.java,
        )
            .allowMainThreadQueries()
            .apply { if (prepopulateSettings) addCallback(SettingsPrepopulateCallback()) }
            .build()

    fun contactEntity(
        id: Long = 0L,
        displayName: String = "Mum",
        originalInput: String = "+15551234567",
        matchKey: String = "1234567",
        e164: String? = "+15551234567",
        enabled: Boolean = true,
        createdAtEpochMs: Long = 1_700_000_000_000L,
    ): PriorityContactEntity = PriorityContactEntity(
        id = id,
        displayName = displayName,
        originalInput = originalInput,
        matchKey = matchKey,
        e164 = e164,
        enabled = enabled,
        createdAtEpochMs = createdAtEpochMs,
    )

    fun auditEntity(
        id: Long = 0L,
        timestampEpochMs: Long,
        type: AuditEventType = AuditEventType.PRIORITY_CALL_DETECTED,
        message: String = "entry",
        relatedContactId: Long? = null,
        recoverable: Boolean = true,
    ): AuditLogEntryEntity = AuditLogEntryEntity(
        id = id,
        timestampEpochMs = timestampEpochMs,
        type = type,
        message = message,
        relatedContactId = relatedContactId,
        recoverable = recoverable,
    )

    fun settingsEntity(
        id: Int = 1,
        ringtoneVolumePercent: Int = 80,
        escalationEnabled: Boolean = true,
        primaryCallCount: Int = 2,
        primaryWindowMinutes: Int = 5,
        secondaryCallCount: Int = 3,
        secondaryWindowMinutes: Int = 10,
        autoRestoreTimeoutSeconds: Int = 90,
        loggingEnabled: Boolean = true,
        dndBypassStrategy: DndBypassStrategy = DndBypassStrategy.PRIORITY_ALLOW_CALLS,
    ): AppSettingsEntity = AppSettingsEntity(
        id = id,
        ringtoneVolumePercent = ringtoneVolumePercent,
        escalationEnabled = escalationEnabled,
        escalationPrimaryCallCount = primaryCallCount,
        escalationPrimaryWindowMinutes = primaryWindowMinutes,
        escalationSecondaryCallCount = secondaryCallCount,
        escalationSecondaryWindowMinutes = secondaryWindowMinutes,
        autoRestoreTimeoutSeconds = autoRestoreTimeoutSeconds,
        loggingEnabled = loggingEnabled,
        dndBypassStrategy = dndBypassStrategy,
    )

    fun pendingRestoreEntity(
        id: Int = 1,
        ringerMode: RingerMode = RingerMode.VIBRATE,
        ringVolumeCurrent: Int = 4,
        ringVolumeMax: Int = 15,
        interruptionFilter: InterruptionFilter = InterruptionFilter.PRIORITY,
        zenRuleId: String? = null,
        capturedAtEpochMs: Long = 1_700_000_000_000L,
        expiresAtEpochMs: Long = 1_700_000_090_000L,
    ): PendingRestoreEntity = PendingRestoreEntity(
        id = id,
        ringerMode = ringerMode,
        ringVolumeCurrent = ringVolumeCurrent,
        ringVolumeMax = ringVolumeMax,
        interruptionFilter = interruptionFilter,
        zenRuleId = zenRuleId,
        capturedAtEpochMs = capturedAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
    )

    fun escalationEntity(
        id: Long = 0L,
        matchKey: String = "1234567",
        timestampEpochMs: Long,
    ): EscalationEventEntity = EscalationEventEntity(
        id = id,
        matchKey = matchKey,
        timestampEpochMs = timestampEpochMs,
    )
}
