plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.trae.social.llm"
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
}

dependencies {
    // #307：降级为 implementation。原 api 是为让 LlmProvider 作为 LlmClient.provider 的
    // 公开类型对消费模块可见，但 #151 重构后 LlmClient 已移除 provider 字段（现仅含
    // endpointId + capabilities），该理由不再成立。所有消费模块（feature-onboarding /
    // feature-profile / core-scheduler / core-profiling / app）均已直接 implementation
    // core-data，不依赖 core-llm 传递暴露 core-data 的持久层类型。
    // core-llm 公共 API 中的 LlmProtocol / ModelCapability 等领域类型由消费模块自身
    // 的 core-data 依赖提供，无需 core-llm 传递。
    implementation(project(":core-data"))

    implementation(libs.androidx.core.ktx)

    // #151：OpenAI / Anthropic 官方 Java SDK，取代旧手写 Retrofit API + SSE 解析。
    // OkHttp 由 SDK 的 *-client-okhttp 子模块传递引入，不再在此直接声明。
    implementation(libs.openai.java.core)
    implementation(libs.openai.java.client.okhttp)
    implementation(libs.anthropic.java.core) {
        // anthropic-java-core 的 POM 把 Apache HTTP 5.x（httpclient5 / httpcore5）列为
        // runtime 依赖，Android 上不在 bootclasspath 且与 OkHttp 路径无关，exclude 掉
        // 避免无用包体与方法数增长。实际网络层走 okhttp client 模块。
        exclude(group = "org.apache.httpcomponents.core5")
        exclude(group = "org.apache.httpcomponents.client5")
    }
    implementation(libs.anthropic.java.client.okhttp) {
        exclude(group = "org.apache.httpcomponents.core5")
        exclude(group = "org.apache.httpcomponents.client5")
    }

    // 序列化 + 协程
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // 日志
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
