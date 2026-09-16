package com.elham.priorityringer.domain.usecase

import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.PriorityContact
import com.elham.priorityringer.domain.phone.PhoneNumberNormalizer
import com.elham.priorityringer.domain.port.Clock
import com.elham.priorityringer.domain.port.TelephonyPort
import com.elham.priorityringer.domain.repository.AuditRepository
import com.elham.priorityringer.domain.repository.ContactRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** FR1 — priority contact management. */

class ObservePriorityContactsUseCase @Inject constructor(
    private val repository: ContactRepository,
) {
    operator fun invoke(): Flow<List<PriorityContact>> = repository.observeAll()
}

/** Why an add attempt did not produce a contact. */
sealed interface AddContactResult {
    data class Added(val id: Long) : AddContactResult
    data object InvalidNumber : AddContactResult
    data object AlreadyExists : AddContactResult
}

/**
 * Add a contact, from the system picker or typed by hand (FR1).
 *
 * Both paths converge here so normalisation and duplicate detection cannot
 * diverge between them.
 */
class AddPriorityContactUseCase @Inject constructor(
    private val repository: ContactRepository,
    private val normalizer: PhoneNumberNormalizer,
    private val telephony: TelephonyPort,
    private val audit: AuditRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(displayName: String, rawNumber: String): AddContactResult {
        val iso = telephony.defaultCountryIso()

        if (!normalizer.isPlausible(rawNumber, iso)) return AddContactResult.InvalidNumber

        val normalized = normalizer.normalize(rawNumber, iso)
        val contact = PriorityContact(
            displayName = displayName.trim().ifBlank { normalized.raw },
            originalInput = normalized.raw,
            matchKey = normalizer.matchKey(normalized),
            e164 = normalized.e164,
            enabled = true,
            createdAtEpochMs = clock.nowEpochMs(),
        )

        val id = repository.add(contact) ?: return AddContactResult.AlreadyExists

        audit.log(
            type = AuditEventType.CONTACT_ADDED,
            message = "Added priority contact ${contact.displayName} " +
                "(${contact.redactedNumber}).",
            relatedContactId = id,
        )
        return AddContactResult.Added(id)
    }
}

class ToggleContactUseCase @Inject constructor(
    private val repository: ContactRepository,
) {
    suspend operator fun invoke(id: Long, enabled: Boolean) = repository.setEnabled(id, enabled)
}

class RemoveContactUseCase @Inject constructor(
    private val repository: ContactRepository,
) {
    suspend operator fun invoke(id: Long) = repository.remove(id)
}
