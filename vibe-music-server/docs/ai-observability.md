# AI 可观测性（Trace / Span）

面向管理员的 LangSmith 风格追踪：回答「这一次 AI 请求到底发生了什么」，而不只是「用了多少 Token」。

数据层级：**Thread（`chatId`）→ Trace（一次 AI 请求，复用 `taskId`）→ Span（模型调用 / 工具执行 / 上下文构建 / 护栏 / 最终输出）**。

---

## 1. 为什么不能只靠 `tb_ai_task_metric`

`tb_ai_task_metric` 是「一次请求一行」的汇总表，能回答「慢不慢、贵不贵」，但回答不了：

- 第 2 次模型调用实际收到了哪些消息？系统提示词当时是哪一版？
- 模型提出调用 `searchSongs`，后端真的执行了吗？参数是什么？返回了什么？
- 这次回答变差，是模型选错工具、工具参数错、工具执行失败，还是话术被护栏替换了？

因此新增 4 张**只增不改**的表（`sql/phase13_ai_observability.sql`，可重复执行）：

| 表 | 作用 |
|---|---|
| `tb_ai_trace_span` | 执行树：`trace_id` / `span_id` / `parent_span_id` / 类型 / 状态 / 耗时 / Token / 属性 JSON |
| `tb_ai_trace_llm_call` | 单次模型调用：真实输入消息、生效的 system message、模型可见工具定义、输出、Tool Call 请求 |
| `tb_ai_trace_tool_call` | 单次工具调用：模型原始参数、服务端解析参数、结果码/条数/脱敏载荷、两个 ID |
| `tb_ai_trace_prompt` | 基础系统提示词版本库（按 SHA-256 去重，可还原历史原文） |

`tb_ai_task_metric` 保持原样，作为「任务级汇总」的权威口径；Trace 是它的下钻明细，两者不一致时**并列展示**而不是互相覆盖（见 §6）。

---

## 2. 采集点：只从真实调用入口取数

```
AiController (/ai/chat, /ai/chat/stream)
   └─ AiAssistantFactory.build()           ← 每次请求装配一套实例
        ├─ AgentSystemPrompt.assemble()    ← 记录「实际拼出来的提示词」+ 每块注入状态
        ├─ MeteredChatModels               ← 唯一的模型入口：真实 messages / tools / 响应
        │    └─ AgentTraceRecorder.beginModelCall / endModelCall
        └─ TracedToolExecutors             ← 唯一的工具入口：模型原始 Tool Call + 真实执行
             └─ AgentTraceRecorder.beginToolCall / endToolCall
   └─ guardFinalText → recordGuard         ← 护栏是否改写了回答
   └─ finally → recordTrace → recorder.flush()
```

**为什么是这两个入口**：`MeteredChatModels` 是唯一同时拿到「真实消息列表 + 工具定义 + 供应商响应（含 tokenUsage 与 Tool Call）」的地方；`TracedToolExecutors` 是唯一能同时看到「模型侧 `ToolExecutionRequest.id()`」和「服务端执行结果」的地方。除此之外任何位置都只能靠推断。

### 2.1 模型调用与工具的可靠关联

问题：SSE 的 `toolCallId` 与模型返回的 Tool Call ID 不是同一个东西，而「按时间接近」推断在并行调用时必然出错。

做法：

1. `AiServices.tools(Map<ToolSpecification, ToolExecutor>)` 替代 `tools(Object...)`，包装器直接读 `ToolExecutionRequest.id()`（模型侧 ID）。
2. 包装器为本次执行分配 `span_id`，并 `ToolCallScope.bind(spanId)`。
3. `AgentContext.nextToolCallId(toolName)` 优先返回 `ToolCallScope.current()`，因此 **SSE 的 `toolCallId` 与数据库 `tb_ai_trace_tool_call.span_id` 完全相同**。
4. 两个 ID 都落库：`model_tool_call_id`（供应商给出）与 `server_tool_call_id`（服务端分配），界面上并排展示。

父子关系同理：TOOL Span 的父节点是**发出该调用的 MODEL Span**（`trace.currentModelSpanId()`），不是「上一个 Span」。

### 2.2 系统提示词版本与快照

- 版本号 = 基础提示词 SHA-256 前 12 位；完整原文进 `tb_ai_trace_prompt`（按哈希 upsert，`seen_count` 累加）。
- `AgentSystemPrompt.assemble()` 额外返回每块的注入事实：`base` / `summary` / `profile` / `manualPreference`，取值 `INJECTED` / `SKIPPED_EMPTY` / `SKIPPED_DISABLED` / `FAILED` / `NOT_APPLICABLE`，另有 `guest`、`personalizationSwitch`、`*Error`。
- 这些状态来自组装者的实测结果，不是「事后从字符串里猜块标题在不在」——后者会把「数据为空」和「注入失败」混为一谈。
- `CONTEXT` Span 上保存**实际生效的完整提示词**（基础 + 已注入的动态块），受 `capture-content` 控制。

---

## 3. 配置

```yaml
ai:
  trace:
    enabled: true                  # 总开关；关闭后不再产生 Span（指标表不受影响）
    capture-content: true          # 是否保存正文；关闭后只留元数据、长度与哈希
    max-text-chars: 4000           # 单条消息 / 单段文本
    max-system-prompt-chars: 40000 # 系统提示词（基础提示词本身就有数千字）
    max-tools-chars: 60000         # 工具定义（10 个工具的中文描述合计可过万字符）
    max-messages-chars: 60000      # 单次调用的输入快照
    max-result-chars: 20000        # 工具返回数据
    retention-days: 30             # <=0 表示不清理
    cleanup-initial-delay-ms: 300000
    cleanup-interval-ms: 86400000
  cost:
    currency: CNY
    price-version: qwen-flash-cn-beijing-2026-10-22
    source-url: https://help.aliyun.com/zh/model-studio/model-pricing
    source-updated-at: "2026-10-22 11:52:55 +08:00"
    tiers:                         # 按单次请求的输入 Token 选档
      - max-input-tokens: 128000
        input-price-per-million: 0.15
        output-price-per-million: 1.5
      - max-input-tokens: 256000
        input-price-per-million: 0.6
        output-price-per-million: 6
      - max-input-tokens: 1000000
        input-price-per-million: 1.2
        output-price-per-million: 12
    # 兼容旧部署；tiers 非空时不使用。
    input-price-per-million:
    output-price-per-million:
```

### 3.1 qwen-flash 官方价格（华北 2 北京）

以下为阿里云百炼[模型调用价格](https://help.aliyun.com/zh/model-studio/model-pricing)中的标准实时推理原价，查询日期 **2026-10-24**，页面更新时间为 **2026-10-22 11:52:55 +08:00**。官方[模型信息页](https://help.aliyun.com/zh/model-studio/qwen-flash)同时注明：`qwen-flash` 当前能力等同于 `qwen-flash-2025-07-28`。

| 单次请求输入 Token | 输入单价（元/百万 Token） | 输出单价（元/百万 Token） |
|---|---:|---:|
| `0 < Token <= 128K` | 0.15 | 1.5 |
| `128K < Token <= 256K` | 0.6 | 6 |
| `256K < Token <= 1M` | 1.2 | 12 |

计费公式（每次模型调用单独计算，再累加）：

```text
调用费用 = 输入 Token / 1,000,000 × 该档输入单价
         + 输出 Token / 1,000,000 × 该档输出单价
```

以本项目真实 Trace `d92eca22-34ee-4641-b1fd-0319ff9e8467` 为例，两次模型调用分别为 `4,387/24` 和 `5,377/76`（输入/输出 Token），均命中第一档：

```text
(4,387 + 5,377) / 1,000,000 × 0.15
  + (24 + 76) / 1,000,000 × 1.5
= 0.0014646 + 0.00015
= 0.0016146 元
```

界面按 6 位小数展示为 **¥0.001615**。这里把两次调用的同类 Token 相加后再乘第一档单价，仅因为两次都命中同一档；若两次分别命中不同档，必须**分别套用各自档位单价后再相加**。

计费边界：

- 档位由**单次请求的输入 Token 总量**决定，命中某档后该次请求的输入和输出 Token 都按该档结算；不是按统计周期内的总 Token 或平均值选档。
- 表中是原价。Batch 调用为实时推理半价；上下文缓存仅输入 Token 有折扣；两者不能同时生效。免费额度和控制台活动优惠也不包含在内。
- 官方价格页显示，华北 2（北京）的 `qwen-flash` 有 100 万 Token 免费额度（有效期规则以官方页面为准）；本项目的费用估算**不会自动扣除**免费额度，因此早期调用可能显示估算金额而实际账单为 0。
- 当前只覆盖默认的华北 2（北京）地域。切换到其他地域或模型时，必须同步更新 `tiers`、`price-version` 与来源。

费用是估算，不是供应商账单。未配置价格时界面显示「未配置价格」，绝不用 `¥0.00` 冒充。

更新价格时，至少同时修改以下三项，避免历史金额被误认为按新价格计算：

- `ai.cost.tiers`：档位边界与输入/输出单价。
- `ai.cost.price-version`：建议使用 `模型-地域-价格页日期`，例如当前值 `qwen-flash-cn-beijing-2026-10-22`。
- `ai.cost.source-updated-at`：价格页的 `last-modified` 时间，而不是本次查询时间。

---

## 4. 管理端接口

全部在 `/admin/ai/**` 下，由 `LoginInterceptor` + `RolePermissionManager` 强制 `ROLE_ADMIN`（`role-path-permissions.permissions.ROLE_ADMIN: ["/admin/"]`）。普通用户 403；不存在「改个 traceId 看别人记录」的入口。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/ai/overview?from=&to=` | 指标、Token 趋势、模型/用户/工具排行、慢请求、失败分布、护栏趋势 |
| POST | `/admin/ai/traces` | 组合检索 + 服务端分页 + 白名单排序 |
| GET | `/admin/ai/filter-options?from=&to=` | 模型候选取自真实数据；工具候选为已注册工具与历史 Trace 中出现过的工具之并集 |
| GET | `/admin/ai/traces/{traceId}` | 汇总 + 执行树 + 每次模型/工具调用明细 |
| GET | `/admin/ai/traces/{traceId}/spans/{spanId}` | 单个节点明细（按需加载） |
| GET | `/admin/ai/users/options?keyword=` | 用户下拉，按账号前缀或精确 ID，不加载全表 |

边界：时间区间硬上限 **31 天**（超出返回 400）、分页上限 **100**、慢请求最多 10 条、用户排行最多 20 条。所有值走 MyBatis `#{}` 参数绑定；排序字段经 `SortWhitelistUtil` 映射到 `SORT_WHITELIST`，未命中回退默认排序。

时间参数用 ISO-8601 本地时间（`2026-10-24T00:00:00`，无时区偏移），避免前后端对「本地时间字符串」理解不一致。

---

## 5. Token 与费用的口径

| 场景 | 记录方式 | 界面表现 |
|---|---|---|
| 供应商返回 `tokenUsage` | `token_source = PROVIDER`，写入真实数值 | 正常数字 |
| 供应商未返回 | `token_source = UNAVAILABLE`，**不写 0** | 「不可获取」/「—」+ 警示标记 |
| 请求整体失败 | 计入 `failedRequests`，Token 按实际可得部分统计 | 失败数单独展示 |

- 根 Span 的 Token = 各 MODEL Span 之和；没有任何可用数值时根 Span 记 `UNAVAILABLE`。
- 总览的 `tokenUnavailable` 单独计数，不摊进平均值。
- 费用按每次 MODEL 调用的输入 Token 选档，再计算 `input/1e6 × inputPrice + output/1e6 × outputPrice` 并累加。**不能先汇总 Token 再选档**：那会把多次小请求错误地推入更高档。
- 总览和模型排行从 `tb_ai_trace_span` 的 MODEL Span 计价；单条详情按该 Trace 的逐次模型调用计价。没有 Trace 明细的历史请求不伪造金额，`unpricedCalls` 会明确计数并在页面说明。
- Token 不可获取、输入超过最高档的调用均不计价；`pricedCalls` / `unpricedCalls` 分别展示，`configured=false` 时 `amount=null`。`priceVersion` 记录估算依据。**这是估算，不是供应商账单**，页面上有明确文案。

---

## 6. 一致性核对

`detail.consistency` 把两套口径并列：

```json
{ "comparable": true, "metricInputTokens": 1200, "spanInputTokens": 1200,
  "llmCallCount": 2, "modelCallCount": 2, "consistent": true, "note": "..." }
```

不一致时页面显示黄色提示并同时列出两边数值，文案是「以明细为准排查，差异通常来自采集关闭或部分调用失败」——差异被**说明**，不被抹平。

---

## 7. 隐私与保留

- **不记录**：Authorization / Cookie / 密码 / API Key / 任何凭据。
- `TraceRedactor` 在**写库前**脱敏：`Bearer ***`、`sk-***`、JSON 与表单里的敏感字段保留键名、值替换为 `***`、`x-api-key`/`authorization`/`cookie`/`x-auth-token` 请求头整段替换。
- 刻意**不**脱敏邮箱与手机号：那是业务身份（用户账号），抹掉会让排障无从下手。
- 截断/脱敏/关闭采集一律写 `snapshot_state`（`FULL` / `TRUNCATED` / `REDACTED` / `DISABLED`）+ `snapshot_note`，**前端必须原样展示**，不得把截断内容说成完整原文。
- 列表页只返回根 Span 上的 `request_summary`（≤300 字符、已脱敏），从不返回完整对话。
- 保留期 `retention-days`（默认 30 天），`AiTraceRetentionTask` 按表清理；`<=0` 关闭清理。
- 采集是**旁路**：任何采集异常只计数（`overview.traceCaptureFailures`）并记日志，绝不影响用户回复。

---

## 8. 前端页面（`vibe-music-admin`）

| 路由 | 文件 | 内容 |
|---|---|---|
| `/ai-observability/overview` | `views/ai-observability/overview/index.vue` | 指标卡、Token 趋势、模型/用户/工具排行、失败分布、慢请求、护栏趋势、口径说明、费用 |
| `/ai-observability/traces` | `views/ai-observability/traces/index.vue` | 组合筛选、服务端分页、白名单排序、跳转详情 |
| `/ai-observability/traces/:traceId` | `views/ai-observability/traces/detail.vue` | 汇总、执行树、每次模型调用（提示词版本与块状态 / 本轮增量消息 / 工具定义 / 输出 / Tool Call 请求）、工具调用（两个 ID / 原始与解析参数 / 返回）、上下文（完整生效提示词） |

纯函数抽在 `views/ai-observability/utils/format.ts`（Token 缺失、费用未配置、状态配色、时间区间、执行树组装），用 `node --test` 直接验证，不挂载 Vue：

```bash
cd vibe-music-admin && pnpm test
```

页面只展示真实接口数据：加载态用 skeleton、空数据用 `el-empty` 并说明原因、401/403/400 分别给出对应文案，不混入任何静态模拟数据。

详情页的信息分层：

- **系统提示词只在「上下文」展示完整原文**。模型调用区只显示版本、哈希、字符数和动态块状态，并提供「查看完整提示词」入口。
- **模型调用的「本轮新增消息」不含 `system`**，只展示该轮真正新增的 `user` / `assistant` / `tool` 消息，避免同一份长提示词在每轮重复铺满页面。
- 调用序号直接使用后端从 1 开始的 `callIndex`。后端值已经是 1、2 时，页面不得再加 1，否则会把两次调用误显示成第 2、3 次。
- **「总 Token」按单次请求筛选**：取该请求所有模型调用的输入 + 输出之和，最小/最大均包含边界。它不是整个时间范围的 Token 总量，也不用于费用档位计算（费用按每次模型调用自己的输入 Token 选档）。
- **低频异常筛选合并为一个下拉框**：护栏命中 / Token 不可获取互斥选择，避免在检索表单里平铺两个复选框。
- **工具下拉展示系统注册的全部工具**（`AgentToolCatalog`），并与历史 Trace 中出现过的工具取并集。选择某个工具后，只匹配实际调用过它的 Trace；从未调用过的工具也必须可见，不能因为候选取自近期数据而消失。

---

## 9. 测试

| 测试 | 覆盖 |
|---|---|
| `agent/AgentTraceCaptureTest` | 单次调用完整捕获、多轮调用分离与父子关系、模型 ID ↔ 服务端 ID 关联（断言不相等）、`EMPTY` 与 `INVALID_ARGUMENT` 可区分、提示词版本去重与 `seen_count`、6 类 Span 齐全、采集失败不影响回复、关闭采集时标注 `DISABLED`、检索/详情/总览走真实 SQL、账号不存在返回空页、超长区间被拒 |
| `security/AiTraceRedactionTest` | 各类凭据脱敏、业务身份不误伤、截断状态标注 |
| `security/AdminAiTraceAccessTest` | 生产 `WebConfig` + `LoginInterceptor`：匿名 401、ROLE_USER 403、ROLE_ADMIN 200 |
| `tests/ai-observability-format.test.mjs` | 前端纯函数：Token 缺失不显示 0、未配置价格不显示 ¥0.00、执行树父子与孤儿/环状兜底、空串不进请求体 |

基座是 `AbstractAgentTest`：真实 MySQL 兼容模式 H2 + 真实 mapper SQL + 真实业务服务，**只替换模型**（脚本替身）。不依赖 MySQL / Redis / MinIO / DashScope。

```bash
cd vibe-music-server
mvn -o -B -ntp test -Dtest='!RedisConnectionTest,!VibeMusicServerApplicationTests'
```

---

## 10. 怎么看到一条真实 Trace

1. 建表：`mysql -u ... vibe_music < sql/phase13_ai_observability.sql`（可重复执行）。
2. 启动后端（需要真实 MySQL/Redis/MinIO 与 `.env` 中的 `DASHSCOPE_API_KEY`）：`mvn spring-boot:run`。
3. 启动管理端：`cd vibe-music-admin && pnpm dev`（8089），用管理员账号登录。
4. 在用户端（8090）打开 AI 浮窗，问一句会触发工具的问题，例如「推荐几首周杰伦的歌」。
5. 管理端左侧「AI 可观测性 → Trace 检索」，时间范围选「最近 24 小时」，点查询。
   - 第一行就是刚才那次请求；「Trace ID」列即 `taskId`（SSE `task` 事件里的同一个值）。
   - 没有明细的旧请求会标注「无 Trace 明细」，点进去会说明原因。
6. 点 Trace ID 进详情：
   - **执行树**：`请求 → 上下文 → 模型调用 → 工具 → 模型调用 → 护栏 → 输出`，点节点可跳到对应明细。
   - **模型调用**：生效的系统提示词（含版本号与块注入状态）、真实输入消息、模型可见的工具定义、输出、模型的 Tool Call 请求。
   - **工具调用**：模型侧 ID 与服务端 ID 并排（后者等于 SSE `toolCallId`）、原始参数 vs 解析参数、返回码/条数/脱敏载荷。
   - 内容被截断或脱敏时，对应区块顶部有黄色提示，不会伪装成完整原文。

若想先不接真实模型看结构，跑 `mvn test -Dtest=AgentTraceCaptureTest` —— 它用脚本化模型走完整链路并把结果写进 H2，断言覆盖上面所有字段。

---

## 11. 已知边界

- 统计口径只覆盖 **Agent 主对话任务**（`tb_ai_task_metric`），**不含**会话摘要、画像概述这类后台模型调用 —— 它们没有请求级 Trace，混进来会让「每请求平均 Token」失真。总览页顶部有原样文案说明。
- `capture-content=false` 时只能看到元数据（调用次数、工具名、Token、耗时），看不到正文；这是刻意的隐私开关，不是缺陷。
- 费用是估算且只覆盖标准实时原价；未配置价格时不显示金额，超出已配置最高档或缺少 Token 的调用会单独标为未计价。
- 采集失败计数是**进程级**（`AgentTraceRecorder.failures()`），重启归零，不落库。
