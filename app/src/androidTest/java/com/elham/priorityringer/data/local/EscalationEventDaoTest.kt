package com.elham.priorityringer.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elham.priorityringer.data.local.dao.EscalationDao
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Escalation timestamps (FR5, Architecture.md § 8, § 10).
 *
 * These are persisted rather than held in memory for one reason: a repeat caller
 * ringing twice five minutes apart very likely spans a process restart, and an
 * in-memory list would silently forget the first call and never escalate.
 *
 * Pruning matters as much as recording — without it the table grows once per
 * matched call forever.
 *
 * See `DaoTestSupport` for the provisional-names caveat.
 */
@RunWith(AndroidJUnit4::class)
class EscalationEventDaoTest {

    private lateinit var database: PriorityRingerDatabase
    private lateinit var dao: EscalationDao

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private val mum = "1234567"
    private val dad = "9998888"

    @Before
    fun setUp() {
        database = DaoTestSupport.inMemoryDatabase()
        dao = database.escalationDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun record(matchKey: String, at: Long) {
        dao.insert(DaoTestSupport.escalationEntity(matchKey = matchKey, timestampEpochMs = at))
    }

    @Test
    fun `timestamps at or after the cutoff are returned`() = runTest {
        record(mum, now - 2 * minute)
        record(mum, now - minute)

        val timestamps = dao.timestampsSince(mum, now - 5 * minute)

        assertEquals(setOf(now - 2 * minute, now - minute), timestamps.toSet())
    }

    @Test
    fun `a timestamp exactly on the cutoff is included, matching the policy's inclusive edge`() =
        runTest {
            record(mum, now - 5 * minute)

            val timestamps = dao.timestampsSince(mum, now - 5 * minute)

            assertEquals(
                "the DAO and EscalationPolicy must agree at the boundary, or a call " +
                    "would be counted by one and not the other",
                listOf(now - 5 * minute),
                timestamps,
            )
        }

    @Test
    fun `a timestamp before the cutoff is excluded`() = runTest {
        record(mum, now - 6 * minute)

        assertEquals(emptyList<Long>(), dao.timestampsSince(mum, now - 5 * minute))
    }

    @Test
    fun `timestamps are isolated per number, so one caller cannot escalate another`() = runTest {
        record(dad, now - minute)
        record(dad, now - 2 * minute)

        assertEquals(
            "escalation is 'this person keeps calling', not 'the phone keeps ringing'",
            emptyList<Long>(),
            dao.timestampsSince(mum, now - 10 * minute),
        )
    }

    @Test
    fun `repeated calls from one number all accumulate`() = runTest {
        record(mum, now - minute)
        record(mum, now - 2 * minute)
        record(mum, now - 3 * minute)

        assertEquals(3, dao.timestampsSince(mum, now - 10 * minute).size)
    }

    @Test
    fun `pruning deletes rows older than the cutoff`() = runTest {
        record(mum, now - 30 * minute)
        record(mum, now - 20 * minute)
        record(mum, now - minute)

        dao.pruneBefore(now - 10 * minute)

        assertEquals(1, dao.count())
    }

    @Test
    fun `pruning keeps a row sitting exactly on the cutoff`() = runTest {
        record(mum, now - 10 * minute)

        dao.pruneBefore(now - 10 * minute)

        assertEquals(
            "pruning must not delete a row the widest window can still count",
            1,
            dao.count(),
        )
    }

    @Test
    fun `pruning applies across every number, not just the one that called`() = runTest {
        record(mum, now - 30 * minute)
        record(dad, now - 30 * minute)

        dao.pruneBefore(now - 10 * minute)

        assertEquals(0, dao.count())
    }

    @Test
    fun `clearing removes every recorded call`() = runTest {
        record(mum, now - minute)
        record(dad, now - minute)

        dao.clear()

        assertEquals(0, dao.count())
    }
}
