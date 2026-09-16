package com.elham.priorityringer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.elham.priorityringer.data.local.entity.AppSettingsEntity
import com.elham.priorityringer.data.local.entity.AuditLogEntryEntity
import com.elham.priorityringer.data.local.entity.EscalationEventEntity
import com.elham.priorityringer.data.local.entity.PendingRestoreEntity
import com.elham.priorityringer.data.local.entity.PriorityContactEntity
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------------------
// Contacts
// ---------------------------------------------------------------------------

@Dao
interface ContactDao {

    @Query("SELECT * FROM priority_contacts ORDER BY displayName COLLATE NOCASE ASC, id ASC")
    fun observeAll(): Flow<List<PriorityContactEntity>>

    @Query(
        "SELECT * FROM priority_contacts WHERE enabled = 1 " +
            "ORDER BY displayName COLLATE NOCASE ASC, id ASC",
    )
    fun observeEnabled(): Flow<List<PriorityContactEntity>>

    @Query("SELECT * FROM priority_contacts ORDER BY displayName COLLATE NOCASE ASC, id ASC")
    suspend fun getAll(): List<PriorityContactEntity>

    @Query(
        "SELECT * FROM priority_contacts WHERE enabled = 1 " +
            "ORDER BY displayName COLLATE NOCASE ASC, id ASC",
    )
    suspend fun getEnabled(): List<PriorityContactEntity>

    @Query("SELECT * FROM priority_contacts WHERE id = :id")
    suspend fun getById(id: Long): PriorityContactEntity?

    /**
     * IGNORE, not REPLACE.
     *
     * REPLACE on a unique-index conflict is a delete-then-insert: it would
     * assign a new row id and silently discard the existing contact's
     * `enabled` flag and `createdAtEpochMs`. IGNORE leaves the existing row
     * untouched and returns `-1`, which `ContactRepositoryImpl` maps to `null`
     * so the caller can say "already in your list" (`ContactRepository.add`).
     *
     * @return the new row id, or `-1` when [PriorityContactEntity.matchKey]
     *   collides with an existing row.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PriorityContactEntity): Long

    @Query("UPDATE priority_contacts SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM priority_contacts WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Test affordance — no production caller. */
    @Query("SELECT COUNT(*) FROM priority_contacts")
    suspend fun count(): Int
}

// ---------------------------------------------------------------------------
// Audit log
// ---------------------------------------------------------------------------

/**
 * An `abstract class` rather than an interface so [insertAndTrim] can carry a
 * body under `@Transaction` without relying on interface default methods.
 */
@Dao
abstract class AuditDao {

    /**
     * Newest first. `id DESC` is a tiebreak, not decoration: a single priority
     * call writes several entries inside the same millisecond
     * (detected → bypass attempted → ringer changed), so timestamp alone does
     * not define an order.
     */
    @Query("SELECT * FROM audit_log ORDER BY timestampEpochMs DESC, id DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int): Flow<List<AuditLogEntryEntity>>

    /**
     * The caller passes the names of the event types that map to
     * `AuditSeverity.ERROR`. Severity is a computed property of
     * `AuditEventType`, not a column — hardcoding the list into SQL here would
     * duplicate that mapping and go stale the next time an ERROR-severity type
     * is added.
     */
    @Query(
        "SELECT * FROM audit_log WHERE type IN (:typeNames) " +
            "ORDER BY timestampEpochMs DESC, id DESC LIMIT 1",
    )
    abstract fun observeLatestOfTypes(typeNames: List<String>): Flow<AuditLogEntryEntity?>

    @Insert
    abstract suspend fun insert(entity: AuditLogEntryEntity): Long

    /**
     * Architecture.md § 10: "after insert, DELETE oldest where count > 500
     * (single transaction)."
     *
     * One transaction means an observer can never see the log momentarily over
     * its cap, and a crash between the two statements cannot leave the table
     * growing unbounded.
     */
    @Query(
        "DELETE FROM audit_log WHERE id NOT IN (" +
            "SELECT id FROM audit_log ORDER BY timestampEpochMs DESC, id DESC LIMIT :max" +
            ")",
    )
    abstract suspend fun trimTo(max: Int)

    /**
     * Suspend counterpart to [observeRecent], for tests.
     *
     * The 500-entry cap (§ 10, FR7) is the one behaviour the contract states as
     * a hard number, so it needs a non-Flow read to assert against.
     */
    @Query("SELECT * FROM audit_log ORDER BY timestampEpochMs DESC, id DESC LIMIT :limit")
    abstract suspend fun getRecent(limit: Int): List<AuditLogEntryEntity>

    /** Test affordance — no production caller. */
    @Query("SELECT COUNT(*) FROM audit_log")
    abstract suspend fun count(): Int

    @Transaction
    open suspend fun insertAndTrim(entity: AuditLogEntryEntity, max: Int): Long {
        val id = insert(entity)
        trimTo(max)
        return id
    }

    @Query("DELETE FROM audit_log")
    abstract suspend fun clear()
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

@Dao
interface SettingsDao {

    /**
     * Architecture.md § 10: `get()` always returns row id = 1, prepopulated by
     * the database callback. Still declared nullable — `onCreate` only fires on
     * a fresh database, so the repository supplies `AppSettings()` defaults
     * rather than trusting the row to exist.
     */
    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun get(): AppSettingsEntity?

    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observe(): Flow<AppSettingsEntity?>

    @Upsert
    suspend fun upsert(entity: AppSettingsEntity)

    /** Test affordance — asserts the table stays a single row. */
    @Query("SELECT COUNT(*) FROM app_settings")
    suspend fun count(): Int
}

// ---------------------------------------------------------------------------
// Pending restore (A.3)
// ---------------------------------------------------------------------------

@Dao
interface RestoreDao {

    /**
     * Insert-if-absent (Architecture.md § A.3).
     *
     * The fixed primary key id = 1 plus IGNORE is the whole mechanism: a second
     * overlapping call conflicts and is dropped, so the snapshot on disk always
     * describes the state *before* the app touched anything. Overwriting it
     * would persist values the app itself just set, and restore would then
     * faithfully restore the phone to its modified state.
     *
     * @return the row id, or `-1` if a snapshot was already pending.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: PendingRestoreEntity): Long

    @Query("SELECT * FROM pending_restore WHERE id = 1")
    suspend fun getPending(): PendingRestoreEntity?

    @Query("SELECT * FROM pending_restore WHERE id = 1")
    fun observePending(): Flow<PendingRestoreEntity?>

    @Query("DELETE FROM pending_restore")
    suspend fun clear()

    /** Test affordance — asserts insert-if-absent never creates a second row. */
    @Query("SELECT COUNT(*) FROM pending_restore")
    suspend fun count(): Int
}

// ---------------------------------------------------------------------------
// Escalation (FR5)
// ---------------------------------------------------------------------------

@Dao
interface EscalationDao {

    @Insert
    suspend fun insert(entity: EscalationEventEntity): Long

    @Query(
        "SELECT timestampEpochMs FROM escalation_events " +
            "WHERE matchKey = :matchKey AND timestampEpochMs >= :sinceEpochMs " +
            "ORDER BY timestampEpochMs ASC",
    )
    suspend fun timestampsSince(matchKey: String, sinceEpochMs: Long): List<Long>

    @Query("DELETE FROM escalation_events WHERE timestampEpochMs < :epochMs")
    suspend fun pruneBefore(epochMs: Long)

    @Query("DELETE FROM escalation_events")
    suspend fun clear()

    /** Test affordance — no production caller. */
    @Query("SELECT COUNT(*) FROM escalation_events")
    suspend fun count(): Int

    /** Test affordance — `timestampsSince` is scoped to one number. */
    @Query("SELECT * FROM escalation_events ORDER BY timestampEpochMs ASC, id ASC")
    suspend fun getAll(): List<EscalationEventEntity>
}
