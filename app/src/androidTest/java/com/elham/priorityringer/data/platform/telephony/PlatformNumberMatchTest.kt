package com.elham.priorityringer.data.platform.telephony

import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elham.priorityringer.domain.phone.PhoneNumberNormalizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `PhoneNumberUtils.compare` on a real device (ImplementationPlan.md § Phase 2).
 *
 * This is the one matching path with no JVM coverage. `PhoneNumberUtils` is
 * stubbed to `false` on the JVM, and the unit tests cover
 * [PhoneNumberNormalizer] — which is the layer that does *not* call it. So this
 * test has to run on a device or it does not run at all.
 *
 * **Scope.** It covers the extra-accept seam and the composed match predicate on
 * a handful of pairs. It does **not** cover `EvaluateIncomingCallUseCase`
 * end-to-end — there is no repository, no settings and no contact list here —
 * and it says nothing about ringer, volume or DND behaviour. The device matrix
 * still owns the decision to sideload.
 *
 * ## Why the assertions are asymmetric
 *
 * [com.elham.priorityringer.domain.port.TelephonyPort.platformNumbersMatch] is
 * layered *after* the pure matcher as an additional accept, so it can only turn
 * a non-match into a match. That makes the two directions completely different
 * risks:
 *
 * - A **false negative** here is harmless. The normalizer already matched, or it
 *   didn't and the platform was never going to save it. Nothing rings that
 *   shouldn't; at worst a call the user wanted loud stays quiet, which is the
 *   phone's ordinary behaviour.
 * - A **false positive** here makes the *wrong caller* loud in Do Not Disturb.
 *   That is the app actively doing harm, on a family member's phone, for a call
 *   they never allowlisted.
 *
 * So the must-not-match pairs are asserted hard against *both* layers, and the
 * must-match pairs are asserted against the **composed** predicate rather than
 * against `platformNumbersMatch` alone.
 *
 * The composed form matters for a specific reason. `PhoneNumberUtils.compare`
 * picks strict or loose comparison from a platform config resource
 * (`config_use_strict_phone_number_comparison`), which the app cannot read
 * without reaching into `com.android.internal` — a hidden resource, which this
 * project does not do, in tests either. On a strict-config device the
 * trunk-prefix pair below legitimately returns `false` from the platform, and
 * the normalizer carries the match instead (it strips the trunk `0` and
 * suffix-matches). Asserting on `platformNumbersMatch` alone would fail there
 * for a device configuration that produces no user-visible difference. Asserting
 * on the composed predicate tests the thing that actually decides whether the
 * phone rings, and it still exercises the platform call on every run.
 */
@RunWith(AndroidJUnit4::class)
class PlatformNumberMatchTest {

    private lateinit var port: AndroidTelephonyPort
    private val normalizer = PhoneNumberNormalizer()

    /**
     * Built directly rather than through Hilt, following the convention
     * `DaoTestSupport` states: these tests exercise platform behaviour, not the
     * DI graph.
     */
    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        port = AndroidTelephonyPort(
            context = context,
            telephonyManager = context.getSystemService(TelephonyManager::class.java),
        )
    }

    // ---------------------------------------------------------------- matches

    @Test
    fun nationalAndE164FormsOfTheSameUsNumberMatch() {
        assertMatches(
            incoming = "+1 555 123 4567",
            saved = "5551234567",
            iso = "US",
            why = "the same subscriber written internationally and nationally",
        )
    }

    @Test
    fun trunkPrefixedAndE164FormsOfTheSameUkNumberMatch() {
        // GB is the trunk-prefix case the reviewer asked for: the national form
        // carries a leading 0 that the international form does not.
        assertMatches(
            incoming = "+44 7700 900123",
            saved = "07700 900123",
            iso = "GB",
            why = "the same subscriber with and without the national trunk prefix",
        )
    }

    @Test
    fun spacingAndPunctuationDoNotDefeatAMatch() {
        assertMatches(
            incoming = "(555) 123-4567",
            saved = "555.123.4567",
            iso = "US",
            why = "identical digits, different punctuation",
        )
    }

    // ------------------------------------------------------------ non-matches

    @Test
    fun differentSubscribersDoNotMatch() {
        assertDoesNotMatch(
            incoming = "+1 555 123 4567",
            saved = "+1 555 123 4568",
            iso = "US",
            why = "two different subscribers one digit apart",
        )
    }

    @Test
    fun differentSubscribersSharingASuffixDoNotMatch() {
        assertDoesNotMatch(
            incoming = "+1 555 987 6543",
            saved = "+1 555 123 4567",
            iso = "US",
            why = "same area code, different subscriber",
        )
    }

    @Test
    fun aShortCodeDoesNotMatchALongerNumberEndingInIt() {
        // The one the app is most exposed to: emergency and service short codes
        // arrive as ordinary incoming numbers. Under the documented loose
        // comparison this is false because only three digits match, below the
        // minimum. A device that ships a smaller
        // `config_phonenumber_compare_min_match` would return true — see the
        // failure message; that is a real finding about the device, not noise.
        assertDoesNotMatch(
            incoming = "911",
            saved = "5550911",
            iso = "US",
            why = "a short code against a longer number that happens to end in it",
        )
    }

    @Test
    fun alphanumericSenderIdsDoNotMatchAnything() {
        assertDoesNotMatch(
            incoming = "VOICEMAIL",
            saved = "5551234567",
            iso = "US",
            why = "an alphanumeric sender ID is not a dialable number",
        )
    }

    @Test
    fun anEmptyNumberDoesNotMatch() {
        // FR2: a withheld number arrives as empty. It must never match a
        // contact, and the platform call must not throw on it.
        assertDoesNotMatch(
            incoming = "",
            saved = "5551234567",
            iso = "US",
            why = "a withheld or unavailable caller number",
        )
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Mirrors the three accepts in
     * [com.elham.priorityringer.domain.usecase.EvaluateIncomingCallUseCase], in
     * the same order, without needing a repository.
     */
    private fun appWouldMatch(incoming: String, saved: String, iso: String): Boolean {
        val incomingNumber = normalizer.normalize(incoming, iso)
        val savedNumber = normalizer.normalize(saved, iso)

        return normalizer.matchKey(incomingNumber)
            .let { it.isNotEmpty() && it == normalizer.matchKey(savedNumber) } ||
            normalizer.matches(incomingNumber, savedNumber) ||
            port.platformNumbersMatch(incoming, saved)
    }

    private fun assertMatches(incoming: String, saved: String, iso: String, why: String) {
        assertTrue(diagnostic("must match — $why", incoming, saved, iso), appWouldMatch(incoming, saved, iso))
    }

    private fun assertDoesNotMatch(incoming: String, saved: String, iso: String, why: String) {
        // Both layers, separately. The composed predicate would hide a
        // too-loose platform comparison behind a correct normalizer, and the
        // platform layer is the one that can only *add* matches.
        assertFalse(
            diagnostic(
                "the platform comparison is looser than this app assumes — $why. " +
                    "The extra-accept layer in AndroidTelephonyPort must be gated " +
                    "off on this device",
                incoming,
                saved,
                iso,
            ),
            port.platformNumbersMatch(incoming, saved),
        )
        assertFalse(
            diagnostic("must not match — $why", incoming, saved, iso),
            appWouldMatch(incoming, saved, iso),
        )
    }

    /**
     * Carries the device's own ISO into every failure message.
     * `PhoneNumberUtils.compare` takes no country argument — its only
     * country-sensitivity is the config resource — but a carrier- or
     * region-dependent failure is undiagnosable from a bare `expected true`,
     * and the reporter is usually not holding the phone.
     *
     * Read through the port's own `defaultCountryIso()`, the same seam the app
     * uses, which is already try/caught and so cannot throw the test.
     */
    private fun diagnostic(message: String, incoming: String, saved: String, iso: String): String =
        "$message\n" +
            "  incoming=\"$incoming\" saved=\"$saved\" assumedIso=$iso\n" +
            "  device defaultCountryIso=${port.defaultCountryIso() ?: "(unknown)"}\n" +
            "  platformNumbersMatch=${port.platformNumbersMatch(incoming, saved)}\n" +
            "  normalizer.matches=${
                normalizer.matches(
                    normalizer.normalize(incoming, iso),
                    normalizer.normalize(saved, iso),
                )
            }"
}
