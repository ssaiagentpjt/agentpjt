package com.agentpjt.shop.ui.shop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/*
 * 미니멀 스크롤 표시: 스크롤하는 동안에만 오른쪽에 얇은 둥근 막대가 나타났다가 잠시 뒤 사라진다.
 * 스크롤 영역(verticalScroll·LazyColumn)보다 앞에 붙여서 보이는 칸 기준으로 그린다.
 */

/** verticalScroll 용. 예: Modifier.minimalScrollbar(scroll, c.inkSoft).verticalScroll(scroll) */
fun Modifier.minimalScrollbar(state: ScrollState, color: Color): Modifier = composed {
    val alpha by fade(state.isScrollInProgress)
    drawWithContent {
        drawContent()
        if (state.maxValue <= 0 || alpha == 0f) return@drawWithContent
        val viewport = size.height
        val fraction = viewport / (viewport + state.maxValue)
        drawThumb(color, alpha, fraction, state.value.toFloat() / state.maxValue)
    }
}

/** LazyColumn 용. 항목 높이가 제각각이라 보이는 항목 수의 비율로 어림한다 */
fun Modifier.minimalScrollbar(state: LazyListState, color: Color): Modifier = composed {
    val alpha by fade(state.isScrollInProgress)
    drawWithContent {
        drawContent()
        val info = state.layoutInfo
        val total = info.totalItemsCount
        val visible = info.visibleItemsInfo.size
        if (total == 0 || visible >= total || alpha == 0f) return@drawWithContent
        val fraction = visible.toFloat() / total
        val position = state.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)
        drawThumb(color, alpha, fraction, position.coerceIn(0f, 1f))
    }
}

@androidx.compose.runtime.Composable
private fun fade(scrolling: Boolean) = animateFloatAsState(
    targetValue = if (scrolling) 1f else 0f,
    animationSpec = if (scrolling) tween(120) else tween(durationMillis = 500, delayMillis = 700),
    label = "scrollbar",
)

private fun DrawScope.drawThumb(color: Color, alpha: Float, fraction: Float, position: Float) {
    val width = 4.dp.toPx()
    val inset = 3.dp.toPx()
    val track = size.height - inset * 2
    val thumb = (track * fraction).coerceAtLeast(36.dp.toPx())
    val top = inset + (track - thumb) * position
    drawRoundRect(
        color = color.copy(alpha = color.alpha * 0.55f * alpha),
        topLeft = Offset(size.width - width - inset, top),
        size = Size(width, thumb),
        cornerRadius = CornerRadius(width / 2),
    )
}
