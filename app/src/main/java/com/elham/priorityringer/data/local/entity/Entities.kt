package com.elham.priorityringer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.DndBypassStrategy
import com.elham.priorityringer.domain.model.InterruptionFilter
import com.elham.priorityringer.domain.model.RingerMode

/**
 * Room entities. Architecture.md § 10.
 *
 * Column names are deliberately left identical to the property names (no
 * `@ColumnInfo` renames): the prepopulation SQL in `PriorityRingerDatabase`
 * writes the settings row by hand, and keeping one spelling means that SQL and
 * this file can be checked against each other by eye.
 *
 * Computed domain properties (`PriorityContact.redactedNumber`,
 * `AuditLogEntry.severity`, `VolumeSnapshot.percent`) are **not** columns —
 * they are derived from the stored values and persisting them would let the
 * database disagree with the domain.
 */

// ---------------------------------------------------------------------------
// FR1 — Priority contacts
// ---------------------------------------------------------------------------

/**
 * Architecture.md § 10: "Contacts: unique index on normalized number."
 *
 * The unique index is the duplicate check. It is enforced by SQLite rather than
 * by a read-then-write in the repository, so two concurrent adds of the same
 * number cannot both succeed — and `ContactDao.insert` turns the conflict into
 * a `-1` row id, which the repository maps to `null` per the
 * `ContactRepository.add` contract.
 */
@Entity(
    tableName = "priority_contacts",
    indices = [Index(value = ["matchKey"], unique = true)],
)
data class PriorityContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val displayName: String,
    val originalInput: String,
    val matchKey: String,
    val e164: String?,
    val enabled: Boolean,
    val createdAtEpochMs: Long,
)

// ---------------------------------------------------------------------------
// FR7 — Audit log
// ---------------------------------------------------------------------------

/**
 * Architecture.md § 10 / § 13. Capped at 500 rows by `AuditDao.insertAndTrim`.
 *
 * `relatedContactId` is a plain nullable `Long`, **not** a foreign key. A
 * cascade from `priority_contacts` would delete the audit history of a contact
 * along with the contact — including the `CONTACT_REMOVED` entry that explains
 * the deletion. The log has to outlive what it describes.
 */
@Entity(
    tableName = "audit_log",
    indices = [Index(value = ["timestampEpochMs"])],
)
data class AuditLogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampEpochMs: Long,
    val type: AuditEventType,
    val message: String,
    val relatedContactId: Long?,
    val recoverable: Boolean,
)

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

/**
 * Single-row settings, id = 1 (Architecture.md § 3, § 10).
 *
 * `EscalationThresholds` is flattened into columns rather than declared
 * `@Embedded`. Flat columns keep the prepopulation `INSERT` in the database
 * callback readable and keep any future migration a matter of plain
 * `ALTER TABLE`, with no embedded-prefix rules to get right.
 */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = SETTINGS_ROW_ID,
    val ringtoneVolumePercent: Int,
    val escalationEnabled: Boolean,
    val escalationPrimaryCallCount: Int,
    val escalationPrimaryWindowMinutes: Int,
    val escalationSecondaryCallCount: Int,
    val escalationSecondaryWindowMinutes: Int,
    val autoRestoreTimeoutSeconds: Int,
    val loggingEnabled: Boolean,
    val dndBypassStrategy: DndBypassStrategy,
)

/** The only id either single-row table ever uses. */
const val SETTINGS_ROW_ID: Int = 1

// ---------------------------------------------------------------------------
// A.3 — write-ahead restore snapshot
// ---------------------------------------------------------------------------

/**
 * Durable backing for `CallSnapshot` (Architecture.md § A.3).
 *
 * Fixed primary key id = 1 is what makes insert-if-absent expressible as a
 * single statement: with `OnConflictStrategy.IGNORE`, a second overlapping call
 * collides on the primary key and is dropped, so the original pre-mutation
 * state can never be overwritten with values the app itself just set.
 *
 * `VolumeSnapshot` is flattened into `ringVolumeCurrent` / `ringVolumeMax`;
 * `percent` is computed and is not stored.
 */
@Entity(tableName = "pending_restore")
data class PendingRestoreEntity(
    @PrimaryKey val id: Int = SETTINGS_ROW_ID,
    val ringerMode: RingerMode,
    val ringVolumeCurrent: Int,
    val ringVolumeMax: Int,
    val interruptionFilter: InterruptionFilter,
    val zenRuleId: String?,
    val capturedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

// ---------------------------------------------------------------------------
// FR5 — Escalation
// ---------------------------------------------------------------------------

/**
 * One row per matched call, keyed by `matchKey` rather than contact id.
 *
 * Architecture.md § 10 persists these so the FR5 sliding windows survive
 * process death; `matchKey` rather than the contact row id means removing and
 * re-adding a contact does not silently reset its escalation history.
 */
@Entity(
    tableName = "escalation_events",
    indices = [Index(value = ["matchKey"])],
)
data class EscalationEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val matchKey: String,
    val timestampEpochMs: Long,
)
