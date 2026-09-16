package com.elham.priorityringer.presentation.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.elham.priorityringer.R
import com.elham.priorityringer.presentation.addcontact.AddContactScreen
import com.elham.priorityringer.presentation.audit.AuditScreen
import com.elham.priorityringer.presentation.contacts.ContactsScreen
import com.elham.priorityringer.presentation.dashboard.DashboardScreen
import com.elham.priorityringer.presentation.permissions.PermissionsScreen
import com.elham.priorityringer.presentation.settings.SettingsScreen
import com.elham.priorityringer.presentation.testmode.TestModeScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = currentRoute in TopLevelDestination.routes
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(currentRoute.titleRes())) },
                navigationIcon = {
                    if (!isTopLevel && currentRoute != null) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        // A plain conditional, not AnimatedVisibility: Scaffold measures whatever
        // occupies this slot into `innerPadding`, so an animating-away bar leaves
        // the pushed screens padded for a bar that is not there.
        bottomBar = {
            if (isTopLevel) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.destination.route,
                            onClick = { navController.navigateToTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Dashboard.route,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            composable(Destination.Dashboard.route) {
                DashboardScreen(
                    onOpenPermissions = { navController.navigateToTab(TopLevelDestination.PERMISSIONS) },
                    onOpenContacts = { navController.navigateToTab(TopLevelDestination.CONTACTS) },
                    onOpenAudit = { navController.navigateToTab(TopLevelDestination.AUDIT) },
                    onOpenTestMode = { navController.navigate(Destination.TestMode.route) },
                )
            }
            composable(Destination.Contacts.route) {
                ContactsScreen(
                    snackbarHostState = snackbarHostState,
                    onAddContact = { navController.navigate(Destination.AddContact.route) },
                )
            }
            composable(Destination.AddContact.route) {
                AddContactScreen(
                    snackbarHostState = snackbarHostState,
                    onSaved = { navController.popBackStack() },
                )
            }
            composable(Destination.Permissions.route) {
                PermissionsScreen(snackbarHostState = snackbarHostState)
            }
            composable(Destination.Audit.route) {
                AuditScreen(snackbarHostState = snackbarHostState)
            }
            composable(Destination.Settings.route) {
                SettingsScreen(
                    onOpenTestMode = { navController.navigate(Destination.TestMode.route) },
                )
            }
            composable(Destination.TestMode.route) {
                TestModeScreen(snackbarHostState = snackbarHostState)
            }
        }
    }
}

/**
 * Tab switching keeps a single instance per tab and restores its scroll/state,
 * so returning to Audit does not reset the filter the user just set.
 */
private fun NavHostController.navigateToTab(tab: TopLevelDestination) {
    navigate(tab.destination.route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun String?.titleRes(): Int = when (this) {
    Destination.Dashboard.route -> R.string.screen_dashboard
    Destination.Contacts.route -> R.string.screen_contacts
    Destination.AddContact.route -> R.string.screen_add_contact
    Destination.Permissions.route -> R.string.screen_permissions
    Destination.Audit.route -> R.string.screen_audit
    Destination.Settings.route -> R.string.screen_settings
    Destination.TestMode.route -> R.string.screen_test_mode
    else -> R.string.app_name
}
