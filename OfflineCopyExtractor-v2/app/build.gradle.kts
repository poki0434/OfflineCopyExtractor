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
        versionCode = 2
        versionName = "2.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("fixedRelease") {
            val keystoreFile = file("fixed_release.jks")
            if (!keystoreFile.exists()) {
                val cmd = listOf(
                    "keytool", "-genkeypair", "-v",
                    "-keystore", keystoreFile.absolutePath,
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                    "-alias", "extractor",
                    "-storepass", "extractor123", "-keypass", "extractor123",
                    "-dname", "CN=Extractor, OU=Local, O=Local, L=Beijing, S=Beijing, C=CN"
                )
                ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor()
            }
            storeFile = keystoreFile
            storePassword = "extractor123"
            keyAlias = "extractor"
            keyPassword = "extractor123"
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixedRelease")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixedRelease")
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
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 本地离线中文图片 OCR 识别
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")

    // 本地网页正文提取 & 网络下载
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("net.dankito.readability4j:readability4j:1.0.8")
}
