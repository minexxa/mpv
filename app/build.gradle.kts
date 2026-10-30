plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 需要打包哪些 ABI。libmpv AAR 自带 arm64-v8a / armeabi-v7a / x86 / x86_64，
 * 全放进去 APK 会到 ~70MB，所以默认只打 arm64-v8a（真机）。
 * 想在模拟器上跑：-PmpvAbis=arm64-v8a,x86_64
 */
val targetAbis = (findProperty("mpvAbis") as? String ?: "arm64-v8a")
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

android {
    namespace = "app.mpvplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.mpvplayer"
        minSdk = 26            // libmpv AAR 要求 minSdk 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters.addAll(targetAbis)
        }
    }

    buildTypes {
        debug {
            // debug 包自动用 ~/.android/debug.keystore 签名，可直接安装
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 未配置签名时 assembleRelease 产出 unsigned apk；用 debug 签名方便侧载：
            signingConfig = signingConfigs.getByName("debug")
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
        viewBinding = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // mpv 内核：libmpv.so + libplayer.so(JNI glue) + ffmpeg，dev.jdtech.mpv:libmpv 即最新版 1.0.0
    implementation("dev.jdtech.mpv:libmpv:1.0.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
