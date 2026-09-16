package com.elham.priorityringer.domain.model

/**
 * Result of an attempted platform mutation.
 *
 * Architecture.md § A.4: every mutating port method returns [Outcome] rather
 * than throwing or returning `Boolean`. `SecurityException` is caught at the
 * port boundary and converted to a typed [FailureReason].
 *
 * **A permission denial is data, not an exception.** This is what makes
 * Architecture.md § 17.2 ("every apply path verifies post-conditions and logs
 * failures") mechanical rather than a matter of developer discipline — a
 * caller cannot accidentally ignore a denial the way it can swallow an
 * exception.
 */
sealed interface Outcome<out T> {

    data class Success<out T>(val value: T) : Outcome<T>

    data class Failure(
        val reason: FailureReason,
        val detail: String? = null,
        val cause: Throwable? = null,
    ) : Outcome<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun valueOrNull(): T? = (this as? Success)?.value

    fun failureOrNull(): Failure? = this as? Failure

    companion object {
        fun ok(): Outcome<Unit> = Success(Unit)

        fun <T> of(value: T): Outcome<T> = Success(value)

        fun fail(
            reason: FailureReason,
            detail: String? = null,
            cause: Throwable? = null,
        ): Outcome<Nothing> = Failure(reason, detail, cause)
    }
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onFailure(action: (Outcome.Failure) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) action(this)
    return this
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) action(value)
    return this
}

/**
 * Why a platform mutation did not do what was asked.
 *
 * These map onto the audit event vocabulary in Architecture.md § 13, including
 * the reviewer's additions (`VOLUME_FIXED`, `SILENT_NOT_OVERRIDDEN`,
 * `DND_BYPASS_INEFFECTIVE`, `NUMBER_UNAVAILABLE`).
 */
enum class FailureReason {
    /** `ACCESS_NOTIFICATION_POLICY` not granted by the user. */
    NOTIFICATION_POLICY_ACCESS_DENIED,

    /** A runtime permission required for this operation is not granted. */
    RUNTIME_PERMISSION_DENIED,

    /** Platform threw `SecurityException` despite our pre-checks. */
    SECURITY_EXCEPTION,

    /**
     * Device reports a fixed output volume (Architecture.md § 7.1) — e.g. some
     * docked/TV/accessory routes. Volume changes are impossible, not merely
     * blocked.
     */
    VOLUME_FIXED,

    /**
     * The call returned without error, but reading the value back showed the
     * device did not actually change. This is the OEM-interference case in
     * Architecture.md § 7 — a *detected* failure, never a cue to reach for
     * undocumented APIs.
     */
    VERIFICATION_FAILED,

    /**
     * DND mutation applied but the effective filter still suppresses ringing —
     * e.g. Android 15+ most-restrictive-wins against a stricter manual DND
     * (Architecture.md § 6.2).
     */
    DND_BYPASS_INEFFECTIVE,

    /** Ringer was `RINGER_MODE_SILENT` and did not become audible. */
    SILENT_NOT_OVERRIDDEN,

    /** `PHONE_STATE` arrived with no usable `EXTRA_INCOMING_NUMBER`. */
    NUMBER_UNAVAILABLE,

    /** Capability does not exist on this API level. */
    UNSUPPORTED_ON_THIS_API,

    /** Nothing to do — e.g. restore ran with no pending snapshot. */
    NOTHING_TO_DO,

    UNKNOWN,
    ;

    /**
     * Whether the user can fix this by granting something. Drives whether the
     * UI offers an action button or only an explanation.
     */
    val isUserActionable: Boolean
        get() = this == NOTIFICATION_POLICY_ACCESS_DENIED ||
            this == RUNTIME_PERMISSION_DENIED
}
