package com.agentpjt.shop.ui.shop

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.agentpjt.shop.shop.Screen
import com.agentpjt.shop.shop.ShopViewModel
import com.agentpjt.shop.ui.LlmTestScreen
import com.agentpjt.shop.ui.theme.ShopTheme
import com.agentpjt.shop.voice.SpeakerStatus

@Composable
fun ShopApp(vm: ShopViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val speaking by vm.speaker.speaking.collectAsStateWithLifecycle()
    val ttsStatus by vm.speaker.status.collectAsStateWithLifecycle()
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

    BackHandler(enabled = s.screen != Screen.Home) { vm.back() }

    Column(Modifier.fillMaxSize().background(c.surface).safeDrawingPadding()) {
        if (ttsStatus == SpeakerStatus.NO_KOREAN || ttsStatus == SpeakerStatus.FAILED) {
            Notice("읽어 주기를 쓸 수 없어요. 휴대폰 설정의 글자 읽어주기(TTS)에서 한국어 음성을 설치해 주세요.")
        }
        if (s.micDenied) Notice("말로 하려면 마이크 권한이 필요해요. 화면을 눌러서도 이용할 수 있어요.")
        if (s.offline) Notice("인터넷에 연결되지 않아 상품을 불러오지 못했어요. 연결을 확인해 주세요.")
        Box(Modifier.weight(1f)) {
            when (s.screen) {
                Screen.Home -> HomeScreen(s, onMic = onMic, onExample = vm::submitText, onDevMenu = vm::openDevMenu, onCart = vm::openCart, onHistory = vm::openHistory)
                Screen.Listening -> ListeningScreen(s, onDone = vm::finishListening, onCancel = vm::cancelListening)
                Screen.Results -> ResultsScreen(s, speaking, onPick = vm::pick, onStopReading = vm::stopReading, onMic = onMic)
                Screen.Detail -> DetailScreen(
                    s, onQty = vm::changeQty, onOption = vm::chooseOption, onAdd = vm::addCurrent,
                    onOthers = { vm.go(Screen.Results) }, onMic = onMic,
                )
                Screen.Cart -> CartScreen(s, onQty = vm::changeLineQty, onRemove = vm::removeLine, onCheckout = vm::checkout, onMic = onMic)
                Screen.History -> HistoryScreen(s, onReorder = vm::reorder, onMic = onMic)
                Screen.Confirm -> ConfirmScreen(s, onYes = vm::placeOrder, onNo = vm::cancelOrder, onMic = onMic)
                Screen.Done -> DoneScreen(s, onHome = { vm.go(Screen.Home) })
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
