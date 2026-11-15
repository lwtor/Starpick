package com.starpick.app.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.starpick.app.AppContainer
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.ui.screen.DetailScreen
import com.starpick.app.ui.screen.DiscoverScreen
import com.starpick.app.ui.screen.HomeScreen
import com.starpick.app.ui.screen.PlayerScreen
import com.starpick.app.ui.screen.ResolvingScreen
import com.starpick.app.ui.screen.SearchScreen
import com.starpick.app.ui.screen.SettingsScreen

/**
 * 极简状态导航：一个返回栈，不引入 Navigation 库。
 * UI 本来就是平台层，将来接 Compose Multiplatform 时替换这一处即可。
 */
sealed interface Screen {
    data object Discover : Screen
    data object Home : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Detail(val group: SearchGroup) : Screen
    data class Resolving(val title: String) : Screen
    data class Player(val item: VodItem, val groupIndex: Int, val episodeIndex: Int) : Screen
}

private data class TabSpec(
    val screen: Screen,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    TabSpec(Screen.Discover, "首页", Icons.Filled.Explore),
    TabSpec(Screen.Home, "片匣", Icons.Filled.Home),
    TabSpec(Screen.Search, "搜索", Icons.Filled.Search),
    TabSpec(Screen.Settings, "设置", Icons.Filled.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarpickNav(container: AppContainer) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Discover) }
    val current = stack.last()
    val pop: () -> Unit = { if (stack.size > 1) stack.removeLastOrNull() }
    val goTab: (Screen) -> Unit = { tab ->
        stack.clear()
        stack.add(tab)
    }

    // 只在栈深 > 1 时拦截返回；栈深 == 1 时放行给系统（首页/根 tab 返回即退出）。
    BackHandler(enabled = stack.size > 1) { pop() }

    when (current) {
        is Screen.Detail -> DetailScreen(
            container = container,
            group = current.group,
            onBack = pop,
            onPlay = { item, groupIndex, episodeIndex ->
                stack.add(Screen.Player(item, groupIndex, episodeIndex))
            },
        )

        is Screen.Resolving -> ResolvingScreen(
            container = container,
            title = current.title,
            onResolved = { group -> stack.add(Screen.Detail(group)) },
            onBack = pop,
        )

        is Screen.Player -> PlayerScreen(
            container = container,
            item = current.item,
            groupIndex = current.groupIndex,
            episodeIndex = current.episodeIndex,
            onBack = pop,
        )

        else -> {
            val title = when (current) {
                is Screen.Discover -> "星匣"
                is Screen.Home -> "片匣"
                is Screen.Search -> "搜索"
                is Screen.Settings -> "设置"
            }
            Scaffold(
                // 首页（Discover）做沉浸式，不显示顶部栏，让英雄横幅顶到状态栏下；
                // 其余 tab 用 CenterAlignedTopAppBar，自带 windowInsets 处理状态栏遮挡。
                topBar = {
                    if (current != Screen.Discover) {
                        CenterAlignedTopAppBar(
                            title = { Text(title) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                            ),
                        )
                    }
                },
                bottomBar = { BottomTabs(current, goTab) },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                ) {
                    when (current) {
                        Screen.Discover -> DiscoverScreen(
                            container = container,
                            onPick = { title -> stack.add(Screen.Resolving(title)) },
                        )

                        Screen.Home -> HomeScreen(
                            container = container,
                            onOpen = { stack.add(Screen.Detail(it)) },
                            onResume = { item, record ->
                                stack.add(Screen.Player(item, record.groupIndex, record.episodeIndex))
                            },
                        )

                        Screen.Search -> SearchScreen(
                            container = container,
                            onOpen = { stack.add(Screen.Detail(it)) },
                        )

                        Screen.Settings -> SettingsScreen(container)
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomTabs(current: Screen, goTab: (Screen) -> Unit) {
    NavigationBar {
        tabs.forEach { tab ->
            val selected = current == tab.screen
            NavigationBarItem(
                selected = selected,
                onClick = { goTab(tab.screen) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                    )
                },
                label = { Text(tab.label) },
            )
        }
    }
}
