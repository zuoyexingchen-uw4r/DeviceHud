plugins {
    id("com.android.application")
}

android {
    namespace = "com.zyxc.devicehud"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zyxc.devicehud"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
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
