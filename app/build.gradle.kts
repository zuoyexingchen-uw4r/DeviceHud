import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

// ---------- 签名配置 ----------
// 从仓库根的 keystore.properties 读取（该文件与 keystore 本身都不入库）。
// 缺失时构建照常进行，release 会产出 unsigned APK —— 这样别人克隆下来无需任何私密文件即可编译。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}
val releaseStoreFile: File? = keystoreProps.getProperty("storeFile")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.isFile }
val hasReleaseSigning = releaseStoreFile != null

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

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
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
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
