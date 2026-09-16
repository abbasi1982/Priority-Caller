package com.elham.priorityringer.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elham.priorityringer.data.local.dao.AuditDao
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
                entity = DaoTestSupport.auditEntity(
                    timestampEpochMs = baseTimestamp + index,
                    message = "entry $index",
                ),
                max = AuditRepository.MAX_ENTRIES,
            )
        }
    }

    @Test
    fun `inserting_more_than_the_cap_leaves_exactly_the_cap`() = runTest {
        insertSequentially(cap + overflow)

        assertEquals(cap, dao.count())
    }

    @Test
    fun `the_oldest_entries_beyond_the_cap_are_the_ones_deleted`() = runTest {
        insertSequentially(cap + overflow)

        val remaining = dao.getRecent(cap).map { it.timestampEpochMs }

        assertTrue(
            "the first $overflow entries must be gone; the newest are what explain the " +
                "most recent failure",
            remaining.none { it < baseTimestamp + overflow },
        )
    }

    @Test
    fun `the_newest_entry_survives_the_trim`() = runTest {
        insertSequentially(cap + overflow)

        val newest = dao.getRecent(1).single()

        assertEquals(baseTimestamp + cap + overflow - 1, newest.timestampEpochMs)
    }

    @Test
    fun `results_are_returned_newest_first_which_is_the_order_the_audit_screen_renders`() =
        runTest {
            insertSequentially(3)

            val timestamps = dao.getRecent(3).map { it.timestampEpochMs }

            assertEquals(
                listOf(baseTimestamp + 2, baseTimestamp + 1, baseTimestamp),
                timestamps,
            )
        }

    @Test
    fun `staying_under_the_cap_deletes_nothing`() = runTest {
        insertSequentially(cap - 1)

        assertEquals(cap - 1, dao.count())
    }

    @Test
    fun `inserting_exactly_the_cap_deletes_nothing_so_the_boundary_is_inclusive`() = runTest {
        insertSequentially(cap)

        assertEquals(cap, dao.count())
    }

    @Test
    fun `an_error_entry_is_stored_with_its_unrecoverable_flag_intact`() = runTest {
        dao.insertAndTrim(
            entity = DaoTestSupport.auditEntity(
                timestampEpochMs = baseTimestamp,
                type = AuditEventType.RESTORATION_FAILED,
                message = "Restore was incomplete",
                recoverable = false,
            ),
            max = AuditRepository.MAX_ENTRIES,
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
    fun `clearing_the_log_removes_every_row`() = runTest {
        insertSequentially(10)

        dao.clear()

        assertEquals(0, dao.count())
    }
}
