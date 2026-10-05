package com.agentpjt.shop.ui.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.agentpjt.shop.R
import com.agentpjt.shop.llm.ModelInstaller
import com.agentpjt.shop.shop.Setup
import com.agentpjt.shop.shop.SetupPhase
import com.agentpjt.shop.ui.theme.ShopTheme
import java.util.Locale

// 첫 실행 화면: 인트로 → AI 파일 설치 안내 → 다운로드. UI 시안(첫 실행 A·B·C)과 같은 배치다.

/** 인트로. 로고와 함께 AI 를 올리는 동안만 보인다 */
@Composable
fun IntroScreen() {
    val c = ShopTheme.colors
    Column(
        Modifier.fillMaxSize().background(c.voice),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(132.dp).clip(RoundedCornerShape(36.dp)).background(c.voice), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_logo_foreground), contentDescription = null, Modifier.fillMaxSize())
        }
        Text("손주야", fontSize = 38.sp, fontWeight = FontWeight.Black, color = c.voiceInk, modifier = Modifier.padding(top = 8.dp))
        Text("말로 하는 장보기", fontSize = 19.sp, color = c.voiceInk.copy(alpha = 0.9f))
        Spacer(Modifier.height(40.dp))
        CircularProgressIndicator(color = c.voiceInk, strokeWidth = 4.dp, modifier = Modifier.size(40.dp))
        Text("준비하고 있어요", fontSize = 18.sp, color = c.voiceInk.copy(alpha = 0.9f), modifier = Modifier.padding(top = 12.dp))
    }
}

/** 설치 안내(처음, 실패). 받기 전에 크기·저장 공간·연결을 보여 준다 */
@Composable
fun InstallScreen(setup: Setup, onInstall: () -> Unit) {
    val c = ShopTheme.colors
    SetupPage(bottom = { BigButton(if (setup.phase == SetupPhase.FAILED) "다시 받기" else "설치하기", onInstall) }) {
        Text("처음 한 번,\nAI 파일을 받아야 해요", fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, color = c.ink)
        Text(
            "손주야가 말을 알아듣고 상품을 고르려면 휴대폰 안에 AI 파일이 있어야 해요. 한 번 받으면 다시 받지 않아요.",
            fontSize = 18.sp, lineHeight = 27.sp, color = c.inkSoft,
        )
        setup.error?.let { Warn(it) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SetupFact("파일 크기", gb(ModelInstaller.SIZE))
            SetupFact("남은 저장 공간", gb(setup.freeBytes))
            SetupFact("연결", when {
                !setup.connected -> "연결 안 됨"
                setup.unmetered -> "Wi-Fi 연결됨"
                else -> "모바일 데이터"
            })
        }
        if (setup.connected && !setup.unmetered) Warn("모바일 데이터로 받으면 요금이 나올 수 있어요. 가능하면 Wi-Fi 에서 받아 주세요.")
        Note("가족이나 도와주시는 분이 함께 해 주셔도 좋아요.")
    }
}

/** 다운로드 게이지. 시스템 다운로더가 받으니 화면을 꺼도 계속 받는다 */
@Composable
fun DownloadScreen(setup: Setup, onLater: () -> Unit) {
    val c = ShopTheme.colors
    val verifying = setup.phase == SetupPhase.VERIFYING
    val ratio = if (setup.total > 0) (setup.done.toFloat() / setup.total).coerceIn(0f, 1f) else 0f
    SetupPage(bottom = { if (!verifying) BigButton("나중에 받기", onLater, primary = false) }) {
        Text(if (verifying) "파일을 검사하고 있어요" else "AI 파일을 받고 있어요", fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, color = c.ink)
        Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(50)).border(2.dp, c.line, RoundedCornerShape(50)).background(c.paper)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(ratio).clip(RoundedCornerShape(50)).background(c.voice))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text("${(ratio * 100).toInt()}%", fontSize = 44.sp, fontWeight = FontWeight.Black, color = c.ink)
            Text("${gb(setup.done)} / ${gb(setup.total)}", fontSize = 18.sp, color = c.inkSoft)
        }
        Text(
            when {
                verifying -> "거의 다 됐어요"
                setup.waiting -> "인터넷 연결을 기다리고 있어요"
                setup.etaSec != null -> "약 ${eta(setup.etaSec)} 남았어요"
                else -> "남은 시간을 재고 있어요"
            },
            fontSize = 19.sp, color = c.inkSoft,
        )
        Note("다른 앱을 쓰거나 화면을 꺼도 계속 받아요. 알림줄에서도 진행을 볼 수 있어요.")
    }
}

@Composable
private fun SetupPage(bottom: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) { content() }
        Column(Modifier.padding(bottom = 16.dp, top = 8.dp)) { bottom() }
    }
}

@Composable
private fun SetupFact(label: String, value: String) {
    val c = ShopTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 19.sp, color = c.inkSoft)
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = c.ink, textAlign = TextAlign.End)
    }
}

@Composable
private fun Note(text: String) {
    val c = ShopTheme.colors
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.paper).padding(14.dp), fontSize = 17.sp, lineHeight = 25.sp, color = c.inkSoft)
}

@Composable
private fun Warn(text: String) {
    val c = ShopTheme.colors
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.speak).padding(14.dp), fontSize = 17.sp, lineHeight = 25.sp, color = c.speakInk)
}

private fun gb(bytes: Long) = String.format(Locale.KOREA, "%.1fGB", bytes / 1_000_000_000.0)

private fun eta(sec: Long) = when {
    sec < 60 -> "1분"
    sec < 3600 -> "${(sec + 59) / 60}분"
    else -> "${sec / 3600}시간 ${(sec % 3600) / 60}분"
}
