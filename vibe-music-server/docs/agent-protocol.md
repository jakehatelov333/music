# Agent 协议说明（第 9～10 项）

本文描述 Agent 工具返回协议、SSE 流式事件、待确认任务状态机与对外接口。
所有内容与仓库中实际代码一致，可在 `vibe-music-server` 中逐项对照。

---

## 一、工具返回协议

### 1.1 结构

所有 Agent 工具统一返回 `ToolResult` 的 **JSON 文本**：

```json
{
  "ok": true,
  "code": "OK",
  "message": "找到 1 首与「夜色」相关的歌曲",
  "kind": "SONG_LIST",
  "total": 1,
  "data": [
    {
      "songId": 10,
      "songName": "夜色温柔",
      "artistName": "测试歌手A",
      "album": "测试专辑A",
      "style": "流行",
      "duration": "03:20",
      "coverUrl": "http://.../10.jpg",
      "audioUrl": "http://.../10.mp3"
    }
  ]
}
```

实现：`model/agent/ToolResult.java`、`model/agent/SongRef.java`。

### 1.2 为什么返回 String 而不是让框架序列化对象

LangChain4j 0.36.2 在 `DefaultToolExecutor` 里对非 `String` 返回值会走自己的
`Json.toJson(...)` 路径，字段与格式不可控。返回已序列化的 JSON 可以完全掌握
字段集合，也让“成功但为空”与“调用失败”在模型侧清晰可辨。

序列化统一走 `model/agent/AgentJson.java`（注册 JSR-310 模块，时间写成 ISO 字符串）。
这一点有专门的回归测试：曾经因为用了裸 `ObjectMapper`，待确认预览里的
`LocalDateTime` 让整个 `confirm` 事件退化成 `{}`。

### 1.3 语义约定

| code | ok | 含义 | 模型应该怎么做 |
|---|---|---|---|
| `OK` | true | 有数据 | 基于数据回答 |
| `EMPTY` | true | **查询成功但没有命中** | 如实告知未找到，可换关键词 |
| `INVALID_ARGUMENT` | false | 参数缺失/不合法 | 修正参数后重试 |
| `UNAUTHORIZED` | false | 需要登录（游客调用写工具） | 提示用户登录，不要重试 |
| `REJECTED` | false | 业务规则拒绝（歌曲不存在、数量超限） | 告知用户原因 |
| `NOT_FOUND` | false | 目标资源不存在 | 换目标 |
| `ERROR` | false | 工具内部异常 | 最多再试一次，之后如实说明工具不可用 |

**关键点：搜索无结果不是失败。** `EMPTY` 让模型能区分“换个词再试”和“放弃这个工具”。

### 1.4 kind 与前端渲染

| kind | 前端渲染 |
|---|---|
| `SONG_LIST` | 可播放歌曲卡片 |
| `ARTIST_LIST` / `ARTIST_DETAIL` | 文本 |
| `PLAYLIST_LIST` | 文本 |
| `PLAYLIST_PREVIEW` | **确认卡片**（必须用户确认） |
| `PLAYLIST_CREATED` | 歌单入口卡片 |
| `TEXT` | 纯文本 |

前端只依据 `kind` 决定组件，不解析 `message` 文本，因此模型措辞变化不会影响界面。

### 1.5 只读工具清单

| 工具 | 参数 | kind |
|---|---|---|
| `searchSongs` | `keyword` | SONG_LIST |
| `searchSongsByArtist` | `artistName` | SONG_LIST |
| `getSongsByStyle` | `styleName` | SONG_LIST |
| `getRandomSongs` | — | SONG_LIST |
| `searchArtists` | `keyword` | ARTIST_LIST |
| `getArtistDetail` | `artistName` | ARTIST_DETAIL |
| `searchPlaylists` | `keyword` | PLAYLIST_LIST |

写工具（**不直接写库**）：

| 工具 | 参数 | 行为 |
|---|---|---|
| `preparePlaylistCreation` | `title` / `introduction` / `style` / `songIds`（英文逗号分隔） | 校验歌曲真实性 → 落待确认任务 → 返回预览，**不创建歌单** |

---

## 二、可信用户上下文

- 工具方法签名里**没有任何用户标识参数**，模型无法伪造身份。
- 身份链路：JWT → `LoginInterceptor` → `ThreadLocalUtil` → `AgentContext`（每次运行一份）。
- `AgentContext` 是**不可变对象**，随工具实例一起构造
  （`MusicTools#withContext` / `PlaylistActionTools#withContext`），
  因此并发请求之间天然隔离，不存在 ThreadLocal 串用。
- 工具只拿到业务所需的 `userId` / `chatId` / `taskId`，不暴露密码、令牌等敏感信息。

实现：`model/agent/AgentContext.java`、`service/ai/AiAssistantFactory.java`。

---

## 三、SSE 流式协议

端点：`POST /ai/chat/stream`，`produces = text/event-stream;charset=UTF-8`。
事件名 = `AgentEvent.type()`，`data` 一律为纯文本（结构化事件是单行 JSON）。

| 事件 | 载荷 | 含义 |
|---|---|---|
| `start` | `{taskId, model}` | Agent 开始处理 |
| `tool` | `{taskId, toolCallId, tool, phase:"start"\|"end", ok, code, message, total, kind, durationMs}` | 工具开始/结束 |
| `songs` | `{taskId, toolCallId, kind, total, data:[SongRef]}` | 结构化歌曲数据 |
| `confirm` | `{data: PendingActionVO}` | 等待用户确认 |
| `task` | `{ok, code, message, data:{taskId, model, success, failureCategory, modelCalls, toolCalls, toolFailures, resultCount, latencyMs, inputTokens, outputTokens, tokenSource, playlistId, pendingTaskId}}` | 任务结束汇总 |
| `token` | 增量文本 | **沿用既有协议，格式未变** |
| `done` | 最终文本 | **沿用既有协议，格式未变** |
| `error` | 错误说明 | **沿用既有协议，格式未变** |

同一任务的多个事件通过 `taskId` 关联；同一次工具调用的 `start` / `end` / 结构化数据
通过 `toolCallId` 关联。

### 输出护栏（重要契约）

**`done` 事件是权威最终文本，`token` 只是渐进预览。**

模型有概率跳过写工具、却在文本里声称「已生成预览，请在下方确认卡片中确认」。
数据库是安全的（没调用工具就没有任何写入），但界面会误导用户去找一张不存在的卡片。
服务端因此加了一道**输出护栏**：用服务端事实校正 `done` 的文本。

| 模型声称 | 需要的服务端事实 | 不满足时 |
|---|---|---|
| 有待确认卡片（含「确认卡片」「待确认」） | 本轮真的产生过待确认任务（`pendingTaskId != null`） | `done` 替换为如实说明 |
| 已创建成功 | 本轮真的写入过歌单（`playlistId != null`） | `done` 替换为如实说明 |

命中时 `task` 事件的 `data.outputGuarded` 为 `true`，并落库到 `tb_ai_task_metric.output_guarded`
（这是衡量「模型有没有真的执行工具」的直接信号）。

**客户端要求**：必须用 `done` 的文本覆盖已累积的 `token` 文本，
不能只依赖流式片段，否则会看到未校正的中间态。

### 异常与中断

- 成功：`start → (tool/songs/confirm)* → token* → task(true) → done`
- 失败：`... → task(false, failureCategory) → error`
- 超时：同上，`failureCategory=TIMEOUT`
- 客户端断开：不推送 `task`，但服务端**必定**执行结算（写指标 + 释放会话租约），
  并把 `failureCategory` 记为 `INTERRUPTED`。
- 事件发送失败（连接已断）后，该通道整体关闭，后续事件静默丢弃，不会抛异常打断任务。

**页面不会因异常永久停留在加载状态**：`done`、`error`、连接结束三种情况都会
在前端触发终态回调，且只触发一次。

### 失败分类

`NONE` / `MODEL_ERROR` / `TOOL_ERROR` / `TIMEOUT` / `INTERRUPTED` / `BUSINESS_ERROR` / `UNKNOWN`
（`service/agent/AgentFailureCategory.java`）。

对外只暴露**分类级**的人类可读说明，绝不回传原始异常文本
（供应商异常里可能带请求 ID、URL 甚至凭据片段）。

---

## 四、待确认任务

### 4.1 状态机

```
PENDING ──claim──▶ EXECUTING ──成功──▶ SUCCEEDED
   │                   │
   │                   └──失败──▶ FAILED
   ├──用户取消──▶ CANCELLED
   └──超时────▶ EXPIRED
```

`SUCCEEDED` / `FAILED` / `CANCELLED` / `EXPIRED` 为终态。
`FAILED` 不会自动重试：在不确定实际写入状态的情况下重复执行是危险的，
用户需要重新发起创建。

### 4.2 持久化

表 `tb_agent_pending_action`（`sql/phase4_agent_actions.sql`）：

| 字段 | 用途 |
|---|---|
| `task_id` | 对外任务 ID（UUID），**同时是幂等键** |
| `user_id` | 任务归属，来自服务端认证 |
| `chat_id` | 关联会话，用于刷新后重建确认卡片 |
| `draft_json` | 执行所需的业务参数（标题/简介/风格/可见性/歌曲 ID） |
| `preview_json` | 用户确认时看到的预览快照 |
| `version` | 预览版本号，每次修改 +1 |
| `status` | 状态机取值 |
| `result_playlist_id` | 成功后真实创建的歌单 ID |
| `error_code` / `error_message` | 失败原因 |
| `expires_at` | 有效期（默认 30 分钟，`ai.pending-action-ttl-minutes`） |

待确认数据**不放在前端变量、浏览器内存或模型上下文里**，因此刷新页面后仍可查询。

### 4.3 幂等与并发保护

三层防护，全部落在数据库：

1. **抢占**：`UPDATE ... SET status='EXECUTING' WHERE task_id=? AND user_id=? AND expires_at>now
   AND (status='PENDING' OR (status='EXECUTING' AND updated_at < staleBefore))`
   —— 只有把 `PENDING` 真正改成 `EXECUTING` 的那次调用返回 1，其余并发请求返回 0 并
   返回已落库的结果，**不会重复执行**。
2. **僵死回收**：`EXECUTING` 超过 60 秒视为进程崩溃残留，允许重新抢占；
   重新执行仍用同一个 `task_id` 作幂等键。
3. **创建收据**：真正的创建复用既有 `PersonalPlaylistService#create` 的
   `tb_playlist_create_request` 收据表。即使进程在“已创建、未落结果”之间崩溃，
   重试也会命中收据并返回同一个歌单，而不是再建一个。

另外还有**乐观锁**：修改预览必须带 `version`，确认时也可带 `version`，
防止“确认的是旧预览、执行的是新内容”。

### 4.4 确认时重新鉴权

`PendingActionService#confirm` 每次都会：

1. 从当前请求的 JWT 重新取身份（`PlaylistAccessService.requireUserId()`，游客 401）；
2. 校验任务归属（不是自己的任务一律 404，不泄漏存在性）；
3. 校验任务状态与有效期（已取消 409、已过期 410、终态直接重放结果）；
4. 校验版本号；
5. 校验歌曲仍然存在（`PersonalPlaylistService#requireSongs`）；
6. 校验歌单数量/名称等业务规则（DTO 校验 + 服务层校验）。

客户端提交的任何参数都不被信任；前端也无法通过篡改 `taskId` 操作他人的任务。

### 4.5 对外接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/ai/actions/pending?chatId=` | 当前用户的待确认任务；`chatId` 可省略 |
| GET | `/ai/actions/{taskId}` | 查询真实状态与执行结果 |
| PUT | `/ai/actions/{taskId}` | 确认前修改名称/歌曲列表（必须带 `version`） |
| POST | `/ai/actions/{taskId}/confirm?version=` | 确认并执行 |
| POST | `/ai/actions/{taskId}/cancel` | 取消 |

全部要求登录（`PlaylistAccessService.requireUserId()`）。
路径挂在 `/ai/` 下以复用既有角色-路径权限（`ROLE_USER` 拥有 `/ai/` 前缀）。

### 4.6 预览与执行的一致性

预览与执行**共享同一份数据源**（`PlaylistDraft`）：

- `prepare` 时由 `PlaylistDraft` 渲染预览并持久化；
- `confirm` 时**仍然**从 `PlaylistDraft` 构造真实创建请求；
- 用户修改后两者一起重建并提升版本号。

因此不存在“看到的和写进去的不一致”。这一点有数据库级断言：
评测用例 `mismatch-01` 会比对预览歌曲集合与 `tb_playlist_binding` 的实际绑定。

---

## 五、执行指标

表 `tb_ai_task_metric`（每次 Agent 任务一行）：

`task_id` / `user_id` / `chat_id` / `model` / `model_calls` / `tool_calls` /
`tool_failures` / `input_tokens` / `output_tokens` / `token_source` /
`result_count` / `success` / `failure_category` / `failure_message` /
`latency_ms` / `playlist_id` / `created_at`

- Token 用量取自模型响应 `Response#tokenUsage()`，是**实测值**；
  拿不到时 `token_source=UNAVAILABLE` 且两个 token 字段为 `NULL`，
  **绝不把估算值写成实测值**。
- 表里**不存**用户消息、模型回复、API Key 或任何认证凭据。
- 查询入口：`GET /ai/chat/metrics?limit=`（只返回当前用户自己的指标）。
