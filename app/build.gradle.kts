plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hypixelminer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hypixelminer"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // Постоянный ключ подписи: благодаря ему новые версии ставятся поверх старой
    // без удаления приложения (и без потери истории). Это ключ любительского проекта,
    // для публикации в Google Play нужен свой секретный ключ.
    signingConfigs {
        create("shared") {
            storeFile = file("signing.keystore")
            storePassword = "hypixelminer"
            keyAlias = "hypixelminer"
            keyPassword = "hypixelminer"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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

// Приложение не использует сторонних библиотек: только стандартный Android SDK.
dependencies {}
