plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.smartisanrevival.bang"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.smartisanrevival.bang"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-thumbpress"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        aidl = true
    }
    signingConfigs {
        create("release") {
            val keyStorePath = System.getenv("BANG_KEYSTORE")
            if (!keyStorePath.isNullOrBlank() && file(keyStorePath).exists()) {
                storeFile = file(keyStorePath)
                storePassword = System.getenv("BANG_STORE_PASSWORD")
                keyAlias = System.getenv("BANG_KEY_ALIAS")
                keyPassword = System.getenv("BANG_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1") // FileProvider：内置 Shizuku 安装
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.huaban:jieba-analysis:1.0.2")
    implementation("io.github.hzkitty:rapidocr4j-android:1.0.0")
}
