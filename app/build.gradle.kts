plugins {
    alias(libs.plugins.android.application)
    // kotlin.android 는 AGP 9.x 가 자동 포함 — 명시적 선언 시 충돌 발생
}

android {
    namespace  = "com.example.carpayin"
    compileSdk = 34          // android.car.jar 는 34 기준 — 34로 고정

    defaultConfig {
        applicationId = "com.example.carpayin"
        minSdk        = 28   // Car API 안정 버전 (Android 9+)
        targetSdk     = 34
        versionCode   = 1
        versionName   = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Android Car API 스텁 (컴파일 전용)
    // 1순위: 프로젝트 로컬 스텁 JAR (generate_car_stubs.py 로 생성)
    // 2순위: AAOS SDK가 설치된 경우 실제 JAR 경로로 교체
    compileOnly(fileTree("libs") { include("*.jar") })

    // Pleos Vehicle SDK (에뮬레이터용 VHAL 시뮬레이션)
    implementation("ai.pleos.playground:Vehicle:2.0.3")

    // MQTT (입차 확정 / 결제 완료 실시간 푸시)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // EncryptedSharedPreferences (토큰 / 주차 상태 보안 저장)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
