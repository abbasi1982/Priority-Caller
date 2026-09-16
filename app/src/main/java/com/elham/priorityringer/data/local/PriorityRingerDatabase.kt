package com.elham.priorityringer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.elham.priorityringer.data.local.dao.AuditDao
import com.elham.priorityringer.data.local.dao.ContactDao
import com.elham.priorityringer.data.local.dao.EscalationDao
import com.elham.priorityringer.data.local.dao.RestoreDao
import com.elham.priorityringer.data.local.dao.SettingsDao
import com.elham.priorityringer.data.local.entity.AppSettingsEntity
import com.elham.priorityringer.data.local.entity.AuditLogEntryEntity
import com.elham.priorityringer.data.local.entity.EscalationEventEntity
import com.elham.priorityringer.data.local.entity.PendingRestoreEntity
import com.elham.priorityringer.data.local.entity.PriorityContactEntity
import com.elham.priorityringer.data.local.entity.SETTINGS_ROW_ID
import com.elham.priorityringer.domain.model.AppSettings

/**
 * Architecture.md § 10 — version 1, schema exported to `app/schemas/`.
 *
 * `exportSchema = true` is not optional here: the androidTest source set adds
 * `app/schemas` as an assets directory precisely so migration tests can read
 * the committed JSON. Turning it off would silently disable those tests.
 */
@Database(
    version = 1,
    exportSchema = true,
    entities = [
        PriorityContactEntity::class,
        AuditLogEntryEntity::class,
        AppSettingsEntity::class,
        PendingRestoreEntity::class,
        EscalationEventEntity::class,
    ],
)
@TypeConverters(Converters::class)
abstract class PriorityRingerDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
    abstract fun auditDao(): AuditDao
    abstract fun settingsDao(): SettingsDao
    abstract fun restoreDao(): RestoreDao
    abstract fun escalationDao(): EscalationDao

    companion object {
        const val NAME = "priority_ringer.db"
    }
}

/**
 * Prepopulates the single settings row so `SettingsDao.get()` has something to
 * return from the first launch onwards (Architecture.md § 10).
 *
 * The values are read from [AppSettings]'s own constructor defaults rather than
 * written out as literals, so the seeded row and the in-memory default can
 * never drift apart.
 *
 * Note `onCreate` fires only when the file is created. That is why the
 * repository still falls back to `AppSettings()` on a null row — this callback
 * is a convenience, not a guarantee.
 */
class SettingsPrepopulateCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        val defaults = AppSettings()
        db.execSQL(
            """
            INSERT OR IGNORE INTO app_settings (
                id,
                ringtoneVolumePercent,
                escalationEnabled,
                escalationPrimaryCallCount,
                escalationPrimaryWindowMinutes,
                escalationSecondaryCallCount,
                escalationSecondaryWindowMinutes,
                autoRestoreTimeoutSeconds,
                loggingEnabled,
                dndBypassStrategy
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(
                SETTINGS_ROW_ID,
                defaults.ringtoneVolumePercent,
                if (defaults.escalation.enabled) 1 else 0,
                defaults.escalation.primaryCallCount,
                defaults.escalation.primaryWindowMinutes,
                defaults.escalation.secondaryCallCount,
                defaults.escalation.secondaryWindowMinutes,
                defaults.autoRestoreTimeoutSeconds,
                if (defaults.loggingEnabled) 1 else 0,
                defaults.dndBypassStrategy.name,
            ),
        )
    }
}
