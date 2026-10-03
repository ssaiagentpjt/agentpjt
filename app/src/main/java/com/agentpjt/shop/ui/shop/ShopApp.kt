package com.agentpjt.shop.ui.shop

import androidx.activity.compose.BackHandler
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

    BackHandler(enabled = s.screen != Screen.Home) { vm.back() }

    Column(Modifier.fillMaxSize().background(c.surface).safeDrawingPadding()) {
        if (ttsStatus == SpeakerStatus.NO_KOREAN || ttsStatus == SpeakerStatus.FAILED) {
            Text(
                "읽어 주기를 쓸 수 없어요. 휴대폰 설정의 글자 읽어주기(TTS)에서 한국어 음성을 설치해 주세요.",
                Modifier.fillMaxWidth().background(c.speak).padding(12.dp),
                color = c.speakInk, fontSize = 16.sp,
            )
        }
        Box(Modifier.weight(1f)) {
            when (s.screen) {
                Screen.Home -> HomeScreen(onMic = vm::startListening, onExample = vm::startListening, onDevMenu = vm::openDevMenu)
                Screen.Listening -> ListeningScreen(s, onDone = vm::finishListening, onCancel = { vm.go(Screen.Home) })
                Screen.Results -> ResultsScreen(s, speaking, onPick = vm::pick, onStopReading = vm::stopReading)
                Screen.Detail -> DetailScreen(s, onQty = vm::changeQty, onOrder = { vm.go(Screen.Confirm) }, onOthers = { vm.go(Screen.Results) })
                Screen.Confirm -> ConfirmScreen(s, onYes = { vm.go(Screen.Done) }, onNo = { vm.go(Screen.Detail) })
                Screen.Done -> DoneScreen(s, onHome = { vm.go(Screen.Home) })
                Screen.DevLlm -> LlmTestScreen()
            }
        }
    }
}
