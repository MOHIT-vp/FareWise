package com.farewise.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.farewise.app.ui.dashboard.DashboardScreen
import com.farewise.app.ui.journal.TripJournalScreen

/**
 * Top-level navigation graph.
 *
 * Two bottom-bar tabs: the live dashboard (start), and the trip journal —
 * a retrospective view powered by the
 * [com.farewise.app.data.local.entity.BookingEntity] Room table the
 * dashboard writes into on every successful deep-link.
 */
object FareWiseDestinations {
    const val DASHBOARD = "dashboard"
    const val JOURNAL = "journal"
}

private data class Tab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val TABS = listOf(
    Tab(FareWiseDestinations.DASHBOARD, "Compare", Icons.Filled.DirectionsCar, Icons.Outlined.DirectionsCar),
    Tab(FareWiseDestinations.JOURNAL, "My trips", Icons.Filled.Receipt, Icons.Outlined.Receipt)
)

@Composable
fun FareWiseNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = FareWiseDestinations.DASHBOARD
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // The dashboard hides the bar while its map picker / full-screen map is up.
    var dashboardImmersive by remember { mutableStateOf(false) }
    val showBottomBar = !(currentRoute == FareWiseDestinations.DASHBOARD && dashboardImmersive)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Each screen pads for the status bar in its own top bar.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    TABS.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = {
                                Icon(
                                    if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = null
                                )
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding)
        ) {
            composable(FareWiseDestinations.DASHBOARD) {
                DashboardScreen(onImmersiveChange = { dashboardImmersive = it })
            }
            composable(FareWiseDestinations.JOURNAL) {
                TripJournalScreen()
            }
        }
    }
}

/**
 * Standard bottom-nav behaviour: one copy of each tab on the back stack,
 * tab state saved and restored, and Back from any tab returns to the start.
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
