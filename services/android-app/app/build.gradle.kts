import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // kotlin.android 는 AGP 9.x 가 자동 포함 — 명시적 선언 시 충돌 발생
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun localConfig(name: String, defaultValue: String): String =
    (
        localProperties.getProperty(name)
            ?: providers.gradleProperty(name).orNull
            ?: System.getenv(name)
            ?: defaultValue
    ).replace("\\", "\\\\").replace("\"", "\\\"")

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

        // URL 설정은 productFlavors에서 환경별로 주입
        buildConfigField("String", "PLEOS_CLIENT_ID", "\"${localConfig("PLEOS_CLIENT_ID", "")}\"")
        buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"${localConfig("PLEOS_CLIENT_SECRET", "")}\"")

    }

    buildFeatures {
        buildConfig = true
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

    flavorDimensions += "env"
    productFlavors {
        create("local") {
            dimension = "env"
            buildConfigField("String", "CARPAYIN_BACKEND_BASE_URL", "\"http://10.0.2.2:8000\"")
            buildConfigField("String", "CARPAYIN_QR_BASE_URL", "\"${localConfig("CARPAYIN_QR_BASE_URL", "http://10.0.2.2:8000")}\"")
            buildConfigField("Boolean", "CARPAYIN_EMULATOR_LOCALHOST_REWRITE", "true")
            buildConfigField("String", "IOT_ENDPOINT", "\"\"")
            buildConfigField("String", "COGNITO_IDENTITY_POOL_ID", "\"\"")
            // Mosquitto (Docker): 에뮬레이터에서 10.0.2.2 = 호스트 머신
            buildConfigField("String", "MOSQUITTO_BROKER_URL", "\"tcp://10.0.2.2:1883\"")
        }
        create("aws") {
            dimension = "env"
            buildConfigField("String", "CARPAYIN_BACKEND_BASE_URL", "\"${localConfig("CARPAYIN_BACKEND_BASE_URL", "")}\"")
            buildConfigField("String", "CARPAYIN_QR_BASE_URL", "\"${localConfig("CARPAYIN_QR_BASE_URL", "")}\"")
            buildConfigField("Boolean", "CARPAYIN_EMULATOR_LOCALHOST_REWRITE", "false")
            buildConfigField("String", "IOT_ENDPOINT", "\"${localConfig("IOT_ENDPOINT", "")}\"")
            buildConfigField("String", "COGNITO_IDENTITY_POOL_ID", "\"${localConfig("COGNITO_IDENTITY_POOL_ID", "")}\"")
            buildConfigField("String", "MOSQUITTO_BROKER_URL", "\"\"")
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
    implementation("com.google.zxing:core:3.5.1")

    // android.car.jar 불필요 — VehicleDataManager가 리플렉션으로 Car API 호출
    // 실제 AAOS 기기에서는 런타임에 android.car 클래스를 찾아 사용
    // Pleos SDKs (에뮬레이터용 VHAL + 패널 소유권/내비 연동)
    implementation("ai.pleos.playground:Vehicle:2.0.3")
    implementation("ai.pleos.playground:NaviHelper:2.0.3")
    // Android 내장 TTS/STT 사용 (android.speech.tts / android.speech)
    // AWS IoT Core (입차 확정 / 결제 완료 실시간 푸시) — aws 플레이버
    implementation("com.amazonaws:aws-android-sdk-iot:2.81.1")
    // Mosquitto MQTT (로컬 시뮬레이션) — local 플레이버
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // EncryptedSharedPreferences (토큰 / 주차 상태 보안 저장)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
