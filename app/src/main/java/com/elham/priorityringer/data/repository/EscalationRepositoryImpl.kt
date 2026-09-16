package com.elham.priorityringer.data.repository

import com.elham.priorityringer.data.local.dao.EscalationDao
import com.elham.priorityringer.data.local.mapper.escalationEventEntity
import com.elham.priorityringer.di.IoDispatcher
import com.elham.priorityringer.domain.repository.EscalationRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Persisted call timestamps for FR5 (Architecture.md § 10).
 *
 * Storage only — the sliding-window decision lives in the pure
 * `EscalationPolicy`, which is handed these timestamps.
 */
@Singleton
class EscalationRepositoryImpl @Inject constructor(
    private val dao: EscalationDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : EscalationRepository {

    override suspend fun record(matchKey: String, timestampEpochMs: Long) = withContext(io) {
        dao.insert(escalationEventEntity(matchKey, timestampEpochMs))
        Unit
    }

    override suspend fun timestampsSince(matchKey: String, sinceEpochMs: Long): List<Long> =
        withContext(io) { dao.timestampsSince(matchKey, sinceEpochMs) }

    override suspend fun pruneBefore(epochMs: Long) = withContext(io) { dao.pruneBefore(epochMs) }

    override suspend fun clear() = withContext(io) { dao.clear() }
}
