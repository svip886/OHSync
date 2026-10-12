plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.bqj6666.ohsync"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.bqj6666.ohsync"
        minSdk = 34
        targetSdk = 36
        versionCode = 12000
        versionName = "1.2.0"
    }

    signingConfigs {
        create("release") {
            // 只在有环境变量时生效：本地无密钥也能构建 debug
            val ksPath = System.getenv("OHSYNC_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("OHSYNC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OHSYNC_KEY_ALIAS")
                keyPassword = System.getenv("OHSYNC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 没提供正式密钥时退回 debug 签名。
            //
            // 不这么做的话，release 包是**未签名**的，根本装不上；而即便用另一套正式密钥
            // 签了，签名与已装的 debug 包不一致，`adb install -r` 会被
            // INSTALL_FAILED_UPDATE_INCOMPATIBLE 拒绝，只能先卸载 —— 那会连应用数据
            // （WebDAV 凭据、导出缓存）和已授的权限一起丢掉。
            // 退回 debug 签名后，release 包可以直接覆盖升级；有了正式密钥（仓库 Secrets
            // 里的 OHSYNC_KEYSTORE_*）就自动优先用正式密钥，这条兜底不再生效。
            signingConfig = signingConfigs.findByName("release")
                ?.takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Health Connect 官方客户端
    implementation("androidx.health.connect:connect-client:1.1.0")

    // Compose + Material 3
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    // Xposed（compileOnly：运行时由 LSPosed 提供）
    compileOnly("io.github.libxposed:api:102.0.0")
}
