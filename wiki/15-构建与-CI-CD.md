# 构建与 CI/CD

本文档说明 Trae Social Android 项目的构建系统、版本目录、构建类型、ABI 拆分与 GitHub Actions 持续集成/发布流程。

## 构建工具链

- Gradle Kotlin DSL + 版本目录（`gradle/libs.versions.toml`）。AGP 8.6.0，Kotlin 2.0.21，KSP 2.0.21-1.0.25，JVM 17。
- `gradle.properties`：
  - `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -XX:+UseParallelGC`
  - `org.gradle.parallel=true`
  - `org.gradle.caching=true`
  - `org.gradle.configuration-cache=true`
  - `android.useAndroidX=true`
  - `android.nonTransitiveRClass=true`
  - `kotlin.code.style=official`
- `settings.gradle.kts`：
  - `pluginManagement.repositories`：`google`（限定 `com.android.*/com.google.*/androidx.*`）、`mavenCentral`、`gradlePluginPortal`。
  - `dependencyResolutionManagement`：`FAIL_ON_PROJECT_REPOS`（`google` + `mavenCentral`，禁止子项目自定义仓库）。
  - `rootProject.name="social"`。
  - 10 个模块 includes：`:app, :core-designsystem, :core-data, :core-llm, :core-scheduler, :feature-feed, :feature-timeline, :feature-profile, :feature-publish, :feature-onboarding`。
- `init.gradle`：临时 init script，在 settings 评估前注入 `pluginManagement.repositories`，确保 AGP 8.6.0 plugin marker artifact 可解析。通常无需手动调用。

## 版本目录（gradle/libs.versions.toml）

### [versions] 关键版本表

| 依赖 | 版本 |
| --- | --- |
| agp | 8.6.0 |
| kotlin | 2.0.21 |
| ksp | 2.0.21-1.0.25 |
| composeBom | 2024.09.00 |
| activityCompose | 1.9.1 |
| navigationCompose | 2.8.0 |
| accompanistPermissions | 0.34.0 |
| hilt | 2.52 |
| hiltNavigationCompose | 1.2.0 |
| room | 2.6.1 |
| retrofit | 2.11.0 |
| okhttp | 4.12.0 |
| coil | 2.6.0 |
| workManager | 2.9.1 |
| cameraX | 1.3.4 |
| dataStore | 1.1.1 |
| paging | 3.3.2 |
| securityCrypto | 1.1.0-alpha06 |
| coroutines | 1.8.1 |
| serialization | 1.6.3 |
| timber | 5.0.1 |
| coreKtx | 1.13.1 |
| lifecycle | 2.8.4 |
| splashscreen | 1.0.1 |
| junit | 4.13.2 |
| mockk | 1.13.12 |
| turbine | 1.1.0 |
| mockwebserver | 4.12.0 |

### [libraries]

32 个库坐标，覆盖：Compose BOM 与 UI 系列、Hilt、Room、Retrofit/OkHttp、Coil、WorkManager、CameraX、DataStore、Paging、Accompanist、security-crypto、kotlinx、Timber、AndroidX、测试库。

### [plugins]

7 个插件：

- `android-application`
- `android-library`
- `kotlin-android`
- `kotlin-compose`
- `kotlin-serialization`
- `hilt`
- `ksp`

## 构建类型

| 构建类型 | applicationIdSuffix | versionNameSuffix | minify | shrinkResources | signingConfig |
| --- | --- | --- | --- | --- | --- |
| debug | `.debug` | `-debug` | false | - | debug |
| release | - | - | `!ciDisableMinify` | `!ciDisableMinify` | release（存在）或 debug（回退） |

- `ciDisableMinify`：CI 传 `-PciDisableMinify` 关闭 R8 混淆，规避 AGP 8.6.0 携带的 R8 8.6.17 `ConcurrentModificationException` 确定性 bug。本地若需混淆可省略该参数。

## ABI 拆分

- `splits.abi`：`isEnable=true`，`reset()`，`include("arm64-v8a", "armeabi-v7a", "x86_64")`，`isUniversalApk=true`。
- release 产出 4 个 APK（`arm64-v8a`、`armeabi-v7a`、`x86_64`、`universal`），全部上传到 GitHub Release（RISK-10 减小 APK 体积）。

## 签名配置

- 复制 `keystore.properties.example` 为 `keystore.properties`，填入 `storeFile`/`storePassword`/`keyAlias`/`keyPassword`。
- keytool 生成：

```
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias release
```

- 未配置时 release 回退 debug 签名保证可构建。`keystore.properties` 与 `*.jks` 在 `.gitignore` 排除。

## 常用构建命令

```
# 编译检查（所有模块 debug）
./gradlew compileDebugKotlin --no-daemon --no-parallel --stacktrace

# Debug APK
./gradlew :app:assembleDebug

# Release APK（需配置 keystore.properties）
./gradlew :app:assembleRelease

# Release APK（CI 模式，关闭混淆）
./gradlew :app:assembleRelease -PciDisableMinify

# 单元测试
./gradlew test

# Android 测试（需设备/模拟器）
./gradlew connectedAndroidTest
```

> 注：`--no-parallel` 规避 `org.gradle.parallel=true` 下 R8/编译器偶发并发问题。

## ProGuard 规则

- `app/proguard-rules.pro`：保留 Hilt/Dagger、Room、Retrofit/OkHttp、Kotlinx Serialization（含 `com.trae.social.**$$serializer` 与 `Companion`）、Compose、WorkManager、CameraX、应用自身模型（`com.trae.social.**.model.**`/`.entity.**`/`.dto.**`）、协程、Timber。
- 各 core 模块 `consumer-rules.pro`：`core-data` keep `entity`/`dao`/`db` 包 + `@Serializable` 类与 `$$serializer`。

## GitHub Actions（.github/workflows/build-and-release.yml）

- name：`Build and Release`。
- 触发：
  - `pull_request` 到 `main`（编译验证）。
  - `push` 到 `v*` tag（构建 release 并发布）。
- permissions：`contents: write`。

### Job 1：validate（仅 PR）

1. `actions/checkout@v4`
2. `actions/setup-java@v4`（temurin 17）
3. `actions/setup-gradle@v3`
4. 执行：

```
./gradlew compileDebugKotlin --no-daemon --no-parallel --stacktrace
```

### Job 2：release（仅 tag）

1. `actions/checkout@v4`
2. `actions/setup-java@v4`
3. `actions/setup-gradle@v3`
4. 执行：

```
./gradlew assembleRelease --no-daemon --no-parallel -PciDisableMinify --stacktrace
```

5. 收集 APK（`find app/build/outputs/apk/release -name "*.apk"` -> `artifacts/`）。
6. `actions/upload-artifact@v4`（name: `release-apks`）。
7. `softprops/action-gh-release@v2`（`generate_release_notes: true`，`files: artifacts/*.apk`）。

### 设计要点

- ABI 拆分产出 4 个 APK 全部发布。
- CI 关闭 R8 minify 规避 R8 8.6.17 bug。
- keystore 不存在回退 debug 签名。

## 模块依赖图

详见 [03-架构设计](./03-架构设计.md)。各模块 `build.gradle.kts` 依赖关系：

- `app` 依赖全部 9 模块。
- feature 模块仅依赖 core。
- `core-scheduler` 依赖 `core-data` + `core-llm`。
- `core-llm` 用 `implementation(project(":core-data"))`（#307：原 `api` 是为让旧 `LlmProvider` 作为 `LlmClient.provider` 公开类型暴露，#151 重构后 LlmClient 已无 provider 字段，故降级）。
