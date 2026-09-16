package com.elham.priorityringer.data.local

import androidx.room.TypeConverter
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.DndBypassStrategy
import com.elham.priorityringer.domain.model.InterruptionFilter
import com.elham.priorityringer.domain.model.RingerMode

/**
 * Enum ↔ String converters.
 *
 * Names are stored, not ordinals: an ordinal silently re-points at a different
 * constant the moment someone inserts a case into the middle of an enum, and
 * `AuditEventType` in particular is grouped by meaning rather than appended to.
 *
 * Signatures are non-null in both directions because every enum column in
 * `entity/Entities.kt` is non-null; a nullable converter against a non-null
 * field is a Room compile error, not a convenience.
 *
 * Decoding never throws. A row written by a newer build — or a constant since
 * renamed — must not be able to crash the audit screen or, worse, the restore
 * path that reads `PendingRestoreEntity` after a process death (§ A.3). Every
 * decode falls back to a defined variant instead.
 *
 * A `class`, not an `object`: Room instantiates converter classes via their
 * no-arg constructor unless they are `@ProvidedTypeConverter`.
 */
class Converters {

    @TypeConverter
    fun fromAuditEventType(value: AuditEventType): String = value.name

    /**
     * Unknown names decode to [AuditEventType.ERROR] rather than to a benign
     * value: `AuditEventType` has no `UNKNOWN`, and an entry the app can no
     * longer interpret is itself a failure. ERROR severity also keeps the entry
     * visible under the always-persist-errors rule (§ 3, § 13).
     */
    @TypeConverter
    fun toAuditEventType(value: String): AuditEventType =
        AuditEventType.entries.firstOrNull { it.name == value } ?: AuditEventType.ERROR

    @TypeConverter
    fun fromRingerMode(value: RingerMode): String = value.name

    @TypeConverter
    fun toRingerMode(value: String): RingerMode =
        RingerMode.entries.firstOrNull { it.name == value } ?: RingerMode.UNKNOWN

    @TypeConverter
    fun fromInterruptionFilter(value: InterruptionFilter): String = value.name

    @TypeConverter
    fun toInterruptionFilter(value: String): InterruptionFilter =
        InterruptionFilter.entries.firstOrNull { it.name == value } ?: InterruptionFilter.UNKNOWN

    @TypeConverter
    fun fromDndBypassStrategy(value: DndBypassStrategy): String = value.name

    /**
     * Falls back to the § 6.2 default — the less invasive of the two
     * strategies. An unreadable preference must not escalate to turning DND
     * fully off.
     */
    @TypeConverter
    fun toDndBypassStrategy(value: String): DndBypassStrategy =
        DndBypassStrategy.entries.firstOrNull { it.name == value }
            ?: DndBypassStrategy.PRIORITY_ALLOW_CALLS
}
