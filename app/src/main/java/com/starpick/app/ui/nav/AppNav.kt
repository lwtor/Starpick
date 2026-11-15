package com.starpick.app.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.ui.screen.DetailScreen
import com.starpick.app.ui.screen.HomeScreen
import com.starpick.app.ui.screen.PlayerScreen
import com.starpick.app.ui.screen.SearchScreen
import com.starpick.app.ui.screen.SourcesScreen

/**
 * 极简状态导航：一个返回栈，不引入 Navigation 库。
 * UI 本来就是平台层，将来接 Compose Multiplatform 时替换这一处即可。
 */
sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Sources : Screen
    data class Detail(val group: SearchGroup) : Screen
    data class Player(val item: VodItem, val groupIndex: Int, val episodeIndex: Int) : Screen
}

@Composable
fun StarpickNav(container: AppContainer) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val current = stack.last()
    val pop: () -> Unit = { if (stack.size > 1) stack.removeLastOrNull() }
    val goTab: (Screen) -> Unit = { tab ->
        stack.clear()
        stack.add(tab)
    }

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

        is Screen.Player -> PlayerScreen(
            container = container,
            item = current.item,
            groupIndex = current.groupIndex,
            episodeIndex = current.episodeIndex,
            onBack = pop,
        )

        else -> Scaffold(
            topBar = { StarBoxTopBar() },
            bottomBar = { BottomTabs(current, goTab) },
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                when (current) {
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

                    Screen.Sources -> SourcesScreen(container)
                }
            }
        }
    }
}

@Composable
private fun StarBoxTopBar() {
    Surface(tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "星匣",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Starpick",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BottomTabs(current: Screen, goTab: (Screen) -> Unit) {
    val tabs = listOf(
        Screen.Home to "片匣",
        Screen.Search to "搜索",
        Screen.Sources to "源",
    )
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            tabs.forEach { (tab, label) ->
                val selected = current == tab
                TextButton(onClick = { goTab(tab) }) {
                    Text(
                        text = label,
                        style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
