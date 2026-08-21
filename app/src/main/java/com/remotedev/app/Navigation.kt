package com.remotedev.app

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.remotedev.app.feature.aichat.ChatScreen
import com.remotedev.app.feature.editor.EditorScreen
import com.remotedev.app.feature.settings.SettingsScreen
import com.remotedev.app.feature.ssh.FileBrowserScreen
import com.remotedev.app.feature.ssh.TerminalScreen

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination("editor", "Editor", Icons.Default.Edit),
    TopLevelDestination("files", "Files", Icons.Default.Folder),
    TopLevelDestination("terminal", "Terminal", Icons.Default.Terminal),
    TopLevelDestination("chat", "AI Chat", Icons.AutoMirrored.Filled.Chat),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteDevNavHost() {
    val navController = rememberNavController()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RemoteDev") },
                actions = {
                    IconButton(onClick = { navController.navigate("settings") { launchSingleTop = true } }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                topLevelDestinations.forEach { destination ->
                    NavigationBarItem(
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                        selected = currentRoute == destination.route ||
                            (destination.route == "editor" && currentRoute == "editor?path={path}"),
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "editor",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("editor") {
                EditorScreen(path = null)
            }
            composable(
                "editor?path={path}",
                arguments = listOf(
                    navArgument("path") {
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                EditorScreen(path = backStackEntry.arguments?.getString("path"))
            }
            composable("files") {
                FileBrowserScreen(onFileOpen = { p ->
                    navController.navigate("editor?path=" + Uri.encode("ssh://$p"))
                })
            }
            composable("terminal") { TerminalScreen() }
            composable("chat") { ChatScreen() }
            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
