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
| `searchSongs` | `keyword` + `limit`（留空 5，最大 20） | SONG_LIST |
| `searchSongsByArtist` | `artistName` + `limit`（留空 5，最大 20） | SONG_LIST |
| `getSongsByStyle` | `styleName` + `limit`（留空 5，最大 20） | SONG_LIST |
| `searchPlaylistCandidates` | `artistName` / `keyword` / `style`（至少一个）+ `limit`（留空 10，最大 20） | SONG_LIST（排除本会话已用歌曲与长期偏好排除项） |
| `getRandomSongs` | `limit`（留空 5，最大 20） | SONG_LIST |
| `searchArtists` | `keyword` | ARTIST_LIST |
| `getArtistDetail` | `artistName` | ARTIST_DETAIL |
| `searchPlaylists` | `keyword` | PLAYLIST_LIST |
| `getRecentPlayedSongs` | `limit`（留空 10，最大 20） | SONG_LIST |

**推荐类工具默认 5 首**（`searchSongs` / `searchSongsByArtist` / `getSongsByStyle` / `getRandomSongs`）：
用户说「推荐几首」「来点类似的」时要的是一个能听完的短列表，10 首会让模型倾向于整份复述、界面也要翻页。
用户明确说了数量（「来 10 首」「全部」）时由模型自己把 `limit` 调大，上限 20（`AgentSongQueryService.MAX_RESULTS`）。
`searchPlaylistCandidates` 保持默认 10：建歌单需要更大的候选池供挑选，预览里的歌数由模型决定而非默认值。

`getRecentPlayedSongs` 读的是「当前用户」的播放历史明细（`tb_play_history ⋈ tb_song`），
返回真实歌名/歌手/封面/播放地址：画像里的 `played_song_count` / `recent_play_artists` 只是聚合信号，
**没有歌名**。游客调用返回 `UNAUTHORIZED`，没有历史返回 `EMPTY`；两者都不能让模型去猜歌名。
不返回 `last_position_seconds`（进度不属于列表契约）。

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
| `start` | `{type, taskId, message, data:{model, chatId}}` | Agent 开始处理 |
| `tool` | `{taskId, toolCallId, tool, phase:"start"\|"end", ok, code, message, total, kind, durationMs}` | 工具开始/结束 |
| `songs` | `{taskId, toolCallId, kind, total, data:[SongRef]}` | 结构化歌曲数据 |
| `confirm` | `{data: PendingActionVO}` | 等待用户确认 |
| `task` | `{ok, code, message, data:{taskId, model, success, failureCategory, modelCalls, toolCalls, toolFailures, resultCount, latencyMs, inputTokens, outputTokens, tokenSource, playlistId, pendingTaskId}}` | 任务结束汇总 |
| `token` | 增量文本 | **沿用既有协议，格式未变** |
| `done` | 最终文本 | **沿用既有协议，格式未变** |
| `error` | 错误说明 | **沿用既有协议，格式未变** |

同一任务的多个事件通过 `taskId` 关联；同一次工具调用的 `start` / `end` / 结构化数据
通过 `toolCallId` 关联。

> ⚠️ **`start` 的 `chatId` 在 `data` 里，不在顶层。** 服务端发的是完整的 `AgentEvent` record，
> 所有结构化载荷（`model` / `chatId`）都在 `data` 下。前端曾按顶层 `payload.chatId` 读取，
> 拿到 `undefined` 后「新对话」标志永远清不掉 —— 每条消息都带 `newConversation:true`，
> 每轮新建一段对话，用户看到的是「聊到第三轮模型突然失忆」，实际是三轮落在三个 chatId 上。
> 前端 `src/api/ai.ts#parseStartEvent` 负责归一化成扁平的 `{taskId, model, chatId}`，
> 回调方不必关心嵌套；`tests/ai-widget.test.mjs` 有「连续三条只建一段会话」的回归用例。

### 输出护栏（重要契约）

**`done` 事件是权威最终文本，`token` 只是渐进预览。**

模型有概率跳过写工具、却在文本里声称「已生成预览，请在下方确认卡片中确认」。
数据库是安全的（没调用工具就没有任何写入），但界面会误导用户去找一张不存在的卡片。
服务端因此加了一道**输出护栏**：用服务端事实校正 `done` 的文本。

| 模型声称 | 需要的服务端事实 | 不满足时 |
|---|---|---|
| 有待确认卡片（含「确认卡片」「待确认」） | 本轮真的产生过待确认任务（`pendingTaskId != null`） | `done` 替换为如实说明 |
| 已创建成功 | 本轮真的写入过歌单（`playlistId != null`） | `done` 替换为如实说明 |
| 已按播放历史过滤/排除 | 本轮真的上报过 `playedHistoryFiltered`（**当前没有任何工具会上报**） | `done` 替换为如实说明 |

**第三类（播放历史过滤）的由来**：真实会话里，工具返回 5 首（其中 3 首已在播放记录中），
模型自行挑掉 3 首后回复「已为您过滤掉播放历史中已听过的歌曲，以下是推荐的 2 首新曲」。
推理是对的、归属是错的，而且与界面上仍是 5 首的歌曲卡片直接矛盾。

关键点：

- **服务端确实没有这个能力**——`searchPlaylistCandidates` 排除的是「本会话近期歌单已用过的歌」，
  与 `tb_play_history` 无关；`getRecentPlayedSongs` 只读不筛。所以这类说法描述的必然不是服务端行为。
- 判定**依赖事实而非"我记得没有这个能力"**：`AgentMetrics.playedHistoryFiltered` 默认 `false`，
  将来真加了按播放历史排除的工具参数，只要在工具里调 `markPlayedHistoryFiltered()`，
  护栏会自动放行，不需要改护栏代码。
- 正则要求**完成态标记**（已/系统已/已自动），因此不会误伤
  「你是想让我去掉听过的歌吗」「我可以按播放历史过滤，需要吗」这类复述与建议。
- 护栏只改**话术**，不碰数据：不会因此产生或取消任何待确认任务。

命中时 `task` 事件的 `data.outputGuarded` 为 `true`，并落库到 `tb_ai_task_metric.output_guarded`
（这是衡量「模型有没有真的执行工具」的直接信号）。

**护栏必须在结算之前跑。** `AgentMetrics` 是在 `StreamSession.settle()` 里落库的，
而 `outputGuarded` 由护栏置位。早先的写法是「先 `settle()`（内部已落库）再跑护栏」，
结果 2026-10-21 那次「已为您过滤掉播放历史中已听过的歌曲」的事故里，
护栏即使生效，库里 `output_guarded` 也仍然是 `0` —— 指标成了查不出问题的摆设。
现在 `settle(..., beforeRecord)` 提供前置钩子，护栏在其中执行；
`AgentSseProtocolTest#playedHistoryFilterClaimIsCorrectedEndToEnd` 同时断言事件与落库值。

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

## 六、动态系统提示词与画像接口

### 6.1 提示词在运行时组装

系统提示词不再来自 `@SystemMessage` 注解，而是每次运行由
`AiAssistantFactory` 通过 `AiServices.systemMessageProvider` 调用
`AgentSystemPrompt#build(userId, chatId)` 组装：

```
基础提示词（MusicAssistant.SYSTEM_PROMPT）
  + 【更早对话的摘要】   ChatSummaryService.renderSummaryBlock(chatId)   无则跳过
  + 【当前用户的音乐偏好】 UserMusicProfileService.renderPreferenceBlock(userId) 无则跳过
```

- 注入的画像/摘要**全部来自服务端数据库**，请求路径上不因此多一次模型调用；
- 偏好块放最后（含用户可影响的内容，避免盖住基础提示词的安全约束）；
- 游客只拿摘要、不拿画像；
- 评测基座注入 `StaticSystemPrompt`（仅基础提示词），离线评测基线不受个性化影响；
- ⚠️ `MusicAssistant` 上的 `@SystemMessage` 必须保持删除状态：
  LangChain4j 0.36.2 的解析顺序是"注解优先"，注解一旦加回，
  `systemMessageProvider` 会**静默失效**（不报错，个性化悄悄不生效）。
  回归手段：`SystemMessageInjectionTest` 直接断言模型收到的第一条消息。

详细防线、阈值、CAS 幂等与排查手册见 `agent-personalization.md`。

### 6.1.1 基础提示词的设计约束（歌名只能来自曲库 + 诚实归属）

基础提示词按**场景分组的原则**组织，不再是一长串编号禁令。旧版 18 条编号规则里，
模型只稳定执行了「调工具」和「复述 message」两条最容易的，其余被稀释——
表现就是回答退化成「把工具 JSON 改写成项目符号列表」，也就是用户感受到的"传统风格"。

**第 0 条（最优先）：歌名只能来自曲库。** 任何要说出歌名的回答都必须先调用检索工具，
且只工具结果里出现过的歌名。模型关于歌手/专辑/代表作的知识**不能**作为推荐依据——
曲库只有几百首，绝大多数歌手的经典作品并不在里面。

这条来自 2026-10-21 的真实事故：用户问「推荐下历史播放列表类似的歌」，
工具如实返回 3 首（西西里 / 乡间的路 / 爱琴海），模型却回答「推荐《晴天》《安静》《星晴》」，
三首**一首都不在曲库里**；指标显示那一轮只有 1 次工具调用（取播放记录），推荐部分零检索。
更早一轮甚至凭空写了「我刚查了下，目前有 10 首候选歌曲」，而 `tool_calls=0`。

根因不是模型不听话，而是旧提示词只枚举了「工具结果 / 创建歌单 / 播放记录」三类场景，
「推荐」根本不在覆盖范围内，模型于是把它当闲聊、用预训练知识作答；
旧的「绝对不要编造歌曲」也只约束 `songId`，对说出口的歌名没有约束力。
由 `PersonalizedSystemPromptTest#basePromptRequiresToolLookupBeforeNamingAnySong` 锁定。

另外三条原则由 `basePromptKeepsHonestAttributionRules` 锁定：

1. **允许自行推理**——对话里已出现的信息可以直接拿来筛选、比较、取舍，不必每一步都回工具；
2. **如实归属**——"我自己挑掉的"必须说成"我按你刚才那份记录挑掉了"，
   不能写成"系统已为你过滤掉"；工具没做过的事不得记在工具头上；
3. **与界面一致**——文字必须和歌曲卡片显示的条数一致，除非真的重新检索产生了新卡片。

另由 `basePromptStaysCompact` 限制编号规则条数（≤10），防止又长回一长串禁令。

### 6.1.2 空结果：给事实，不给指令

**工具返回的 `message` 里不得出现「不要改用其它工具」这类停止信号。** 助手跑的是
LangChain4j `AiServices` 的 ReAct 循环（模型决策 → 调工具 → 观察 → 再决策），
一次检索为空本该是「换一条路」的信号；一旦工具直接下指令禁止探索，
循环里唯一有价值的动作就被掐断了，模型只能把「没找到」原样转述给用户。

真实事故（2026-10-21 21:40）：用户问「推荐最近播放歌曲类似风格的歌」，模型正确归纳出
「周杰伦 + 华语流行」并调用候选检索，工具返回「…可能曲库中没有…**不要改用其它工具**绕过
排除条件」，模型于是回答「很抱歉，目前曲库里暂时没有适合的推荐歌曲」。
而数据库里该歌手有 7 首、该风格有 57 首 —— 只是那 7 首**没有风格标注**，交集才为 0。

现在 `MusicTools#emptyDiagnosis` 只陈述可观察的事实：

| 情形 | 返回的事实 |
|---|---|
| 完整条件与逐项放宽都是 0 | 「未找到…曲库里确实没有这类歌」 |
| 完整条件为 0、单项放宽有结果 | 「只按歌手有 N 首；只按风格有 M 首 → 条件交集过窄」 |
| 曲库里有但被会话历史挡掉 | 「本会话已用过 N 首，不会重复出现」+ 复用出路 |
| 用户偏好排除生效 | 排除的歌手/风格数量 + 「最终推荐不得违反」 |

最后一条是关键区别：**约束是对结果的要求，不是对手段的限制**。模型可以用任何工具探索，
只是最终推荐不能违反用户设定的排除条件。这与「禁止换工具」是两回事。

配套改动：

- `AgentSongMapper#countMatching` 提供只读诊断计数，筛选条件与 `findSongsExcluding` 完全一致，
  但**不应用**会话历史与偏好排除 —— 回答的是「曲库里有没有」，不是「这次还剩几首」。
- `UserMusicProfileService` 删掉了「最多再调用 1~2 次检索工具」。那条约束压住了检索扇出，
  但也把模型换路径的预算提前花光；扇出是**预算太小**的症状，应靠放开预算解决。
- `ai.max-tool-calls` 从 6 提到 10：每次「换条路试试」至少花 1 次工具调用，6 次只够走一条直线。

回归：`EmptyResultDiagnosisTest`（4 条，含事故复现）与
`PersonalizedSystemPromptTest#basePromptTreatsEmptyResultAsSignalToAdapt`。

采样温度固定为 `ai.temperature`（默认 `0.3`）而不是供应商默认值：助手的工作是
"如实转述服务端事实 + 简短判断"，不需要创作性发散。实测同一段对话里，
同样的工具返回（一字不差）两轮给出了两种语气，其中一轮把"模型自行筛选"说成了"系统已过滤"。
**要更活泼的文案请改提示词，不要靠提高温度碰运气。**

摘要（`ChatSummaryService.MERGE_PROMPT`）**刻意不记录具体歌曲清单**：
推荐过哪几首、歌单里放了哪几首、哪几首"已听过"都不进摘要。那是一次性的对话产物，
会过期、会与曲库变化不一致，而且会被后续对话当成事实复用——实测出现过
摘要把已播放的歌写成"推荐了未听过的歌"，再被下一轮照抄的情况。

### 6.2 画像查询接口

| 接口 | 说明 |
|---|---|
| `GET /ai/profile` | 当前登录用户自己的音乐画像（只读）。游客返回 401；只返回本人的数据。 |
