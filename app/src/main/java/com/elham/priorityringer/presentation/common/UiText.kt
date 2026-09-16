package com.elham.priorityringer.presentation.common

import androidx.annotation.StringRes
import com.elham.priorityringer.R
import com.elham.priorityringer.domain.model.AuditEventType
import com.elham.priorityringer.domain.model.Capability
import com.elham.priorityringer.domain.model.CapabilityStatus
import com.elham.priorityringer.domain.model.DndBypassStrategy
import com.elham.priorityringer.domain.model.FailureReason
import com.elham.priorityringer.domain.model.InterruptionFilter
import com.elham.priorityringer.domain.model.RingerMode

/**
 * Domain enum → string resource. Every user-facing word in this app lives in
 * `strings.xml`; domain types carry no copy of their own except
 * [CapabilityStatus.consequence], which is authored by the capability probe in
 * `data` because only it knows the device specifics.
 */

@get:StringRes
val Capability.labelRes: Int
    get() = when (this) {
        Capability.READ_PHONE_STATE -> R.string.capability_read_phone_state
        Capability.READ_CALL_LOG -> R.string.capability_read_call_log
        Capability.READ_CONTACTS -> R.string.capability_read_contacts
        Capability.NOTIFICATION_POLICY_ACCESS -> R.string.capability_notification_policy
        Capability.POST_NOTIFICATIONS -> R.string.capability_post_notifications
        Capability.FULL_SCREEN_INTENT -> R.string.capability_full_screen_intent
        Capability.VOLUME_ADJUSTABLE -> R.string.capability_volume_adjustable
        Capability.BATTERY_OPTIMISATION_EXEMPT -> R.string.capability_battery_optimisation
    }

/** What granting this buys. Shown above the [CapabilityStatus.consequence]. */
@get:StringRes
val Capability.purposeRes: Int
    get() = when (this) {
        Capability.READ_PHONE_STATE -> R.string.capability_read_phone_state_purpose
        Capability.READ_CALL_LOG -> R.string.capability_read_call_log_purpose
        Capability.READ_CONTACTS -> R.string.capability_read_contacts_purpose
        Capability.NOTIFICATION_POLICY_ACCESS -> R.string.capability_notification_policy_purpose
        Capability.POST_NOTIFICATIONS -> R.string.capability_post_notifications_purpose
        Capability.FULL_SCREEN_INTENT -> R.string.capability_full_screen_intent_purpose
        Capability.VOLUME_ADJUSTABLE -> R.string.capability_volume_adjustable_purpose
        Capability.BATTERY_OPTIMISATION_EXEMPT ->
            R.string.capability_battery_optimisation_purpose
    }

@get:StringRes
val CapabilityStatus.State.labelRes: Int
    get() = when (this) {
        CapabilityStatus.State.GRANTED -> R.string.capability_state_granted
        CapabilityStatus.State.DENIED -> R.string.capability_state_denied
        CapabilityStatus.State.RESTRICTED -> R.string.capability_state_restricted
        CapabilityStatus.State.NOT_REQUIRED -> R.string.capability_state_not_required
    }

/** Label on the row's action button. Only read when [CapabilityStatus.isActionable]. */
@get:StringRes
val Capability.actionLabelRes: Int
    get() = when (this) {
        Capability.NOTIFICATION_POLICY_ACCESS -> R.string.action_open_dnd_access
        Capability.FULL_SCREEN_INTENT -> R.string.action_open_full_screen_settings
        Capability.BATTERY_OPTIMISATION_EXEMPT -> R.string.action_open_battery_settings
        else -> R.string.action_grant
    }

@get:StringRes
val RingerMode.labelRes: Int
    get() = when (this) {
        RingerMode.SILENT -> R.string.ringer_mode_silent
        RingerMode.VIBRATE -> R.string.ringer_mode_vibrate
        RingerMode.NORMAL -> R.string.ringer_mode_normal
        RingerMode.UNKNOWN -> R.string.ringer_mode_unknown
    }

@get:StringRes
val InterruptionFilter.labelRes: Int
    get() = when (this) {
        InterruptionFilter.ALL -> R.string.filter_all
        InterruptionFilter.PRIORITY -> R.string.filter_priority
        InterruptionFilter.ALARMS -> R.string.filter_alarms
        InterruptionFilter.NONE -> R.string.filter_none
        InterruptionFilter.UNKNOWN -> R.string.filter_unknown
    }

@get:StringRes
val DndBypassStrategy.labelRes: Int
    get() = when (this) {
        DndBypassStrategy.PRIORITY_ALLOW_CALLS -> R.string.dnd_strategy_priority_calls
        DndBypassStrategy.DISABLE_DND_TEMPORARILY -> R.string.dnd_strategy_disable_dnd
    }

@get:StringRes
val DndBypassStrategy.descriptionRes: Int
    get() = when (this) {
        DndBypassStrategy.PRIORITY_ALLOW_CALLS -> R.string.dnd_strategy_priority_calls_desc
        DndBypassStrategy.DISABLE_DND_TEMPORARILY -> R.string.dnd_strategy_disable_dnd_desc
    }

@get:StringRes
val FailureReason.labelRes: Int
    get() = when (this) {
        FailureReason.NOTIFICATION_POLICY_ACCESS_DENIED -> R.string.failure_policy_access_denied
        FailureReason.RUNTIME_PERMISSION_DENIED -> R.string.failure_runtime_permission_denied
        FailureReason.SECURITY_EXCEPTION -> R.string.failure_security_exception
        FailureReason.VOLUME_FIXED -> R.string.failure_volume_fixed
        FailureReason.VERIFICATION_FAILED -> R.string.failure_verification_failed
        FailureReason.DND_BYPASS_INEFFECTIVE -> R.string.failure_dnd_ineffective
        FailureReason.SILENT_NOT_OVERRIDDEN -> R.string.failure_silent_not_overridden
        FailureReason.NUMBER_UNAVAILABLE -> R.string.failure_number_unavailable
        FailureReason.UNSUPPORTED_ON_THIS_API -> R.string.failure_unsupported_api
        FailureReason.NOTHING_TO_DO -> R.string.failure_nothing_to_do
        FailureReason.UNKNOWN -> R.string.failure_unknown
    }

@get:StringRes
val AuditEventType.labelRes: Int
    get() = when (this) {
        AuditEventType.PRIORITY_CALL_DETECTED -> R.string.audit_type_priority_call_detected
        AuditEventType.DND_BYPASS_ATTEMPTED -> R.string.audit_type_dnd_bypass_attempted
        AuditEventType.RINGER_MODE_CHANGED -> R.string.audit_type_ringer_mode_changed
        AuditEventType.VOLUME_CHANGED -> R.string.audit_type_volume_changed
        AuditEventType.RESTORATION_COMPLETED -> R.string.audit_type_restoration_completed
        AuditEventType.ERROR -> R.string.audit_type_error
        AuditEventType.NUMBER_UNAVAILABLE -> R.string.audit_type_number_unavailable
        AuditEventType.DND_BYPASS_INEFFECTIVE -> R.string.audit_type_dnd_bypass_ineffective
        AuditEventType.SILENT_NOT_OVERRIDDEN -> R.string.audit_type_silent_not_overridden
        AuditEventType.VOLUME_FIXED -> R.string.audit_type_volume_fixed
        AuditEventType.RINGER_CHANGE_FAILED -> R.string.audit_type_ringer_change_failed
        AuditEventType.VOLUME_CHANGE_FAILED -> R.string.audit_type_volume_change_failed
        AuditEventType.RESTORATION_FAILED -> R.string.audit_type_restoration_failed
        AuditEventType.ESCALATION_TRIGGERED -> R.string.audit_type_escalation_triggered
        AuditEventType.FULL_SCREEN_ALERT_SHOWN -> R.string.audit_type_full_screen_alert_shown
        AuditEventType.FULL_SCREEN_ALERT_FALLBACK -> R.string.audit_type_full_screen_alert_fallback
        AuditEventType.PERMISSION_DENIED -> R.string.audit_type_permission_denied
        AuditEventType.STALE_RESTORE_RECOVERED -> R.string.audit_type_stale_restore_recovered
        AuditEventType.SIMULATION_RUN -> R.string.audit_type_simulation_run
        AuditEventType.CONTACT_ADDED -> R.string.audit_type_contact_added
        AuditEventType.CONTACT_REMOVED -> R.string.audit_type_contact_removed
    }
