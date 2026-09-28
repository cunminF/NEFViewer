package com.nefviewer.android

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nefviewer.android.ui.browser.BrowserScreen
import com.nefviewer.android.ui.projects.NewProjectScreen
import com.nefviewer.android.ui.projects.ProjectListScreen
import com.nefviewer.android.ui.settings.SettingsScreen

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "projects") {
        composable("projects") {
            ProjectListScreen(
                onOpenProject = { nav.navigate("browser/$it") },
                onNewProject = { nav.navigate("new") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("new") {
            NewProjectScreen(
                onBack = { nav.popBackStack() },
                onCreated = { id ->
                    nav.navigate("browser/$id") {
                        popUpTo("projects")
                    }
                },
            )
        }
        composable(
            route = "browser/{projectId}",
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            BrowserScreen(
                projectId = entry.arguments?.getString("projectId") ?: return@composable,
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
