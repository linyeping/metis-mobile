# Metis Mobile

Android 上的手机端 AI 代理：通过无障碍 API 操控手机。能用自然语言或语音下指令，把"打开设置、打开蓝牙""在微信里给张三发一条 XX"这类事情交给它做。

Metis Mobile 是 [Metis](https://github.com/linyeping/Metis) 桌面端的手机伴侣。同一个 Metis 生态里还有：

- [Metis 桌面端](https://github.com/linyeping/Metis) —— Electron + Python，跑在电脑上能读写代码、操作终端、驱动浏览器
- [metis-cli](https://github.com/linyeping/metis-cli) —— Metis 的 headless CLI，给 CI / 自动化用
- `relay/`（在桌面端仓库里） —— 中继服务器，手机端扫码配对靠它转发命令

## 它能做什么

| 场景 | 例子 |
| --- | --- |
| 单条指令 | 「打开设置把蓝牙开一下」 |
| 多步操作 | 「打开微信，找到张三的聊天，发一条『晚上 6 点见』」 |
| 定时任务 | 每天 7 点打开新闻应用截图给我看 |
| 角色对话 | 内置 5 张角色卡（通用助手、文案写作、代码评审、翻译、手机管家）；自定义人设 @ 一下就能在群聊里接力 |
| 远程指挥 | 扫桌面端二维码后，从手机端给电脑发指令、接收流式事件、审批权限 |

工具集是同一套：读屏、点按、滑动、输入文字、设闹钟、读写文件、读写 Termux、分享文件。详情见 [工具表面](#工具表面)。

## 和 Metis 桌面端联动

手机端是 Metis 生态的入口；电脑端是算力。两者通过「中继」转发命令，不要求在同一局域网：

```
手机 (Metis Mobile)  ── 扫码 ──►  中继服务器  ◄── WebSocket ──  电脑 (Metis Desktop)
                              (任一 Python 机器，
                               可放内网 NAS / 云主机)
```

部署和协议细节见桌面端仓库的 [`relay/README.md`](https://github.com/linyeping/Metis/tree/main/relay)。

## 安装

构建好的 APK 暂未公开发布，需要从源码构建：

```bash
git clone https://github.com/linyeping/metis-mobile
cd metis-mobile
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17、Android SDK 34+、Gradle 8.7。

## 第一次运行

1. 装好 APK 后打开 → 授予**无障碍服务权限**（设置 → 无障碍 → Metis 服务）
2. 授予**通知使用权**（如果想用通知触发自动化任务）
3. 在「设置 → 模型与 API」里填一个兼容 OpenAI 的中转地址 + Key，或填 DeepSeek 官方 Key
4. 进聊天页就能用了

## 配置

`app/src/main/java/com/mrgreenapps/a11ypilot/agent/AgentSettings.kt` 里的常量是默认值：

- `DEFAULT_BASE_URL = ""` —— 默认空。首次启动需要用户手动填，避开任何隐式默认中转
- `DEFAULT_RELAY_BASE_URL = ""` —— 同上
- `DEEPSEEK_BASE_URL = "https://api.deepseek.com"` —— DeepSeek 官方地址

密钥存在 `EncryptedSharedPreferences`（AES256-GCM），不进任何日志，不上传。

## 工具表面

Metis Mobile 的工具集通过两条路暴露：

1. **应用内 Agent** —— 聊天页直接调用，自然语言驱动
2. **本地 MCP 服务器** —— 同网段的 MCP 客户端（Claude Desktop、Claude Code、其他 IDE）通过 HTTP/SSE 连接，手机变 MCP server，远程操控

`mcp/McpService.kt` 启动一个 `McpServer`（端口默认 `8765`），暴露与 app 内 Agent 同一套工具。

## 角色卡

`AgentSettings.ensureSeededCharacterCards()` 在首次启动时写入 5 张内置卡片：

| ID | 名称 | 手机操作 |
| --- | --- | --- |
| `builtin-assistant` | 通用助手 | 关闭 |
| `builtin-copywriter` | 文案写作 | 关闭 |
| `builtin-coder` | 代码评审 | 关闭 |
| `builtin-translator` | 翻译助理 | 关闭 |
| `builtin-phone` | 手机管家 | 开启 |

在「设置 → 个性化 → 角色卡」可以编辑或删除。删除后**不会自动恢复**——避免把用户清掉的内容塞回来。

## 自动化任务

「设置 → 自动化任务」里能配置定时触发的指令：

- **触发**：每天 / 每周 / 每月 / 每年 / 单次
- **执行方式**：直接发到聊天 / 后台跑（关屏也不影响）
- **模型 + 推理强度**：每个任务独立选

执行走 `AutomationReceiver`（`phoneuse/AutomationReceiver.kt`），完成后通过 Metis 后台通知反馈结果。

## 隐私与安全

- **密钥**：用 `EncryptedSharedPreferences` 加密存储在本地；永远不写日志、不上传
- **屏幕内容**：无障碍服务读取的内容仅供本地 LLM 调用，不上传任何遥测
- **危险操作**：「手机管家」角色会在支付、删除、发送前主动二次确认
- **网络出站**：仅 LLM 调用（你填的 baseUrl）、MCP 客户端主动连接、可选的 Metis 桌面端中继。无主动上报

## 限制

- 无障碍服务在某些厂商（MIUI、EMUI、ColorOS）上需要额外放开「后台保活」与「电池优化白名单」
- Android 14+ 的部分前台服务类型限制会更严，自动化任务建议走通知触发
- 截图 OCR 走的是 LLM 调用，端上不存原图；频次高时按 LLM token 计费

## 贡献

欢迎 PR。仓库布局：

```
app/src/main/java/com/mrgreenapps/a11ypilot/
├── MainActivity.kt            # 入口路由 + 文件预览
├── agent/                     # Agent 引擎、API 客户端、角色卡解析
├── data/                      # DataStore / Room 仓库
├── mcp/                       # 本机 MCP 服务器
├── phoneuse/                  # 自动化 + 通知监听
├── remote/                    # 与桌面端中继的 WebSocket 客户端
├── tools/                     # 工具实现（Termux / DocumentTool）
└── ui/                        # Compose 界面
```

## 许可

Apache 2.0。原始项目为 [azizahmed45/a11ypilot](https://github.com/azizahmed45/a11ypilot)，本仓库为其 fork 与扩展，保留上游版权与许可。