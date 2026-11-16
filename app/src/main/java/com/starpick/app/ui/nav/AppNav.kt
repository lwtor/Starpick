package com.starpick.app.ui.nav

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.ui.screen.DetailScreen
import com.starpick.app.ui.screen.DiscoverScreen
import com.starpick.app.ui.screen.DiscoverSearchScreen
import com.starpick.app.ui.screen.DiscoverUiState
import com.starpick.app.ui.screen.DoubanSearchUiState
import com.starpick.app.ui.screen.HomeScreen
import com.starpick.app.ui.screen.HomeUiState
import com.starpick.app.ui.screen.ResolvingScreen
import com.starpick.app.ui.screen.SearchScreen
import com.starpick.app.ui.screen.SearchUiState
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

    /** 独立搜索页：只查豆瓣条目，与底部「搜索」（查采集源）分工不同。 */
    data object DiscoverSearch : Screen
    data class Detail(val group: SearchGroup, val initialPlay: WatchProgress? = null) : Screen
    data class Resolving(val title: String) : Screen
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
    // 各 tab 的数据与滚动位置都挂在导航层：Composable 会因为切页被销毁重建，
    // 但这两样东西不能让用户丢掉（重新拉榜单、搜索结果消失、列表被顶回顶部）。
    val discoverState = remember { DiscoverUiState() }
    val homeState = remember { HomeUiState() }
    val searchState = remember { SearchUiState() }
    val doubanSearchState = remember { DoubanSearchUiState() }
    val current = stack.last()
    val pop: () -> Unit = { if (stack.size > 1) stack.removeLastOrNull() }
    val goTab: (Screen) -> Unit = { tab ->
        stack.clear()
        stack.add(tab)
    }

    // 根 tab 第一次返回只提示，1 秒内再按一次才真正退出。
    // 子页面的 BackHandler（搜索页清搜索、播放器退全屏）注册得更晚，会先于这里响应。
    val context = LocalContext.current
    var lastBackAt by remember { mutableLongStateOf(0L) }
    var exitToast by remember { mutableStateOf<Toast?>(null) }
    BackHandler {
        if (stack.size > 1) {
            pop()
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackAt < 1_000L) {
                exitToast?.cancel()
                context.findActivity()?.finish()
            } else {
                lastBackAt = now
                exitToast = Toast.makeText(context, "再按一次退出星匣", Toast.LENGTH_SHORT)
                exitToast?.show()
            }
        }
    }

    when (current) {
        is Screen.Detail -> DetailScreen(
            container = container,
            group = current.group,
            initialPlay = current.initialPlay,
            onBack = pop,
        )

        is Screen.Resolving -> ResolvingScreen(
            container = container,
            title = current.title,
            // 必须「替换」而不是「压栈」：过渡页只负责解析片名，解析完就不该留在返回栈里。
            // 之前是 add，导致详情页返回后回落到 Resolving，它又会重新解析并再次推开详情，
            // 表现出来就是「详情页返回不了」。
            onResolved = { group -> stack[stack.lastIndex] = Screen.Detail(group) },
            onBack = pop,
        )

        is Screen.DiscoverSearch -> DiscoverSearchScreen(
            container = container,
            state = doubanSearchState,
            // 与首页海报同一条路径：拿豆瓣片名去用户的源里解析，命中后直接换成详情页。
            onPick = { title -> stack.add(Screen.Resolving(title)) },
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
                            state = discoverState,
                            onPick = { title -> stack.add(Screen.Resolving(title)) },
                            onResume = { item, record ->
                                stack.add(Screen.Detail(SearchGroup(item.title, listOf(item)), record))
                            },
                            // 顶栏搜索图标 → 独立的豆瓣搜索页；底部「搜索」tab 保持搜采集源。
                            onOpenSearch = { stack.add(Screen.DiscoverSearch) },
                            onOpenSettings = { goTab(Screen.Settings) },
                        )

                        Screen.Home -> HomeScreen(
                            container = container,
                            state = homeState,
                            onOpen = { stack.add(Screen.Detail(it)) },
                            onResume = { item, record ->
                                stack.add(Screen.Detail(SearchGroup(item.title, listOf(item)), record))
                            },
                            onOpenSettings = { goTab(Screen.Settings) },
                        )

                        Screen.Search -> SearchScreen(
                            container = container,
                            state = searchState,
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

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
