package com.agentpjt.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentpjt.shop.llm.BackendKind

@Composable
fun LlmTestScreen(vm: LlmTestViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val busy = s.loading || s.generating

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Gemma 탑재 테스트", style = MaterialTheme.typography.headlineSmall)

        // 모델 파일
        if (s.modelFiles.isEmpty()) {
            Card {
                SelectionContainer {
                    Text(
                        "모델 파일(.litertlm)이 없습니다. PC에서:\n" +
                            "adb push gemma-4-E2B-it-gpu.litertlm ${vm.modelDir.absolutePath}/",
                        Modifier.padding(12.dp),
                    )
                }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.modelFiles.forEach { f ->
                    FilterChip(
                        selected = f == s.selectedModel,
                        onClick = { vm.selectModel(f) },
                        enabled = !busy,
                        label = { Text("${f.name} (${f.length() / 1_048_576}MB)") },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackendKind.entries.forEach { b ->
                FilterChip(selected = b == s.backend, onClick = { vm.selectBackend(b) }, enabled = !busy, label = { Text(b.name) })
            }
            OutlinedButton(onClick = vm::refreshModels, enabled = !busy) { Text("새로고침") }
            Button(onClick = vm::load, enabled = !busy && s.selectedModel != null) { Text(if (s.loading) "로드 중…" else "로드") }
        }
        s.loadMs?.let { Text("로드 완료: ${it}ms (${s.backend})") }

        HorizontalDivider()

        // 프롬프트
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { p ->
                OutlinedButton(onClick = { input = p.prompt; vm.run(p.prompt, p.label) }, enabled = s.loaded && !busy) {
                    Text(p.label)
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("프롬프트") },
            minLines = 3,
        )
        Button(onClick = { vm.run(input, "free") }, enabled = s.loaded && !busy && input.isNotBlank()) {
            Text(if (s.generating) "생성 중…" else "보내기")
        }

        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (s.metrics.isNotEmpty()) Text(s.metrics, style = MaterialTheme.typography.bodySmall)
        if (s.output.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer { Text(s.output, Modifier.padding(12.dp)) }
            }
        }
    }
}
