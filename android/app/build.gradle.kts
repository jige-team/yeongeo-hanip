import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// local.properties: Git에 올리지 않는 값(서버 자격 증명, 서명 키 비밀번호)
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val useServer = local.getProperty("pleos.useServer", "false").toBoolean()

android {
    namespace = "app.redlight.hanmadi"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.redlight.hanmadi"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 서버 음성(HYBRID). 2026-11-01 기준 HTTP 401로 거절된다(Playground에서 STT·TTS "Coming Soon").
        // 거절 뒤 차량 내부 엔진으로 넘어가느라 느려질 수 있어 기본은 끈다. 열리면 local.properties에 pleos.useServer=true
        buildConfigField("boolean", "PLEOS_USE_SERVER", useServer.toString())
        // 자격 증명은 서버를 켤 때만 APK에 넣는다. 제출 APK는 누구나 풀어 볼 수 있다(핵심 규칙 10)
        buildConfigField("String", "PLEOS_CLIENT_ID", "\"${if (useServer) local.getProperty("pleos.clientId", "") else ""}\"")
        buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"${if (useServer) local.getProperty("pleos.clientSecret", "") else ""}\"")
    }

    // 제출용 release 서명. 키 파일과 비밀번호는 local.properties에만 둔다(Git 제외)
    signingConfigs {
        create("release") {
            local.getProperty("release.storeFile")?.let { storeFile = file(it) }
            storePassword = local.getProperty("release.storePassword")
            keyAlias = local.getProperty("release.keyAlias")
            keyPassword = local.getProperty("release.keyPassword")
            // v3 서명: 서명 키 교체(리니지)를 지원한다. minSdk 28 요건의 이유와 같다 [공식: Pleos 개발 환경 문서]
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            // 코드 축소는 끈다. Pleos SDK가 리플렉션을 쓰는지 확인하지 못했고, 시연 APK가 깨질 위험이 더 크다 [판단]
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // 차량 속도(PERF_VEHICLE_SPEED)는 Pleos SDK에 없어서 AAOS Car API로 읽는다
    useLibrary("android.car")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true // 디버그 빌드에서만 측정 모드를 켠다
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
