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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpjt.shop.shop.AiStatus
import com.agentpjt.shop.shop.ITEM_PREFIX
import com.agentpjt.shop.shop.Product
import com.agentpjt.shop.shop.Scripts
import com.agentpjt.shop.shop.ShopState
import com.agentpjt.shop.shop.formatWon
import com.agentpjt.shop.shop.readWon
import com.agentpjt.shop.shop.withObjectParticle
import com.agentpjt.shop.ui.theme.ShopTheme

/** 모든 화면의 바탕: 세로 스크롤(큰 글꼴 대비) + 좌우 20dp 여백. 아래 버튼은 [bottom] 에 둔다. */
@Composable
private fun Page(bottom: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
        if (bottom != null) {
            Column(Modifier.padding(bottom = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = bottom)
        }
    }
}

@Composable
private fun Title(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.headlineMedium, color = ShopTheme.colors.ink)
}

// 1. 처음 화면 -------------------------------------------------------------

@Composable
fun HomeScreen(s: ShopState, onMic: () -> Unit, onExample: (String) -> Unit, onDevMenu: () -> Unit, onToggleThinking: () -> Unit) {
    val c = ShopTheme.colors
    Page {
        AiChip(s.ai, s.thinkingMode, onToggleThinking)
        // 제목을 길게 누르면 개발자 메뉴(Gemma 테스트). 어르신이 우연히 누를 일은 드물다.
        Title(
            "안녕하세요\n무엇을 사 드릴까요?",
            Modifier.padding(top = 8.dp).pointerInput(Unit) { detectTapGestures(onLongPress = { onDevMenu() }) },
        )
        Column(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MicButton(onMic, enabled = s.ai == AiStatus.READY)
            Text("누르고 말씀하세요", style = MaterialTheme.typography.titleLarge, color = c.ink)
        }
        Text("이렇게 말해 보세요", fontSize = 16.sp, color = c.inkSoft)
        // 누르면 그 문장을 말한 것처럼 에이전트에 넘긴다. 말이 서툰 분과 시연을 위한 길.
        EXAMPLES.forEach { ExampleSay(it, enabled = s.ai == AiStatus.READY) { onExample(it) } }
    }
}

private val EXAMPLES = listOf("무릎 아플 때 붙이는 거 2만 원 안쪽으로", "아침에 마실 거 찾아줘")

/** AI 준비 상태. 길게 누르면 생각 모드를 켜고 끈다(실측 비교용). */
@Composable
private fun AiChip(ai: AiStatus, thinking: Boolean, onLongPress: () -> Unit) {
    val c = ShopTheme.colors
    val (text, dot) = when (ai) {
        AiStatus.LOADING -> "AI 준비 중" to c.inkSoft
        AiStatus.READY -> (if (thinking) "AI 준비됨 · 생각 모드" else "AI 준비됨") to c.ok
        AiStatus.UNAVAILABLE -> "AI를 쓸 수 없어요 · 화면을 눌러 이용하세요" to c.speakInk
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (ai == AiStatus.UNAVAILABLE) c.speak else c.voiceTint)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (ai == AiStatus.UNAVAILABLE) c.speakInk else c.ink)
    }
}

@Composable
private fun ExampleSay(text: String, enabled: Boolean, onClick: () -> Unit) {
    val c = ShopTheme.colors
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp), border = BorderStroke(2.dp, c.line),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink),
    ) {
        Text("“$text”", Modifier.fillMaxWidth(), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}

// 2. 말하기 ----------------------------------------------------------------

@Composable
fun ListeningScreen(s: ShopState, onDone: () -> Unit, onCancel: () -> Unit) {
    val c = ShopTheme.colors
    Page(bottom = {
        BigButton("다 말했어요", onDone, enabled = !s.agentBusy)
        BigButton("그만하기", onCancel, primary = false)
    }) {
        Column(
            Modifier.fillMaxWidth().padding(top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(c.voiceTint).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c.voice))
                Text(if (s.agentBusy) "생각하고 있어요" else "듣고 있어요", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.ink)
            }
            Box(contentAlignment = Alignment.Center) {
                Rings()
                MicButton(onClick = {}, enabled = false)
            }
            Text(
                s.heard.ifEmpty { " " }, Modifier.fillMaxWidth(),
                fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, color = c.ink, textAlign = TextAlign.Center,
            )
        }
    }
}

/** 마이크 둘레로 퍼지는 고리 셋. */
@Composable
private fun Rings() {
    val c = ShopTheme.colors
    val t = rememberInfiniteTransition(label = "rings")
    (0..2).forEach { i ->
        val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, delayMillis = i * 600), RepeatMode.Restart), label = "ring$i")
        Box(
            Modifier.size(168.dp).scale(1f + 0.7f * p).graphicsLayer { alpha = 0.55f * (1 - p) }
                .border(3.dp, c.voice, CircleShape),
        )
    }
}

// 3. 추천 상품 --------------------------------------------------------------

@Composable
fun ResultsScreen(s: ShopState, speakingId: String?, onPick: (Int) -> Unit, onStopReading: () -> Unit, onMic: () -> Unit) {
    val c = ShopTheme.colors
    val readingIndex = speakingId?.removePrefix(ITEM_PREFIX)?.toIntOrNull()?.takeIf { speakingId.startsWith(ITEM_PREFIX) }
    Page(bottom = { SpeakButton(onMic, s.ai == AiStatus.READY) }) {
        Title("${withObjectParticle(s.label)}\n${s.shown.size}개 골랐어요")
        if (speakingId != null) {
            ReadingBar(if (readingIndex != null) "${readingIndex + 1}번째를 읽고 있어요" else "읽어 드리고 있어요", onStopReading)
        }
        s.shown.forEachIndexed { i, p -> ProductCard(i, p, highlighted = i == readingIndex) { onPick(i) } }
        Text(
            "쿠팡 파트너스 활동의 일환으로 수수료를 받을 수 있습니다. (연동 시 표시 자리)",
            fontSize = 13.sp, color = c.inkSoft,
        )
    }
}

@Composable
private fun ProductCard(index: Int, p: Product, highlighted: Boolean, onClick: () -> Unit) {
    val c = ShopTheme.colors
    val shape = RoundedCornerShape(18.dp)
    OutlinedButton(
        onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = shape,
        border = BorderStroke(2.dp, if (highlighted) c.speakInk else c.line),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (highlighted) c.speak.copy(alpha = 0.25f) else c.surface, contentColor = c.ink,
        ),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NumberBadge(index + 1)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(formatWon(p.price), fontSize = 28.sp, fontWeight = FontWeight.Black, color = c.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (p.isRocket) Badge("로켓배송")
                    if (p.isFreeShipping) Badge("무료배송")
                    Badge("${p.arriveLabel} 도착", plain = true)
                }
            }
        }
    }
}

// 4. 상품 자세히 -------------------------------------------------------------

@Composable
fun DetailScreen(s: ShopState, onQty: (Int) -> Unit, onOrder: () -> Unit, onOthers: () -> Unit, onMic: () -> Unit) {
    val c = ShopTheme.colors
    val p = s.current ?: return
    Page(bottom = {
        BigButton("주문하기", onOrder)
        SpeakButton(onMic, s.ai == AiStatus.READY)
        if (s.shown.isNotEmpty()) BigButton("다른 상품 보기", onOthers, primary = false)
    }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(18.dp)).background(c.voiceTint),
            contentAlignment = Alignment.Center,
        ) { PatchPicture(Modifier.fillMaxWidth(0.46f).aspectRatio(4f / 3f)) }
        Title(p.name)
        Text(formatWon(p.price), fontSize = 34.sp, fontWeight = FontWeight.Black, color = c.ink)
        Fact("배송비", if (p.shippingFee > 0) formatWon(p.shippingFee) else "없음")
        Fact("도착", p.arriveLabel)
        QtyStepper(s.qty, onQty)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val c = ShopTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 19.sp, color = c.inkSoft)
        Spacer(Modifier.size(12.dp))
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = c.ink, textAlign = TextAlign.End)
    }
}

/** 상품 이미지 자리. 시안의 파스 그림(120×90)을 옮겼다. 실제 이미지는 데이터 연동 뒤. */
@Composable
private fun PatchPicture(modifier: Modifier) {
    val c = ShopTheme.colors
    Canvas(modifier) {
        val u = size.width / 120f
        drawRoundRect(c.surface, Offset(8 * u, 14 * u), Size(104 * u, 62 * u), CornerRadius(12 * u))
        drawRoundRect(c.ink, Offset(8 * u, 14 * u), Size(104 * u, 62 * u), CornerRadius(12 * u), style = Stroke(3 * u))
        drawRoundRect(c.voice, Offset(34 * u, 28 * u), Size(52 * u, 34 * u), CornerRadius(6 * u))
        listOf(20f, 100f).forEach { x -> listOf(26f, 45f, 64f).forEach { y -> drawCircle(c.inkSoft, 2.5f * u, Offset(x * u, y * u)) } }
    }
}

// 5. 주문 확인 --------------------------------------------------------------

@Composable
fun ConfirmScreen(s: ShopState, onYes: () -> Unit, onNo: () -> Unit, onMic: () -> Unit) {
    val c = ShopTheme.colors
    val p = s.current ?: return
    val total = Scripts.total(p, s.qty)
    Page(bottom = {
        // "아니요"도 같은 크기. 작은 취소 버튼은 잘못 누르기 쉽다.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("네, 주문", onYes, Modifier.weight(1f), minHeight = 76.dp)
            BigButton("아니요", onNo, Modifier.weight(1f), primary = false, minHeight = 76.dp)
        }
        SpeakButton(onMic, s.ai == AiStatus.READY)
    }) {
        Title("이대로 주문할까요?")
        Fact("상품", "${p.name} · ${s.qty}개")
        Fact("받는 곳", "서울 노원구 상계로 77\n홍길순 님")
        Fact("결제", "국민카드 ****1234")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.ink).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("배송비 포함 결제 금액", fontSize = 17.sp, color = c.surface.copy(alpha = 0.85f))
            Text(formatWon(total), fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Black, color = c.surface)
            Text(readWon(total), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.speak)
        }
    }
}

// 6. 주문 완료 --------------------------------------------------------------

@Composable
fun DoneScreen(s: ShopState, onHome: () -> Unit) {
    val c = ShopTheme.colors
    Page(bottom = { BigButton("처음으로", onHome) }) {
        Column(
            Modifier.fillMaxWidth().padding(top = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(120.dp).clip(CircleShape).background(c.ok), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(60.dp)) {
                    val u = size.width / 24f
                    val path = Path().apply { moveTo(5 * u, 12.5f * u); lineTo(9.5f * u, 17 * u); lineTo(19 * u, 7.5f * u) }
                    drawPath(path, c.surface, style = Stroke(3 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            Title("주문했어요")
            Text(
                "${s.current?.arriveLabel}에 도착해요\n가족께 알렸어요",
                fontSize = 19.sp, lineHeight = 28.sp, color = c.inkSoft, textAlign = TextAlign.Center,
            )
        }
    }
}
