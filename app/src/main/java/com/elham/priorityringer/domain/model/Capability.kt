package com.elham.priorityringer.domain.model

/**
 * Live status of one permission or device capability.
 *
 * Architecture.md § 12 requires every non-granted status to carry an
 * explanation and an action. That obligation is encoded in the type: a
 * [CapabilityStatus] cannot be constructed without saying what is lost, so the
 * UI cannot render a bare ❌.
 */
data class CapabilityStatus(
    val capability: Capability,
    val state: State,
    /** What stops working while in this state. Plain language, user-facing. */
    val consequence: String,
    /** Extra device detail, e.g. the observed ringer mode. */
    val detail: String? = null,
) {
    enum class State {
        GRANTED,

        /** User has not granted it; they can. */
        DENIED,

        /**
         * The platform or device forbids it regardless of user action —
         * API too old, fixed volume, OEM restriction. No action button.
         */
        RESTRICTED,

        /** Not needed on this API level; not a problem. */
        NOT_REQUIRED,
    }

    val isBlocking: Boolean
        get() = (state == State.DENIED || state == State.RESTRICTED) && capability.isRequired

    val isActionable: Boolean get() = state == State.DENIED
}

/**
 * Every permission and capability the app depends on.
 *
 * [isRequired] marks the ones without which priority ringing cannot function at
 * all — see [CapabilityReport.readiness].
 */
enum class Capability(val isRequired: Boolean) {
    /** Needed to receive `PHONE_STATE` at all. */
    READ_PHONE_STATE(isRequired = true),

    /**
     * Architecture.md § A.5 — without this, `EXTRA_INCOMING_NUMBER` is absent
     * on Android 9+, no caller can be identified, and the app is **inert**.
     */
    READ_CALL_LOG(isRequired = true),

    /** Bulk contact reading. The picker (FR1) works without it. */
    READ_CONTACTS(isRequired = false),

    /** `ACCESS_NOTIFICATION_POLICY` — gates all DND work (FR3). */
    NOTIFICATION_POLICY_ACCESS(isRequired = false),

    /** API 33+. Without it no alert or heads-up notification is visible. */
    POST_NOTIFICATIONS(isRequired = false),

    /** API 34+ may require explicit user allowance (FR5). */
    FULL_SCREEN_INTENT(isRequired = false),

    /** Device-level: some routes report a fixed volume (§ 7.1). */
    VOLUME_ADJUSTABLE(isRequired = false),
}

/**
 * Everything the Dashboard, Permissions and Test Mode screens render
 * (Architecture.md § 3, FR6, FR8). Built fresh from real APIs on every probe —
 * never cached, because these grants can be revoked outside the app.
 */
data class CapabilityReport(
    val statuses: List<CapabilityStatus>,
    val sdkInt: Int,
    val targetSdk: Int,
    val currentRingerMode: RingerMode,
    val currentInterruptionFilter: InterruptionFilter,
    val isVolumeFixed: Boolean,
    val currentRingVolume: VolumeSnapshot,
    /** Documented limitations applying to this device/API (§ 3). */
    val notes: List<String> = emptyList(),
) {
    operator fun get(capability: Capability): CapabilityStatus? =
        statuses.firstOrNull { it.capability == capability }

    fun isGranted(capability: Capability): Boolean =
        this[capability]?.state == CapabilityStatus.State.GRANTED

    val blocking: List<CapabilityStatus> get() = statuses.filter { it.isBlocking }

    /**
     * Aggregate state shown on the Dashboard.
     *
     * The [INERT] case is deliberately distinct from [DEGRADED]: § A.5 requires
     * the UI to say the app can do *nothing*, not merely less, when the caller
     * cannot be identified. Collapsing the two would be a comfortable lie.
     */
    val readiness: Readiness
        get() = when {
            !isGranted(Capability.READ_PHONE_STATE) || !isGranted(Capability.READ_CALL_LOG) ->
                Readiness.INERT

            !isGranted(Capability.NOTIFICATION_POLICY_ACCESS) ->
                Readiness.DEGRADED

            else -> Readiness.ARMED
        }

    enum class Readiness {
        /** Everything granted. Priority ringing will be attempted in full. */
        ARMED,

        /** Calls are detected, but DND cannot be touched. */
        DEGRADED,

        /** No caller can be identified. Nothing will ever happen. */
        INERT,
    }
}
