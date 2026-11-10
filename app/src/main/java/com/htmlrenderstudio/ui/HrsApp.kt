package com.htmlrenderstudio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private fun iconFor(tab: MainTab): ImageVector = when (tab) {
    MainTab.HOME -> Icons.Default.Home
    MainTab.PROJECTS -> Icons.Default.Build
    MainTab.HISTORY -> Icons.Default.List
    MainTab.SETTINGS -> Icons.Default.Settings
}

@Composable
fun HrsApp(vm: AppViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val atHome = (screen as? Screen.Tab)?.tab == MainTab.HOME
    // Layar render menangani tombol back sendiri (dengan konfirmasi).
    BackHandler(enabled = !atHome && screen !is Screen.Rendering) { vm.back() }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val s = screen
            if (s is Screen.Tab) {
                NavigationBar(containerColor = SurfaceColor) {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = s.tab == tab,
                            onClick = { vm.selectTab(tab) },
                            icon = { Icon(iconFor(tab), contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent,
                                selectedTextColor = Accent,
                                indicatorColor = SurfaceHigh,
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted,
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Crossfade(targetState = screen, animationSpec = tween(220), label = "nav") { s ->
                when (s) {
                    is Screen.Tab -> when (s.tab) {
                        MainTab.HOME -> HomeScreen(vm)
                        MainTab.PROJECTS -> ProjectsScreen(vm)
                        MainTab.HISTORY -> HistoryScreen(vm)
                        MainTab.SETTINGS -> SettingsScreen(vm)
                    }
                    is Screen.ProjectDetail -> ProjectScreen(vm, s.id)
                    is Screen.Rendering -> RenderingScreen(vm)
                    is Screen.Result -> ResultScreen(vm, s.historyId)
                }
            }
        }
    }
}
