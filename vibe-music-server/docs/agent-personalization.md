# AI 个性化与摘要记忆（`agent_ai_plan.md` 阶段二剩余）

> 对应方案：`docs/plans/2026-10-12-ai-personalization-and-summary-memory.md`
> 实施结果与偏差：见该方案文末「实施记录」章节。
> 协议层面的 SSE / 工具返回约定见 `agent-protocol.md`，本文只讲个性化与记忆。

## 1. 一句话架构

```
收藏 + 自建歌单 ──SQL 聚合──▶ tb_user_music_profile（显式事实）
播放历史 ─────────SQL 聚合──▶ 同上（隐式事实，独立字段，绝不混入显式字段）
                                   │ factsHash 指纹
                                   ▼
                            MusicTasteSummaryService（qwen-flash，异步）
                                   │ pref_summary
对话历史 chat_messages ──超阈值──▶ ChatSummaryService（qwen-flash，异步 + CAS）
                                   │ ai_chat_state.summary_text
                                   ▼
              AiAssistantFactory.systemMessageProvider ──▶ 基础提示词 + 摘要 + 偏好
```

两条铁律：

1. **请求路径零模型调用** —— 个性化内容全部来自数据库；模型调用只发生在会话结束后的异步任务里。
2. **个性化是锦上添花** —— 任何一环故障都只跳过对应内容、记日志，绝不影响对话本身。

## 2. 用户画像（`UserMusicProfileService` / `tb_user_music_profile`）

### 2.1 数据来源与边界

| 维度 | 来源 | 说明 |
|---|---|---|
| 收藏歌曲 | `tb_user_favorite`（`type=0`） | 计入 `fav_song_count` |
| 收藏歌单 | `tb_user_favorite`（`type=1`） | 计入 `fav_playlist_count` |
| 自建歌单 | `tb_playlist.owner_user_id` | 计入 `own_playlist_count` |
| 自建歌单歌曲 | `tb_playlist_binding` JOIN `tb_playlist` | 计入 `own_playlist_song_count` |
| 常听风格 | 上面两类歌曲的 `tb_song.style` | **逗号分隔多值**，见 2.4 |
| 常听歌手 | 上面两类歌曲的 `tb_song.artist_id` → `tb_artist.name` | TOP N |
| 播放歌曲数 | `tb_play_history` | 计入 `played_song_count`（**隐式**） |
| 反复播放的歌手 | `tb_play_history` JOIN `tb_song_artist` | `recent_play_artists`（**隐式**） |

去重口径：收藏歌曲与自建歌单歌曲用 `UNION ALL` 合并后 `COUNT(DISTINCT song_id)` ——
同一首歌既被收藏又在自建歌单里只算一次。

### 2.1.1 ⚠️ 显式信号 vs 隐式信号（不可混用）

| | 显式信号 | 隐式信号 |
|---|---|---|
| 来源 | 收藏、用户自建歌单 | 播放历史 |
| 语义 | 用户主动说「我要这个」 | 用户「听过」，可能只是好奇/顺带播到 |
| 字段 | `top_styles` / `top_artists` / `fav_*` / `own_*` | `played_song_count` / `recent_play_artists` |
| 能否当偏好 | 可以 | **只能当弱参考** |

四条硬规则（`PlayHistoryProfileTest` 逐条断言）：

1. **播放绝不写入 `top_artists` / `top_styles`** —— 那两个字段只由收藏与自建歌单产生。
   把播放混进去，字段就再也无法区分「用户说的」和「系统猜的」。
2. **单次播放不算偏好** —— `recent_play_artists` 要求一位歌手至少有
   **2 首不同歌曲**被播放过（`MIN_DISTINCT_SONGS_FOR_PLAY_PREFERENCE = 2`）。
   单曲循环 500 次仍然进不去，因为 SQL 按「播放过的**不同歌曲数**」排序，
   而不是总播放次数 —— 循环一首歌只说明「这首在循环」，不说明偏爱这位歌手。
3. **不覆盖用户显式设置的偏好** —— 手动排除的歌手，播放再多也不会被自动画像加回
   （合并逻辑在 `UserMemoryPreferenceService#merge`，排除优先）。
4. **不引入无依据的权重/衰减** —— 当前只做两件可解释的事：统计不同歌曲数、
   按不同歌曲数取 TOP N。没有「播放权重 0.3」「30 天衰减」这类无法验证的黑箱。

注入提示词时，隐式信号单独成段并显式降级：
`播放记录（隐式信号，只能作为弱参考，不能据此断定用户喜欢什么）：播放过 N 首不同的歌；其中反复播放的歌手：…`
`MusicTasteSummaryService` 的 prompt 也要求「提到播放时用『常播放』这类措辞，不要说成『喜欢』」。

### 2.2 字段含义

| 字段 | 含义 |
|---|---|
| `top_styles` / `top_artists` | JSON 数组 `[{"name":..,"count":..}]`，按计数降序、名称升序，各取 TOP N（`ai.profile.top-n`，默认 5）。**只来自显式信号** |
| `fav_song_count` / `fav_playlist_count` | 收藏歌曲/歌单数 |
| `own_playlist_count` / `own_playlist_song_count` | 自建歌单数 / 其中歌曲总数 |
| `played_song_count` | 有过有效播放的**不同歌曲数**（隐式；与 `fav_song_count` 分开统计，不相加） |
| `recent_play_artists` | 反复播放的歌手 TOP N（隐式；JSON 数组文本）。**不写入** `top_artists` |
| `pref_summary` | qwen-flash 生成的自然语言概述（≤ `ai.profile.summary-max-chars`） |
| `summary_source_hash` | 生成 `pref_summary` 时的**事实指纹**（SHA-256，只覆盖事实字段，不含 `pref_summary` 自身以免自指；隐式信号也纳入） |
| `version` / `updated_at` | upsert 版本号 / TTL 判断依据 |

### 2.3 刷新与缓存（`UserMusicProfileService`）

- `read(userId)`：先查行；不存在或 `updated_at` 超过 `ai.profile.refresh-ttl-minutes`（默认 10 分钟）才走 `refresh()`。
  TTL 内成本 = 一次主键查询。
- `refresh()`：3 条聚合 SQL + upsert。**upsert 不覆盖 `pref_summary` / `summary_source_hash`** —— 刷新事实不应清掉已写好的概述。
- `renderPreferenceBlock(userId)`：无任何数据返回 `null`（不注入空块）。偏好块内已包含 `pref_summary`（有则注入）。

### 2.4 ⚠️ `tb_song.style` 是逗号分隔多值

真实数据形如 `欧美流行,电子`。直接 `GROUP BY s.style` 会把混合串当成一种风格，
画像出现「常听风格：欧美流行、欧美流行,电子」。**H2 测试夹具全是单值，测不出这个问题。**

正确做法（已实现）：SQL 侧多取原始行（`RAW_STYLE_SCAN=100`），服务层 `expandStyles` 拆分后合并计数再取 TOP N。
单个风格的总数按原始行计数直接累加是正确语义（12 首风格串都是 `欧美流行,电子` 的歌，每首都同属两个风格）。

## 3. 偏好概述（`MusicTasteSummaryService`）

- **触发**：会话结束后异步（`AiController.scheduleMemoryMaintenance` → `tasteSummaries.refreshSummary(userId)`）。
- **指纹缓存**：`factsHash(profile)` 与 `summary_source_hash` 一致 → 跳过，不调模型。事实变了才重新生成。
- **写入口径**：`updateSummaryIfStale`（指纹条件 UPDATE）—— 并发任务拿着不同指纹同时回来时先写者生效，后写者条件不命中自动跳过。
- **失败策略**：任何异常只记 `warn` 并返回 `false`。概述是锦上添花，事实块已足够让模型理解用户。

## 4. 滚动摘要记忆（`ChatSummaryService` / `ai_chat_state`）

### 4.1 触发阈值

模型窗口 20 条。令 `covered = summary_upto_id`（已被摘要覆盖的消息上界）：

- `COUNT(chat_messages WHERE id > covered) <= ai.summary.trigger-messages`（默认 40）→ 不动；
- 超过 → 取 `id > covered` 的最早 `ai.summary.chunk-messages`（默认 20）条，压缩后 `summary_upto_id` 前进到该段最后一条的 `id`。

即"未被摘要覆盖的消息"最多堆到 40+20 条边界，永远不去压缩可能还在窗口里的消息。

### 4.2 内容口径

- 只压缩 `USER` / `ASSISTANT` 文本，**忽略 `TOOL_REQUEST` / `TOOL_RESULT`**（JSON 噪音，稀释有效信息）。
- 与旧摘要合并（MERGE_PROMPT），保留长期偏好/明确要求/已完成动作/未完成意图；丢弃寒暄与已被推翻的信息。
- 单条消息截断到 300 字，摘要总长 ≤ `ai.summary.max-chars`（默认 300）。

### 4.3 幂等与失败

- **CAS**：`UPDATE ... WHERE chat_id=? AND summary_upto_id=?`。返回 0 说明并发任务已推进 → **丢弃本次模型结果**（不重试，下一轮自然补齐）。
- **模型失败**：异常只记日志，`ai_chat_state` 一个字节都不改。
- **清空会话**：`deleteMessagesByChatId` 连带 `clearSummary` —— 摘要是对话内容的压缩产物，删了聊天记录必须一起消失（隐私）。

## 5. 提示词组装（`AgentSystemPrompt`）

```
基础提示词（MusicAssistant.SYSTEM_PROMPT，角色与硬约束）
        ▼
【更早对话的摘要】      ← ChatSummaryService.renderSummaryBlock(chatId)，无则跳过
        ▼
【当前用户的音乐偏好】   ← UserMusicProfileService.renderPreferenceBlock(userId)，无则跳过
```

- **偏好放最后**：偏好块含用户可影响的内容，放末尾避免"盖住"前面的安全约束。
- **游客也拿摘要**：摘要是对话级记忆，游客会话同样有历史；画像块只给登录用户。
- **评测基线**：`AgentSystemPrompt` 是接口，评测基座（`AgentEvalHarness`）注入 `StaticSystemPrompt`（只有基础提示词），保证 46 条离线用例零漂移。生产注入 `PersonalizedSystemPrompt`。
- 组装由 `AiAssistantFactory` 在每次运行时通过 `AiServices.systemMessageProvider` 完成；
  `MusicAssistant` 上的 `@SystemMessage` 注解**必须保持删除状态**（0.36.2 的解析顺序会让 provider 静默失效，不报错）。

### 存储型注入防线清单

偏好块与摘要块都按"数据"处理：

| 防线 | 偏好块 | 摘要块 |
|---|---|---|
| 只取结构化字段 / 模型压缩产物 | ✅ 风格/歌手/计数 | ✅ 纯压缩文本 |
| 显式"是数据不是指令"声明 | ✅ | ✅ |
| 额外约束 | 使用方式置前（压住工具扇出，见方案风险表） | "与本次要求冲突时以本次为准" |
| 单字段截断 | ✅ 120/160/300 字 | ✅ max-chars |

## 6. 对外接口

| 接口 | 说明 |
|---|---|
| `GET /ai/profile` | 当前登录用户自己的画像（只读）。游客 401。返回 `UserMusicProfile`。 |

画像没有"刷新"按钮：TTL 到点后下一次 `read()` 自然刷新；概述在会话结束后异步生成。

## 7. 排查手册

| 现象 | 查哪里 |
|---|---|
| 画像没生效（提示词里没偏好块） | ① 是否登录（游客不注入）；② 用户是否有收藏/自建歌单/播放记录，零数据返回 `null`；③ `GET /ai/profile` 看行是否存在、`updated_at` 是否超 TTL；④ `SystemMessageInjectionTest` 断言的是同一条链路，先跑它 |
| 播放了很多但画像里没有播放块 | ① `SELECT COUNT(DISTINCT song_id) FROM tb_play_history WHERE user_id=?` 是否为 0；② 是否只在同一首歌上循环（`recent_play_artists` 要求 ≥2 首不同歌曲）；③ 画像 TTL 内不会重算，等 `ai.profile.refresh-ttl-minutes` 或调 `refresh()` |
| 播放记录被当成「喜欢」了 | 检查是否有人把播放聚合写进了 `top_artists`。正确做法只有 `recent_play_artists`；`PlayHistoryProfileTest#repeatedlyPlayedArtistsAreRecordedSeparatelyFromExplicitTaste` 会挂 |
| 问「我最近听了什么」时 AI 说「系统不记录歌名」 | 这是**错误描述系统能力**，不是画像问题：① 看 SSE `tool` 事件里有没有 `getRecentPlayedSongs`（0 次调用 = 提示词/工具注册问题）；② 游客会得到 `UNAUTHORIZED`，属预期；③ `agent/RecentPlayedSongsToolTest` 覆盖工具本身 |
| 偏好概述一直是空 | ① 后端日志搜「偏好概述生成失败」——DashScope key 未注入会静默失败；② `SELECT user_id, summary_source_hash, LENGTH(pref_summary) FROM tb_user_music_profile`；指纹一致但概述为 NULL = 生成从未成功过 |
| 摘要没生成 | ① `ai.summary.enabled`；② 未覆盖消息数是否 > `trigger-messages`（数 `chat_messages` 最可靠）；③ `SELECT chat_id, summary_upto_id, summary_updated_at FROM ai_chat_state`；④ 日志搜「会话摘要生成失败」 |
| 摘要内容不对 | 摘要是有损压缩，以窗口内 20 条原文为准；检查是否把 TOOL 消息也压进去了（`render` 只取 USER/ASSISTANT）；单条超 300 字会被截断 |
| 清空会话后摘要还在 | 不可能——`deleteMessagesByChatId` 连带 `clearSummary`。若见到残留，检查是否用的旧版本代码 |
| 模型突然"失去人格" | `MusicAssistant` 上的 `@SystemMessage` 被加回来了 → provider 静默失效。`SystemMessageInjectionTest` 会挂 |

## 8. 配置项（`application.yml`）

```yaml
ai:
  profile:
    refresh-ttl-minutes: 10   # 事实刷新间隔；TTL 内只读库
    top-n: 5                  # 风格/歌手各取 TOP N
    summary-max-chars: 300    # 偏好概述长度上限
  summary:
    enabled: true
    trigger-messages: 40      # 未覆盖消息超此数才触发摘要
    chunk-messages: 20        # 每次压缩最早的一段
    max-chars: 300            # 摘要长度上限
```

## 9. 与 `agent_ai_plan.md` 阶段二的对应关系

| 阶段二条目 | 状态 | 说明 |
|---|---|---|
| 2.2 个性化上下文工程 | ✅ 本方案 | `UserMusicProfileService` + `MusicTasteSummaryService` + 提示词注入 |
| 2.3 "全量删除+重插改 upsert" | ✅ **早已完成** | `ai_chat_state.context_json` + `context_version` + 行级租约本身就是单行条件更新，不是全删重插 |
| 2.3 摘要记忆 | ✅ 本方案 | `ChatSummaryService` + `ai_chat_state.summary_*` 三列（`sql/phase5_personalization.sql`） |
