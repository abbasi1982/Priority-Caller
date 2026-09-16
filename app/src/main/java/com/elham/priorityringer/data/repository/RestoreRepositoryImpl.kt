package com.elham.priorityringer.data.repository

import com.elham.priorityringer.data.local.dao.RestoreDao
import com.elham.priorityringer.data.local.mapper.toDomain
import com.elham.priorityringer.data.local.mapper.toEntity
import com.elham.priorityringer.di.IoDispatcher
import com.elham.priorityringer.domain.model.CallSnapshot
import com.elham.priorityringer.domain.repository.RestoreRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Write-ahead restore snapshot. Architecture.md § A.3. */
@Singleton
class RestoreRepositoryImpl @Inject constructor(
    private val dao: RestoreDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : RestoreRepository {

    /**
     * Insert-if-absent, enforced by the fixed primary key plus
     * `OnConflictStrategy.IGNORE` rather than by a read-then-write.
     *
     * The distinction matters: between a read and a write, a second overlapping
     * call could observe "nothing pending" and persist the *already mutated*
     * state as if it were the original. Restore would then restore the phone to
     * loud-and-off-DND, which is the failure § A.3 exists to prevent.
     *
     * @return `false` when a snapshot was already pending and this one was
     *   correctly discarded.
     */
    override suspend fun saveIfAbsent(snapshot: CallSnapshot): Boolean = withContext(io) {
        dao.insertIfAbsent(snapshot.toEntity()) != -1L
    }

    override suspend fun getPending(): CallSnapshot? =
        withContext(io) { dao.getPending()?.toDomain() }

    override fun observePending(): Flow<CallSnapshot?> =
        dao.observePending().map { it?.toDomain() }.flowOn(io)

    override suspend fun clear() = withContext(io) { dao.clear() }
}
