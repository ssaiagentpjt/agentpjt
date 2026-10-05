package com.agentpjt.shop.ui.shop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.agentpjt.shop.shop.CartLine
import com.agentpjt.shop.shop.DEMO_USER_NAME
import com.agentpjt.shop.shop.ITEM_PREFIX
import com.agentpjt.shop.shop.PastOrder
import com.agentpjt.shop.shop.ProductSummary
import com.agentpjt.shop.shop.ShopState
import com.agentpjt.shop.shop.formatWon
import com.agentpjt.shop.shop.readWon
import com.agentpjt.shop.shop.total
import com.agentpjt.shop.ui.theme.ShopTheme
import kotlinx.coroutines.delay

/** 모든 쇼핑 화면이 공통으로 쓰는 동작: 맨 위 버튼 줄의 이동과 글자로 쓰기 */
data class Nav(
    val home: () -> Unit,
    val cart: () -> Unit,
    val history: () -> Unit,
    val devMenu: () -> Unit,
    val openTyping: () -> Unit,
    val closeTyping: () -> Unit,
    val sendTyped: (String) -> Unit,
)

@Composable
private fun Frame(s: ShopState, nav: Nav, dock: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    ShopFrame(s, nav, dock, content)

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = ShopTheme.colors.ink)
}

// 1. 처음 화면 -------------------------------------------------------------

@Composable
fun HomeScreen(s: ShopState, nav: Nav, onMic: () -> Unit, onExample: (String) -> Unit) {
    val c = ShopTheme.colors
    Frame(s, nav, dock = { MicDock(s, onMic) }) {
        Title("안녕하세요, $DEMO_USER_NAME 님")
        Text("아래 파란 버튼을 누르고 편하게 말씀하세요.", fontSize = 18.sp, lineHeight = 26.sp, color = c.inkSoft)
        Text("이렇게 말해 보세요", fontSize = 16.sp, color = c.inkSoft, modifier = Modifier.padding(top = 6.dp))
        // 누르면 그 문장을 말한 것처럼 에이전트에 넘긴다. 말이 서툰 분과 시연을 위한 길.
        EXAMPLES.forEach { ExampleSay(it, enabled = s.ai == com.agentpjt.shop.shop.AiStatus.READY && !s.agentBusy) { onExample(it) } }
    }
}

private val EXAMPLES = listOf("아침에 먹을 만한 거 찾아 줘", "라디오 하나 오만 원 안쪽으로", "지난번에 산 거 또 사고 싶어")

@Composable
private fun ExampleSay(text: String, enabled: Boolean, onClick: () -> Unit) {
    val c = ShopTheme.colors
    Text(
        "“$text”",
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(2.dp, c.line, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (enabled) c.ink else c.inkSoft,
    )
}

// 2. 듣는 중 · 찾는 중 -------------------------------------------------------

/** 말하는 동안에는 마이크와 쉼 막대, 판단하는 동안에는 손주야가 거친 단계를 보여 준다 */
@Composable
fun ListeningScreen(s: ShopState, nav: Nav, pauseUntil: Long, onDone: () -> Unit, onCancel: () -> Unit) {
    val c = ShopTheme.colors
    if (s.agentBusy) {
        Frame(s, nav, dock = { BigButton("그만", onCancel, primary = false) }) {
            Title("찾고 있어요")
            s.steps.forEachIndexed { i, step ->
                val now = i == s.steps.lastIndex
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(if (now) c.voice else c.ok),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (now) "…" else "✓", color = c.surface, fontSize = 17.sp, fontWeight = FontWeight.Black) }
                    Text(if (now) step.doing else step.done, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (now) c.voice else c.ink)
                }
            }
        }
        return
    }
    Frame(s, nav, dock = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("다 말했어요", onDone, Modifier.weight(1f))
            BigButton("그만", onCancel, Modifier.weight(1f), primary = false)
        }
    }) {
        Box(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
            Rings()
            Box(Modifier.size(140.dp).clip(CircleShape).background(c.voice), contentAlignment = Alignment.Center) {
                MicIcon(c.voiceInk, Modifier.size(60.dp))
            }
        }
        Text("듣고 있어요", Modifier.fillMaxWidth(), fontSize = 22.sp, fontWeight = FontWeight.Black, color = c.ink, textAlign = TextAlign.Center)
        Text(
            "천천히 말씀하셔도 돼요.\n말이 멈추면 잠시 뒤 넘어가요.", Modifier.fillMaxWidth(),
            fontSize = 18.sp, lineHeight = 26.sp, color = c.inkSoft, textAlign = TextAlign.Center,
        )
        PauseBar(pauseUntil)
    }
}

/** 말이 멈춘 뒤 남은 시간. 다 줄면 듣기를 끝낸다(Listener 의 PAUSE_MS) */
@Composable
private fun PauseBar(pauseUntil: Long) {
    val c = ShopTheme.colors
    var left by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(pauseUntil) {
        val total = (pauseUntil - System.currentTimeMillis()).coerceAtLeast(1)
        while (pauseUntil > 0) {
            left = ((pauseUntil - System.currentTimeMillis()).toFloat() / total).coerceIn(0f, 1f)
            if (left <= 0f) break
            delay(50)
        }
        if (pauseUntil == 0L) left = 0f
    }
    Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50)).background(c.paper)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(left).clip(RoundedCornerShape(50)).background(c.voice))
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
            Modifier.size(140.dp).scale(1f + 0.6f * p).graphicsLayer { alpha = 0.5f * (1 - p) }
                .border(3.dp, c.voice, CircleShape),
        )
    }
}

// 3. 추천 상품 --------------------------------------------------------------

@Composable
fun ResultsScreen(
    s: ShopState, nav: Nav, speakingId: String?, onPick: (Int) -> Unit, onAdd: (Int) -> Unit, onStopReading: () -> Unit, onMic: () -> Unit,
) {
    val readingIndex = speakingId?.removePrefix(ITEM_PREFIX)?.toIntOrNull()?.takeIf { speakingId.startsWith(ITEM_PREFIX) }
    Frame(s, nav, dock = { MicDock(s, onMic) }) {
        Title("${s.label} · ${s.shown.size}개")
        if (speakingId != null) {
            ReadingBar(if (readingIndex != null) "${readingIndex + 1}번째를 읽고 있어요" else "읽어 드리고 있어요", onStopReading)
        }
        s.shown.forEachIndexed { i, p ->
            ProductCard(i, p, highlighted = i == readingIndex, inCart = s.cart.any { it.productId == p.id }, enabled = !s.agentBusy,
                onOpen = { onPick(i) }, onAdd = { onAdd(i) })
        }
        Text("상품과 가격은 공모전 시연용 가상 데이터예요.", fontSize = 13.sp, color = ShopTheme.colors.inkSoft)
    }
}

@Composable
private fun ProductCard(index: Int, p: ProductSummary, highlighted: Boolean, inCart: Boolean, enabled: Boolean, onOpen: () -> Unit, onAdd: () -> Unit) {
    val c = ShopTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (highlighted) c.speak.copy(alpha = 0.25f) else c.surface)
            .border(2.dp, if (highlighted) c.speakInk else c.line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "자세히", onClick = onOpen).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberBadge(index + 1)
            ProductImage(p.image, Modifier.size(52.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(formatWon(p.price), fontSize = 26.sp, fontWeight = FontWeight.Black, color = c.ink)
            Text("${p.arriveSpoken} 도착 · 별점 ${p.rating}", fontSize = 16.sp, color = c.inkSoft)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.badges.take(2).forEach { Badge(it) }
                if (p.options.isNotEmpty()) Badge("옵션 ${p.options.keys.joinToString("·")}", plain = true)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipButton("자세히", onOpen, enabled = enabled)
                ChipButton(if (inCart) "더 담기" else "담기", onAdd, enabled = enabled, accent = true)
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
    s: ShopState, nav: Nav,
    onQty: (Int) -> Unit, onOption: (String, String) -> Unit, onAdd: () -> Unit, onOthers: () -> Unit, onMic: () -> Unit,
) {
    val c = ShopTheme.colors
    val p = s.current ?: return
    Frame(s, nav, dock = {
        BigButton(if (p.soldOut) "품절이에요" else "장바구니에 담기", onAdd, enabled = !p.soldOut && !s.agentBusy)
        MicDock(s, onMic)
    }) {
        ProductImage(p.image, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Title(p.name)
        Text(formatWon(p.price), fontSize = 32.sp, fontWeight = FontWeight.Black, color = c.ink)
        Fact("배송비", if (p.shippingFee > 0) formatWon(p.shippingFee) else "없음")
        Fact("도착", p.arriveLabel)
        Fact("규격", p.spec)
        Text(p.description, fontSize = 19.sp, lineHeight = 28.sp, color = c.ink)
        p.reviewSummary?.let { Text("“$it”", fontSize = 17.sp, lineHeight = 25.sp, color = c.inkSoft) }
        p.options.forEach { axis ->
            // 담으려는데 이 옵션이 빠졌으면 눈에 띄게 한다(음성으로도 같은 안내를 읽는다)
            val missing = s.missingOption == axis.name
            Text(
                if (missing) "${axis.name}을(를) 골라 주세요" else axis.name,
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (missing) c.speak else c.surface).padding(horizontal = if (missing) 10.dp else 0.dp, vertical = 2.dp),
                fontSize = 19.sp, fontWeight = FontWeight.Black, color = if (missing) c.speakInk else c.ink,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                axis.values.forEach { v ->
                    ChipButton(
                        v.value + (if (v.priceAdd > 0) " (+${formatWon(v.priceAdd)})" else "") + if (v.soldOut) " 품절" else "",
                        onClick = { onOption(axis.name, v.value) },
                        selected = s.selected[axis.name] == v.value,
                        enabled = !v.soldOut && !s.agentBusy,
                    )
                }
            }
        }
        QtyStepper(s.qty, onQty)
        if (s.shown.isNotEmpty()) ChipButton("← 추천 목록으로", onOthers)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val c = ShopTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 18.sp, color = c.inkSoft)
        Spacer(Modifier.size(12.dp))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = c.ink, textAlign = TextAlign.End)
    }
}

// 5. 장바구니 ---------------------------------------------------------------

@Composable
fun CartScreen(s: ShopState, nav: Nav, onQty: (String, Int) -> Unit, onRemove: (String) -> Unit, onCheckout: () -> Unit, onMic: () -> Unit) {
    val c = ShopTheme.colors
    Frame(s, nav, dock = {
        if (s.cart.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("합계", fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.ink)
                Text(formatWon(s.cart.total()), fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.ink)
            }
            BigButton("주문하기", onCheckout, enabled = !s.agentBusy)
        }
        MicDock(s, onMic)
    }) {
        Title(if (s.cart.isEmpty()) "장바구니가 비어 있어요" else "장바구니 · ${s.cart.size}가지")
        s.cart.forEachIndexed { i, l -> CartRow(i, l, problem = s.cartProblem == l.lineId, enabled = !s.agentBusy, onQty = onQty, onRemove = onRemove) }
    }
}

@Composable
private fun CartRow(index: Int, l: CartLine, problem: Boolean, enabled: Boolean, onQty: (String, Int) -> Unit, onRemove: (String) -> Unit) {
    val c = ShopTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (problem) c.speak.copy(alpha = 0.3f) else c.surface)
            .border(2.dp, if (problem) c.speakInk else c.line, shape).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NumberBadge(index + 1)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(l.name, style = MaterialTheme.typography.titleMedium, color = c.ink)
            if (l.options.isNotEmpty()) Text(l.options.values.joinToString(" "), fontSize = 16.sp, color = c.inkSoft)
            if (problem) Text("지금 주문할 수 없어요. 빼거나 다른 걸로 바꿔 주세요.", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.speakInk)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChipButton("−", { onQty(l.lineId, -1) }, enabled = enabled && l.qty > 1)
                    Text("${l.qty}", fontSize = 22.sp, fontWeight = FontWeight.Black, color = c.ink)
                    ChipButton("+", { onQty(l.lineId, 1) }, enabled = enabled && l.qty < 9)
                }
                Text(formatWon(l.total), fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.ink)
            }
            ChipButton("빼기", { onRemove(l.lineId) }, enabled = enabled)
        }
    }
}

// 6. 주문 확인 --------------------------------------------------------------

@Composable
fun ConfirmScreen(s: ShopState, nav: Nav, onYes: () -> Unit, onNo: () -> Unit, onMic: () -> Unit) {
    val c = ShopTheme.colors
    if (s.cart.isEmpty()) return
    val total = s.cart.total()
    Frame(s, nav, dock = {
        // "아니요"도 같은 크기. 작은 취소 버튼은 잘못 누르기 쉽다.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("네, 주문", onYes, Modifier.weight(1f), minHeight = 76.dp, enabled = !s.agentBusy)
            BigButton("아니요", onNo, Modifier.weight(1f), primary = false, minHeight = 76.dp)
        }
        MicDock(s, onMic)
    }) {
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
            Text(formatWon(total), fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.Black, color = c.surface)
            Text(readWon(total), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.speak)
        }
    }
}

// 7. 주문 완료 --------------------------------------------------------------

@Composable
fun DoneScreen(s: ShopState, nav: Nav, onMic: () -> Unit) {
    val c = ShopTheme.colors
    Frame(s, nav, dock = { MicDock(s, onMic, label = "더 살 것 말하기") }) {
        Column(
            Modifier.fillMaxWidth().padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(112.dp).clip(CircleShape).background(c.ok), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(56.dp)) {
                    val u = size.width / 24f
                    val path = Path().apply { moveTo(5 * u, 12.5f * u); lineTo(9.5f * u, 17 * u); lineTo(19 * u, 7.5f * u) }
                    drawPath(path, c.surface, style = Stroke(3 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            Title("주문했어요")
            s.orderTotal?.let { Text("${s.orderIds.size}가지 · ${formatWon(it)}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.ink) }
            Text("차례로 도착해요. 가족께 알렸어요.", fontSize = 18.sp, color = c.inkSoft, textAlign = TextAlign.Center)
            if (s.orderIds.isNotEmpty()) Text("주문번호 ${s.orderIds.first()}" + if (s.orderIds.size > 1) " 외" else "", fontSize = 16.sp, color = c.inkSoft)
        }
    }
}

// 8. 주문 내역 --------------------------------------------------------------

@Composable
fun HistoryScreen(s: ShopState, nav: Nav, onReorder: (PastOrder) -> Unit, onMic: () -> Unit) {
    val c = ShopTheme.colors
    Frame(s, nav, dock = { MicDock(s, onMic) }) {
        Title(if (s.pastOrders.isEmpty()) "주문하신 것이 없어요" else "주문 내역")
        var lastDate = ""
        s.pastOrders.forEach { o ->
            if (o.date != lastDate) {
                Text(koDate(o.date), fontSize = 17.sp, fontWeight = FontWeight.Black, color = c.inkSoft)
                lastDate = o.date
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(2.dp, c.line, RoundedCornerShape(16.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(o.name, style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text("${o.qty}개" + if (o.options.isEmpty()) "" else " · " + o.options.values.joinToString(" "), fontSize = 16.sp, color = c.inkSoft)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(formatWon(o.total), fontSize = 19.sp, fontWeight = FontWeight.Black, color = c.ink)
                    ChipButton("또 담기", { onReorder(o) }, enabled = !s.agentBusy, accent = true)
                }
            }
        }
    }
}

/** "2026-09-12" → "9월 12일" */
private fun koDate(iso: String): String {
    val parts = iso.split("-")
    return if (parts.size == 3) "${parts[1].toInt()}월 ${parts[2].toInt()}일" else iso
}
