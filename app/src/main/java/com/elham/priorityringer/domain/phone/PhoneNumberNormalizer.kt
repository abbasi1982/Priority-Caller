package com.elham.priorityringer.domain.phone

/**
 * A phone number reduced to a form that can be compared.
 *
 * @param raw        exactly what we were given, kept for display.
 * @param digits     all non-dial characters stripped; no leading `+`.
 * @param e164       `+<country><subscriber>` when we could confidently build
 *                   one, else `null`. Absence is normal, not an error.
 */
data class NormalizedNumber(
    val raw: String,
    val digits: String,
    val e164: String?,
) {
    val isUsable: Boolean get() = digits.isNotEmpty()

    /** Last-4 only, for logs. Architecture.md § 13 forbids full numbers in logcat. */
    val redacted: String
        get() = when {
            digits.length >= 4 -> "•••${digits.takeLast(4)}"
            digits.isNotEmpty() -> "•••"
            else -> "(unknown)"
        }

    companion object {
        val UNKNOWN = NormalizedNumber(raw = "", digits = "", e164 = null)
    }
}

/**
 * Pure-Kotlin number normalisation and matching.
 *
 * Architecture.md § 5.3 nominates `PhoneNumberUtils` for this. That class lives
 * in `android.telephony` and cannot run on the JVM, which would push the single
 * most bug-prone piece of logic in the app (does this incoming number match a
 * saved contact?) out of reach of ordinary unit tests.
 *
 * So the rules live here, in pure Kotlin, table-tested. The platform's
 * `PhoneNumberUtils.compare` is layered *on top* in the data layer as an
 * additional accept — it can only turn a non-match into a match, never the
 * reverse. § 5.3's intent ("compare using `PhoneNumberUtils.compare` **and**
 * equality of E.164") is preserved; only the ordering changes.
 *
 * Deliberately **not** a full libphonenumber implementation. It handles the
 * cases a family-phone allowlist actually sees and refuses to guess beyond them.
 */
class PhoneNumberNormalizer {

    /**
     * @param defaultCountryIso ISO-3166 alpha-2, from network or SIM
     *   (Architecture.md § 5.3), lowercase or uppercase. Used only to promote a
     *   national number to E.164. `null` is fine — we simply won't produce E.164.
     */
    fun normalize(raw: String?, defaultCountryIso: String? = null): NormalizedNumber {
        if (raw.isNullOrBlank()) return NormalizedNumber.UNKNOWN

        val trimmed = raw.trim()

        // Reject alphanumeric sender IDs ("VM", "GOOGLE") outright — they are
        // not dialable numbers and must never match a contact.
        if (trimmed.any { it.isLetter() }) return NormalizedNumber.UNKNOWN

        val hadPlus = trimmed.startsWith("+") || trimmed.startsWith("00")
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return NormalizedNumber.UNKNOWN

        // "00" is the international access prefix in most of the world.
        val international = when {
            trimmed.startsWith("+") -> digits
            trimmed.startsWith("00") -> digits.removePrefix("00")
            else -> null
        }

        val e164 = when {
            international != null && international.isNotEmpty() -> "+$international"
            else -> promoteToE164(digits, defaultCountryIso)
        }

        return NormalizedNumber(
            raw = trimmed,
            digits = if (hadPlus) international ?: digits else digits,
            e164 = e164,
        )
    }

    /**
     * Turn a national-format number into E.164 when — and only when — we know
     * the country. Guessing here would create false matches, so an unknown
     * country yields `null` and matching falls back to suffix comparison.
     */
    private fun promoteToE164(digits: String, countryIso: String?): String? {
        val code = countryIso?.let { COUNTRY_CALLING_CODES[it.uppercase()] } ?: return null

        // Strip a national trunk prefix ("0" in most of Europe/Asia) before
        // prepending the country code. NANP (+1) has no trunk prefix.
        val national = if (code != "1" && digits.startsWith("0")) {
            digits.removePrefix("0")
        } else {
            digits
        }

        if (national.length < MIN_SUBSCRIBER_DIGITS) return null

        // Already carries its own country code (user typed "15551234567").
        if (national.startsWith(code) && national.length > code.length + MIN_SUBSCRIBER_DIGITS) {
            return "+$national"
        }
        return "+$code$national"
    }

    /**
     * Do these two numbers refer to the same subscriber?
     *
     * Precedence:
     *  1. Both have E.164 → exact equality. Authoritative.
     *  2. Otherwise → compare the trailing [SUFFIX_MATCH_DIGITS] digits, which
     *     absorbs country code and trunk-prefix differences
     *     (`+44 7700 900123` vs `07700 900123`).
     *
     * Rule 2 is the same heuristic `PhoneNumberUtils.compare` uses. It can
     * theoretically collide across countries; for a handful of hand-entered
     * family contacts that risk is acceptable and strongly preferable to
     * missing a real call. Short codes below the suffix length must match
     * exactly, so `911` never matches `5550911`.
     */
    fun matches(a: NormalizedNumber, b: NormalizedNumber): Boolean {
        if (!a.isUsable || !b.isUsable) return false

        if (a.e164 != null && b.e164 != null) return a.e164 == b.e164

        val shorter = minOf(a.digits.length, b.digits.length)
        if (shorter < SUFFIX_MATCH_DIGITS) return a.digits == b.digits

        return a.digits.takeLast(SUFFIX_MATCH_DIGITS) == b.digits.takeLast(SUFFIX_MATCH_DIGITS)
    }

    /**
     * Stable key for the unique index on the contacts table
     * (Architecture.md § 10). Matching at query time is never re-normalised —
     * the key is computed once on insert.
     */
    fun matchKey(number: NormalizedNumber): String = when {
        !number.isUsable -> ""
        number.digits.length >= SUFFIX_MATCH_DIGITS -> number.digits.takeLast(SUFFIX_MATCH_DIGITS)
        else -> number.digits
    }

    /** Is this plausibly dialable? Used to validate manual entry (FR1). */
    fun isPlausible(raw: String?, defaultCountryIso: String? = null): Boolean {
        val n = normalize(raw, defaultCountryIso)
        return n.isUsable && n.digits.length >= MIN_SUBSCRIBER_DIGITS
    }

    companion object {
        /**
         * Trailing digits compared when E.164 is unavailable on either side.
         * 7 matches the platform's own default and is long enough that ordinary
         * national numbers don't collide.
         */
        const val SUFFIX_MATCH_DIGITS = 7

        /** Below this, treat as a short code rather than a subscriber number. */
        const val MIN_SUBSCRIBER_DIGITS = 5

        /**
         * Enough coverage for the app's realistic user base. An ISO absent from
         * this map simply means no E.164 promotion — matching degrades to
         * suffix comparison, which still works.
         */
        private val COUNTRY_CALLING_CODES: Map<String, String> = mapOf(
            "US" to "1", "CA" to "1",
            "GB" to "44", "IE" to "353",
            "DE" to "49", "FR" to "33", "ES" to "34", "IT" to "39",
            "NL" to "31", "BE" to "32", "CH" to "41", "AT" to "43",
            "SE" to "46", "NO" to "47", "DK" to "45", "FI" to "358",
            "PL" to "48", "PT" to "351", "GR" to "30", "CZ" to "420",
            "RO" to "40", "HU" to "36", "TR" to "90", "RU" to "7",
            "UA" to "380",
            "IN" to "91", "PK" to "92", "BD" to "880", "LK" to "94",
            "CN" to "86", "JP" to "81", "KR" to "82", "TW" to "886",
            "HK" to "852", "SG" to "65", "MY" to "60", "ID" to "62",
            "TH" to "66", "VN" to "84", "PH" to "63",
            "AU" to "61", "NZ" to "64",
            "AE" to "971", "SA" to "966", "QA" to "974", "KW" to "965",
            "BH" to "973", "OM" to "968", "JO" to "962", "LB" to "961",
            "IL" to "972", "IR" to "98", "IQ" to "964",
            "EG" to "20", "MA" to "212", "DZ" to "213", "TN" to "216",
            "ZA" to "27", "NG" to "234", "KE" to "254", "GH" to "233",
            "ET" to "251", "TZ" to "255", "UG" to "256",
            "BR" to "55", "MX" to "52", "AR" to "54", "CL" to "56",
            "CO" to "57", "PE" to "51", "VE" to "58",
        )
    }
}
