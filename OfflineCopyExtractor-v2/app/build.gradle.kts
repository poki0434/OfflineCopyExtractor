plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.local.extractor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.local.extractor"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    androidResources {
        noCompress += listOf("onnx", "txt")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.cardview:cardview:1.0.0")

    // 直接从 Maven 中央仓库自动引入 Sherpa-ONNX 离线语音引擎
    implementation("com.k2fsa.sherpa.onnx:sherpa-onnx:1.12.14")

    // 1. 本地音视频抽离 (FFmpeg 精简音频版)
    implementation("com.arthenica:ffmpeg-kit-audio:6.0-2")

    // 2. 本地离线中文图片 OCR 识别 (自带离线模型，无需联网)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")

    // 3. 本地网页正文提取 & 网络下载
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("net.dankito.readability4j:readability4j:1.0.8")
}
