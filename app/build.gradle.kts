import java.util.Properties
import java.io.FileInputStream

// 读取 keystore.properties 用于 release 签名；文件不存在时回退到 debug 签名
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.trae.social.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.trae.social"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // #303 修复：release 始终开启 R8 混淆 + 资源压缩。
            // 此前 CI 传 -PciDisableMinify 关闭 R8 规避 AGP 8.6.0 的 R8 8.6.17
            // ConcurrentModificationException，导致线上 APK 无混淆、易被逆向。
            // 已升级 AGP 至 8.7.3 修复该 R8 bug，故移除关闭开关，强制混淆。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // #303 修复：release 不再静默回退 debug 签名（会导致线上 APK 用 debug keystore
            // 签名，任何人都能用同一 debug key 签名恶意应用冒充本应用）。
            // 仅在以下任一条件成立时启用 release 签名：
            //   1. keystore.properties 存在（本地正式签名 / CI 通过 secret 注入）
            //   2. 显式传 -PreleaseSigningDebug=true（仅限本地调试 release 构建，会告警）
            // 否则 release 构建不绑定 signingConfig，assembleRelease 产出 unsigned APK，
            // 由发布流程显式失败，避免静默用 debug key 发布。
            val releaseSigningDebug = project.hasProperty("releaseSigningDebug")
            if (releaseSigningDebug) {
                logger.warn("release 构建使用 debug 签名（-PreleaseSigningDebug），严禁用于正式发布")
            }
            signingConfig = when {
                keystorePropertiesFile.exists() -> signingConfigs.getByName("release")
                releaseSigningDebug -> signingConfigs.getByName("debug")
                else -> null
            }
        }
    }

    // ABI 拆分以减小 APK 体积（RISK-10）
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

// #228：Compose Compiler 报告输出（稳定性报告 + 指标），用于识别 @Unstable 类型导致的
// 过度重组。生成产物位于 build/compose_reports 与 build/compose_metrics，供后续重组优化参考。
// 仅在 Kotlin 2.0+ Compose Compiler Gradle 插件下可用（org.jetbrains.kotlin.plugin.compose）。
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
}

dependencies {
    // 项目内模块
    implementation(project(":core-designsystem"))
    implementation(project(":core-data"))
    implementation(project(":core-llm"))
    implementation(project(":core-profiling"))
    implementation(project(":core-scheduler"))
    implementation(project(":feature-feed"))
    implementation(project(":feature-timeline"))
    implementation(project(":feature-profile"))
    implementation(project(":feature-publish"))
    implementation(project(":feature-onboarding"))

    // AndroidX 基础
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.core.splashscreen)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // 协程
    implementation(libs.kotlinx.coroutines.android)

    // 序列化（#146：app 层埋点构造 TAB_SWITCH 的 extra[from/to] 需 JsonElement；
    // core-profiling/core-data 以 implementation 暴露，不传递到 app 编译类路径，故显式声明）
    implementation(libs.kotlinx.serialization.json)

    // 日志
    implementation(libs.timber)

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // IMPL-41：Hilt androidTest 基础设施
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
