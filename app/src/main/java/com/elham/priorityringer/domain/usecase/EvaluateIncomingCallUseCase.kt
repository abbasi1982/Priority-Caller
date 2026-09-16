package com.elham.priorityringer.domain.usecase

import com.elham.priorityringer.domain.model.CallMatchResult
import com.elham.priorityringer.domain.model.IncomingCallEvent
import com.elham.priorityringer.domain.phone.PhoneNumberNormalizer
import com.elham.priorityringer.domain.port.TelephonyPort
import com.elham.priorityringer.domain.repository.ContactRepository
import javax.inject.Inject

/**
 * Match an incoming call against the configured list (FR2 step 3).
 *
 * Matching runs against **enabled contacts only** (Architecture.md § 5.3), and
 * uses the pure [PhoneNumberNormalizer] first with the platform's
 * `PhoneNumberUtils.compare` layered on top as an additional accept — § 5.3
 * asks for both, and this ordering keeps the decisive logic JVM-testable.
 */
class EvaluateIncomingCallUseCase @Inject constructor(
    private val contacts: ContactRepository,
    private val normalizer: PhoneNumberNormalizer,
    private val telephony: TelephonyPort,
) {

    suspend operator fun invoke(event: IncomingCallEvent): CallMatchResult {
        if (!event.number.isUsable) return CallMatchResult.NumberUnavailable

        val all = contacts.getAll()
        if (all.isEmpty()) return CallMatchResult.Miss

        val incomingKey = normalizer.matchKey(event.number)

        val match = all.firstOrNull { contact ->
            // Cheap exact key comparison first — the common case.
            contact.matchKey == incomingKey ||
                // Then the fuller pure comparison, which also handles E.164.
                normalizer.matches(
                    event.number,
                    normalizer.normalize(contact.originalInput, telephony.defaultCountryIso()),
                ) ||
                // Finally the platform's opinion. This can only add matches,
                // never remove them.
                telephony.platformNumbersMatch(event.number.raw, contact.originalInput)
        } ?: return CallMatchResult.Miss

        return if (match.enabled) {
            CallMatchResult.EnabledPriority(match)
        } else {
            CallMatchResult.DisabledPriority(match)
        }
    }
}
