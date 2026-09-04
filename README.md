# Metis Mobile

<div align="center">

**一个运行在 Android 手机上的 AI Agent。**

自然语言进入，屏幕理解、工具调用与可验证的手机动作完成闭环。

[![Version](https://img.shields.io/badge/version-26.9.5-6b5cff.svg)](https://github.com/linyeping/metis-mobile/releases/tag/v26.9.5)
[![Platform](https://img.shields.io/badge/platform-Android%2024%2B-3ddc84.svg)](https://www.android.com/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

</div>

> Metis Mobile 与 Metis Desktop 采用相近的 Agent 技术路线，但这是一个独立设计、独立实现的 Android 客户端，并非 Metis Desktop 的 fork。

## 产品定位

Metis Mobile 把 Android 手机变成一个可以被自然语言指挥的执行端。它不依赖固定坐标脚本，也不要求用户为每个流程编写 UI 测试；Agent 先读取当前屏幕和可访问节点，再根据模型返回的工具调用执行点击、输入、滑动、打开应用、读写文件等动作。

手机端适合即时操作、语音指令、定时任务和外出场景；Metis Desktop 适合代码、终端、浏览器和长流程。两端可以独立运行，也可以通过中继协议组成一套跨设备 Agent 系统。

## 技术路线

```text
用户文字 / 语音
       │
       ▼
┌──────────────────────────────┐
│ Compose UI + Session Store   │  会话、角色卡、自动化、权限确认
└──────────────┬───────────────┘
               ▼
┌──────────────────────────────┐
│ AgentEngine                  │  上下文、重试、工具循环、状态机
│ GroupCoordinator             │  @角色群聊与多成员上下文
└──────────────┬───────────────┘
               ▼
┌──────────────────────────────┐
│ Model Adapters               │  OpenAI-compatible / Responses /
│                              │  Anthropic / DeepSeek
└──────────────┬───────────────┘
               ▼
┌──────────────────────────────┐
│ ToolDispatcher               │  屏幕、无障碍、文件、Termux、闹钟、分享
└──────────────┬───────────────┘
               ▼
        Android Accessibility / PhoneUse
```

### 核心实现

- **Agent Loop**：模型请求 → 解析文本与工具调用 → 执行工具 → 把结果写回上下文 → 继续请求，直到任务完成或需要用户确认。
- **统一模型适配层**：OpenAI-compatible、OpenAI Responses、Anthropic、DeepSeek 使用各自协议适配器；重试和 API Key 轮询位于模型调用边界。
- **手机执行层**：Android AccessibilityService 负责读取节点、点击、长按、滑动和输入；PhoneUse 服务负责后台任务、通知触发和系统级动作。
- **安全控制**：支付、发送、删除等高风险操作走显式确认；工具调用结果必须回灌上下文，不能只靠模型口头声称“已完成”。
- **本地数据**：会话和设置使用 DataStore / Room；API Key 使用 `EncryptedSharedPreferences` 加密保存，不写入日志。
- **MCP 接入**：手机端可把同一套工具暴露为本地 MCP 服务，让同一网络内的 MCP 客户端调用手机能力。
- **客户端身份**：模型请求统一携带 `MetisMobile/<当前版本>`、`X-Metis-Client` 和 Android 平台头，便于中转服务识别真实客户端来源。

## 与 Metis Desktop 联动

联动不是把 APK 变成桌面端的远程桌面，而是把手机端作为控制台、桌面端作为执行 Agent。当前架构如下：

```text
┌────────────────┐       WebSocket       ┌──────────────────┐
│ Metis Mobile   │ ◄──────────────────► │ Relay Server     │
│ 扫码、下发命令  │                       │ FastAPI / Python  │
└────────────────┘                       └────────┬─────────┘
                                                  │ SSE / HTTP
                                                  ▼
                                         ┌──────────────────┐
                                         │ Metis Desktop    │
                                         │ 本地 Agent API    │
                                         └──────────────────┘
```

### 当前联动流程

1. Metis Desktop 启动 `relay_client`，向中继注册并生成一次性 6 位配对码与二维码。
2. 手机端进入「远程指挥」，扫描二维码，使用 `pairing_code` 兑换短期 `pairing_token`。
3. 手机端通过 WebSocket 发送 `hello`、`create_session`、`create_run` 等命令。
4. 中继把命令转给桌面端；桌面端再调用本机 attach API 执行。
5. 桌面端的文本增量、工具调用、状态变化和权限请求通过 SSE 事件回传。
6. 手机端可以展示过程、批准权限、取消任务，并在终态显示结果。

桌面端中继实现位于 [Metis/relay](https://github.com/linyeping/Metis/tree/main/relay)，部署说明见其中的 `relay/README.md`。

### 联动机制的下一步

当前中继已经能完成远程指挥，但可靠的“任务同步”还需要独立的任务状态层。推荐的演进顺序：

1. **统一任务信封**：每个任务携带 `task_id`、来源端、会话 ID、创建时间和客户端版本。
2. **幂等命令**：以 `command_id + task_id` 去重，网络重试不能重复发送消息、创建文件或执行敏感操作。
3. **断点续传**：客户端记录最后收到的 `seq`，重连时从 `after=seq` 继续接收事件，不重新执行任务。
4. **状态持久化**：桌面端持久化 `queued / running / waiting_permission / completed / failed / cancelled`，手机端只负责展示与控制。
5. **任务列表**：手机端增加跨设备任务列表，显示执行设备、进度、授权状态、失败原因和继续/取消入口。

这套设计比“维持一条 WebSocket 并把所有状态放在内存里”更适合长任务、断网重连和多设备并行。

## 内置角色卡

首次进入角色功能时，应用会写入 5 张内置角色卡：

| 角色 | 用途 | 手机工具 |
| --- | --- | --- |
| 通用助手 | 日常问答、任务拆解 | 关闭 |
| 文案写作 | 多版本文案与去 AI 味改写 | 关闭 |
| 代码评审 | 按严重程度审查代码 | 关闭 |
| 翻译助理 | 保留语域和术语的翻译 | 关闭 |
| 手机管家 | 需要实际操作 Android 的任务 | 开启 |

在任意会话中输入 `@角色名` 可以让角色参与群聊。群聊会按成员分别生成回答，并保留前序成员的上下文。

## 自动化任务

自动化任务使用 Android AlarmManager / BroadcastReceiver 触发，支持：

- 每天、每周、每月、每年和单次触发
- 每个任务独立选择模型、推理强度和执行模式
- 后台运行与完成通知
- 任务列表、编辑、启停、删除和执行时间展示

## 安装与构建

当前版本：**26.9.5**

```bash
git clone https://github.com/linyeping/metis-mobile.git
cd metis-mobile
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

构建要求：

- Android Studio / JDK 11+
- Android SDK 36
- minSdk 24（Android 7.0）
- compileSdk 36

首次运行需要在系统设置中开启 Metis 的无障碍服务。若使用远程指挥，还需要先部署桌面端中继服务并扫描二维码。

## 项目结构

```text
app/src/main/java/com/mrgreenapps/a11ypilot/
├── agent/       Agent 引擎、模型适配器、角色卡、工具调度
├── data/        DataStore、Room、会话与自动化数据模型
├── mcp/         本地 MCP Server 与 Relay
├── phoneuse/    无障碍 PhoneUse、自动化接收器
├── remote/      Metis Desktop 中继客户端与扫码配对协议
├── tools/       文件、Termux、设备操作工具
└── ui/          Jetpack Compose 页面与组件
```

## 隐私与安全

- API Key 只保存在本机加密存储中。
- 屏幕内容只在用户发起模型任务时参与上下文构造。
- 应用不内置第三方 API Key，不默认指向私有中转服务。
- 远程配对码短期有效且一次性消费；公网部署中继时应启用 TLS 与服务端令牌。
- 高风险工具动作必须经过确认或安全策略拦截。

## 开源协议

Apache License 2.0，见 [LICENSE](LICENSE)。

## 相关项目

- [Metis Desktop](https://github.com/linyeping/Metis)
- [metis-cli](https://github.com/linyeping/metis-cli)
- [Metis Mobile Releases](https://github.com/linyeping/metis-mobile/releases)
