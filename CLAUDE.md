# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

Vibe Music 是一个全栈音乐流媒体平台，包含三个独立子项目（无父级 POM/工作空间）：

| 子项目 | 类型 | 端口 | 核心技术 |
|---|---|---|---|
| `vibe-music-server/` | Spring Boot 3 后端 | 8080 | Java 17, Maven, MyBatis-Plus 3.5.9, MySQL, Redis, MinIO, LangChain4j 0.36.2 |
| `vibe-music-admin/` | 管理端 SPA | 8089 | Vue 3, Vite 6, Pinia, Element Plus, Tailwind CSS, vue-pure-admin |
| `vibe-music-client/` | 用户端 SPA | 8090 | Vue 3, Vite 6, Pinia, Element Plus, Tailwind CSS, ArtPlayer |

**远程仓库：** `github.com/jakehatelov333/music`（当前分支 `test1`）

## 常用命令

### 服务端 (`vibe-music-server/`)

```bash
cd vibe-music-server
mvn spring-boot:run                        # 启动开发服务器 (8080)
mvn -B -ntp verify                         # 构建 + 全部测试（CI 使用的命令）
mvn test -Dtest=AgentEvalReportTest        # 只跑 Agent 离线评测（cases.json 用例集）
mvn test -Dtest=SongArtistContractTest#方法名 -DfailIfNoTests=false   # 单个测试方法
```

### 管理端 (`vibe-music-admin/`)

```bash
cd vibe-music-admin
pnpm install && pnpm dev     # 安装依赖 + 启动 (8089)
pnpm build                   # 生产构建
pnpm lint                    # ESLint + Prettier + Stylelint 全量检查
pnpm typecheck               # tsc --noEmit && vue-tsc --noEmit --skipLibCheck
```

### 用户端 (`vibe-music-client/`)

```bash
cd vibe-music-client
pnpm install && pnpm dev     # 安装依赖 + 启动 (8090)
pnpm build                   # 生产构建
pnpm lint                    # ESLint 检查（lint:fix 自动修复）
pnpm type-check              # vue-tsc --noEmit
```

### CI (`.github/workflows/ci.yml`)

三个 job：后端 `mvn -B -ntp verify`（含离线评测报告上传）、用户端 `pnpm build`、管理端 `pnpm build`。
另有手动触发的 `online-eval`：需仓库 Secret `DASHSCOPE_API_KEY` + `-Donline=true`，调真实模型跑评测。

## 服务端架构 (`cn.edu.seig.vibemusic`)

**包结构：** `controller/`（21 个 REST 控制器）、`service/` + `service/impl/`、`service/agent/`、`service/ai/`、`service/lyrics/`、`mapper/`（接口 + `resources/mapper/*.xml`）、`model/entity|dto|vo|agent`、`config/`、`interceptor/`、`security/`、`memory/`、`task/`、`tool/`、`handler/`、`result/`、`util/`、`constant/`、`enumeration/`（10 个枚举）

### 认证与鉴权链路

- JWT（Auth0 java-jwt），密钥 `VIBE_MUSIC`，有效期 6 小时，Token 存 Redis（database 1）。
- `WebConfig` 注册两个拦截器：`LoginInterceptor`（order 0，`/**`）→ `RateLimitInterceptor`（order 1，`/**`）。**`excludePathPatterns` 只是让请求不经过登录校验，不代表匿名可用** —— 见下条。
- `LoginInterceptor` 的逻辑是「先查 token，再判路径」：无 token 时若路径命中 `PathConstant` 白名单（歌单列表/详情、歌手详情、歌曲列表/详情、专辑、`/ai/**`）则放行，否则 401。有 token 时校验 Redis 中的 token 存在性 + `RolePermissionManager.hasPermission(role, uri)`，失败返回 403。
- 角色-路径映射在 `application.yml` 的 `role-path-permissions.permissions`（`ROLE_ADMIN` → `/admin/`；`ROLE_USER` → `/user/ /playlist/ /artist/ /song/ /album/ /favorite/ /comment/ /banner/ /feedback/ /ai/`），由 `RolePathPermissionsConfig` 绑定。
- **新增公开端点时必须同步改两处**：`WebConfig` 的 `excludePathPatterns` 和 `PathConstant` 白名单。
- 身份通过 `ThreadLocalUtil` 传递；`security/PlaylistAccessService.requireUserId()` 取强制登录用户（游客抛 401），`optionalUserId()` 取可选登录用户（用于「匿名可见但登录后个性化」的端点）。
- 限流在 `security/RedisRateLimiter` + `RateLimitInterceptor`。

### Agent（AI 对话）—— 本项目最核心的子系统

模型：DashScope `qwen-flash`（`langchain4j.dashscope.model-name`），阻塞式 + 流式两套模型。

**每次请求装配一套独立实例**（`service/ai/AiAssistantFactory`）。四件事：

1. **可信上下文**：`AgentContext.of(userId, chatId, taskId, sink)` —— **工具方法签名里没有任何用户标识参数**，模型无法伪造身份。工具 bean 是 Spring 单例但本身无状态，通过 `withContext(context)` 派生带上下文的新实例，因此并发请求天然隔离，不依赖 ThreadLocal。
2. **指标采集**：`MeteredChatModels` 包装模型，把调用次数与**真实** Token 用量（取自 `Response#tokenUsage()`，拿不到记 `UNAVAILABLE`，绝不估算）写入 `AgentMetrics` → `tb_ai_task_metric`。
3. **事件出口**：`AgentEventSink` 让工具层直接上报工具开始/结束/结构化结果/等待确认。
4. **系统提示词**：由 `AgentSystemPrompt#build(userId, chatId)` 运行时组装。⚠️ **`MusicAssistant` 上的 `@SystemMessage` 必须保持删除状态** —— LangChain4j 0.36.2 的注解优先会让 `systemMessageProvider` **静默失效**（不报错，个性化悄悄不生效）。回归手段：`SystemMessageInjectionTest`。

**工具返回协议**（`tool/MusicTools.java` 9 个只读工具 + `tool/PlaylistActionTools.java` 1 个写工具）：
统一返回 `ToolResult` 的 **JSON 文本**（不是让框架序列化对象 —— LangChain4j 对非 String 返回值会走自己的 `Json.toJson`，字段不可控）。序列化统一走 `model/agent/AgentJson`（注册 JSR-310 模块；曾因裸 `ObjectMapper` 导致 `confirm` 事件里的 `LocalDateTime` 退化成 `{}`）。
`code` 语义：`OK` / `EMPTY`（查询成功但无命中，**不是失败**）/ `INVALID_ARGUMENT` / `UNAUTHORIZED` / `REJECTED` / `NOT_FOUND` / `ERROR`。
`kind` 决定前端渲染组件：`SONG_LIST` / `ARTIST_LIST` / `ARTIST_DETAIL` / `PLAYLIST_LIST` / `PLAYLIST_PREVIEW`（确认卡片）/ `PLAYLIST_CREATED` / `TEXT`。前端只依据 `kind` 选组件，不解析 `message`。
工具经 `MusicTools#withContext` 暴露，`AgentToolCodes` / `AgentToolKinds` 集中管理取值。

**SSE 协议**（`POST /ai/chat/stream`，事件名 = `AgentEvent.type()`，data 一律纯文本）：
`start` → (`tool` / `songs` / `confirm`)\* → `token`\* → `task` → `done`（失败时 `task(false, failureCategory)` → `error`）。
`taskId` 关联同一任务的多个事件，`toolCallId` 关联同一次工具调用的 start/end/结构化数据。
`token`/`done`/`error` 沿用旧格式，新增事件是叠加的（旧前端不会报错）。

**输出护栏（重要契约）**：`done` 是权威最终文本，`token` 只是渐进预览。模型有概率跳过写工具却声称「已生成预览，请在下方确认卡片中确认」——用服务端事实校正：声称有待确认卡片必须真的产生过 `pendingTaskId`，声称已创建必须真的有 `playlistId`，否则替换为如实说明并置 `outputGuarded=true`（落库到 `tb_ai_task_metric.output_guarded`）。**前端必须用 `done` 覆盖已累积的 `token` 文本**。

**异常结算**：`AiController.StreamSession.settle()` 用 CAS 保证「写指标 + 释放会话租约 + 关闭通道」只执行一次；成功/失败/超时/客户端断开都走同一路径（断开时 `failureCategory=INTERRUPTED`），页面不会永久停在加载态。

**待确认任务状态机**（`service/agent/PendingActionService`，表 `tb_agent_pending_action`）：
`PENDING → EXECUTING → SUCCEEDED|FAILED`，旁支 `CANCELLED` / `EXPIRED`（默认 30 分钟 TTL，`ai.pending-action-ttl-minutes`）。
三层数据库级防护：① 用 `UPDATE ... WHERE status='PENDING'` 抢占（返回 1 才执行，其余返回已落库结果）；② `EXECUTING` 超 60 秒视为僵死进程残留，允许同 `taskId` 重新抢占；③ 真实创建复用 `tb_playlist_create_request` 收据表，崩溃重试命中收据返回同一歌单。另有乐观锁 `version` 防止「确认的是旧预览、执行的是新内容」。
**确认时每次重新鉴权**：从当前 JWT 重取身份（游客 401）→ 校验任务归属（非本人一律 404，不泄漏存在性）→ 校验状态与有效期 → 校验版本 → 校验歌曲仍存在 → 校验业务规则。客户端参数一律不信任。
接口：`GET /ai/actions/pending?chatId=`、`GET|PUT /ai/actions/{taskId}`、`POST /ai/actions/{taskId}/confirm?version=`、`POST /ai/actions/{taskId}/cancel`（全部要求登录）。
预览与执行**共享同一份 `PlaylistDraft`**，因此不存在「看到的和写进去的不一致」（评测用例 `mismatch-01` 有数据库级断言）。

**个性化与记忆**（文档：`vibe-music-server/docs/agent-protocol.md`、`agent-personalization.md`）：
- 系统提示词 = 基础提示词 + `ChatSummaryService.renderSummaryBlock(chatId)`（滚动摘要，`ai.summary.*`）+ `UserMusicProfileService.renderPreferenceBlock(userId)`（音乐画像，游客只拿摘要、不拿画像）。注入内容**全部来自服务端数据库**，请求路径上不额外调模型。
- 画像事实来自收藏与自建歌单（暂无播放历史表），TTL 内直接读库；偏好概述用 qwen-flash 生成并按事实指纹缓存。
- 会话结束**异步**整理记忆与画像（`scheduleMemoryMaintenance`，延迟 1 秒），失败只记日志。
- `GET /ai/profile` 只返回当前登录用户自己的画像（游客 401）。
- 选曲去重：读取当前用户当前会话最近 N 条歌单预览（`ai.playlist-history.lookback`），在 SQL 层排除已用歌曲，避免连续创建同类歌单时整组重复。

**预算**：`ai.max-tool-calls`（当前 6）、`ai.timeout-seconds`（60）、`ai.max-output-tokens`（512），由 `AiRunBudget` + `BoundedChatModels` 约束。工具/模型调用超限抛异常，被 `AiController.classify()` 归到 `TOOL_ERROR`。

### 其它要点

- **多歌手**：`tb_song_artist` 关系表 + `SongArtistService` / `ArtistNameParser`。`SongRef.artists` 是结构化列表，`artistName` 是兼容用展示串（「银临 / Aki阿杰」）。详见 `docs/phase6-song-multiple-artists.md`。
- **两段式建库**：专辑/单曲上传「确认后创建」，`ArtistProvisioningService` 负责缺失歌手的创建。详见 `docs/phase6-*.md`。
- **歌词补全**：`service/lyrics/`（QQ 音乐主源 + LRCLIB 兜底），匹配打分阈值 `lyrics.match.min-score`，低于阈值宁可不写。异步任务 `task/`、Redis 存任务状态。
- **上传清理**：`task/SongUploadCleanupTask`、`AlbumUploadCleanupTask` 清理未提交的上传；待确认任务过期由 `AgentPendingActionExpiryTask` 扫描。
- **排序白名单**：列表接口的排序字段必须经 `util/SortWhitelistUtil` 校验，禁止直接拼接前端传入的列名。
- **MinIO**：`MinioService` 封装；对象名解析对端点迁移做了兼容。另见 `docs/` 下相关记录。
- **数据库脚本**：`vibe-music-server/sql/`。`vibe_music.sql` 是初始建表，其余为增量迁移（`phase2_*`、`phase3_*`、`phase4_agent_actions.sql`、`phase5_personalization.sql`、`phase6_song_multiple_artists.sql`、`album_migration.sql`、`migration_lyrics.sql`）。**改表结构要同时加迁移脚本**。
- MyBatis-Plus 驼峰映射 + `tb_` 前缀；`log-impl` 刻意设为 `NoLoggingImpl` 以免 SQL 参数泄露密码和聊天内容。

### 测试

- `AbstractAgentTest` 是 Agent 测试的共享基座：**真实 MySQL 兼容模式 H2 内存库 + 真实 mapper SQL + 真实业务服务**，刻意不用 mock 覆盖业务层（要验证的正是「确认前不写库」「幂等不重复建歌单」「越权被拒绝」这类数据库层面的事实）。**模型是唯一被替换的部件**（脚本化替身）。
- `agent/eval/AgentEvalReportTest` 跑 `src/test/resources/agent-eval/cases.json` 的离线用例（当前 50 条，测试断言其数量落在 30~50），输出 `target/agent-eval/report.md|json`，断言失败即构建失败（评测进 CI）。在线端到端评测需 `AI_EVAL_ONLINE=1` + `-Donline=true`。
- 测试不依赖 MySQL / Redis / MinIO / DashScope，可在 CI 中稳定重复执行。

### 敏感配置（务必遵守，见 `SECURITY.md`）

曾发生过 DashScope API Key 与 QQ 邮箱授权码被明文提交的泄露事件。现行规范：

- **任何密钥只允许通过环境变量 / `vibe-music-server/.env` 注入**（`spring.config.import: optional:file:./.env[.properties]`）；`application.yml` 里只允许 `${ENV_NAME:}` 占位符。模板见 `.env.example`（不含真实值）。
- `config/SecretsGuard` 启动期体检：prod 环境缺任一密钥直接启动失败，本地只告警。新增第三方服务时先在 `.env.example` 登记变量名，再登记到 `SecretsGuard.REQUIRED_SECRETS`，最后写配置占位符。
- `scripts/check-secrets.ps1 -Install -Repo <repo>` 安装 pre-commit 钩子拦截明文密钥。误提交即视为泄露，立即吊销重发。

## 用户端架构 (`vibe-music-client/`)

- Vue 3 Composition API + `<script setup>`；Pinia 持久化到 localStorage。
- **自动导入**：unplugin-auto-import 导入 vue/vue-router/pinia API 及 `src/utils/`、`src/stores/`、`src/hooks/` 导出；unplugin-vue-components 自动注册组件。新增工具函数无需手写 import。
- **Axios baseURL 硬编码 `http://localhost:8080`**（`src/utils/http.ts`），**但 AI 相关请求走 `VITE_AI_BASE ?? ''` 同源**（`src/api/agent.ts`、`api/ai.ts`）—— 因为游客会话身份在 Cookie 里，跨端口收发不稳定，同源才能保证连续对话。
- AI 流式必须用**原生 fetch**（axios 不支持 `ReadableStream`）。SSE 事件在 `src/utils/aiConversation.ts` 被组装成**区块模型** `Block[]`（`text` / `tool` / `songs`（分页卡片）/ `confirm`（确认卡片）/ `notice`），Markdown 经 `src/utils/aiMarkdown.ts` 安全渲染。`AiWidget.vue` 是全局浮窗（拖拽、游客模式、刷新后按 taskId 查回确认卡片）。
- 路由（history 模式）：`/landing`、`/login`、`/` 首页、`/library` 曲库、`/artist` + `/artist/:id`、`/album` + `/album/:id`、`/playlist` + `/playlist/:id`、`/my-playlists/:id?`、`/like`、`/user`。
- ArtPlayer 播放器 + `hooks/useAudioPlayer.ts` 管理播放状态；歌词组件在 `components/Lyrics/`。

## 管理端架构 (`vibe-music-admin/`)

- 基于 vue-pure-admin 模板。Axios 经 Vite 代理 `/api` → `http://localhost:8080`（路径重写移除 `/api`）。
- Vue Router **hash 模式**，路由模块自动从 `src/router/modules/*.ts` 导入（home、user、artist、song、songUpload、album、playlist、feedback、banner、error、remaining）；路由守卫验证 Cookie 中 token + userInfo 的 roles，白名单 `["/login"]`。
- 视图按业务模块划分：`views/{user,song,artist,album,playlist,banner,feedback}/`；上传功能独立在 `views/song-upload/`、`views/album-upload/`（路由 `/song-upload/index`、`/album-upload/index`，对应后端 `SongUploadController`、`AlbumUploadController` 的两段式建库流程）。
- 列表页列头排序依赖 `pinyin-pro`（拼音排序）与后端排序白名单字段，表头排序图标位置有专门样式约定。
- 自定义指令：auth、copy、longpress、optimize、perms、ripple。
- Tailwind CSS 暗色模式（class 策略），`preflight: false` 避免与 Element Plus 冲突。

## 文档索引

`vibe-music-server/docs/` 下有分阶段方案与验收记录：`agent-protocol.md`（Agent 协议权威说明）、`agent-personalization.md`、`agent-demo.md`、`agent-deployment.md`、`agent-e2e-verification.md`、`phase1-security.md`、`phase2-*`、`phase3-*`、`phase6-song-multiple-artists.md`、`plans/`。根目录另有 `agent_ai_plan.md`、`change_files.md`、`future_plan.md`、`SECURITY.md`。

**修改 Agent 相关代码前先读 `docs/agent-protocol.md`** —— 它记录了 SSE 契约、工具返回协议、状态机与幂等设计的完整理由，多数"看似可以简化"的地方都有明确的反例。
