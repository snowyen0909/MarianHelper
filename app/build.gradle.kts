plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nikkeauto"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nikkeauto"
        minSdk = 30          // takeScreenshot() 需要 Android 11 以上
        targetSdk = 34
        versionCode = 210
        versionName = "2.1.0"
    }

    // 固定簽章：GitHub Actions 從 Secrets 解出金鑰後透過環境變數傳進來。
    // 沒有設定時（例如本機編譯）就照舊用 debug 簽章。
    val ksFile = System.getenv("MH_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    val ksPass = System.getenv("MH_KEYSTORE_PASSWORD")
    signingConfigs {
        if (ksFile != null && !ksPass.isNullOrEmpty()) {
            create("marian") {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = "marian"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("marian")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // 文字辨識（中文模型，同時能讀英文）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
