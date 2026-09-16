package com.elham.priorityringer.presentation.common

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.elham.priorityringer.domain.model.Capability
import timber.log.Timber

/**
 * Deep links into the right Settings page per capability (Architecture.md
 * § 6.1, § 8, FR8).
 *
 * Dumping the user on the app's root settings page and expecting them to find
 * "Do Not Disturb access" three levels down is the main reason permission
 * screens fail, so each capability gets its own destination.
 */
object SettingsLinks {

    /**
     * The runtime permission backing [capability], or `null` when it is granted
     * through a Settings screen instead of a runtime dialog.
     */
    fun runtimePermission(capability: Capability): String? = when (capability) {
        Capability.READ_PHONE_STATE -> Manifest.permission.READ_PHONE_STATE
        Capability.READ_CALL_LOG -> Manifest.permission.READ_CALL_LOG
        Capability.READ_CONTACTS -> Manifest.permission.READ_CONTACTS

        Capability.POST_NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.POST_NOTIFICATIONS
            } else {
                null
            }

        Capability.NOTIFICATION_POLICY_ACCESS,
        Capability.FULL_SCREEN_INTENT,
        Capability.VOLUME_ADJUSTABLE,
        Capability.BATTERY_OPTIMISATION_EXEMPT,
        -> null
    }

    /**
     * Settings destination for [capability].
     *
     * Returns `null` for the runtime permissions, which should go through a
     * [androidx.activity.result.contract.ActivityResultContracts.RequestPermission]
     * launcher first; [appDetails] is the fallback once the system stops showing
     * that dialog.
     */
    fun settingsIntent(context: Context, capability: Capability): Intent? = when (capability) {
        Capability.NOTIFICATION_POLICY_ACCESS ->
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

        Capability.FULL_SCREEN_INTENT ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    packageUri(context),
                )
            } else {
                // Pre-34 the permission is install-time; there is nothing to open.
                null
            }

        Capability.POST_NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        // The direct request, which shows a system dialog naming this app.
        //
        // ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS needs no permission, but
        // it opens an alphabetical list of every installed app and leaves the
        // user to find this one — the exact "dumped three levels away" failure
        // this object exists to avoid. Verified on an emulator: it lands on
        // "App battery usage" with Android Auto at the top.
        //
        // This action needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS declared in
        // the manifest. It is a documented public API, not a hidden one. It is
        // also Play-policy-restricted, which does not apply here: this app is
        // installed by hand for one family member and has no INTERNET
        // permission, let alone a Play listing. If that ever changes, switch to
        // [batteryOptimisationFallback] — the screen already falls back to it.
        Capability.BATTERY_OPTIMISATION_EXEMPT ->
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                packageUri(context),
            )

        else -> null
    }

    /**
     * Used when the direct request above is refused or unhandled — some OEM
     * builds omit it. Opens the battery-optimisation list, where the app is
     * findable with scrolling, which is worse but is not nothing.
     */
    fun batteryOptimisationFallback(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    /**
     * Unwraps the `ContextWrapper` chain Compose hands out, so the caller can
     * ask `shouldShowRequestPermissionRationale` — the only way to tell
     * "declined once" from "declined permanently".
     */
    fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun packageUri(context: Context): Uri =
        Uri.fromParts("package", context.packageName, null)

    /**
     * Starts [intent], returning false if no activity handles it. OEM builds do
     * occasionally omit these screens; silently doing nothing would look like a
     * broken button, so the caller shows a snackbar instead.
     */
    fun launch(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrElse { error ->
        Timber.w(error, "No activity for settings intent %s", intent.action)
        false
    }
}
