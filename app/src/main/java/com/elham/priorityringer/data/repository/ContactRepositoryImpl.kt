package com.elham.priorityringer.data.repository

import com.elham.priorityringer.data.local.dao.ContactDao
import com.elham.priorityringer.data.local.mapper.toContactDomain
import com.elham.priorityringer.data.local.mapper.toDomain
import com.elham.priorityringer.data.local.mapper.toEntity
import com.elham.priorityringer.di.IoDispatcher
import com.elham.priorityringer.domain.model.PriorityContact
import com.elham.priorityringer.domain.repository.ContactRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class ContactRepositoryImpl @Inject constructor(
    private val dao: ContactDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ContactRepository {

    override fun observeAll(): Flow<List<PriorityContact>> =
        dao.observeAll().map { it.toContactDomain() }.flowOn(io)

    override fun observeEnabled(): Flow<List<PriorityContact>> =
        dao.observeEnabled().map { it.toContactDomain() }.flowOn(io)

    override suspend fun getAll(): List<PriorityContact> =
        withContext(io) { dao.getAll().toContactDomain() }

    override suspend fun getEnabled(): List<PriorityContact> =
        withContext(io) { dao.getEnabled().toContactDomain() }

    override suspend fun getById(id: Long): PriorityContact? =
        withContext(io) { dao.getById(id)?.toDomain() }

    /**
     * The duplicate check is the unique index on `matchKey` (Architecture.md
     * § 10), not a preceding read: a read-then-insert would race, and two adds
     * of the same number could both report success.
     *
     * `OnConflictStrategy.IGNORE` returns `-1` on conflict, which becomes
     * `null` — the `ContactRepository.add` contract's "already in your list".
     */
    override suspend fun add(contact: PriorityContact): Long? = withContext(io) {
        val rowId = dao.insert(contact.toEntity())
        if (rowId == -1L) null else rowId
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) =
        withContext(io) { dao.setEnabled(id, enabled) }

    override suspend fun remove(id: Long) = withContext(io) { dao.deleteById(id) }
}
