package com.agentpjt.shop.ui.shop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpjt.shop.ui.theme.ShopTheme

/** 화면 아래 큰 버튼. 높이 64dp 이상, 글자 22sp. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    minHeight: Dp = 64.dp,
) {
    val c = ShopTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val mod = modifier.fillMaxWidth().heightIn(min = minHeight)
    if (primary) {
        Button(
            onClick = onClick, enabled = enabled, modifier = mod, shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = c.voice, contentColor = c.voiceInk),
        ) { Text(text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
    } else {
        OutlinedButton(
            onClick = onClick, enabled = enabled, modifier = mod, shape = shape,
            border = BorderStroke(2.dp, c.line),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink),
        ) { Text(text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
    }
}

/** 마이크 그림. 시안의 SVG(24×24 viewBox)를 Canvas 로 옮겼다. */
@Composable
fun MicIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(tint, topLeft = Offset(9 * u, 3 * u), size = Size(6 * u, 11 * u), cornerRadius = CornerRadius(3 * u), style = stroke)
        val p = Path().apply {
            moveTo(5 * u, 11 * u)
            cubicTo(5 * u, 15 * u, 8 * u, 18 * u, 12 * u, 18 * u)
            cubicTo(16 * u, 18 * u, 19 * u, 15 * u, 19 * u, 11 * u)
            moveTo(12 * u, 18 * u)
            lineTo(12 * u, 21 * u)
        }
        drawPath(p, tint, style = stroke)
    }
}

/** 168dp 원형 마이크 버튼. 듣는 중에는 눌리지 않지만 색은 그대로 둔다. */
@Composable
fun MicButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = ShopTheme.colors
    Button(
        onClick = onClick, enabled = enabled, shape = CircleShape,
        modifier = modifier.size(168.dp).semantics { contentDescription = "누르고 말하기" },
        colors = ButtonDefaults.buttonColors(
            containerColor = c.voice, contentColor = c.voiceInk,
            disabledContainerColor = c.voice, disabledContentColor = c.voiceInk,
        ),
    ) { MicIcon(c.voiceInk, Modifier.size(64.dp)) }
}

/** 추천·상세·확인 화면 아래의 "말로 하기" 버튼. 마이크 그림 + 글자. */
@Composable
fun SpeakButton(onClick: () -> Unit, enabled: Boolean) {
    val c = ShopTheme.colors
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp), border = BorderStroke(2.dp, c.voice),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.voice),
    ) {
        MicIcon(c.voice, Modifier.size(28.dp))
        Text("  말로 하기", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** 지금 읽고 있다는 노란 띠. 막대 셋이 오르내린다. */
@Composable
fun ReadingBar(text: String, onStop: () -> Unit) {
    val c = ShopTheme.colors
    val t = rememberInfiniteTransition(label = "eq")
    val bars = (0..2).map { i ->
        t.animateFloat(6f, 18f, infiniteRepeatable(tween(450, delayMillis = i * 150), RepeatMode.Reverse), label = "bar$i")
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.speak).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            bars.forEach { bar ->
                val h by bar
                Box(Modifier.width(4.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(c.speakInk))
            }
        }
        Text(text, Modifier.weight(1f), color = c.speakInk, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        OutlinedButton(
            onClick = onStop, border = BorderStroke(2.dp, c.speakInk), shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.speakInk),
        ) { Text("그만 읽기", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
    }
}

/** 상품 번호. "두 번째 거"라고 말할 수 있게 크게. */
@Composable
fun NumberBadge(n: Int) {
    val c = ShopTheme.colors
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(c.ink), contentAlignment = Alignment.Center) {
        Text("$n", color = c.surface, fontSize = 28.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun Badge(text: String, plain: Boolean = false) {
    val c = ShopTheme.colors
    val shape = RoundedCornerShape(8.dp)
    val mod = if (plain) Modifier.border(1.5.dp, c.line, shape) else Modifier.clip(shape).background(c.voiceTint)
    Text(
        text, mod.padding(horizontal = 10.dp, vertical = 3.dp),
        color = if (plain) c.inkSoft else c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
    )
}

/** 수량: 빼기 · 숫자 · 더하기. 버튼 64dp, 숫자 40sp. */
@Composable
fun QtyStepper(qty: Int, onChange: (Int) -> Unit) {
    val c = ShopTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StepButton("−", "하나 빼기", enabled = qty > 1) { onChange(-1) }
        Text("${qty}개", Modifier.weight(1f), color = c.ink, fontSize = 40.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        StepButton("+", "하나 더하기", enabled = qty < 9) { onChange(1) }
    }
}

@Composable
private fun StepButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = ShopTheme.colors
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.size(64.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(16.dp), border = BorderStroke(2.dp, c.line),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink),
    ) { Text(label, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold) }
}
