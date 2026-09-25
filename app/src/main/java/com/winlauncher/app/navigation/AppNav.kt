package com.winlauncher.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.winlauncher.app.LauncherApplication
import com.winlauncher.app.ui.addgame.AddGameScreen
import com.winlauncher.app.ui.controller.ControllerManagerScreen
import com.winlauncher.app.ui.details.GameDetailsScreen
import com.winlauncher.app.ui.home.HomeScreen
import com.winlauncher.app.ui.library.LibraryScreen
import com.winlauncher.app.ui.runtime.RuntimeManagerScreen
import com.winlauncher.app.ui.settings.SettingsScreen
import com.winlauncher.app.viewmodel.AppViewModelFactory

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val ADD_GAME = "add_game"
    const val DETAILS = "details/{gameId}"
    const val RUNTIME_MANAGER = "runtime_manager"
    const val CONTROLLER_MANAGER = "controller_manager"
    const val SETTINGS = "settings"

    fun details(gameId: Long) = "details/$gameId"
}

@Composable
fun AppNavHost(app: LauncherApplication, navController: NavHostController = rememberNavController()) {
    val factory = AppViewModelFactory(app)

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                factory = factory,
                onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                onOpenGame = { id -> navController.navigate(Routes.details(id)) },
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                factory = factory,
                onAddGame = { navController.navigate(Routes.ADD_GAME) },
                onOpenGame = { id -> navController.navigate(Routes.details(id)) },
                onOpenRuntimeManager = { navController.navigate(Routes.RUNTIME_MANAGER) },
                onOpenControllerManager = { navController.navigate(Routes.CONTROLLER_MANAGER) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.ADD_GAME) {
            AddGameScreen(factory = factory, onSaved = { navController.popBackStack() })
        }
        composable(
            Routes.DETAILS,
            arguments = listOf(navArgument("gameId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getLong("gameId") ?: 0L
            GameDetailsScreen(factory = factory, gameId = gameId)
        }
        composable(Routes.RUNTIME_MANAGER) {
            RuntimeManagerScreen(factory = factory)
        }
        composable(Routes.CONTROLLER_MANAGER) {
            ControllerManagerScreen(factory = factory)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen()
        }
    }
}
