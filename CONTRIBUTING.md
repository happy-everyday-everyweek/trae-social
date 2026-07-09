# 贡献指南

感谢你对 Trae Social 项目的兴趣！本文档说明开发环境配置、代码规范与提交规范。

## 许可证声明

本项目采用 [PolyForm Noncommercial License 1.0.0](./LICENSE)。提交贡献即表示你同意你的贡献将以同一许可证授权。

## 开发环境

- JDK 17
- Android SDK 34（compileSdk），minSdk 26
- Gradle 8.14+（项目含 wrapper）
- Android Studio Koala+（推荐）或 IntelliJ IDEA

## 开发流程

1. Fork 仓库并克隆到本地
2. 基于 `main` 分支创建特性分支：`git checkout -b feature/your-feature`
3. 编写代码并确保通过编译与测试
4. 提交代码（见下方提交规范）
5. 推送到你的 Fork 并创建 Pull Request

### 分支命名

- `feature/` — 新功能
- `fix/` — Bug 修复
- `refactor/` — 重构
- `docs/` — 文档
- `chore/` — 构建 / 工具 / 杂项

## 代码规范

### Kotlin

- 遵循 [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- 优先使用 `val`，避免可变状态
- 避免 `!!` 强解包，使用安全调用 `?.` 或 `requireNotNull` / `checkNotNull`
- 公共 API 添加 KDoc 注释
- 代码注释使用中文（与项目现有风格一致）

### Compose

- 状态提升到父 Composable
- 使用 `remember` / `rememberSaveable` 管理局部状态
- 避免在 Composable 体内创建昂贵对象，使用 `remember` 缓存
- 为列表项提供稳定的 `key`

### 架构

- feature 模块不直接依赖其他 feature 模块，通过 `core-*` 模块通信
- 跨模块共享的类型定义在最底层的 core 模块（如 `LlmProvider` 定义在 core-data）
- 新增 Room Entity / DAO 需递增数据库版本并编写 Migration，导出 schema JSON

### Hilt DI

- `@Module` + `@InstallIn(SingletonComponent::class)` 暴露绑定
- 避免 DuplicateBindings：同一类型仅在唯一模块中提供
- 使用 `@Qualifier` 区分同一类型的不同绑定（如 `@SecurePreferences`）

## 提交规范

采用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：

```
<type>(<scope>): <subject>

<body>

<footer>
```

- **type**：`feat` / `fix` / `refactor` / `docs` / `chore` / `test` / `build`
- **scope**：模块名（如 `scheduler` / `feed` / `onboarding`），可选
- **subject**：简短描述（祈使句，不加句号）
- **body**：详细说明（可选）
- **footer**：关联 issue 或 breaking change（可选）

示例：

```
feat(scheduler): 人设更新 Worker 支持档位缩放周期

PersonaUpdateWorker 周期按 AiActivityLevel 缩放（LOW=14天/MEDIUM=7天/HIGH=3天），
批次大小按档位 10/20/40。
```

## 测试

```bash
# 单元测试
./gradlew test

# Android 测试（需连接设备或模拟器）
./gradlew connectedAndroidTest
```

新增功能或修复 Bug 时建议添加对应测试。项目已集成 MockK、Turbine、MockWebServer 测试库。

## 构建

```bash
# 编译检查
./gradlew compileDebugKotlin

# Debug APK
./gradlew :app:assembleDebug

# Release APK（需配置 keystore.properties）
./gradlew :app:assembleRelease
```

## Pull Request 检查清单

- [ ] 代码通过 `compileDebugKotlin` 编译
- [ ] 提交信息符合 Conventional Commits 规范
- [ ] 新增公共 API 有 KDoc 注释
- [ ] 不引入 `!!` 强解包
- [ ] 不引入硬编码密钥或 API Key
- [ ] feature 模块不直接依赖其他 feature 模块
