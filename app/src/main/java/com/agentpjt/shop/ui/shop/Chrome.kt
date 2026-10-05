package com.agentpjt.shop.ui.shop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpjt.shop.shop.AiStatus
import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopState
import com.agentpjt.shop.ui.theme.ShopTheme
import kotlinx.coroutines.delay

/** 일반 휴대폰 폭(360~412dp) 기준 한 줄 배치. 넓은 화면(폴드 펼침·태블릿)은 이 폭으로 묶어 가운데 둔다 */
val MAX_CONTENT_WIDTH = 480.dp

/**
 * 쇼핑 화면 공통 틀: 맨 위 버튼 줄 → 대화 띠 → 내용(세로 스크롤) → 아래 고정 영역(말하기 버튼 등).
 * 말하기 버튼은 모든 화면 같은 자리(맨 아래)에 둔다.
 */
@Composable
fun ShopFrame(
    s: ShopState,
    nav: Nav,
    dock: @Composable ColumnScope.() -> Unit,
    /** false 면 내용이 스스로 스크롤한다(처음 화면 대화 기록의 LazyColumn) */
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ShopTheme.colors
    // 말하는 중(듣기·판단)에는 글자 입력을 띄우지 않는다
    val canType = s.screen != Screen.Listening
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxSize()) {
            TopBar(s, nav)
            // 처음 화면은 대화 기록이 같은 일을 하므로 대화 띠를 두지 않는다
            if (s.screen != Screen.Home) TalkStrip(s)
            if (scrollable) {
                val scroll = rememberScrollState()
                Column(
                    Modifier.weight(1f).minimalScrollbar(scroll, c.inkSoft).verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = content,
                )
            } else {
                Column(Modifier.weight(1f), content = content)
            }
            HorizontalDivider(color = c.line)
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (canType && s.typing) {
                    TypingBar(nav)
                } else {
                    dock()
                    if (canType) TypeButton(enabled = s.ai == AiStatus.READY && !s.agentBusy, onClick = nav.openTyping)
                }
            }
        }
    }
}

/** 말하기 버튼 아래 한 단계 작은 "글자로 쓰기". 말하기가 어려운 곳이나 가족이 대신 쓸 때 */
@Composable
private fun TypeButton(enabled: Boolean, onClick: () -> Unit) {
    val c = ShopTheme.colors
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp), border = BorderStroke(2.dp, c.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink),
    ) {
        KeyboardIcon(c.ink)
        Text("  글자로 쓰기", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** 입력 칸 + 보내기 + 말로 하기. 자판 바로 위에 붙는다(ShopFrame 의 imePadding) */
@Composable
private fun TypingBar(nav: Nav) {
    val c = ShopTheme.colors
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val send = { if (text.isNotBlank()) { nav.sendTyped(text); text = "" } }
    androidx.compose.foundation.text.BasicTextField(
        value = text, onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp))
            .border(3.dp, c.voice, RoundedCornerShape(16.dp)).background(c.surface)
            .focusRequester(focus).padding(horizontal = 16.dp, vertical = 16.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.ink),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.voice),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
        decorationBox = { inner ->
            if (text.isEmpty()) Text("무엇을 사 드릴까요?", fontSize = 22.sp, color = c.inkSoft)
            inner()
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton("보내기", { send() }, Modifier.weight(1f), enabled = text.isNotBlank())
        BigButton("말로 하기", nav.closeTyping, Modifier.weight(1f), primary = false)
    }
}

/** 자판 그림(24 격자) */
@Composable
private fun KeyboardIcon(tint: Color) {
    Canvas(Modifier.size(26.dp)) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(tint, topLeft = Offset(2.5f * u, 6 * u), size = androidx.compose.ui.geometry.Size(19 * u, 12 * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * u), style = stroke)
        listOf(6f, 10f, 14f, 18f).forEach { x -> drawCircle(tint, radius = 1.1f * u, center = Offset(x * u, 10 * u)) }
        drawLine(tint, Offset(7 * u, 14 * u), Offset(17 * u, 14 * u), strokeWidth = 2f * u, cap = StrokeCap.Round)
    }
}

/** 맨 위: 이름(누르면 처음 화면) + 그림·글자가 함께 있는 큰 버튼(장바구니·주문 내역) */
@Composable
private fun TopBar(s: ShopState, nav: Nav) {
    val c = ShopTheme.colors
    // 담거나 빼면 장바구니 버튼을 잠깐 강조한다(말로 바꿔도 바뀐 것이 보이게)
    val signature = s.cart.map { it.lineId to it.qty }
    var seen by remember { mutableStateOf(signature) }
    var bump by remember { mutableStateOf(false) }
    LaunchedEffect(signature) {
        if (signature != seen) {
            seen = signature
            bump = true
            delay(1500)
            bump = false
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 이름을 길게 누르면 개발자 메뉴(Gemma 테스트). 어르신이 우연히 누를 일은 드물다
        Text(
            "손주야", Modifier.pointerInput(Unit) { detectTapGestures(onTap = { nav.home() }, onLongPress = { nav.devMenu() }) },
            fontSize = 22.sp, fontWeight = FontWeight.Black, color = c.ink,
        )
        // 말하는 법 도움말. 이름 바로 옆 같은 자리
        Box(
            Modifier.padding(start = 8.dp).size(44.dp).clip(CircleShape).border(2.dp, c.line, CircleShape)
                .clickable(role = Role.Button, onClickLabel = "도움말", onClick = nav.help),
            contentAlignment = Alignment.Center,
        ) { Text("?", fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.ink) }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavButton("장바구니", on = s.screen == Screen.Cart, bump = bump, badge = s.cart.size, onClick = nav.cart) { CartIcon(it) }
            NavButton("주문 내역", on = s.screen == Screen.History, bump = false, badge = 0, onClick = nav.history) { ReceiptIcon(it) }
        }
    }
}

@Composable
private fun NavButton(label: String, on: Boolean, bump: Boolean, badge: Int, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val c = ShopTheme.colors
    val fg = if (on) c.surface else c.ink
    val bg = when {
        on -> c.ink
        bump -> c.voiceTint
        else -> c.surface
    }
    Box {
        Column(
            Modifier.widthIn(min = 80.dp).heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                .border(2.dp, if (on) c.ink else if (bump) c.voice else c.line, RoundedCornerShape(14.dp))
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            icon(fg)
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = fg)
        }
        if (badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-8).dp).size(28.dp).clip(CircleShape).background(c.voice),
                contentAlignment = Alignment.Center,
            ) { Text("$badge", fontSize = 15.sp, fontWeight = FontWeight.Black, color = c.voiceInk) }
        }
    }
}

/**
 * 대화 띠: 들은 말과 손주야의 말. 받아쓰기가 틀려도 어르신이 바로 알아챌 수 있게 늘 보인다.
 * 손주야가 묻거나 답하는 말(ask·answer)은 노란 띠로 눈에 띄게 한다.
 */
@Composable
private fun TalkStrip(s: ShopState) {
    val c = ShopTheme.colors
    val heard = s.heard.ifEmpty { s.lastHeard }
    val said = s.bubble.ifEmpty { s.said }
    if (heard.isEmpty() && said.isEmpty()) return
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.paper).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (heard.isNotEmpty()) {
            Row {
                Text(if (s.heardTyped) "쓴 말  " else "들은 말  ", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.inkSoft)
                Text(heard, fontSize = 18.sp, lineHeight = 26.sp, color = if (s.screen == Screen.Listening && !s.agentBusy) c.voice else c.ink)
            }
        }
        if (said.isNotEmpty() && !(s.screen == Screen.Listening && !s.agentBusy)) {
            val ask = s.bubble.isNotEmpty()
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (ask) c.speak else c.paper).padding(if (ask) 8.dp else 0.dp),
            ) {
                Text("손주야  ", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (ask) c.speakInk else c.inkSoft)
                Text(said, fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, color = if (ask) c.speakInk else c.ink)
            }
        }
    }
}

/** 아래 고정 말하기 버튼. 모든 쇼핑 화면 같은 자리 */
@Composable
fun MicDock(s: ShopState, onMic: () -> Unit, label: String = "누르고 말하기") {
    val c = ShopTheme.colors
    val ready = s.ai == AiStatus.READY && !s.agentBusy
    Button(
        onClick = onMic, enabled = ready,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.voice, contentColor = c.voiceInk),
    ) {
        MicIcon(if (ready) c.voiceInk else c.inkSoft, Modifier.size(30.dp))
        Text(
            "  " + when {
                s.ai == AiStatus.READY -> label
                s.setup.later -> "AI 파일을 받는 중이에요"
                s.ai == AiStatus.LOADING -> "AI 준비 중"
                else -> "화면을 눌러 이용해 주세요"
            },
            fontSize = 22.sp, fontWeight = FontWeight.Black,
        )
    }
}

/** 장바구니 그림(24 격자) */
@Composable
private fun CartIcon(tint: Color) {
    Canvas(Modifier.size(28.dp)) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path().apply {
            moveTo(3 * u, 4 * u); lineTo(5 * u, 4 * u); lineTo(7.2f * u, 14.2f * u)
            lineTo(18 * u, 14.2f * u); lineTo(21 * u, 8 * u); lineTo(6.2f * u, 8 * u)
        }
        drawPath(p, tint, style = stroke)
        drawCircle(tint, radius = 1.5f * u, center = Offset(9.5f * u, 19.5f * u))
        drawCircle(tint, radius = 1.5f * u, center = Offset(17 * u, 19.5f * u))
    }
}

/** 영수증 그림(24 격자) */
@Composable
private fun ReceiptIcon(tint: Color) {
    Canvas(Modifier.size(28.dp)) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path().apply {
            moveTo(6 * u, 3 * u); lineTo(18 * u, 3 * u); lineTo(18 * u, 21 * u); lineTo(15 * u, 19 * u)
            lineTo(12 * u, 21 * u); lineTo(9 * u, 19 * u); lineTo(6 * u, 21 * u); close()
            moveTo(9 * u, 8 * u); lineTo(15 * u, 8 * u)
            moveTo(9 * u, 12 * u); lineTo(15 * u, 12 * u)
            moveTo(9 * u, 16 * u); lineTo(13 * u, 16 * u)
        }
        drawPath(p, tint, style = stroke)
    }
}

/** 큰 칩 버튼(옵션 값, 담기, 빼기 등). 고른 값은 진하게, 품절은 비활성 + "품절" */
@Composable
fun ChipButton(text: String, onClick: () -> Unit, selected: Boolean = false, enabled: Boolean = true, accent: Boolean = false) {
    val c = ShopTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Text(
        text,
        Modifier.clip(shape)
            .background(if (selected) c.voice else c.surface)
            .border(BorderStroke(2.dp, if (selected || accent) c.voice else c.line), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        fontSize = 18.sp, fontWeight = FontWeight.Black,
        color = when {
            selected -> c.voiceInk
            !enabled -> c.inkSoft
            accent -> c.voice
            else -> c.ink
        },
    )
}
