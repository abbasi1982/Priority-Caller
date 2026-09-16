package com.elham.priorityringer.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.repository.AuditRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Audit log cap (FR7, Architecture.md § 10 — "after insert, DELETE oldest where
 * count > 500, single transaction").
 *
 * The cap is a storage guarantee, not a display limit: the app writes several
 * rows per priority call and would otherwise grow without bound on a phone that
 * is never cleared. The trim must also keep the *newest* rows — a trim that
 * dropped the newest would quietly delete the evidence of the failure the user
 * is trying to diagnose.
 *
 * See `DaoTestSupport` for the provisional-names caveat.
 */
@RunWith(AndroidJUnit4::class)
class AuditLogDaoTest {

    private lateinit var database: PriorityRingerDatabase
    private lateinit var dao: AuditDao

    private val cap = AuditRepository.MAX_ENTRIES
    private val overflow = 20
    private val baseTimestamp = 1_700_000_000_000L

    @Before
    fun setUp() {
        database = DaoTestSupport.inMemoryDatabase()
        dao = database.auditDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** Distinct, strictly increasing timestamps so "oldest" is unambiguous. */
    private suspend fun insertSequentially(count: Int) {
        repeat(count) { index ->
            dao.insertAndTrim(
                DaoTestSupport.auditEntity(
                    timestampEpochMs = baseTimestamp + index,
                    message = "entry $index",
                ),
            )
        }
    }

    @Test
    fun `inserting more than the cap leaves exactly the cap`() = runTest {
        insertSequentially(cap + overflow)

        assertEquals(cap, dao.count())
    }

    @Test
    fun `the oldest entries beyond the cap are the ones deleted`() = runTest {
        insertSequentially(cap + overflow)

        val remaining = dao.getRecent(cap).map { it.timestampEpochMs }

        assertTrue(
            "the first $overflow entries must be gone; the newest are what explain the " +
                "most recent failure",
            remaining.none { it < baseTimestamp + overflow },
        )
    }

    @Test
    fun `the newest entry survives the trim`() = runTest {
        insertSequentially(cap + overflow)

        val newest = dao.getRecent(1).single()

        assertEquals(baseTimestamp + cap + overflow - 1, newest.timestampEpochMs)
    }

    @Test
    fun `results are returned newest first, which is the order the audit screen renders`() =
        runTest {
            insertSequentially(3)

            val timestamps = dao.getRecent(3).map { it.timestampEpochMs }

            assertEquals(
                listOf(baseTimestamp + 2, baseTimestamp + 1, baseTimestamp),
                timestamps,
            )
        }

    @Test
    fun `staying under the cap deletes nothing`() = runTest {
        insertSequentially(cap - 1)

        assertEquals(cap - 1, dao.count())
    }

    @Test
    fun `inserting exactly the cap deletes nothing, so the boundary is inclusive`() = runTest {
        insertSequentially(cap)

        assertEquals(cap, dao.count())
    }

    @Test
    fun `an error entry is stored with its unrecoverable flag intact`() = runTest {
        dao.insertAndTrim(
            DaoTestSupport.auditEntity(
                timestampEpochMs = baseTimestamp,
                type = AuditEventType.RESTORATION_FAILED,
                message = "Restore was incomplete",
                recoverable = false,
            ),
        )

        val stored = dao.getRecent(1).single()

        assertEquals(
            "§ 3 — this flag is what distinguishes a platform restriction the user can " +
                "act on from one they cannot",
            false,
            stored.recoverable,
        )
    }

    @Test
    fun `clearing the log removes every row`() = runTest {
        insertSequentially(10)

        dao.clear()

        assertEquals(0, dao.count())
    }
}
