# Metis Mobile

<div align="center">

**面向 Android 的可执行 AI Agent 客户端**

让模型理解手机当前状态，并在明确的工具边界与安全策略内完成真实操作。

[![Version](https://img.shields.io/badge/version-26.9.5-6b5cff.svg)](https://github.com/linyeping/metis-mobile/releases/tag/v26.9.5)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3ddc84.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7f52ff.svg)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285f4.svg)](https://developer.android.com/compose)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

</div>

> Metis Mobile 是独立设计、独立实现的 Android 项目。它与 [Metis Desktop](https://github.com/linyeping/Metis) 采用相近的 Agent 工程路线，但不是 Metis Desktop 的 fork。

## 目录

- [项目定位](#项目定位)
- [技术架构](#技术架构)
- [Agent 执行模型](#agent-执行模型)
- [手机操作与安全边界](#手机操作与安全边界)
- [模型与客户端身份](#模型与客户端身份)
- [与 Metis Desktop 联动](#与-metis-desktop-联动)
- [角色卡、群聊与自动化](#角色卡群聊与自动化)
- [工程结构](#工程结构)
- [构建与安装](#构建与安装)
- [隐私与发布说明](#隐私与发布说明)

## 项目定位

Metis Mobile 将 Android 手机作为一个可以被自然语言驱动的执行环境。它不是坐标脚本播放器，也不是简单的聊天壳：一次任务通常经历“观察当前状态 → 请求模型决策 → 调用工具 → 获取真实结果 → 回灌上下文 → 继续执行”的闭环。

适合的任务包括：

- 读取屏幕和可访问节点，定位界面元素；
- 点击、长按、滑动、返回、输入文字；
- 打开应用、设置闹钟、发送通知；
- 读取和写入用户选择的文件；
- 通过 Termux 执行用户授权的命令；
- 使用本地 MCP 服务扩展工具；
- 将长流程交给 Metis Desktop 执行，手机负责控制和查看进度。

手机端和桌面端可以完全独立运行，也可以通过 Relay 组成“移动控制台 + 桌面执行端”的跨设备工作流。

## 技术架构

```text
┌──────────────────────────────────────────────────────────────┐
│                         Presentation                         │
│ Jetpack Compose · Chat/Work/Settings · Remote · Widgets      │
└──────────────────────────────┬───────────────────────────────┘
                               │ State / Events
┌──────────────────────────────▼───────────────────────────────┐
│                     Application & Data                       │
│ AppViewModel · SessionRepository · Room · DataStore           │
│ AgentSettings · AutomationScheduler · UsageRepository         │
└───────────────┬──────────────────────────────┬───────────────┘
                │                                │
┌───────────────▼────────────────┐ ┌─────────────▼─────────────┐
│        Agent Runtime            │ │     Device Integration     │
│ AgentEngine · GroupCoordinator  │ │ AccessibilityService       │
│ Prompt assembly · RetryPolicy   │ │ PhoneUse · Alarm · Termux  │
│ SafetyEvaluator · ToolDispatcher│ │ Notifications · Files     │
└───────────────┬────────────────┘ └─────────────┬─────────────┘
                │                                │
┌───────────────▼────────────────────────────────▼──────────────┐
│                         Capability Layer                       │
│ OpenAI-compatible · Responses · Anthropic · DeepSeek · MCP     │
│ Metis Desktop Remote Relay · WebSocket · SSE / HTTP             │
└─────────────────────────────────────────────────────────────────┘
```

### 核心技术选型

| 层次 | 实现 | 责任 |
| --- | --- | --- |
| UI | Kotlin + Jetpack Compose + Material 3 | 响应式页面、会话、角色卡、远程控制和设置 |
| 状态 | ViewModel + Kotlin Coroutines/Flow | 单向状态流、后台任务和生命周期协调 |
| 持久化 | Room + DataStore Preferences | 会话、消息、自动化、主题和用户配置 |
| 密钥 | AndroidX Security 加密存储 | API Key 不进入源码、普通日志或导出数据 |
| 模型 | OkHttp + JSON 序列化 | 多供应商协议适配、流式响应、重试和错误归一化 |
| 手机执行 | AccessibilityService | 节点树观察、可访问动作和文本输入 |
| 扩展 | 本地 MCP Server / Relay | 向 MCP 客户端暴露手机能力或连接桌面端 |

## Agent 执行模型

```text
User input (text / voice)
          │
          ▼
Session context + character card + screen snapshot
          │
          ▼
Model adapter ──► text delta / tool call / finish reason
          │                         │
          │                         ▼
          │                 ToolDispatcher
          │                         │
          │                         ▼
          │                 Real device result
          │                         │
          └──────────── result appended to history ◄──────────┘
```

一次 Agent Loop 的关键约束：

1. 先组合系统规则、角色卡、会话历史和必要的设备上下文；
2. 由协议适配器发送模型请求，统一处理流式增量和错误；
3. 只执行已注册工具，工具参数经过解析与安全检查；
4. 工具必须返回可验证结果，再写回上下文；
5. 需要授权的动作暂停在 `waiting_permission`，等待用户明确批准；
6. 任务在模型结束、用户取消、策略拒绝或不可恢复错误时进入终态。

模型适配器彼此隔离：OpenAI-compatible Chat Completions、OpenAI Responses、Anthropic Messages 和 DeepSeek 使用各自请求/响应格式，但在 Agent Runtime 中收敛为统一的文本增量、工具调用和结束状态。

## 手机操作与安全边界

手机操作通过 Android AccessibilityService 和 PhoneUse 工具层完成，而不是依赖固定屏幕坐标。工具层负责把模型意图转换为 Android 动作，并将成功、失败、当前界面和异常原因反馈给 Agent。

安全策略包含以下边界：

- 高风险动作（支付、发送、删除、提交等）需要显式确认；
- 关闭或限制手机工具的角色不能调用 PhoneUse 能力；
- 用户取消后停止后续工具循环；
- 工具失败不能被模型自行“猜测”为成功；
- Relay 配对采用短期一次性配对码，公网部署应启用 TLS 和服务端令牌；
- API Key 只保存在本机加密存储，不写入调试日志。

## 模型与客户端身份

所有模型请求携带动态客户端身份：

```http
User-Agent: MetisMobile/26.9.5
X-Metis-Client: MetisMobile/26.9.5
X-Metis-Client-Platform: android
```

身份版本来自 Android `versionName`，当前为 `26.9.5`。以后只需更新构建版本号，请求身份会自动变为对应版本，例如 `MetisMobile/27.0.0`。图片下载、远程控制和网络诊断等非模型请求不会误用模型客户端身份。

## 与 Metis Desktop 联动

联动采用“手机端控制、桌面端执行”的 Relay 架构，不把手机伪装成桌面端，也不是远程桌面投屏。

```text
┌───────────────┐       WebSocket        ┌────────────────┐
│ Metis Mobile  │ ◄────────────────────► │ Relay Server   │
│ 扫码 / 控制   │                         │ 配对 / 转发     │
└───────┬───────┘                         └───────┬────────┘
        │                                         │ SSE / HTTP
        │                                         ▼
        │                                ┌────────────────┐
        └──────────────────────────────► │ Metis Desktop  │
                                         │ 本地 Agent API │
                                         └────────────────┘
```

当前流程：

1. 桌面端 Relay Client 注册，生成短期配对码和二维码；
2. 手机端扫码，用配对码兑换短期配对令牌；
3. 手机端通过 WebSocket 建立会话并发送任务命令；
4. Relay 将命令转给桌面端，桌面端调用本机 Agent API；
5. 桌面端通过 SSE/Relay 回传文本增量、工具调用、状态和权限请求；
6. 手机端展示执行过程，可批准权限、取消任务并查看最终结果。

桌面端中继实现：[Metis/relay](https://github.com/linyeping/Metis/tree/main/relay)。

### 可靠任务同步的演进方向

实时桥接适合远程指挥，但长任务还需要任务状态层。后续设计应逐步加入：

- `task_id` 任务信封，区分任务、会话和一次运行；
- `command_id + task_id` 幂等去重，防止网络重试导致重复操作；
- 带序号的事件流与 `after=seq` 断点续传；
- `queued / running / waiting_permission / completed / failed / cancelled` 状态持久化；
- 手机端跨设备任务列表和继续、取消、重试入口。

## 角色卡、群聊与自动化

### 角色卡与群聊

应用内置通用助手、文案写作、代码评审、翻译助理和手机管家等角色卡。角色卡包含人设、工作规则、工具权限和头像，可导入 PNG/JSON 角色卡。

在会话中使用 `@角色名` 可以让指定成员参与群聊。`GroupCoordinator` 负责成员轮次、上下文传递和工具权限；消息记录保存发言人 ID 与名称，UI 按成员显示独立的颜色、头像和思考状态。

### 自动化任务

自动化使用 Android AlarmManager 与 BroadcastReceiver 触发，支持单次、每天、每周、每月和每年计划。每个任务可独立配置模型、推理强度、执行模式和完成通知，并支持启用、停用、编辑和删除。

## 工程结构

```text
app/src/main/java/com/mrgreenapps/a11ypilot/
├── agent/       AgentEngine、模型适配器、角色卡、策略与工具调度
├── data/        Room、DataStore、会话、消息、自动化和安全配置
├── mcp/         JSON-RPC、本地 MCP Server 与 Relay
├── phoneuse/    Accessibility/PhoneUse、闹钟和自动化接收器
├── remote/      Metis Desktop 配对、远程协议和远程 UI
├── tools/       文件、Termux、工具注册表和设备能力
├── ui/          Compose ViewModel、页面、组件和主题
└── widget/      Android 桌面小组件
```

## 构建与安装

### 环境要求

- JDK 11 或更高版本；
- Android SDK 36；
- Android 7.0（API 24）或更高版本；
- 可访问网络，用于首次解析 Gradle 依赖。

### 构建

```bash
git clone https://github.com/linyeping/metis-mobile.git
cd metis-mobile
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Debug APK 位于：`app/build/outputs/apk/debug/app-debug.apk`。

Release 构建：

```bash
./gradlew :app:assembleRelease
```

安装后需要在系统设置中启用 Metis 的无障碍服务。使用远程指挥功能时，还需先部署 Metis Desktop Relay 并完成扫码配对。

## 隐私与发布说明

- 应用不内置第三方 API Key，模型供应商由用户配置；
- 屏幕内容只在用户发起需要设备上下文的任务时参与请求构造；
- 本地 API Key 使用 Android 加密存储；
- 远程 Relay 只用于配对、命令转发和事件回传，生产部署请使用 TLS；
- 本项目以 Apache License 2.0 发布，详见 [LICENSE](LICENSE)。

`v26.9.5` 是首次公开版本。当前免费分发包使用开发者 debug keystore 签名；后续版本必须继续使用相同签名密钥才能覆盖安装升级。请勿将签名密钥提交到仓库。

## 相关项目

- [Metis Desktop](https://github.com/linyeping/Metis)
- [metis-cli](https://github.com/linyeping/metis-cli)
- [Metis Mobile Releases](https://github.com/linyeping/metis-mobile/releases)
