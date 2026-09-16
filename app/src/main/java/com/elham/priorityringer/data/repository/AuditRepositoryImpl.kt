package com.elham.priorityringer.data.repository

import com.elham.priorityringer.data.local.dao.AuditDao
import com.elham.priorityringer.data.local.dao.SettingsDao
import com.elham.priorityringer.data.local.entity.AuditLogEntryEntity
import com.elham.priorityringer.data.local.mapper.toAuditDomain
import com.elham.priorityringer.data.local.mapper.toDomain
import com.elham.priorityringer.di.IoDispatcher
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.AuditLogEntry
import com.elham.priorityringer.domain.model.AuditSeverity
import com.elham.priorityringer.domain.port.Clock
import com.elham.priorityringer.domain.repository.AuditRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Architecture.md § 13 / FR7.
 *
 * [SettingsDao] is injected rather than `SettingsRepository`: the audit log is
 * written from inside almost every other path, and a repository→repository edge
 * here is the one most likely to be closed into a cycle by a later change.
 */
@Singleton
class AuditRepositoryImpl @Inject constructor(
    private val dao: AuditDao,
    private val settingsDao: SettingsDao,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AuditRepository {

    override fun observeRecent(limit: Int): Flow<List<AuditLogEntry>> =
        dao.observeRecent(limit).map { it.toAuditDomain() }.flowOn(io)

    /**
     * Most recent wins.
     *
     * The query asks for the latest entry that either raises the banner or
     * resolves one, and a resolving entry yields `null` — no banner. Doing it
     * in one ordered query rather than two comparisons is what makes it
     * correct across calls: a `SILENT_NOT_OVERRIDDEN` from *this* call
     * legitimately raises the banner again even though the previous call ended
     * in a successful fallback.
     */
    override fun observeLatestFailure(): Flow<AuditLogEntry?> =
        dao.observeLatestOfTypes(BANNER_TYPE_NAMES)
            .map { entry ->
                entry?.toDomain()?.takeIf { it.type.raisesPersistentBanner }
            }
            .flowOn(io)

    /**
     * Architecture.md § 3 and § 13.
     *
     * Two properties this method must have, in this order:
     *
     * 1. **Errors are always persisted**, even with `loggingEnabled = false`.
     *    The severity check therefore happens *before* the settings read — if
     *    reading settings fails, an ERROR must still be written rather than
     *    suppressed by the failure of the check that was supposed to allow it.
     * 2. **It never throws.** This is called from failure handlers; an
     *    exception here would replace a recoverable, explainable platform
     *    failure with a crash. Storage problems are reported to Timber and
     *    otherwise swallowed.
     */
    override suspend fun log(
        type: AuditEventType,
        message: String,
        relatedContactId: Long?,
        recoverable: Boolean,
    ) {
        runCatching {
            val isError = type.severity == AuditSeverity.ERROR
            if (!isError && !loggingEnabled()) return@runCatching

            withContext(io) {
                dao.insertAndTrim(
                    entity = AuditLogEntryEntity(
                        timestampEpochMs = clock.nowEpochMs(),
                        type = type,
                        message = message,
                        relatedContactId = relatedContactId,
                        recoverable = recoverable,
                    ),
                    max = AuditRepository.MAX_ENTRIES,
                )
            }
        }.onFailure { t ->
            // Cancellation is not a failure: swallowing it here would break
            // structured concurrency for the `goAsync()` receiver scope (§ A.2).
            if (t is CancellationException) throw t
            Timber.e(t, "Failed to persist audit entry %s", type.name)
        }
    }

    override suspend fun clear() = withContext(io) { dao.clear() }

    /** Defaults to enabled when the row is missing, matching `AppSettings()`. */
    private suspend fun loggingEnabled(): Boolean =
        withContext(io) { settingsDao.get()?.loggingEnabled ?: true }

    private companion object {
        /**
         * Derived from `AuditEventType.raisesPersistentBanner` rather than
         * listed in SQL, so a new banner-worthy event type is picked up
         * automatically instead of quietly missing from the dashboard's
         * "last failure".
         *
         * Note this is deliberately *not* `severity == ERROR`. Architecture.md
         * § 6.3 requires the banner for `DND_BYPASS_INEFFECTIVE` and
         * `SILENT_NOT_OVERRIDDEN` too — only WARNING severity, since they are
         * expected platform limits rather than malfunctions, but still the
         * outcomes the user most needs to see.
         *
         * Resolving types are in the list too, so that the newest of the two
         * kinds is what the query returns. Leaving them out would make the
         * banner permanent: the raising entry would stay the latest match
         * forever, no matter what happened afterwards.
         */
        val BANNER_TYPE_NAMES: List<String> = AuditEventType.entries
            .filter { it.raisesPersistentBanner || it.resolvesPersistentBanner }
            .map { it.name }
    }
}
