# ModelRouter - Android AI 模型路由代理

运行在 Android 设备上的智能 AI 模型路由代理服务器。在手机本地启动 HTTP 服务，接收 OpenAI / Anthropic 格式的 API 请求，自动选择最快可用模型，转发到上游 Provider，支持流式/非流式响应、429 限流智能处理、Key 自动轮换、协议双向转换等高级功能。

## 核心特性

### 智能路由

- **自动选模型** - 基于测速结果自动选择响应最快的模型，1.5x 容差范围内选连接数最少的，实现负载均衡
- **模型锁定** - 可锁定分组到指定模型，避免自动切换
- **多端口多分组** - 每个分组绑定独立端口（8190-8194），可独立启停
- **并发连接感知** - 选模型时考虑当前活跃连接数，避免单模型过载
- **模型降级与自动恢复** - 超时/5xx 自动标记降级，到期后自动重试恢复

### 协议兼容

- **OpenAI Chat Completions** - `/v1/chat/completions`，完整兼容 OpenAI API
- **Anthropic Messages API** - `/v1/messages`，完整兼容 Anthropic API
- **双向协议转换** - Anthropic <-> OpenAI 请求/响应自动转换，对客户端透明
- **流式 + 非流式** - 两种协议均支持流式（SSE）和非流式响应
- **Thinking / 推理内容** - 支持 `reasoning_content` -> Anthropic `thinking` block 转换
- **Tool Use / Function Calling** - 完整支持工具调用的协议转换
- **图像输入** - 支持 base64 / URL 图像的 Anthropic -> OpenAI 格式转换

### 限流与容错

- **429 智能处理** - 区分"早期 429"（模型级限流，自动切换模型）和"普通 429"（Key 级限流，自动换 Key）
- **自动 Key 轮换** - 多 Key 自动切换，支持阈值切换和轮询切换两种策略
- **模型自动切换** - 遇到 early rate limit 自动切换到其他可用模型
- **指数退避重试** - 429 和超时自动重试，带抖动的指数退避
- **请求参数过滤** - 自动移除非标准参数，避免上游 400 错误
- **服务过载保护** - 线程池满时返回 503

### 测速与监控

- **TTFT 测速** - 基于首字节到达时间（Time To First Token）的实时测速
- **启动自动测速** - 应用启动时自动对所有已启用模型测速
- **批量测速** - 5 并发批量测速所有模型
- **定时自动测速** - 可开启每 5 分钟自动测速
- **调用统计** - 按模型统计调用次数和错误率
- **健康检查** - `/health` 端点

### Provider 管理

- **多 Provider 支持** - 可添加多个 AI 服务商（默认内置 NVIDIA NIM 和 Agnes AI）
- **从 API 自动获取模型** - 一键从 Provider 的 `/models` 端点拉取可用模型
- **多种限流策略** - 无限制 / 每分钟 / 每 5 小时 / 每天
- **Provider 级别 Key 管理** - 每个 Provider 独立管理 API Key 和限流配置

## API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/v1/chat/completions` | POST | OpenAI 兼容的 Chat Completion（流式 + 非流式） |
| `/v1/messages` | POST | Anthropic Messages API 兼容（流式 + 非流式） |
| `/v1/models` | GET | 列出所有可用模型（从各 Provider 聚合） |
| `/api/status` | GET | 获取当前路由状态（锁定模型、端口、分组） |
| `/api/speed_test` | POST | 对指定模型发起测速 |
| `/api/lock` | POST | 锁定分组到指定模型 |
| `/api/unlock` | POST | 解锁分组 |
| `/api/config` | GET | 获取当前配置 |
| `/api/stats` | GET | 获取调用统计 |
| `/api/dashboard` | GET | 获取仪表盘全量数据 |
| `/api/reload` | POST | 热重载所有配置 |
| `/api/lock_model` | POST | 锁定指定分组的模型 |
| `/api/unlock_model` | POST | 解锁指定分组 |
| `/api/lock_status` | GET | 查询锁定状态 |
| `/health` | GET | 健康检查 |

## 使用方式

### 快速开始

1. 安装并启动 App
2. App 自动在本地启动 HTTP 代理服务器（默认端口 8190）
3. 将你的 AI 客户端 API Base URL 设置为 `http://<手机IP>:8190`
4. 使用 OpenAI 或 Anthropic 格式发送请求即可

### 请求示例

**OpenAI 格式：**

```bash
curl http://<手机IP>:8190/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "meta/llama-3.1-405b-instruct",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": true
  }'
```

**Anthropic 格式：**

```bash
curl http://<手机IP>:8190/v1/messages \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-3-5-sonnet-20241022",
    "max_tokens": 1024,
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": true
  }'
```

### 多分组使用

不同分组绑定不同端口，可用于区分不同用途：

| 分组 | 端口 | 用途 |
|------|------|------|
| 综合对话组 | 8190 | 通用对话和问答 |
| 代码组 | 8191 | 代码生成、审查、调试 |
| 复杂组 | 8192 | 复杂推理任务 |
| 图像组 | 8193 | 图像解析 |
| 语音处理 | 8194 | 语音处理 |

将客户端指向对应端口即可使用该分组的模型。

## 管理界面

App 提供 4 个管理页面：

### 模型市场

- 浏览 NVIDIA NIM 可用模型列表
- 按名称 / ID / 厂商搜索模型
- 一键添加模型到配置分组
- 查看模型详情和文档链接

### 仪表盘

- 实时展示分组状态和模型健康度
- 查看每个模型的响应时间和请求数
- 模型锁定 / 解锁操作
- 单模型测速和批量测速
- Key 使用率监控（请求计数 / 限流上限）
- 自动刷新（每 10 秒）

### 配置管理

- 分组 CRUD：添加 / 删除 / 重命名分组，启用 / 禁用分组
- 模型 CRUD：添加 / 替换 / 删除模型，启用 / 禁用单个模型
- 从 Provider 模型列表选择或手动输入模型 ID

### API 密钥管理

- Provider 卡片式管理：名称、限流类型、Key 切换策略
- API Key 增删改（脱敏显示）
- 可用模型列表管理
- 一键从 Provider API 拉取模型
- 限流配置：类型、限流值、切换阈值

## 默认 Provider 配置

| Provider | Base URL | 限流 | Key 数量 |
|----------|----------|------|----------|
| NVIDIA NIM | `https://integrate.api.nvidia.com/v1` | 40 次/分钟 | 3 |
| Agnes AI | `https://apihub.agnes-ai.com/v1` | 无限制 | - |

## 项目结构

```
app/src/main/java/com/example/modelrouter/
├── models/              # 数据模型
│   ├── ApiResponse.kt         # API 响应模型
│   ├── ConfigModelItem.kt     # 配置中的模型项
│   ├── DashboardData.kt       # 仪表盘数据模型
│   ├── GroupItem.kt           # 分组项
│   ├── ModelItem.kt           # 模型市场模型
│   ├── ModelStats.kt          # 模型统计
│   └── ProviderInfo.kt        # Provider 信息 + 限流/切换策略枚举
├── network/             # 网络请求模块
│   ├── ApiService.kt          # Retrofit API 接口
│   ├── ModelRepository.kt     # Repository 封装
│   ├── NvidiaApiService.kt    # NVIDIA NIM API 客户端
│   ├── RetrofitClient.kt      # Retrofit 客户端单例
│   └── RouterApiService.kt    # 本地路由服务器 API 客户端
├── service/             # 核心服务
│   ├── ModelRouterServer.kt   # HTTP 代理服务器（核心）
│   ├── RouterService.kt       # Android 前台服务
│   ├── RouterState.kt         # 全局路由状态管理
│   ├── ConfigManager.kt       # 配置管理器
│   ├── ProviderManager.kt     # 多 Provider 管理器
│   ├── ApiKeyManager.kt       # API Key 管理器
│   ├── StatsManager.kt        # 调用统计管理器
│   ├── SpeedTester.kt         # 模型测速器
│   └── ProtocolConverter.kt   # Anthropic <-> OpenAI 协议转换器
├── ui/                  # UI 组件
│   ├── MainActivity.kt        # 主 Activity
│   ├── fragments/             # 页面
│   │   ├── ModelsFragment.kt        # 模型市场
│   │   ├── DashboardFragment.kt     # 仪表盘
│   │   ├── ConfigFragment.kt        # 配置管理
│   │   └── ApiKeysFragment.kt       # API 密钥管理
│   └── adapters/              # 适配器
│       ├── ModelAdapter.kt          # 模型市场适配器
│       ├── DashboardAdapter.kt      # 仪表盘适配器
│       └── ConfigAdapter.kt         # 配置适配器
├── utils/               # 工具类
│   └── Constants.kt           # 常量定义
└── viewmodels/          # 视图模型
    ├── ModelViewModel.kt      # 模型 ViewModel
    └── ConfigViewModel.kt     # 配置 ViewModel
```

## 技术栈

- **语言**: Kotlin
- **HTTP 服务器**: NanoHTTPD
- **HTTP 客户端**: OkHttp + Retrofit 2
- **架构**: MVVM (ViewModel + LiveData)
- **UI**: Material Design 3
- **序列化**: Gson
- **最低 SDK**: 24 (Android 7.0)
- **目标 SDK**: 34 (Android 14)

## 构建与运行

1. 使用 Android Studio 打开项目
2. 等待 Gradle 同步完成
3. 连接 Android 设备（需开启 USB 调试或无线调试）
4. 点击运行按钮

确保手机和调用方在同一局域网内。

## Android 特性

- **前台服务保活** - 防止系统杀掉服务，`START_STICKY` 自动重启
- **通知权限适配** - Android 13+ 动态请求通知权限
- **崩溃保护** - 全局 Crash Handler，崩溃前停止服务器释放资源
- **HTTP 明文流量** - 允许局域网 HTTP 访问（`usesCleartextTraffic`）
- **配置热重载** - 修改配置后无需重启，`/api/reload` 即时生效
