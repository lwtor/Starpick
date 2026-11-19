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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Explore
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
import com.starpick.app.core.model.MatchAnchor
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.ui.screen.DetailScreen
import com.starpick.app.ui.screen.DiscoverScreen
import com.starpick.app.ui.screen.DiscoverUiState
import com.starpick.app.ui.screen.HomeScreen
import com.starpick.app.ui.screen.HomeUiState
import com.starpick.app.ui.screen.ResolvingScreen
import com.starpick.app.ui.screen.SearchScreen
import com.starpick.app.ui.screen.SearchUiState
import com.starpick.app.ui.screen.SettingsScreen
import com.starpick.app.ui.screen.SourceHealthUiState
import com.starpick.app.ui.screen.SourceScreen
import com.starpick.app.ui.theme.Accent
import com.starpick.app.ui.theme.Ink

/**
 * 极简状态导航：一个返回栈，不引入 Navigation 库。
 * UI 本来就是平台层，将来接 Compose Multiplatform 时替换这一处即可。
 */
sealed interface Screen {
    data object Discover : Screen
    data object Home : Screen
    data object Settings : Screen

    /** 内容源管理：设置里的二级页，配一次就不常来，所以不占设置首屏。 */
    data object Sources : Screen

    /**
     * 搜索：首页顶栏进入的二级页，一次搜索同时查豆瓣片和采集源，
     * 用「全部 / 豆瓣 / 视频源」三个 tab 分维度看。
     */
    data object Search : Screen

    /**
     * [enrichAnchor] 非空时，详情页会按这个锚点在后台补齐其余源。
     * 入口只带了一条记录（历史 / 收藏）或只等到第一个命中的源时用它。
     */
    data class Detail(
        val group: SearchGroup,
        val initialPlay: WatchProgress? = null,
        val enrichAnchor: MatchAnchor? = null,
    ) : Screen

    /** 只有豆瓣条目、手里还没有任何源时才需要：拿到命中的源就换成详情页。 */
    data class Resolving(val anchor: MatchAnchor) : Screen
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
    // 探活结果跟着导航层活：切到别处再回来还是上次的结论，不会自己又跑一遍。
    val sourceHealthState = remember { SourceHealthUiState() }
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
            enrichAnchor = current.enrichAnchor,
            onBack = pop,
            onRematch = { anchor ->
                // 重新匹配：用同一锚点回到解析页，重新在全源里择优（替换过渡页位置逻辑在这里不需要，
                // 从详情压一个新的 Resolving，解析成功会再压一个 Detail，返回可回到原详情）。
                stack.add(Screen.Resolving(anchor))
            },
        )

        is Screen.Resolving -> ResolvingScreen(
            container = container,
            anchor = current.anchor,
            // 必须「替换」而不是「压栈」：过渡页只负责解析片名，解析完就不该留在返回栈里。
            // 之前是 add，导致详情页返回后回落到 Resolving，它又会重新解析并再次推开详情，
            // 表现出来就是「详情页返回不了」。
            onResolved = { group ->
                stack[stack.lastIndex] = Screen.Detail(group, enrichAnchor = current.anchor)
            },
            onBack = pop,
        )

        is Screen.Search -> SearchScreen(
            container = container,
            state = searchState,
            // 豆瓣条目没有可直接播的源，带锚点（片名/年份/原名）去用户的源里解析。
            onOpenDouban = { anchor -> stack.add(Screen.Resolving(anchor)) },
            // 视频源条目本身带播放地址，直接进详情，由用户挑源和选集。
            onOpenSource = { group -> stack.add(Screen.Detail(group)) },
            onOpenSettings = { goTab(Screen.Settings) },
            onBack = pop,
        )

        is Screen.Sources -> SourceScreen(
            container = container,
            state = sourceHealthState,
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
                            onPick = { anchor -> stack.add(Screen.Resolving(anchor)) },
                            // 记录里已有可用条目，不必先等聚合：直接进详情，
                            // 其余源由详情页在后台补齐（enrichTitle）。
                            onResume = { item, record ->
                                stack.add(
                                    Screen.Detail(
                                        group = SearchGroup(item.title, listOf(item)),
                                        initialPlay = record,
                                        enrichAnchor = MatchAnchor(title = item.title, year = item.year),
                                    ),
                                )
                            },
                            // 搜索是首页顶栏的唯一入口，收在二级页，不占底部 tab。
                            onOpenSearch = { stack.add(Screen.Search) },
                            onOpenSettings = { goTab(Screen.Settings) },
                        )

                        Screen.Home -> HomeScreen(
                            container = container,
                            state = homeState,
                            onOpen = { item ->
                                stack.add(
                                    Screen.Detail(
                                        group = SearchGroup(item.title, listOf(item)),
                                        enrichAnchor = MatchAnchor(title = item.title, year = item.year),
                                    ),
                                )
                            },
                            onResume = { item, record ->
                                stack.add(
                                    Screen.Detail(
                                        group = SearchGroup(item.title, listOf(item)),
                                        initialPlay = record,
                                        enrichAnchor = MatchAnchor(title = item.title, year = item.year),
                                    ),
                                )
                            },
                            onOpenSettings = { goTab(Screen.Settings) },
                        )

                        Screen.Settings -> SettingsScreen(
                            container = container,
                            health = sourceHealthState,
                            onOpenSources = { stack.add(Screen.Sources) },
                        )
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
