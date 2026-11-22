package com.starpick.app.ui.screen

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starpick.app.core.search.match.MatchTrace
import com.starpick.app.ui.component.EmptyState
import com.starpick.app.ui.component.GhostButton
import com.starpick.app.ui.theme.Accent
import com.starpick.app.ui.theme.Ink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 匹配诊断日志。
 *
 * 用途单一：把「这次为什么没匹配上 / 为什么选中了这条」变成可复制的文本，
 * 用户复制后直接反馈，不必靠截图猜。所以整页不做任何筛选和折叠，
 * 就是原文 + 两个动作（复制、清空）。
 *
 * 日志只存在内存里，App 重启即清空——这是有意的，诊断只需要「刚才那几次」。
 */
@Composable
fun MatchLogScreen(onBack: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf(MatchTrace.all()) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2000)
            notice = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Header(
            onBack = onBack,
            onCopy = {
                if (log.isBlank()) {
                    notice = "还没有日志，先去搜一次再来"
                } else {
                    val text = log
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("starpick-match-log", text)),
                        )
                        notice = "已复制 ${text.length} 个字符"
                    }
                }
            },
            onClear = {
                MatchTrace.clear()
                log = ""
                notice = "已清空"
            },
        )

        notice?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = Accent.Brand,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
            )
        }

        if (log.isBlank()) {
            EmptyState(
                title = "还没有匹配记录",
                description = "从首页点开一部片、或在搜索页搜一次，这里就会出现完整的匹配过程",
                actionLabel = "返回",
                onAction = onBack,
            )
        } else {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Ink.Card)
                            .border(1.dp, Ink.Line, RoundedCornerShape(10.dp))
                            .padding(14.dp),
                    ) {
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                            ),
                            color = Ink.Text2,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun Header(
    onBack: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.padding(start = 4.dp, end = 20.dp, top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Ink.Text1,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = "匹配诊断",
                style = MaterialTheme.typography.titleMedium,
                color = Ink.Text1,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "复制后直接发给开发者",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = Ink.Text3,
            )
        }

        Row(
            modifier = Modifier.padding(start = 12.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostButton(text = "复制全部", modifier = Modifier.height(32.dp), onClick = onCopy)
            GhostButton(text = "清空", modifier = Modifier.height(32.dp), onClick = onClear)
            Spacer(Modifier.width(2.dp))
            Text(
                text = "保留最近 8 次匹配",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = Ink.Text3,
            )
        }
    }
}
