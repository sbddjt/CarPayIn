plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.carpayin"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.carpayin"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
    // Vehicle SDK (VIN 등 차량 데이터)
    implementation("ai.pleos.playground:Vehicle:2.0.3")
    // MQTT 클라이언트
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    compileOnly(files("C:/Users/USER/AppData/Local/Android/Sdk/platforms/android-34/optional/android.car.jar"))

    implementation("ai.pleos.playground:Vehicle:2.0.3")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}