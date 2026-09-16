package com.elham.priorityringer.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elham.priorityringer.data.local.dao.ContactDao
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contacts table (Architecture.md § 10 — "unique index on normalized number").
 *
 * The index is what stops one person appearing twice in the priority list under
 * two different formattings of the same number. The DAO's job is to report that
 * collision as data (`-1` from an IGNORE-strategy insert) rather than throwing,
 * so `ContactRepository.add` can turn it into "already in your list" instead of
 * an error dialog.
 *
 * Backtick test names are safe here: `minSdk = 30` and method names with spaces
 * are permitted from API 30 onwards.
 *
 * See `DaoTestSupport` for the provisional-names caveat.
 */
@RunWith(AndroidJUnit4::class)
class PriorityContactDaoTest {

    private lateinit var database: PriorityRingerDatabase
    private lateinit var dao: ContactDao

    @Before
    fun setUp() {
        database = DaoTestSupport.inMemoryDatabase()
        dao = database.contactDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `inserting the first contact returns a real row id`() = runTest {
        val id = dao.insert(DaoTestSupport.contactEntity(matchKey = "1234567"))

        assertEquals(1L, id)
    }

    @Test
    fun `a contact whose match key is already taken returns minus one instead of throwing`() =
        runTest {
            dao.insert(DaoTestSupport.contactEntity(matchKey = "1234567", displayName = "Mum"))

            val id = dao.insert(
                DaoTestSupport.contactEntity(matchKey = "1234567", displayName = "Mum (mobile)"),
            )

            assertEquals(
                "a duplicate is an ordinary user mistake, not an exception — the unique " +
                    "index must surface as -1 so the UI can say 'already in your list'",
                -1L,
                id,
            )
        }

    @Test
    fun `a rejected duplicate does not overwrite the existing row`() = runTest {
        dao.insert(DaoTestSupport.contactEntity(matchKey = "1234567", displayName = "Mum"))

        dao.insert(
            DaoTestSupport.contactEntity(matchKey = "1234567", displayName = "Unknown caller"),
        )

        assertEquals(listOf("Mum"), dao.getAll().map { it.displayName })
    }

    @Test
    fun `two different numbers both insert successfully`() = runTest {
        dao.insert(DaoTestSupport.contactEntity(matchKey = "1234567", displayName = "Mum"))
        dao.insert(DaoTestSupport.contactEntity(matchKey = "9998888", displayName = "Dad"))

        assertEquals(2, dao.getAll().size)
    }

    @Test
    fun `deleting a contact frees its match key, so a mistaken delete is not permanent`() = runTest {
        val id = dao.insert(DaoTestSupport.contactEntity(matchKey = "1234567"))
        dao.deleteById(id)

        val reinserted = dao.insert(DaoTestSupport.contactEntity(matchKey = "1234567"))

        assertTrue("re-inserting a removed number must succeed", reinserted > 0L)
    }

    @Test
    fun `toggling a contact off keeps the row so the user can toggle it back on`() = runTest {
        val id = dao.insert(DaoTestSupport.contactEntity(matchKey = "1234567"))

        dao.setEnabled(id, false)

        assertEquals(listOf(false), dao.getAll().map { it.enabled })
    }
}
