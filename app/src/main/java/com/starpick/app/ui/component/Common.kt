package com.starpick.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.starpick.app.ui.theme.Accent
import com.starpick.app.ui.theme.Ink

// ---------------------------------------------------------------- 基础

/** 影视封面。空封面用首字占位；豆瓣图片防盗链 Referer 由全局 ImageLoader 统一注入。 */
@Composable
fun VodCover(
    title: String,
    cover: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
) {
    if (cover.isBlank()) {
        Box(
            modifier = modifier.clip(shape).background(Ink.Raised),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.take(1),
                style = MaterialTheme.typography.titleLarge,
                color = Ink.Text3,
            )
        }
    } else {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(cover)
                .crossfade(true)
                .build(),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape).background(Ink.Raised),
        )
    }
}

/** 分区标题：左标题 + 右次要信息，左右各 20dp 边距由组件自己带。 */
@Composable
fun SectionHeader(
    title: String,
    trailing: String? = null,
    modifier: Modifier = Modifier,
    onTrailingClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Ink.Text1,
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (onTrailingClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onTrailingClick)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                } else {
                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                },
            ) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.Text3,
                )
            }
        }
    }
}

/** 3dp 细进度条：已播星金，未播白 14%。 */
@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(Color(0x24FFFFFF)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(height / 2))
                .background(Accent.Gold),
        )
    }
}

// ---------------------------------------------------------------- 卡片

/**
 * 海报卡：2:3 封面 + 右上评分 + 左下更新角标 + 两行片名。
 * 评分固定在右上（与视线落点一致），角标只在「有新集」时出现。
 */
@Composable
fun PosterCard(
    title: String,
    cover: String,
    modifier: Modifier = Modifier,
    rate: String? = null,
    badge: String? = null,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            VodCover(
                title = title,
                cover = cover,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(12.dp),
            )
            // 底部压暗，让角标与画面边缘不打架。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.55f to Color.Transparent,
                                1f to Color(0x73000000),
                            ),
                        ),
                    ),
            )
            if (!rate.isNullOrBlank()) {
                Text(
                    text = rate,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Accent.Gold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xB806080C))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (!badge.isNullOrBlank()) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Accent.New)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Text1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 列表行：16:9 缩略图 + 双行文字 + 进度条。
 * 一行能同时给出进度和剩余时长，比网格更适合「接着看」。
 */
@Composable
fun MediaRow(
    title: String,
    subtitle: String,
    cover: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(112.dp).height(63.dp)) {
            VodCover(
                title = title,
                cover = cover,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(9.dp),
            )
            if (progress != null) {
                ThinProgress(
                    fraction = progress,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 0.dp),
                    height = 2.5.dp,
                )
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) Ink.Text1 else Ink.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = Ink.Text3,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = Ink.Text3,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ---------------------------------------------------------------- 控件

/** 主按钮：品牌蓝实心，高 46。 */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent.Brand,
            contentColor = Color.White,
            disabledContainerColor = Ink.Selected,
            disabledContentColor = Ink.Text3,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp),
        modifier = modifier.height(46.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** 次按钮：白 10% 填充 + 淡描边。 */
@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x1AFFFFFF))
            .border(1.dp, Ink.LineStrong, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Ink.Text1, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Ink.Text1,
            maxLines = 1,
        )
    }
}

/** 方形图标按钮（收藏等）。 */
@Composable
fun SquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Color(0x24F6BE4F) else Ink.Card)
            .border(
                1.dp,
                if (active) Color(0x8CF6BE4F) else Ink.LineStrong,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) Accent.Gold else Ink.Text1,
            modifier = Modifier.size(19.dp),
        )
    }
}

/** 分段控件：互斥的 2~3 项切换。深底上比下划线 Tab 清晰得多。 */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Ink.Card)
            .border(1.dp, Ink.Line, RoundedCornerShape(11.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Ink.Selected else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 18.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) Ink.Text1 else Ink.Text2,
                )
            }
        }
    }
}

/** 线路 / 筛选胶囊。 */
@Composable
fun LineChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0x1F4C8DFF) else Ink.Card)
            .border(
                1.dp,
                if (selected) Color(0x804C8DFF) else Ink.Line,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Accent.Brand else Ink.Text2,
            maxLines = 1,
        )
    }
}

/** 选集格子：当前集金色描边，看过的低对比。 */
@Composable
fun EpisodeCell(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    seen: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        selected -> Color(0x24F6BE4F)
        seen -> Color(0x0AFFFFFF)
        else -> Ink.Card
    }
    val borderColor = if (selected) Color(0x8CF6BE4F) else Ink.Line
    val textColor = when {
        selected -> Accent.Gold
        seen -> Ink.Text3
        else -> Ink.Text2
    }
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
        )
    }
}

/**
 * 空态：图标 + 标题 + 说明 + 一条出路。
 * 空态必须能点，不能只有一行灰字。
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Ink.Text3,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Ink.Text1,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.Text2,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            GhostButton(text = actionLabel, onClick = onAction)
        }
    }
}

/**
 * 手写分行网格：把一维列表按固定列数切成若干 Row，供 LazyColumn 的 item 内部使用。
 *
 * 为什么不用 LazyVerticalGrid：LazyVerticalGrid 是惰性滚动容器，不能嵌在另一个纵向滚动容器
 * （LazyColumn）里——会触发 "Vertically scrollable component was measured with an infinity
 * maximum height constraints" 崩溃。这里用普通 Row/Column 平铺，数据量不大，性能足够。
 */
@Composable
fun FlowPosterGrid(
    columns: Int,
    itemCount: Int,
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 11.dp,
    verticalSpacing: Dp = 16.dp,
    itemContent: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
    ) {
        var index = 0
        while (index < itemCount) {
            val rowItems = (index until (index + columns).coerceAtMost(itemCount)).toList()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            ) {
                rowItems.forEach { i ->
                    itemContent(i, Modifier.weight(1f))
                }
                // 末行补位：用占位保证不足一行的项左对齐。
                repeat(columns - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
            index += columns
        }
    }
}

/** 时间格式：m:ss，长片自动变 h:mm:ss。 */
fun formatPosition(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
