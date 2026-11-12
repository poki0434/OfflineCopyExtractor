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
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixedRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixedRelease")
        }
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.dankito.readability4j:readability4j:1.0.8")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
}
