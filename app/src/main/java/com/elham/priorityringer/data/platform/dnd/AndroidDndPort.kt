package com.elham.priorityringer.data.platform.dnd

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import androidx.annotation.RequiresApi
import com.elham.priorityringer.domain.model.DndBypassStrategy
import com.elham.priorityringer.domain.model.FailureReason
import com.elham.priorityringer.domain.model.InterruptionFilter
import com.elham.priorityringer.domain.model.Outcome
import com.elham.priorityringer.domain.port.DndPort
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Do Not Disturb (FR3).
 *
 * Architecture.md § A.1: two strategies, chosen at runtime from
 * `targetSdkVersion`, because Android 15 changed what these APIs mean.
 *
 * | target | mechanism |
 * |---|---|
 * | < 35 | global `setInterruptionFilter` |
 * | ≥ 35 | an explicit `AutomaticZenRule`, combined most-restrictive-wins |
 *
 * Both obey the § 6.2 rule that makes this class trustworthy: **read the filter
 * back and compare.** If the effective filter still suppresses calls, the result
 * is [FailureReason.DND_BYPASS_INEFFECTIVE], not success. On Android 15+ that
 * genuinely happens — a stricter manual DND wins and no app can override it.
 * Reporting that honestly is the entire requirement.
 */
@Singleton
class AndroidDndPort @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
) : DndPort {

    /**
     * Note this reads *our own* target SDK, not the device API level. The
     * platform gates the behaviour change on what the app targets, so raising
     * `targetSdk` in `build.gradle.kts` is what flips this — exactly as § A.1
     * describes.
     */
    private val usesZenRuleBranch: Boolean
        get() = Build.VERSION.SDK_INT >= 35 &&
            context.applicationInfo.targetSdkVersion >= 35

    @Volatile
    private var createdZenRuleId: String? = null

    override fun hasPolicyAccess(): Boolean = try {
        notificationManager.isNotificationPolicyAccessGranted
    } catch (e: Exception) {
        Timber.w(e, "Could not read notification policy access")
        false
    }

    override fun currentFilter(): InterruptionFilter = try {
        when (notificationManager.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> InterruptionFilter.ALL
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> InterruptionFilter.PRIORITY
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> InterruptionFilter.ALARMS
            NotificationManager.INTERRUPTION_FILTER_NONE -> InterruptionFilter.NONE
            else -> InterruptionFilter.UNKNOWN
        }
    } catch (e: Exception) {
        Timber.w(e, "Could not read interruption filter")
        InterruptionFilter.UNKNOWN
    }

    override fun activeZenRuleId(): String? = createdZenRuleId

    override fun applyBypass(strategy: DndBypassStrategy): Outcome<InterruptionFilter> {
        if (!hasPolicyAccess()) {
            return Outcome.Failure(
                reason = FailureReason.NOTIFICATION_POLICY_ACCESS_DENIED,
                detail = "ACCESS_NOTIFICATION_POLICY not granted",
            )
        }

        val before = currentFilter()

        val attempted = if (usesZenRuleBranch && Build.VERSION.SDK_INT >= 35) {
            applyViaZenRule()
        } else {
            applyViaGlobalFilter(strategy)
        }
        if (attempted is Outcome.Failure) return attempted

        // § 6.2 — read back. "Applied without throwing" is not evidence.
        val after = currentFilter()
        return if (callsCanRingUnder(after)) {
            Outcome.Success(after)
        } else {
            Timber.w("DND bypass ineffective: filter was %s, still %s", before, after)
            Outcome.Failure(
                reason = FailureReason.DND_BYPASS_INEFFECTIVE,
                detail = "Filter still $after after bypass attempt",
            )
        }
    }

    /**
     * Will a call actually ring under this filter *right now*?
     *
     * This is the real effectiveness test, and it cannot be answered by the
     * filter value alone. `PRIORITY` — which is what the default
     * [DndBypassStrategy.PRIORITY_ALLOW_CALLS] deliberately aims for — lets
     * calls through only when the user's notification policy includes the calls
     * category. Judging `PRIORITY` as failure on principle would report the
     * default strategy's intended success as `DND_BYPASS_INEFFECTIVE` every
     * time, which would be a false alarm on the one screen the user relies on
     * to know whether the app is working.
     */
    private fun callsCanRingUnder(filter: InterruptionFilter): Boolean = when (filter) {
        InterruptionFilter.ALL -> true
        InterruptionFilter.PRIORITY -> policyAllowsCalls()
        InterruptionFilter.ALARMS, InterruptionFilter.NONE -> false
        // We could not read the filter, so we cannot claim it worked.
        InterruptionFilter.UNKNOWN -> false
    }

    // ---- target < 35 ------------------------------------------------------

    /**
     * [DndBypassStrategy.PRIORITY_ALLOW_CALLS] is the less invasive option per
     * § 6.2 and is preferred — but only when the user's existing policy already
     * permits calls. Otherwise we would have to overwrite the global
     * `NotificationManager.Policy`, and that object is not captured in
     * `CallSnapshot`, so we could not reliably put it back.
     *
     * Given the choice between an unrestorable mutation of a global setting on
     * someone else's phone and a restorable one, this takes the restorable one
     * and falls back to `INTERRUPTION_FILTER_ALL`. The fallback is recorded in
     * the audit log via the caller's before/after entry.
     */
    private fun applyViaGlobalFilter(strategy: DndBypassStrategy): Outcome<Unit> {
        val target = when (strategy) {
            DndBypassStrategy.PRIORITY_ALLOW_CALLS ->
                if (policyAllowsCalls()) {
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY
                } else {
                    NotificationManager.INTERRUPTION_FILTER_ALL
                }

            DndBypassStrategy.DISABLE_DND_TEMPORARILY ->
                NotificationManager.INTERRUPTION_FILTER_ALL
        }

        return try {
            notificationManager.setInterruptionFilter(target)
            Outcome.ok()
        } catch (e: SecurityException) {
            Timber.w(e, "SecurityException setting interruption filter")
            Outcome.Failure(
                FailureReason.NOTIFICATION_POLICY_ACCESS_DENIED,
                e.message,
                e,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to set interruption filter")
            Outcome.Failure(FailureReason.UNKNOWN, e.message, e)
        }
    }

    private fun policyAllowsCalls(): Boolean = try {
        val policy = notificationManager.notificationPolicy
        (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS) != 0
    } catch (e: Exception) {
        Timber.w(e, "Could not read notification policy")
        false
    }

    // ---- target >= 35 -----------------------------------------------------

    /**
     * Android 15+ routes `setInterruptionFilter` through an *implicit* zen rule
     * and combines rules most-restrictive-wins. An explicit rule is therefore
     * the supported way to ask for calls to come through — and it still loses
     * to a stricter manual DND, which is why the read-back in [applyBypass]
     * matters more here, not less.
     */
    @RequiresApi(35)
    private fun applyViaZenRule(): Outcome<Unit> = try {
        val ruleId = createdZenRuleId ?: createZenRule()
        createdZenRuleId = ruleId

        notificationManager.setAutomaticZenRuleState(
            ruleId,
            Condition(
                conditionUri(ruleId),
                "Priority caller ringing",
                Condition.STATE_TRUE,
            ),
        )
        Outcome.ok()
    } catch (e: SecurityException) {
        Timber.w(e, "SecurityException applying zen rule")
        Outcome.Failure(FailureReason.NOTIFICATION_POLICY_ACCESS_DENIED, e.message, e)
    } catch (e: Exception) {
        Timber.e(e, "Failed to apply zen rule")
        Outcome.Failure(FailureReason.UNKNOWN, e.message, e)
    }

    /**
     * @throws Exception deliberately propagated to [applyViaZenRule], which
     *   converts it to an [Outcome.Failure]. The rule creation APIs are the
     *   least-verified part of this class (no API 35 device was available), so
     *   a rejection must degrade to `DND_BYPASS_INEFFECTIVE` rather than crash
     *   the app during an incoming call.
     */
    @RequiresApi(35)
    private fun createZenRule(): String {
        val policy = ZenPolicy.Builder()
            .allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE)
            .allowRepeatCallers(true)
            .build()

        val rule = AutomaticZenRule.Builder(ZEN_RULE_NAME, conditionUri(ZEN_RULE_NAME))
            .setZenPolicy(policy)
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            // A *configuration activity*, not an owner.
            //
            // `setOwner` expects a ComponentName pointing at a
            // ConditionProviderService — a real, declared service that the
            // system binds to evaluate the rule's condition. Pointing it at
            // this class, which is an ordinary injected object and not a
            // Service at all, is rejected by the platform.
            //
            // This app drives the rule's state directly via
            // setAutomaticZenRuleState, so it needs no condition provider. The
            // configuration activity is the supported way for an app-managed
            // rule to say where the user can go to adjust it, and it makes the
            // rule tappable in system DND settings instead of a dead entry.
            .setConfigurationActivity(
                ComponentName(context, "com.elham.priorityringer.presentation.MainActivity"),
            )
            .setEnabled(true)
            .build()

        return notificationManager.addAutomaticZenRule(rule)
    }

    private fun conditionUri(id: String): Uri =
        Uri.parse("condition://com.elham.priorityringer/$id")

    // ---- restore ----------------------------------------------------------

    /**
     * Idempotent, and safe to call when no bypass is active — restore is
     * invoked from three independent triggers (§ A.3), so "already restored"
     * must be a no-op rather than an error.
     */
    override fun restore(filter: InterruptionFilter, zenRuleId: String?): Outcome<Unit> {
        if (!hasPolicyAccess()) {
            // Without access we could never have changed anything.
            return Outcome.ok()
        }

        val ruleId = zenRuleId ?: createdZenRuleId
        if (usesZenRuleBranch && ruleId != null) {
            return try {
                notificationManager.setAutomaticZenRuleState(
                    ruleId,
                    Condition(conditionUri(ruleId), "Call ended", Condition.STATE_FALSE),
                )
                createdZenRuleId = null
                Outcome.ok()
            } catch (e: Exception) {
                Timber.e(e, "Failed to deactivate zen rule %s", ruleId)
                Outcome.Failure(FailureReason.UNKNOWN, e.message, e)
            }
        }

        val target = when (filter) {
            InterruptionFilter.ALL -> NotificationManager.INTERRUPTION_FILTER_ALL
            InterruptionFilter.PRIORITY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            InterruptionFilter.ALARMS -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            InterruptionFilter.NONE -> NotificationManager.INTERRUPTION_FILTER_NONE
            // We never captured a usable value, so changing anything now would
            // be guessing at someone else's setting. Leave it alone.
            InterruptionFilter.UNKNOWN -> return Outcome.ok()
        }

        return try {
            notificationManager.setInterruptionFilter(target)
            val after = currentFilter()
            if (after == filter) {
                Outcome.ok()
            } else {
                Timber.w("DND restore mismatch: wanted %s, got %s", filter, after)
                Outcome.Failure(
                    FailureReason.VERIFICATION_FAILED,
                    "Wanted $filter, device reports $after",
                )
            }
        } catch (e: SecurityException) {
            Outcome.Failure(FailureReason.NOTIFICATION_POLICY_ACCESS_DENIED, e.message, e)
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore interruption filter")
            Outcome.Failure(FailureReason.UNKNOWN, e.message, e)
        }
    }

    private companion object {
        const val ZEN_RULE_NAME = "Priority Caller"
    }
}
