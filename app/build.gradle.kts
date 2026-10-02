plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.maomaoyu.coopanionpet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.maomaoyu.coopanionpet"
        minSdk = 26
        targetSdk = 34
        versionCode = 37
        versionName = "3.7.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 桌宠网页资源在 CI 里从上游仓库拉取后放进这里
    sourceSets["main"].assets.srcDirs("src/main/assets")
}
