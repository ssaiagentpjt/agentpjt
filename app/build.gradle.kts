import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 서버 주소와 API 키는 커밋하지 않는 local.properties 에서 읽는다(shop.apiKey=..., shop.apiBaseUrl=...).
// 키는 APK 에서 꺼낼 수 있다 — 시연용 목업 서버라 감수한다.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun local(key: String, default: String) = localProps.getProperty(key) ?: default

// 공개 APK 서명 키. CI(app-release.yml)가 GitHub Secret 에서 환경변수로 넣는다. 없으면(로컬) release 는 서명 없이 빌드된다.
// 같은 키로 계속 내야 새 버전이 기존 설치 위에 업데이트된다 — 키 파일은 저장소 밖(C:\dev\secrets)에 둔다.
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")

android {
    namespace = "com.agentpjt.shop"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.agentpjt.shop"
        // litertlm-android 0.17.1 AAR 매니페스트의 minSdkVersion
        minSdk = 24
        targetSdk = 36
        // 태그(app-v0.2.0)와 실행 번호를 CI 가 넘긴다. 업데이트 설치는 versionCode 가 커져야 한다
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("appVersion") as String?) ?: "0.1.0"
        buildConfigField("String", "SHOP_API_BASE_URL", "\"${local("shop.apiBaseUrl", "https://shop-api.bomun.dev")}\"")
        buildConfigField("String", "SHOP_API_KEY", "\"${local("shop.apiKey", "")}\"")
        // 개발용 일괄 시험(adb 로 문장 목록을 돌린다). 로컬에서 -PdevHooks=true 일 때만 켜고 CI 릴리스에는 들어가지 않는다
        buildConfigField("boolean", "DEV_HOOKS", ((findProperty("devHooks") as String?) == "true").toString())
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // LiteRT-LM 이 리플렉션으로 클래스를 읽어서 줄이기(minify)는 끈다. 켜려면 keep 규칙을 따로 맞춰야 한다
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // JVM 단위 테스트에서 android.util.Log 같은 프레임워크 호출이 예외 대신 기본값을 돌려주게 한다
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.litertlm.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.network.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
