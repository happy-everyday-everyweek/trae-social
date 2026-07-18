plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.trae.social.core.scheduler"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
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

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // 项目内模块
    implementation(project(":core-data"))
    implementation(project(":core-llm"))
    // #146：UserProfileWorker 注入 UserProfileAggregator / ProfileVersionStore（profiling 层），
    // 后续 TweetGenerationWorker / InteractionWorker / PersonaUpdateWorker 也要读画像驱动反哺（A/E）。
    // 缺此依赖会导致 Hilt KSP 报 NonExistentClass（core-scheduler:kspDebugKotlin FAILED）。
    implementation(project(":core-profiling"))

    implementation(libs.androidx.core.ktx)

    // WorkManager + Hilt Work
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Retrofit（用于捕获 retrofit2.HttpException 判定 429）
    implementation(libs.retrofit)

    // 协程
    implementation(libs.kotlinx.coroutines.android)
    // #146 A：反哺层打标用 JsonPrimitive（scenarioId/drivenByProfile/group extra 字段）
    implementation(libs.kotlinx.serialization.json)

    // 日志
    implementation(libs.timber)

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.work.testing)
    testImplementation(libs.mockk)
}
