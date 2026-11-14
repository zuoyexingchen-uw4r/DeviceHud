plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.zuoyexingchen.devicehud"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.zuoyexingchen.devicehud"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 内测版：包名派生 .debug 后缀，可与正式版同时安装，数据互不干扰
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Shizuku：借 shell 身份读 /proc/stat 之类应用层拿不到的数据
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
