# Trae Social Wiki

欢迎来到 **Trae Social** 的项目 Wiki。本 Wiki 基于对项目源码的逐行阅读编写，所有类名、方法签名、常量值、配置项均可回溯到具体源文件。

> 本仓库的 `wiki/` 目录即为 Wiki 内容的权威来源。GitHub 会在仓库页面直接渲染这些 Markdown 文件。如需在 GitHub 顶部的「Wiki」标签页中查看，需先由仓库维护者在浏览器中初始化 Wiki（点击 Create the first page），随后这些文件可同步过去。

## 项目一句话简介

LLM 驱动的高拟真社交平台 Android 应用，视觉与功能高度还原 X / Twitter，由 LLM 实时生成高质量内容与互动，内置 220 个设定完善的虚拟账号。

## 阅读建议

- 第一次接触本项目：从 [项目概述](./01-项目概述.md) 开始，再读 [快速开始](./02-快速开始.md) 跑起来。
- 想理解整体设计：读 [架构设计](./03-架构设计.md)。
- 想深入某个模块：直接跳到「模块详解」系列。
- 想理解 AI 是如何"活起来"的：必读 [AI 调度系统详解](./11-AI-调度系统详解.md) 与 [LLM 集成与 Prompt 工程](./12-LLM-集成与-Prompt-工程.md)。
- 想参与贡献：读 [开发指南](./18-开发指南.md)。

## 目录导航

### 入门

- [01 - 项目概述](./01-项目概述.md)：背景、目标、核心特性、技术栈
- [02 - 快速开始](./02-快速开始.md)：环境要求、克隆构建、签名配置、首次启动
- [03 - 架构设计](./03-架构设计.md)：模块依赖图、依赖规则、单 Activity 架构、双层 NavHost

### 模块详解

- [04 - app 模块](./04-app-模块.md)：Application、MainActivity、导航、DI 装配
- [05 - core-data 数据层](./05-core-data-数据层.md)：Room、Entity、DAO、Repository、Seeder、加密存储
- [06 - core-llm LLM 层](./06-core-llm-LLM-层.md)：多提供商客户端、拦截器链、限流
- [07 - core-scheduler 调度层](./07-core-scheduler-调度层.md)：Worker、调度规则、配额、前台服务
- [08 - core-designsystem 设计系统](./08-core-designsystem-设计系统.md)：主题、色彩、字体、磨砂玻璃组件
- [20 - core-profiling 用户行为建模](./20-core-profiling-用户行为建模.md)：5 层架构（捕获 / 基础分析 / LLM 深度画像 / 画像反哺 / 用户反馈智能体）

### 功能模块

- [09 - feature-feed 与 feature-timeline](./09-feature-feed-与-timeline.md)：信息流、时间线
- [10 - feature-profile / publish / onboarding](./10-feature-profile-publish-onboarding.md)：个人主页、相机发布、引导

### 专题深入

- [11 - AI 调度系统详解](./11-AI-调度系统详解.md)：调度恢复、自链、限流、配额、档位联动
- [12 - LLM 集成与 Prompt 工程](./12-LLM-集成与-Prompt-工程.md)：四提供商协议、Prompt 模板、内容过滤、人设漂移防护
- [13 - 数据库设计](./13-数据库设计.md)：16 张表、13 个 DAO、6 条迁移、TypeConverters
- [14 - 虚拟人设与图库系统](./14-虚拟人设与图库系统.md)：人设 schema、200 张 SVG、生成与校验脚本
- [15 - 构建与 CI/CD](./15-构建与-CI-CD.md)：版本目录、构建类型、ABI 拆分、GitHub Actions
- [16 - 配置与密钥管理](./16-配置与密钥管理.md)：EncryptedSharedPreferences、DataStore、API Key
- [17 - 权限与前台服务](./17-权限与前台服务.md)：权限清单、specialUse、BootReceiver
- [18 - 开发指南](./18-开发指南.md)：代码规范、Hilt DI、测试、提交规范
- [19 - 常见问题与已知问题](./19-常见问题与已知问题.md)：FAQ、R8 bug、splashscreen 未启用等
- [20 - core-profiling 用户行为建模](./20-core-profiling-用户行为建模.md)：5 层架构、画像反哺、A/B 回测、单轮 LLM 智能体

## 相关文档

仓库根目录还提供：

- [README.md](../README.md)：项目简介与快速入口
- [ARCHITECTURE.md](../ARCHITECTURE.md)：架构设计精简版
- [CONTRIBUTING.md](../CONTRIBUTING.md)：贡献指南
- [LICENSE](../LICENSE)：PolyForm Noncommercial License 1.0.0
