package com.agentpjt.shop.ui.shop

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpjt.shop.ui.theme.ShopTheme

/** 기능별 대표 문장 하나씩: 찾기, 예산, 지난 구매, 장바구니 */
private val EXAMPLES = listOf("아침에 먹을 만한 거 찾아 줘", "라디오 하나 오만 원 안쪽으로", "지난번에 산 거 또 사 줘", "장바구니 보여 줘")

/** "?" 도움말. 아래에서 올라오고, 예시를 누르면 그 말로 바로 시작한다 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(enabled: Boolean, onExample: (String) -> Unit, onClose: () -> Unit) {
    val c = ShopTheme.colors
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("이렇게 말해 보세요", fontSize = 24.sp, fontWeight = FontWeight.Black, color = c.ink)
            EXAMPLES.forEach { text ->
                Text(
                    "“$text”",
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(2.dp, c.line, RoundedCornerShape(14.dp))
                        .clickable(enabled = enabled, role = Role.Button) { onExample(text) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (enabled) c.ink else c.inkSoft,
                )
            }
            Text(
                if (enabled) "누르면 그 말을 한 것처럼 손주야가 알아봐요." else "AI 가 준비되면 눌러서 시작할 수 있어요.",
                fontSize = 17.sp, color = c.inkSoft,
            )
            BigButton("닫기", onClose, primary = false)
        }
    }
}
