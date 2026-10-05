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
import com.agentpjt.shop.shop.DEMO_USER_NAME
import com.agentpjt.shop.shop.PastOrder
import com.agentpjt.shop.shop.ProductSummary
import com.agentpjt.shop.shop.total
import com.agentpjt.shop.shop.ShopState
import com.agentpjt.shop.shop.formatWon
import com.agentpjt.shop.shop.readWon
import com.agentpjt.shop.shop.withObjectParticle
import com.agentpjt.shop.ui.theme.ShopTheme
import coil3.compose.AsyncImage

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
fun HomeScreen(s: ShopState, onMic: () -> Unit, onExample: (String) -> Unit, onDevMenu: () -> Unit, onCart: () -> Unit, onHistory: () -> Unit) {
    val c = ShopTheme.colors
    Page {
        AiChip(s.ai)
        if (s.bubble.isNotEmpty()) ReplyBubble(s.bubble)
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(if (s.cart.isEmpty()) "장바구니" else "장바구니 ${s.cart.size}", onCart, Modifier.weight(1f), primary = false)
            BigButton("주문 내역", onHistory, Modifier.weight(1f), primary = false)
        }
    }
}

private val EXAMPLES = listOf("무릎 아플 때 붙이는 거 2만 원 안쪽으로", "아침에 마실 거 찾아줘")

/** AI 준비 상태. */
@Composable
private fun AiChip(ai: AiStatus) {
    val c = ShopTheme.colors
    val (text, dot) = when (ai) {
        AiStatus.LOADING -> "AI 준비 중" to c.inkSoft
        AiStatus.READY -> "AI 준비됨" to c.ok
        AiStatus.UNAVAILABLE -> "AI를 쓸 수 없어요 · 화면을 눌러 이용하세요" to c.speakInk
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (ai == AiStatus.UNAVAILABLE) c.speak else c.voiceTint)
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

/** 손주야가 화면을 바꾸지 않고 한 말(ask·answer). 소리로는 앞부분만 읽으므로 전문을 여기 보인다. */
@Composable
private fun ReplyBubble(text: String) {
    val c = ShopTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.voiceTint).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("손주야", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.inkSoft)
        Text(text, fontSize = 21.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
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
                // 한 단계가 수 초씩 걸려서, 무엇을 하는 중인지 보여 줘야 어르신이 안 들린 줄 알고 다시 말하지 않는다
                Text(if (s.agentBusy) s.progress.ifEmpty { "생각하고 있어요" } else "듣고 있어요", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.ink)
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
    Page(bottom = { SpeakButton(onMic, s.ai == AiStatus.READY && !s.agentBusy) }) {
        if (s.bubble.isNotEmpty()) ReplyBubble(s.bubble)
        Title("${withObjectParticle(s.label)}\n${s.shown.size}개 골랐어요")
        if (speakingId != null) {
            ReadingBar(if (readingIndex != null) "${readingIndex + 1}번째를 읽고 있어요" else "읽어 드리고 있어요", onStopReading)
        }
        s.shown.forEachIndexed { i, p -> ProductCard(i, p, highlighted = i == readingIndex, enabled = !s.agentBusy) { onPick(i) } }
        Text("상품과 가격은 공모전 시연용 가상 데이터예요.", fontSize = 13.sp, color = c.inkSoft)
    }
}

@Composable
private fun ProductCard(index: Int, p: ProductSummary, highlighted: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = ShopTheme.colors
    val shape = RoundedCornerShape(18.dp)
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = shape,
        border = BorderStroke(2.dp, if (highlighted) c.speakInk else c.line),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (highlighted) c.speak.copy(alpha = 0.25f) else c.surface, contentColor = c.ink,
            disabledContainerColor = c.surface, disabledContentColor = c.ink,
        ),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberBadge(index + 1)
                ProductImage(p.image, Modifier.size(52.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(formatWon(p.price), fontSize = 28.sp, fontWeight = FontWeight.Black, color = c.ink)
                Text("${p.tier} · 별점 ${p.rating} (${p.reviews})", fontSize = 16.sp, color = c.inkSoft)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.badges.take(3).forEach { Badge(it) }
                    Badge("${p.arriveSpoken} 도착", plain = true)
                    if (p.options.isNotEmpty()) Badge("옵션 ${p.options.keys.joinToString("·")}", plain = true)
                }
            }
        }
    }
}

/** 서버가 준 이미지(지금은 대분류 SVG 아이콘). 실패하면 빈 자리만 남는다 */
@Composable
private fun ProductImage(url: String, modifier: Modifier) {
    val c = ShopTheme.colors
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(c.voiceTint), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(0.8f))
    }
}

// 4. 상품 자세히 -------------------------------------------------------------

@Composable
fun DetailScreen(
    s: ShopState,
    onQty: (Int) -> Unit,
    onOption: (String, String) -> Unit,
    onAdd: () -> Unit,
    onOthers: () -> Unit,
    onMic: () -> Unit,
) {
    val c = ShopTheme.colors
    val p = s.current ?: return
    Page(bottom = {
        BigButton(if (p.soldOut) "품절이에요" else "장바구니에 담기", onAdd, enabled = !p.soldOut && !s.agentBusy)
        SpeakButton(onMic, s.ai == AiStatus.READY && !s.agentBusy)
        if (s.shown.isNotEmpty()) BigButton("다른 상품 보기", onOthers, primary = false)
    }) {
        if (s.bubble.isNotEmpty()) ReplyBubble(s.bubble)
        ProductImage(p.image, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
        Title(p.name)
        Text(formatWon(p.price), fontSize = 34.sp, fontWeight = FontWeight.Black, color = c.ink)
        Text("${p.tier} · 별점 ${p.rating} (${p.reviews})", fontSize = 17.sp, color = c.inkSoft)
        Fact("배송비", if (p.shippingFee > 0) formatWon(p.shippingFee) else "없음")
        Fact("도착", p.arriveLabel)
        Fact("규격", p.spec)
        Text(p.description, fontSize = 19.sp, lineHeight = 28.sp, color = c.ink)
        p.reviewSummary?.let { Text("“$it”", fontSize = 17.sp, lineHeight = 25.sp, color = c.inkSoft) }
        p.options.forEach { axis ->
            // 주문하려는데 이 옵션이 빠졌으면 눈에 띄게 한다(음성으로도 같은 안내를 읽는다)
            val missing = s.missingOption == axis.name
            Text(
                if (missing) "${withObjectParticle(axis.name)} 골라 주세요" else axis.name,
                fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (missing) c.speakInk else c.ink,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                axis.values.forEach { v ->
                    OptionChip(
                        text = v.value + if (v.priceAdd > 0) " (+${formatWon(v.priceAdd)})" else "",
                        selected = s.selected[axis.name] == v.value,
                        enabled = !v.soldOut && !s.agentBusy,
                        soldOut = v.soldOut,
                    ) { onOption(axis.name, v.value) }
                }
            }
        }
        QtyStepper(s.qty, onQty)
    }
}

/** 옵션 값 하나. 고른 값은 진하게, 품절은 비활성 + "품절" */
@Composable
private fun OptionChip(text: String, selected: Boolean, enabled: Boolean, soldOut: Boolean, onClick: () -> Unit) {
    val c = ShopTheme.colors
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(2.dp, if (selected) c.voice else c.line),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) c.voice else c.surface, contentColor = if (selected) c.voiceInk else c.ink,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) { Text(if (soldOut) "$text 품절" else text, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
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

// 장바구니 -----------------------------------------------------------------

@Composable
fun CartScreen(s: ShopState, onQty: (String, Int) -> Unit, onRemove: (String) -> Unit, onCheckout: () -> Unit, onMic: () -> Unit) {
    val c = ShopTheme.colors
    Page(bottom = {
        if (s.cart.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("합계", fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.ink)
                Text(formatWon(s.cart.total()), fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.ink)
            }
            BigButton("주문하기", onCheckout, enabled = !s.agentBusy)
        }
        SpeakButton(onMic, s.ai == AiStatus.READY && !s.agentBusy)
    }) {
        if (s.bubble.isNotEmpty()) ReplyBubble(s.bubble)
        Title(if (s.cart.isEmpty()) "장바구니가 비어 있어요" else "장바구니 · ${s.cart.size}가지")
        s.cart.forEachIndexed { i, l ->
            val problem = s.cartProblem == l.lineId
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .border(2.dp, if (problem) c.speakInk else c.line, RoundedCornerShape(16.dp))
                    .background(if (problem) c.speak.copy(alpha = 0.25f) else c.surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumberBadge(i + 1)
                    Column(Modifier.weight(1f)) {
                        Text(l.name, style = MaterialTheme.typography.titleMedium, color = c.ink)
                        if (l.options.isNotEmpty()) Text(l.options.values.joinToString(" "), fontSize = 16.sp, color = c.inkSoft)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OptionChip("−", selected = false, enabled = !s.agentBusy && l.qty > 1, soldOut = false) { onQty(l.lineId, -1) }
                        Text("${l.qty}", fontSize = 22.sp, fontWeight = FontWeight.Black, color = c.ink)
                        OptionChip("+", selected = false, enabled = !s.agentBusy && l.qty < 9, soldOut = false) { onQty(l.lineId, 1) }
                    }
                    Text(formatWon(l.total), fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.ink)
                }
                OptionChip("빼기", selected = false, enabled = !s.agentBusy, soldOut = false) { onRemove(l.lineId) }
            }
        }
    }
}

// 주문 내역 ----------------------------------------------------------------

@Composable
fun HistoryScreen(s: ShopState, onReorder: (PastOrder) -> Unit, onMic: () -> Unit) {
    val c = ShopTheme.colors
    Page(bottom = { SpeakButton(onMic, s.ai == AiStatus.READY && !s.agentBusy) }) {
        if (s.bubble.isNotEmpty()) ReplyBubble(s.bubble)
        Title(if (s.pastOrders.isEmpty()) "주문하신 것이 없어요" else "주문 내역")
        s.pastOrders.forEach { o ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(2.dp, c.line, RoundedCornerShape(16.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(o.date, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.inkSoft)
                Text(o.name, style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text("${o.qty}개" + if (o.options.isEmpty()) "" else " · " + o.options.values.joinToString(" "), fontSize = 16.sp, color = c.inkSoft)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(formatWon(o.total), fontSize = 19.sp, fontWeight = FontWeight.Black, color = c.ink)
                    OptionChip("또 담기", selected = false, enabled = !s.agentBusy, soldOut = false) { onReorder(o) }
                }
            }
        }
    }
}

// 5. 주문 확인 --------------------------------------------------------------

@Composable
fun ConfirmScreen(s: ShopState, onYes: () -> Unit, onNo: () -> Unit, onMic: () -> Unit) {
    val c = ShopTheme.colors
    if (s.cart.isEmpty()) return
    val total = s.cart.total()
    Page(bottom = {
        // "아니요"도 같은 크기. 작은 취소 버튼은 잘못 누르기 쉽다.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("네, 주문", onYes, Modifier.weight(1f), minHeight = 76.dp, enabled = !s.agentBusy)
            BigButton("아니요", onNo, Modifier.weight(1f), primary = false, minHeight = 76.dp)
        }
        SpeakButton(onMic, s.ai == AiStatus.READY && !s.agentBusy)
    }) {
        if (s.bubble.isNotEmpty()) ReplyBubble(s.bubble)
        Title("이대로 주문할까요?")
        s.cart.forEach { l ->
            Fact(l.name + (if (l.options.isEmpty()) "" else " " + l.options.values.joinToString(" ")) + " × ${l.qty}", formatWon(l.total))
        }
        Fact("받는 곳", "서울 노원구 상계로 77\n$DEMO_USER_NAME 님")
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
            if (s.bubble.isNotEmpty()) ReplyBubble(s.bubble)
            Title("주문했어요")
            Text(
                "차례로 도착해요\n가족께 알렸어요",
                fontSize = 19.sp, lineHeight = 28.sp, color = c.inkSoft, textAlign = TextAlign.Center,
            )
            if (s.orderIds.isNotEmpty()) Text("주문 ${s.orderIds.size}건 · 주문번호 ${s.orderIds.first()}" + if (s.orderIds.size > 1) " 외" else "", fontSize = 17.sp, color = c.inkSoft)
        }
    }
}
