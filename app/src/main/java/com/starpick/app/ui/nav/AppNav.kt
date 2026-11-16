package com.starpick.app.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
import com.starpick.app.ui.theme.Accent
import com.starpick.app.ui.theme.Ink

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
    val iconSelected: ImageVector,
)

private val tabs = listOf(
    TabSpec(Screen.Discover, "首页", Icons.Outlined.Explore, Icons.Filled.Explore),
    TabSpec(Screen.Home, "片匣", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
    TabSpec(Screen.Search, "搜索", Icons.Outlined.Search, Icons.Filled.Search),
    TabSpec(Screen.Settings, "设置", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun StarpickNav(container: AppContainer) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Discover) }
    val current = stack.last()
    val pop: () -> Unit = { if (stack.size > 1) stack.removeLastOrNull() }
    val goTab: (Screen) -> Unit = { tab ->
        stack.clear()
        stack.add(tab)
    }

    // 只在栈深 > 1 时拦截返回；栈深 == 1 时放行给系统（根 tab 返回即退出）。
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
            Scaffold(
                // 顶部 inset 不再由 Scaffold 统一给：首页 Hero 要顶到状态栏下，
                // 各页面自己 statusBarsPadding()，这里清零避免双倍留白。
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = { BottomTabs(current, goTab) },
                containerColor = Ink.Base,
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
                            onResume = { item, record ->
                                stack.add(
                                    Screen.Player(item, record.groupIndex, record.episodeIndex),
                                )
                            },
                            onOpenSearch = { goTab(Screen.Search) },
                            onOpenSettings = { goTab(Screen.Settings) },
                        )

                        Screen.Home -> HomeScreen(
                            container = container,
                            onOpen = { stack.add(Screen.Detail(it)) },
                            onResume = { item, record ->
                                stack.add(
                                    Screen.Player(item, record.groupIndex, record.episodeIndex),
                                )
                            },
                        )

                        Screen.Search -> SearchScreen(
                            container = container,
                            onOpen = { stack.add(Screen.Detail(it)) },
                            onOpenSettings = { goTab(Screen.Settings) },
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
    NavigationBar(
        containerColor = Color(0xF20A0E14),
        contentColor = Ink.Text3,
        tonalElevation = 0.dp,
    ) {
        tabs.forEach { tab ->
            val selected = current == tab.screen
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) goTab(tab.screen) },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.iconSelected else tab.icon,
                        contentDescription = tab.label,
                    )
                },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Accent.Gold,
                    selectedTextColor = Ink.Text1,
                    unselectedIconColor = Ink.Text3,
                    unselectedTextColor = Ink.Text3,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
