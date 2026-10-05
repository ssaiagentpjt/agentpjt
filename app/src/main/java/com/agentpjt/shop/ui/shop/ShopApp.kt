package com.agentpjt.shop.ui.shop

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentpjt.shop.shop.AiStatus
import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.SetupPhase
import com.agentpjt.shop.shop.ShopViewModel
import com.agentpjt.shop.ui.LlmTestScreen
import com.agentpjt.shop.ui.theme.ShopTheme
import com.agentpjt.shop.voice.SpeakerStatus

@Composable
fun ShopApp(vm: ShopViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val speaking by vm.speaker.speaking.collectAsStateWithLifecycle()
    val ttsStatus by vm.speaker.status.collectAsStateWithLifecycle()
    val pauseUntil by vm.listener.pauseUntil.collectAsStateWithLifecycle()
    val c = ShopTheme.colors
    val context = LocalContext.current

    // 마이크 권한: 처음 누를 때 묻고, 허락하면 바로 듣기 시작한다. 거절해도 터치로는 계속 쓸 수 있다.
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setMicDenied(!granted)
        if (granted) vm.startListening()
    }
    val onMic: () -> Unit = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startListening()
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BackHandler(enabled = s.screen != Screen.Home || s.typing) { vm.back() }

    // 첫 실행: AI 파일이 준비되기 전에는 인트로·설치 화면만 보인다("나중에 받기"를 고르면 쇼핑 화면으로)
    if (s.setup.phase != SetupPhase.DONE && !s.setup.later) {
        Box(Modifier.fillMaxSize().background(if (s.setup.phase == SetupPhase.BOOT) c.voice else c.surface).safeDrawingPadding()) {
            when (s.setup.phase) {
                SetupPhase.BOOT -> IntroScreen()
                SetupPhase.NEED, SetupPhase.FAILED -> InstallScreen(s.setup, onInstall = vm::installModel)
                SetupPhase.DOWNLOADING, SetupPhase.VERIFYING -> DownloadScreen(s.setup, onLater = vm::installLater)
                SetupPhase.DONE -> Unit
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(c.surface).safeDrawingPadding()) {
        if (ttsStatus == SpeakerStatus.NO_KOREAN || ttsStatus == SpeakerStatus.FAILED) {
            Notice("읽어 주기를 쓸 수 없어요. 휴대폰 설정의 글자 읽어주기(TTS)에서 한국어 음성을 설치해 주세요.")
        }
        if (s.micDenied) Notice("말로 하려면 마이크 권한이 필요해요. 화면을 눌러서도 이용할 수 있어요.")
        if (s.offline) Notice("인터넷에 연결되지 않아 상품을 불러오지 못했어요. 연결을 확인해 주세요.")
        if (s.setup.later && s.setup.total > 0) {
            Notice("AI 파일을 받고 있어요 ${s.setup.done * 100 / s.setup.total}%. 다 받으면 말로 하실 수 있어요.")
        } else if (s.ai == AiStatus.UNAVAILABLE) {
            Notice("지금은 말로 도와 드리기 어려워요. 화면을 눌러 이용해 주세요.")
        }
        val nav = Nav(
            home = { vm.go(Screen.Home) }, cart = vm::openCart, history = vm::openHistory, devMenu = vm::openDevMenu,
            openTyping = vm::openTyping, closeTyping = vm::closeTyping, sendTyped = vm::submitTyped, help = vm::openHelp,
        )
        if (s.helpOpen) HelpSheet(enabled = s.ai == AiStatus.READY && !s.agentBusy, onExample = vm::tryExample, onClose = vm::closeHelp)
        // 화면이 뚝 바뀌면 무엇이 바뀌었는지 놓치기 쉬워서 짧게 겹쳐 바꾼다. 움직임이 큰 미끄러짐은 쓰지 않는다
        Crossfade(s.screen, Modifier.weight(1f), animationSpec = tween(200), label = "screen") { screen ->
            when (screen) {
                Screen.Home -> HomeScreen(s, nav, onMic = onMic, onLink = vm::openLink)
                Screen.Listening -> ListeningScreen(s, nav, pauseUntil, onDone = vm::finishListening, onCancel = vm::cancelListening)
                Screen.Results -> ResultsScreen(s, nav, speaking, onPick = vm::pick, onAdd = vm::addShown, onStopReading = vm::stopReading, onMic = onMic)
                Screen.Detail -> DetailScreen(
                    s, nav, onQty = vm::changeQty, onOption = vm::chooseOption, onAdd = vm::addCurrent,
                    onOthers = { vm.go(Screen.Results) }, onMic = onMic,
                )
                Screen.Cart -> CartScreen(s, nav, onQty = vm::changeLineQty, onRemove = vm::removeLine, onCheckout = vm::checkout, onMic = onMic)
                Screen.History -> HistoryScreen(s, nav, onReorder = vm::reorder, onMic = onMic)
                Screen.Confirm -> ConfirmScreen(s, nav, onYes = vm::placeOrder, onNo = vm::cancelOrder, onMic = onMic)
                Screen.Done -> DoneScreen(s, nav, onMic = onMic)
                Screen.DevLlm -> LlmTestScreen()
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    val c = ShopTheme.colors
    Text(text, Modifier.fillMaxWidth().background(c.speak).padding(12.dp), color = c.speakInk, fontSize = 16.sp)
}
