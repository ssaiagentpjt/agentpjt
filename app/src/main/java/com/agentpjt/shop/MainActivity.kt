package com.agentpjt.shop

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.svg.SvgDecoder
import com.agentpjt.shop.shop.ShopViewModel
import com.agentpjt.shop.ui.shop.ShopApp
import com.agentpjt.shop.ui.theme.ShopTheme

class MainActivity : ComponentActivity() {

    private val vm: ShopViewModel by viewModels()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        devEval(intent)
    }

    /** 개발용: adb shell am start -n com.agentpjt.shop/.MainActivity --ez devEval true (DEV_HOOKS 빌드에서만) */
    private fun devEval(intent: Intent?) {
        if (BuildConfig.DEV_HOOKS && intent?.getBooleanExtra("devEval", false) == true) vm.runDevEval()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        devEval(intent)
        setContent {
            // 서버 상품 이미지가 지금은 대분류 SVG 아이콘이라 SVG 디코더를 등록한다(네트워크는 coil-network-okhttp)
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build()
            }
            ShopTheme { ShopApp() }
        }
    }
}
