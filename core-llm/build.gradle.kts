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
    // IMPL-44：复用 core-data 的 LlmProvider 枚举（含 id/displayName 元数据），
    // 消除 core-llm 与 core-data 间的重复枚举定义与手动映射。
    // 使用 api 以便 LlmProvider 作为 LlmClient.provider 的公开类型对消费模块可见。
    api(project(":core-data"))

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
