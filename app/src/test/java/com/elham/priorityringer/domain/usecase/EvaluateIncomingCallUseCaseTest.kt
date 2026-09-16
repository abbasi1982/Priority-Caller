package com.elham.priorityringer.domain.usecase

import com.elham.priorityringer.domain.model.CallMatchResult
import com.elham.priorityringer.domain.model.IncomingCallEvent
import com.elham.priorityringer.domain.phone.NormalizedNumber
import com.elham.priorityringer.domain.phone.PhoneNumberNormalizer
import com.elham.priorityringer.fake.FakeContactRepository
import com.elham.priorityringer.fake.FakeTelephonyPort
import com.elham.priorityringer.fake.testContact
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matching an incoming call against the configured list (FR2, § 5.3).
 *
 * The distinction between [CallMatchResult.Miss] and
 * [CallMatchResult.NumberUnavailable] is not cosmetic: a miss is an ordinary
 * call the app must ignore in silence, while an unavailable number means
 * `READ_CALL_LOG` is missing and the app is **inert** rather than merely quiet
 * (§ A.5). They are surfaced to the user completely differently.
 */
class EvaluateIncomingCallUseCaseTest {

    private val normalizer = PhoneNumberNormalizer()
    private val telephony = FakeTelephonyPort(countryIso = "US")
    private val contacts = FakeContactRepository()

    private val useCase = EvaluateIncomingCallUseCase(
        contacts = contacts,
        normalizer = normalizer,
        telephony = telephony,
    )

    private fun callFrom(raw: String?) = IncomingCallEvent(
        number = normalizer.normalize(raw, telephony.defaultCountryIso()),
        timestampEpochMs = 1_700_000_000_000L,
    )

    @Test
    fun `a call from an enabled contact is an enabled priority match`() = runTest {
        val mum = testContact(id = 1L, displayName = "Mum", originalInput = "+15551234567")
        contacts.seed(mum)

        val result = useCase(callFrom("5551234567"))

        assertEquals(CallMatchResult.EnabledPriority(mum), result)
    }

    @Test
    fun `a call from a contact the user toggled off is a disabled priority match, not a miss`() =
        runTest {
            val mum = testContact(id = 1L, originalInput = "+15551234567", enabled = false)
            contacts.seed(mum)

            val result = useCase(callFrom("5551234567"))

            assertEquals(
                "the UI needs to distinguish 'not in your list' from 'switched off', " +
                    "because only one of those is something the user can undo",
                CallMatchResult.DisabledPriority(mum),
                result,
            )
        }

    @Test
    fun `a call from a number that is not in the list is a miss`() = runTest {
        contacts.seed(testContact(id = 1L, originalInput = "+15551234567"))

        val result = useCase(callFrom("+15559998888"))

        assertEquals(CallMatchResult.Miss, result)
    }

    @Test
    fun `a call with no usable number is NUMBER_UNAVAILABLE, never a miss`() = runTest {
        contacts.seed(testContact(id = 1L))

        val result = useCase(
            IncomingCallEvent(
                number = NormalizedNumber.UNKNOWN,
                timestampEpochMs = 1_700_000_000_000L,
            ),
        )

        assertEquals(
            "§ A.5 — this is the only result possible with READ_CALL_LOG denied, and " +
                "the dashboard must say 'inert', not 'no priority call'",
            CallMatchResult.NumberUnavailable,
            result,
        )
    }

    @Test
    fun `an alphanumeric sender id is NUMBER_UNAVAILABLE and can never reach a contact`() = runTest {
        contacts.seed(testContact(id = 1L))

        val result = useCase(callFrom("VM"))

        assertEquals(CallMatchResult.NumberUnavailable, result)
    }

    @Test
    fun `an empty contact list yields a miss without consulting the platform matcher`() = runTest {
        val result = useCase(callFrom("+15551234567"))

        assertEquals(CallMatchResult.Miss, result)
        assertTrue(telephony.platformMatchQueries.isEmpty())
    }

    @Test
    fun `a contact is matched by full comparison even when the stored key does not match`() =
        runTest {
            // A contact saved before a key-format change: originalInput is still right.
            val mum = testContact(id = 1L, originalInput = "+15551234567", matchKey = "0000000")
            contacts.seed(mum)

            val result = useCase(callFrom("+1 555 123 4567"))

            assertEquals(CallMatchResult.EnabledPriority(mum), result)
        }

    @Test
    fun `the platform matcher can add a match the pure rules missed, but is consulted last`() =
        runTest {
            val mum = testContact(
                id = 1L,
                originalInput = "+445559998888",
                matchKey = "5559998888".takeLast(7),
            )
            contacts.seed(mum)
            telephony.platformMatch = { _, _ -> true }

            val result = useCase(callFrom("+15551234567"))

            assertEquals(
                "§ 5.3 asks for both comparisons; the platform's opinion may only turn " +
                    "a non-match into a match, never the reverse",
                CallMatchResult.EnabledPriority(mum),
                result,
            )
        }

    @Test
    fun `matching runs against every contact, so the second entry is found too`() = runTest {
        val dad = testContact(id = 1L, displayName = "Dad", originalInput = "+15550000001")
        val mum = testContact(id = 2L, displayName = "Mum", originalInput = "+15551234567")
        contacts.seed(dad, mum)

        val result = useCase(callFrom("5551234567"))

        assertEquals(CallMatchResult.EnabledPriority(mum), result)
    }

    @Test
    fun `a short code does not match a contact whose number merely ends in the same digits`() =
        runTest {
            contacts.seed(testContact(id = 1L, originalInput = "5550911", matchKey = "5550911"))

            val result = useCase(callFrom("911"))

            assertEquals(CallMatchResult.Miss, result)
        }
}
