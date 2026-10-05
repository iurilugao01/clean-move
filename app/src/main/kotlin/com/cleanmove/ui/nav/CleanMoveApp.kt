package com.cleanmove.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cleanmove.ui.favorites.FavoritesScreen
import com.cleanmove.ui.line.LineDetailScreen
import com.cleanmove.ui.map.MapPlaceholderScreen
import com.cleanmove.ui.search.SearchScreen
import com.cleanmove.ui.theme.CleanMoveTheme

@Composable
fun CleanMoveApp() {
    CleanMoveTheme {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val current = Tab.entries.firstOrNull { it.route == entry?.destination?.route }
        Scaffold(
            bottomBar = {
                if (current != null) {
                    BottomBar(selected = current, onSelect = { tab ->
                        nav.navigate(tab.route) {
                            popUpTo(Tab.Map.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    })
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = Tab.Map.route, modifier = Modifier.padding(padding)) {
                composable(Tab.Map.route) { MapPlaceholderScreen() }
                composable(Tab.Search.route) { SearchScreen(onLineClick = { nav.navigate("line/$it") }, onStopClick = {}) }
                composable(Tab.Favorites.route) { FavoritesScreen(onLineClick = { nav.navigate("line/$it") }) }
                composable("line/{code}", arguments = listOf(navArgument("code") { type = NavType.StringType })) {
                    LineDetailScreen(onBack = { nav.popBackStack() })
                }
            }
        }
    }
}
