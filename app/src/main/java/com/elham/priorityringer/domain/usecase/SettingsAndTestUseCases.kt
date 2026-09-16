package com.elham.priorityringer.domain.usecase

import com.elham.priorityringer.domain.model.AppSettings
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.CapabilityReport
import com.elham.priorityringer.domain.model.IncomingCallEvent
import com.elham.priorityringer.domain.phone.PhoneNumberNormalizer
import com.elham.priorityringer.domain.port.CapabilityPort
import com.elham.priorityringer.domain.port.Clock
import com.elham.priorityringer.domain.port.TelephonyPort
import com.elham.priorityringer.domain.repository.AuditRepository
import com.elham.priorityringer.domain.repository.ContactRepository
import com.elham.priorityringer.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class ObserveSettingsUseCase @Inject constructor(
    private val repository: SettingsRepository,
) {
    operator fun invoke(): Flow<AppSettings> = repository.observe()
}

class UpdateSettingsUseCase @Inject constructor(
    private val repository: SettingsRepository,
) {
    /** Clamping happens in [AppSettings.validated], inside the repository. */
    suspend operator fun invoke(settings: AppSettings) = repository.update(settings)
}

/**
 * Live capability probe (FR6, FR8).
 *
 * Never cached. Architecture.md § 10 of the addendum and § 12 both require
 * re-probing on every resume, because these grants can be revoked from outside
 * the app at any time and a stale ✅ would be a lie.
 */
class BuildCapabilityReportUseCase @Inject constructor(
    private val capabilityPort: CapabilityPort,
) {
    operator fun invoke(): CapabilityReport = capabilityPort.report()
}

/**
 * FR6 — run the real pipeline against a synthetic call.
 *
 * Deliberately routes through [IncomingCallCoordinator], the same path a real
 * `PHONE_STATE` broadcast takes. A simulation that exercised a separate code
 * path would verify nothing.
 *
 * Architecture.md § 9 is explicit that simulation must **never** fake
 * permission results — the real ports are used, so a denied permission produces
 * a real denial here too.
 */
class SimulatePriorityCallUseCase @Inject constructor(
    private val coordinator: IncomingCallCoordinator,
    private val contacts: ContactRepository,
    private val normalizer: PhoneNumberNormalizer,
    private val telephony: TelephonyPort,
    private val audit: AuditRepository,
    private val clock: Clock,
) {
    /**
     * @param rawNumber number to simulate. When `null`, the first enabled
     *   contact is used; if there are none, the simulation is refused rather
     *   than silently doing nothing.
     */
    suspend operator fun invoke(rawNumber: String? = null): SimulationResult {
        val number = rawNumber
            ?: contacts.getEnabled().firstOrNull()?.originalInput
            ?: return SimulationResult.NoContactsConfigured

        audit.log(
            type = AuditEventType.SIMULATION_RUN,
            message = "Simulated priority call — this runs the same code a real " +
                "call does, including real permission checks.",
        )

        coordinator.onIncomingCall(
            IncomingCallEvent(
                number = normalizer.normalize(number, telephony.defaultCountryIso()),
                timestampEpochMs = clock.nowEpochMs(),
                isSimulated = true,
            ),
        )
        return SimulationResult.Ran
    }

    sealed interface SimulationResult {
        data object Ran : SimulationResult
        data object NoContactsConfigured : SimulationResult
    }
}

class ObserveAuditLogUseCase @Inject constructor(
    private val repository: AuditRepository,
) {
    operator fun invoke(limit: Int = AuditRepository.MAX_ENTRIES) =
        repository.observeRecent(limit)
}

class ClearAuditLogUseCase @Inject constructor(
    private val repository: AuditRepository,
) {
    suspend operator fun invoke() = repository.clear()
}
