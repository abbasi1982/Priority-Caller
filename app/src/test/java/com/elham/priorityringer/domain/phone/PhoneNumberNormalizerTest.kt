package com.elham.priorityringer.domain.phone

import com.elham.priorityringer.domain.phone.PhoneNumberNormalizer.Companion.MIN_SUBSCRIBER_DIGITS
import com.elham.priorityringer.domain.phone.PhoneNumberNormalizer.Companion.SUFFIX_MATCH_DIGITS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-driven tests for the matching rules (Architecture.md § 5.3, § 14).
 *
 * This is the single most bug-prone decision in the app — "did this incoming
 * call come from a contact the user cares about?" — and both possible errors
 * are serious. A false negative means the family call the app exists for rings
 * quietly. A false positive means the app raises the volume and relaxes Do Not
 * Disturb for a stranger, which is exactly the behaviour § 4 rule 2 forbids.
 *
 * The rules are pure Kotlin precisely so they can be tabulated here rather than
 * probed on a device.
 */
class PhoneNumberNormalizerTest {

    private val normalizer = PhoneNumberNormalizer()

    private data class NormalizeCase(
        val description: String,
        val raw: String?,
        val iso: String?,
        val expectedDigits: String,
        val expectedE164: String?,
    )

    // -----------------------------------------------------------------------
    // normalize()
    // -----------------------------------------------------------------------

    @Test
    fun `normalize resolves every input shape the phone actually delivers to the same comparable form`() {
        val cases = listOf(
            NormalizeCase(
                description = "E.164 passes through untouched",
                raw = "+15551234567",
                iso = null,
                expectedDigits = "15551234567",
                expectedE164 = "+15551234567",
            ),
            NormalizeCase(
                description = "E.164 needs no country hint, so an unknown ISO is harmless",
                raw = "+447700900123",
                iso = "ZZ",
                expectedDigits = "447700900123",
                expectedE164 = "+447700900123",
            ),
            NormalizeCase(
                description = "'00' international access prefix is equivalent to '+'",
                raw = "004917612345678",
                iso = null,
                expectedDigits = "4917612345678",
                expectedE164 = "+4917612345678",
            ),
            NormalizeCase(
                description = "'00' prefix survives spacing",
                raw = "00 1 555 123 4567",
                iso = null,
                expectedDigits = "15551234567",
                expectedE164 = "+15551234567",
            ),
            NormalizeCase(
                description = "national NANP number is promoted using the SIM country",
                raw = "5551234567",
                iso = "US",
                expectedDigits = "5551234567",
                expectedE164 = "+15551234567",
            ),
            NormalizeCase(
                description = "ISO is case-insensitive",
                raw = "5551234567",
                iso = "us",
                expectedDigits = "5551234567",
                expectedE164 = "+15551234567",
            ),
            NormalizeCase(
                description = "national number that already carries its country code is not doubled",
                raw = "15551234567",
                iso = "US",
                expectedDigits = "15551234567",
                expectedE164 = "+15551234567",
            ),
            NormalizeCase(
                description = "trunk-prefix '0' is stripped for non-NANP countries",
                raw = "07700900123",
                iso = "GB",
                expectedDigits = "07700900123",
                expectedE164 = "+447700900123",
            ),
            NormalizeCase(
                description = "NANP has no trunk prefix, so a leading 0 is kept as a digit",
                raw = "0555123456",
                iso = "US",
                expectedDigits = "0555123456",
                expectedE164 = "+10555123456",
            ),
            NormalizeCase(
                description = "unknown ISO refuses to guess a country code rather than inventing one",
                raw = "07700900123",
                iso = "ZZ",
                expectedDigits = "07700900123",
                expectedE164 = null,
            ),
            NormalizeCase(
                description = "absent ISO refuses to guess a country code",
                raw = "5551234567",
                iso = null,
                expectedDigits = "5551234567",
                expectedE164 = null,
            ),
            NormalizeCase(
                description = "parentheses, dashes and spaces are all separators",
                raw = "+1 (555) 123-4567",
                iso = null,
                expectedDigits = "15551234567",
                expectedE164 = "+15551234567",
            ),
            NormalizeCase(
                description = "dots and non-breaking punctuation are separators too",
                raw = " 555.123.4567 ",
                iso = "US",
                expectedDigits = "5551234567",
                expectedE164 = "+15551234567",
            ),
            NormalizeCase(
                description = "short code is kept verbatim and never promoted to E.164",
                raw = "911",
                iso = "US",
                expectedDigits = "911",
                expectedE164 = null,
            ),
        )

        cases.forEach { case ->
            val actual = normalizer.normalize(case.raw, case.iso)
            assertEquals(
                "digits for '${case.raw}' (${case.description})",
                case.expectedDigits,
                actual.digits,
            )
            assertEquals(
                "e164 for '${case.raw}' (${case.description})",
                case.expectedE164,
                actual.e164,
            )
        }
    }

    @Test
    fun `alphanumeric sender ids normalize to UNKNOWN because they are not dialable`() {
        listOf("VM", "GOOGLE", "Voicemail", "MyBank", "+44VODAFONE", "A1").forEach { senderId ->
            assertEquals(
                "'$senderId' must not survive normalization",
                NormalizedNumber.UNKNOWN,
                normalizer.normalize(senderId),
            )
        }
    }

    @Test
    fun `blank and null input normalize to UNKNOWN rather than throwing`() {
        listOf(null, "", "   ", "\t\n", "()-", "+", "--").forEach { raw ->
            assertEquals(
                "'$raw' must normalize to UNKNOWN",
                NormalizedNumber.UNKNOWN,
                normalizer.normalize(raw),
            )
        }
    }

    @Test
    fun `an unusable number is reported as unusable so the caller can log NUMBER_UNAVAILABLE`() {
        assertFalse(normalizer.normalize(null).isUsable)
        assertTrue(normalizer.normalize("5551234567").isUsable)
    }

    @Test
    fun `redaction never exposes more than the last four digits`() {
        assertEquals("•••4567", normalizer.normalize("+15551234567").redacted)
        assertEquals("(unknown)", NormalizedNumber.UNKNOWN.redacted)
    }

    // -----------------------------------------------------------------------
    // matches()
    // -----------------------------------------------------------------------

    private data class MatchCase(
        val description: String,
        val a: String?,
        val aIso: String? = null,
        val b: String?,
        val bIso: String? = null,
        val expected: Boolean,
    )

    @Test
    fun `matching accepts the same subscriber written differently and rejects everyone else`() {
        val cases = listOf(
            MatchCase(
                description = "E.164 against the same national number — the everyday case",
                a = "+1 555 123 4567",
                b = "5551234567",
                expected = true,
            ),
            MatchCase(
                description = "both sides E.164 and identical",
                a = "+447700900123",
                b = "+44 7700 900123",
                expected = true,
            ),
            MatchCase(
                description = "UK trunk prefix against UK E.164 matches on the 7-digit suffix",
                a = "07700900123",
                aIso = "ZZ",
                b = "+447700900123",
                expected = true,
            ),
            MatchCase(
                description = "'00' prefixed form equals the '+' form",
                a = "00447700900123",
                b = "+447700900123",
                expected = true,
            ),
            MatchCase(
                description = "two genuinely different numbers must never match",
                a = "+15551234567",
                b = "+15559998888",
                expected = false,
            ),
            MatchCase(
                description = "different numbers sharing a prefix but not a suffix",
                a = "5551234567",
                b = "5551230000",
                expected = false,
            ),
            MatchCase(
                description = "both sides E.164 in different countries — equality is authoritative",
                a = "+15551234567",
                aIso = "US",
                b = "+445551234567",
                bIso = "GB",
                expected = false,
            ),
            MatchCase(
                description = "alphanumeric sender id must never match a real contact",
                a = "VM",
                b = "+15551234567",
                expected = false,
            ),
            MatchCase(
                description = "unusable against unusable is still not a match",
                a = "",
                b = "",
                expected = false,
            ),
        )

        cases.forEach { case ->
            val actual = normalizer.matches(
                normalizer.normalize(case.a, case.aIso),
                normalizer.normalize(case.b, case.bIso),
            )
            assertEquals(
                "'${case.a}' vs '${case.b}' (${case.description})",
                case.expected,
                actual,
            )
        }
    }

    @Test
    fun `a short code must match exactly, so 911 never matches a number merely ending in 911`() {
        val emergency = normalizer.normalize("911", "US")
        val ordinary = normalizer.normalize("5550911", "US")

        assertFalse(
            "911 is shorter than the $SUFFIX_MATCH_DIGITS-digit suffix window and must " +
                "require exact equality",
            normalizer.matches(emergency, ordinary),
        )
    }

    @Test
    fun `a short code matches itself`() {
        assertTrue(
            normalizer.matches(
                normalizer.normalize("911", "US"),
                normalizer.normalize("911", "GB"),
            ),
        )
    }

    @Test
    fun `matching is symmetric, because contact-first and call-first orderings both occur`() {
        val a = normalizer.normalize("+1 555 123 4567")
        val b = normalizer.normalize("5551234567")

        assertEquals(normalizer.matches(a, b), normalizer.matches(b, a))
    }

    // -----------------------------------------------------------------------
    // matchKey()
    // -----------------------------------------------------------------------

    @Test
    fun `matchKey is stable across formatting, which is what makes the unique index correct`() {
        val equivalent = listOf(
            "+15551234567",
            "+1 (555) 123-4567",
            "0015551234567",
            "15551234567",
            "5551234567",
            "555-123-4567",
        )

        val keys = equivalent.map { normalizer.matchKey(normalizer.normalize(it, "US")) }.toSet()

        assertEquals(
            "all formattings of one number must produce one key, else § 10's unique " +
                "index would let the same contact be added twice: $keys",
            setOf("1234567"),
            keys,
        )
    }

    @Test
    fun `matchKey of a short code is the whole number, not a padded suffix`() {
        assertEquals("911", normalizer.matchKey(normalizer.normalize("911", "US")))
    }

    @Test
    fun `matchKey of an unusable number is empty so it can never collide with a real contact`() {
        assertEquals("", normalizer.matchKey(NormalizedNumber.UNKNOWN))
        assertEquals("", normalizer.matchKey(normalizer.normalize("GOOGLE")))
    }

    // -----------------------------------------------------------------------
    // isPlausible()
    // -----------------------------------------------------------------------

    @Test
    fun `isPlausible rejects one digit below the subscriber-length boundary`() {
        val justTooShort = "1".repeat(MIN_SUBSCRIBER_DIGITS - 1)

        assertFalse(
            "$justTooShort has ${MIN_SUBSCRIBER_DIGITS - 1} digits and must be rejected",
            normalizer.isPlausible(justTooShort),
        )
    }

    @Test
    fun `isPlausible accepts exactly the subscriber-length boundary`() {
        val exactlyLongEnough = "1".repeat(MIN_SUBSCRIBER_DIGITS)

        assertTrue(
            "$exactlyLongEnough has exactly $MIN_SUBSCRIBER_DIGITS digits and must be accepted",
            normalizer.isPlausible(exactlyLongEnough),
        )
    }

    @Test
    fun `isPlausible rejects blank, null and alphanumeric manual entry`() {
        listOf(null, "", "   ", "VM", "call mum").forEach { raw ->
            assertFalse("'$raw' must be rejected by manual-entry validation", normalizer.isPlausible(raw))
        }
    }

    @Test
    fun `isPlausible does not require a known country, because E164 promotion is optional`() {
        assertTrue(normalizer.isPlausible("07700900123", defaultCountryIso = null))
        assertNull(normalizer.normalize("07700900123", defaultCountryIso = null).e164)
    }
}
