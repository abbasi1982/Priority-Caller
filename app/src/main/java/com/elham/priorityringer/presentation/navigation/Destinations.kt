package com.elham.priorityringer.presentation.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.ui.graphics.vector.ImageVector
import com.elham.priorityringer.R

/**
 * Navigation graph (Architecture.md § 12).
 *
 * Seven screens do not fit a bottom bar legibly, so the five that are visited
 * repeatedly are tabs and the two that are entered from a specific context are
 * pushed: Add Contact from Contacts, Test Mode from Settings.
 */
sealed class Destination(val route: String) {
    data object Dashboard : Destination("dashboard")
    data object Contacts : Destination("contacts")
    data object Permissions : Destination("permissions")
    data object Audit : Destination("audit")
    data object Settings : Destination("settings")

    data object AddContact : Destination("contacts/add")
    data object TestMode : Destination("settings/test-mode")
}

/** The five bottom-bar tabs, in display order. */
enum class TopLevelDestination(
    val destination: Destination,
    val icon: ImageVector,
    @param:StringRes val labelRes: Int,
) {
    DASHBOARD(Destination.Dashboard, Icons.Filled.Dashboard, R.string.nav_dashboard),
    CONTACTS(Destination.Contacts, Icons.Filled.People, R.string.nav_contacts),
    PERMISSIONS(Destination.Permissions, Icons.Filled.VerifiedUser, R.string.nav_permissions),
    AUDIT(Destination.Audit, Icons.Filled.History, R.string.nav_audit),
    SETTINGS(Destination.Settings, Icons.Filled.Settings, R.string.nav_settings),
    ;

    companion object {
        val routes: Set<String> = entries.map { it.destination.route }.toSet()
    }
}
