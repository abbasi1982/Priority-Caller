package com.elham.priorityringer.domain.repository

import com.elham.priorityringer.domain.model.AppSettings
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.AuditLogEntry
import com.elham.priorityringer.domain.model.CallSnapshot
import com.elham.priorityringer.domain.model.PriorityContact
import kotlinx.coroutines.flow.Flow

/** Repository interfaces. Architecture.md § 2 — domain declares, data implements. */

interface ContactRepository {
    fun observeAll(): Flow<List<PriorityContact>>
    fun observeEnabled(): Flow<List<PriorityContact>>

    suspend fun getAll(): List<PriorityContact>
    suspend fun getEnabled(): List<PriorityContact>
    suspend fun getById(id: Long): PriorityContact?

    /**
     * @return the new row id, or `null` if [PriorityContact.matchKey] collides
     *   with an existing contact (the unique index in § 10). Callers surface
     *   this as "already in your list", not as an error.
     */
    suspend fun add(contact: PriorityContact): Long?

    suspend fun setEnabled(id: Long, enabled: Boolean)
    suspend fun remove(id: Long)
}

interface AuditRepository {
    /** Newest first, capped at [MAX_ENTRIES]. */
    fun observeRecent(limit: Int = MAX_ENTRIES): Flow<List<AuditLogEntry>>

    fun observeLatestFailure(): Flow<AuditLogEntry?>

    /**
     * Persists an event, then trims to [MAX_ENTRIES] in the same transaction.
     *
     * Respects [AppSettings.loggingEnabled] — except for
     * [com.elham.priorityringer.domain.model.AuditSeverity.ERROR] entries,
     * which are always written (Architecture.md § 3, § 13). Turning logging off
     * must not make failures invisible.
     */
    suspend fun log(
        type: AuditEventType,
        message: String,
        relatedContactId: Long? = null,
        recoverable: Boolean = true,
    )

    suspend fun clear()

    companion object {
        /** FR7. */
        const val MAX_ENTRIES = 500
    }
}

interface SettingsRepository {
    fun observe(): Flow<AppSettings>
    suspend fun get(): AppSettings

    /** Values are clamped via [AppSettings.validated] before writing. */
    suspend fun update(settings: AppSettings)

    /**
     * Record the ring index seen while the phone was audible.
     *
     * A single-field write rather than [update] of a whole [AppSettings]: this
     * is a background observation, and round-tripping the user's settings
     * through it could overwrite an edit they are making at that moment.
     */
    suspend fun recordAudibleRingIndex(index: Int)
}

/**
 * Durable backing for the pre-mutation snapshot (Architecture.md § A.3).
 *
 * This is what makes § 17.5 ("restore is guaranteed by call-state **and**
 * timeout") actually true across process death.
 */
interface RestoreRepository {
    /**
     * Write the snapshot **before** any device mutation.
     *
     * Insert-if-absent: if a snapshot is already pending, this is a no-op and
     * returns `false`. A second overlapping call must never overwrite the
     * original pre-mutation state with values we ourselves just set — that
     * would make restore restore the wrong thing.
     */
    suspend fun saveIfAbsent(snapshot: CallSnapshot): Boolean

    suspend fun getPending(): CallSnapshot?
    fun observePending(): Flow<CallSnapshot?>

    /** Called only after a restore has been applied and verified. */
    suspend fun clear()
}

/**
 * Timestamps of matched calls, per number, for FR5.
 *
 * Persisted rather than in-memory so escalation survives process death
 * (Architecture.md § 10) — a repeat caller ringing twice five minutes apart is
 * very likely to span a process restart.
 */
interface EscalationRepository {
    suspend fun record(matchKey: String, timestampEpochMs: Long)

    /** Timestamps for [matchKey] at or after [sinceEpochMs]. */
    suspend fun timestampsSince(matchKey: String, sinceEpochMs: Long): List<Long>

    /** Drops rows no window can still reference. */
    suspend fun pruneBefore(epochMs: Long)

    suspend fun clear()
}
