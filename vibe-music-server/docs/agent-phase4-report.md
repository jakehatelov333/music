# 第三批 Agent 工程化实施报告（第 9～12 项）

> 报告原则：**以真实代码、实际测试和可验证的业务结果为依据**。
> 未实测的项目一律标注为「未执行 / 待测」，不用代码已编写冒充功能已验收。

- 报告时间：2026-10-12
- 范围：统一工具与流式协议（第 9 项）、确认与执行流程（第 10 项）、对话创建歌单闭环（第 11 项）、评测与交付（第 12 项）
- 规模：新增 45 个文件、修改 10 个文件，约 4.5k 行

---

## A. 功能完成情况

| 项 | 状态 | 依据 |
|---|---|---|
| 9.1 统一工具返回格式 | ✅ 已完成 | `ToolResult`/`SongRef` 协议；`AgentToolProtocolTest` 11 条全通过（含真实反序列化断言） |
| 9.2 统一 SSE 流式协议 | ✅ 已完成 | `start/tool/songs/confirm/task` 新增，`token/done/error` 保持原格式；`AgentSseProtocolTest` 4 条在**真实控制器**上验证事件顺序与载荷 |
| 9.3 显式传递可信用户上下文 | ✅ 已完成 | `AgentContext`（每次运行一份不可变对象）随工具实例构造；工具签名无用户标识参数；游客写操作返回 `UNAUTHORIZED` 有测试覆盖 |
| 10.1 服务端待确认机制 | ✅ 已完成 | 表 `tb_agent_pending_action` + `PendingActionService`；数据落库，刷新后可查 |
| 10.2 确认之前禁止写入 | ✅ 已完成 | `PendingActionTest#prepareWritesNothingAndConfirmCreatesExactlyOnce` 断言 `tb_playlist` 行数不变 |
| 10.3 确认时重新鉴权 | ✅ 已完成 | 每次 confirm 重新取 JWT 身份 + 归属/状态/有效期/版本/歌曲有效性校验 |
| 10.4 可靠幂等执行 | ✅ 已完成 | 数据库条件更新抢占 + 僵死回收 + 复用既有 `Idempotency-Key` 收据；重复确认与 6 并发确认各有测试 |
| 10.5 任务结果查询能力 | ✅ 已完成 | `GET /ai/actions/{taskId}`、`GET /ai/actions/pending`；越权一律 404 |
| 11.1 自然语言理解 | ⚠️ 部分完成 | 系统提示词 + 工具描述已按「何时该用我」重写；**真实模型侧准确率未做批量统计**（只跑了 1 条真实用例，成功） |
| 11.2 调用真实工具检索 | ✅ 已完成 | 全部数据来自 `tb_song`/`tb_artist`，无硬编码；真实端到端验证返回 20 首真实歌曲 |
| 11.3 展示预览并等待确认 | ✅ 已完成 | `confirm` 事件 + `AiWidget.vue` 确认卡片；**已在真实浏览器中验证**（23/23，见 E.1） |
| 11.4 确认后创建真实歌单 | ✅ 已完成 | 复用 `PersonalPlaylistService#create`；真实端到端创建并核对绑定与标题 |
| 11.5 返回真实执行结果 | ✅ 已完成 | 仅在后端写入成功后返回 `playlistId`；真实验证：成功卡片数据与库内一致 |
| 11.6 处理主要异常情况 | ✅ 已完成 | 12 类场景中 11 类有自动化覆盖（见 E.2），「页面离开后重新进入」已在浏览器验证（刷新后卡片恢复并可继续确认） |
| 12.1 记录 Agent 执行指标 | ✅ 已完成 | `tb_ai_task_metric`；真实 Token（`PROVIDER`）已验证落库 |
| 12.2 30～50 条评测用例 | ✅ 已完成 | 46 条，覆盖 27 个类别 |
| 12.3 设计评测指标 | ✅ 已完成 | 成功率/工具正确性/业务正确性/安全性/性能/成本六维，分类统计 |
| 12.4 评测运行入口与报告 | ✅ 已完成 | `mvn -o test -Dtest=AgentEvalReportTest` → `target/agent-eval/report.md` + `.json` |
| 12.5 CI / 部署说明 / 演示材料 | ⚠️ 部分完成 | CI、部署文档、演示脚本、浏览器验证脚本已写；**CI 未在真实 GitHub 上执行过**（本地无 GitHub runner） |

**结论**：第 9、10 项完成度最高（协议与状态机有完整自动化覆盖）；第 11 项后端闭环已完成并真实跑通，
**前端交互已在真实浏览器中验证通过（23/23）**；第 12 项评测能力已完成，CI 配置未经真实 runner 验证。
真实模型 + 真实浏览器的联合验证还**发现并修复了一个真实缺陷**（模型跳过写工具却声称有确认卡片，
见 E.4 与 H）。

---

## B. 文件修改清单

### B.1 新增（45 个）

**协议与上下文（`model/agent/`，11 个）**

| 文件 | 职责 |
|---|---|
| `ToolResult.java` | 统一工具返回协议（ok/code/message/kind/total/data） |
| `SongRef.java` / `ArtistRef.java` / `PlaylistRef.java` | 结构化业务引用 |
| `AgentToolCodes.java` / `AgentToolKinds.java` | 结果码与业务类型标识常量 |
| `AgentEvent.java` / `AgentEventSink.java` | SSE 事件模型与事件出口 |
| `AgentContext.java` | 单次运行的可信上下文（userId/chatId/taskId/事件出口/指标） |
| `PlaylistDraft.java` / `PlaylistPreview.java` | 预览与执行的唯一共同数据源 |
| `AgentJson.java` | 协议层序列化器（注册 JSR-310） |

**服务层（`service/agent/`，6 个）**

| 文件 | 职责 |
|---|---|
| `AgentSongQueryService.java` | 只读歌曲查询适配层，输出 `SongRef` |
| `PendingActionService.java` | 待确认任务全生命周期（准备/修改/确认/取消/查询/过期） |
| `AgentMetrics.java` | 单任务指标累加器（真实 Token） |
| `AgentMetricsRecorder.java` | 指标落库（失败不影响主流程） |
| `MeteredChatModels.java` | 模型装饰器，采集每轮调用与 Token |
| `AgentFailureCategory.java` | 失败分类常量 |

**其他后端（10 个）**
`AgentSongMapper` / `AgentPendingActionMapper` / `AiTaskMetricMapper`（新增 mapper，不动既有 mapper）、
`AgentPendingAction` / `AiTaskMetric`（实体）、`AgentSongVO` / `PendingActionVO`（VO）、
`AgentPlaylistDraftDTO`、`PendingActionStatusEnum`、`AgentActionController`、
`PlaylistActionTools`（写工具）、`AgentPendingActionExpiryTask`（过期清理）。

**前端（1 个）**：`vibe-music-client/src/api/agent.ts`

**测试（6 个）**：`AbstractAgentTest`、`AgentToolProtocolTest`、`PendingActionTest`、
`AgentEvalHarness`、`AgentEvalReportTest`、`AgentSseProtocolTest`、`EvalCase`

**资源与交付（8 个）**：`agent-eval/cases.json`、`sql/phase4_agent_actions.sql`、
`docs/agent-protocol.md`、`docs/agent-deployment.md`、`docs/agent-demo.md`、
`docs/agent-e2e-verification.md`、`docs/agent-phase4-report.md`（本文件）、
`.github/workflows/ci.yml`、`scripts/agent-ui-verify.cjs`（浏览器验证脚本）

### B.2 修改（10 个）

| 文件 | 改动 | 原因 |
|---|---|---|
| `tool/MusicTools.java` | 返回结构化 JSON；发工具事件；支持绑定上下文 | 让搜索结果能变成可播放卡片、让过程可观测 |
| `controller/AiController.java` | 新增 4 类 SSE 事件；统一结算（指标+租约释放）；`/chat/metrics`；`produces` 加 UTF-8 | 流式协议扩展 + 异常兜底 + 指标 |
| `service/ai/AiAssistantFactory.java` | 每次运行构造 `AgentContext`、注入指标、装配专属工具实例 | 可信上下文 + 指标 + 并发隔离 |
| `service/MusicAssistant.java` | 重写系统提示词（含创建歌单流程约束） | 防止模型抢跑写操作 |
| `application.yml` | 新增 `ai.pending-action-*` 配置 | 待确认任务有效期与清理 |
| `api/ai.ts` | SSE 解析新增 5 类事件 | 前端消费新协议 |
| `AiWidget.vue` | 结构化消息块、歌曲卡片、确认卡片、刷新恢复 | 第 11 项前端闭环 |
| `AiAssistantFactoryTest` | 适配 5 参构造器 + `withContext` 桩 | 接口变更适配（**断言未弱化**） |
| `AiControllerSecurityTest` | 补 `AgentMetricsRecorder` 依赖、改 `factory.create` 桩 | 接口变更适配（**断言未弱化**） |
| `PlaylistBusinessTest` | `MusicTools` 构造器适配（用 mock 的 `AgentSongQueryService`） | 接口变更适配（**断言未弱化**） |

---

## C. 兼容性说明

### 保持不变（未改一行）

- **既有 REST 接口的响应契约**：`/playlist/**`、`/song/**`、`/artist/**`、`/album/**`、
  `/search`、`/favorite/**`、`/comment/**`、`/admin/**` 全部未动。
- **既有业务 Service 的返回类型**：`PersonalPlaylistService`、`PlaylistServiceImpl`、
  `SongServiceImpl` 等方法签名与返回类型未变。
- **既有 mapper**：`SongMapper`、`PlaylistMapper`、`ArtistMapper` 等未新增/修改任何方法
  —— Agent 的查询走**新增**的 `AgentSongMapper`。
- **既有 SSE 事件**：`token` / `done` / `error` 的事件名与载荷格式完全保持原样，
  新增事件是叠加的，旧前端不会因此报错。
- **既有前端组件**：`AiWidget.vue` 的拖拽、最小化、浮窗定位、`open/close` 暴露、历史加载、
  身份切换清理逻辑全部保留。
- **既有测试**：72 条原测试全部保留并通过，没有删除或弱化任何断言。

### 发生变化的调用关系

| 变化 | 影响面 | 是否已验证 |
|---|---|---|
| `MusicTools` 增加 `AgentSongQueryService` 依赖 | 仅工具层 | 编译 + 测试通过 |
| `AiAssistantFactory` 增加 `PlaylistActionTools` 依赖 | 仅 Agent 装配层 | 编译 + 测试通过 |
| `AiController` 增加 `AgentMetricsRecorder` 依赖 | 仅 Agent 入口 | 编译 + 测试通过 |
| `/ai/chat/stream` 的 `produces` 增加 `;charset=UTF-8` | 仅该端点 | `AgentSseProtocolTest` + 真实调用通过 |

### 如何验证原有功能没有破坏

1. `mvn -o test` → **102 条全部通过**，其中 72 条是改动前就存在的回归测试。
2. 前端 `vite build` 成功。
3. 真实启动后端，`GET /` 返回 401、`GET /playlist/mine` 返回 401、既有登录/歌单接口行为不变。
4. 手工回归清单见 `docs/agent-demo.md` 第四节。

---

## D. 协议说明

完整协议见 **`docs/agent-protocol.md`**（与实际代码一致）。要点：

- **工具返回**：`{ok, code, message, kind, total, data}` 的 JSON 文本。
  `code` 语义表见协议文档；**`EMPTY`（成功无结果）≠ `ERROR`（失败）**。
- **SSE 事件**：`start` / `tool` / `songs` / `confirm` / `task` / `token` / `done` / `error`。
  同任务用 `taskId` 关联，同工具调用用 `toolCallId` 关联。
- **输出护栏**：`done` 是权威最终文本，`token` 只是渐进预览。
  模型声称「有确认卡片」但本轮没产生待确认任务时，`done` 会被替换为如实说明
  （`task.data.outputGuarded=true`，并落库到 `tb_ai_task_metric.output_guarded`）。
- **待确认状态机**：`PENDING → EXECUTING → SUCCEEDED|FAILED`，另有 `CANCELLED` / `EXPIRED`。
- **接口**：`/ai/actions/pending`、`/ai/actions/{taskId}`（GET/PUT）、
  `/ai/actions/{taskId}/confirm`、`/ai/actions/{taskId}/cancel`、`/ai/chat/metrics`。

---

## E. 测试结果

### E.1 构建

| 项目 | 命令 | 结果 |
|---|---|---|
| 后端编译 | `mvn -o compile` | ✅ 通过 |
| 后端测试编译 | `mvn -o test-compile` | ✅ 通过 |
| 前端类型检查 | `vue-tsc --noEmit` | ✅ 新增文件 0 错误（5 个报错为**既有**问题，位于 `build/plugins.ts`、`ResetPasswordForm.vue`、`album/index.vue`、`themeUtils.ts`） |
| 前端生产构建 | `vite build` | ✅ 成功（22.4s） |
| **前端交互（真实浏览器）** | `node scripts/agent-ui-verify.cjs` | ✅ **23/23 通过**（Playwright + 系统 Chrome，真实模型，截图见 `out/agent-ui/`） |

### E.2 自动化测试（`mvn -o test`）

```
Tests run: 105, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

其中新增 33 条：

| 测试类 | 条数 | 覆盖 |
|---|---|---|
| `AgentToolProtocolTest` | 11 | 协议结构、EMPTY/INVALID/ERROR 语义、事件关联、游客写操作拦截、编造 ID 拒绝、songIds 解析 |
| `PendingActionTest` | 14 | 准备不写库、确认只创建一次、预览与落库一致、重复确认、6 并发确认、取消、过期、越权、游客、修改+版本、过期版本、刷新查询、歌曲失效回滚、非法草稿、持久化 |
| `AgentSseProtocolTest` | 7 | 事件顺序、confirm 事件、失败分类落库、原始异常文本不外泄、**输出护栏三种情形** |
| `AgentEvalReportTest` | 1 | 46 条数据集评测 + 报告输出 |

### E.3 确认流程 / 并发与幂等测试

- 重复确认：`dup-01`、`dup-02`、`dup-03` 断言两次返回**同一个 playlistId** 且 `tb_playlist` 只增加 1 行。
- 并发确认：`conc-01`、`conc-02` 用 6 线程同时 confirm，断言所有成功请求指向**同一个歌单**。
- 越权：`cross-01`、`cross-02`、`guest-confirm-01` 断言 404/401 且任务状态不变。
- 过期：`expire-01` 断言 410 + `EXPIRED` + 无歌单。
- 失败回滚：`dberr-01`、`dberr-02` 断言整体回滚 + `FAILED` + 保留失败原因。

### E.4 真实模型端到端 + 真实浏览器

✅ **已执行并通过**（详见 `docs/agent-e2e-verification.md`）：

**后端链路**（真实 `qwen-flash`）
- 模型自主调用 `searchArtists → searchSongsByArtist → preparePlaylistCreation`
- 真实检索 20 首歌曲，预览真实歌曲；确认后创建歌单，绑定与预览完全一致
- 重复确认返回同一个歌单；刷新后查询状态正确
- 指标：`model_calls=4, tool_calls=3, input_tokens=13793, output_tokens=186, token_source=PROVIDER`

**浏览器交互**（Playwright + 系统 Chrome，23/23 通过）
- 工具状态（含真实耗时）、歌曲卡片（封面/歌名/歌手/时长）、确认卡片（预填标题、逐首播放/移除）
- **歌曲卡片点击播放 → 音频真实播放**（`currentTime=3.49s / duration=173.7s`）
- 改名 + 改可见性 + 移除一首后确认 → 落库标题与歌曲数与卡片完全一致
- 取消场景、刷新后卡片从服务端恢复并可继续确认、确认后待确认列表清空
- 页面无 JS 运行时错误

**联合验证发现并修复的真实缺陷**
模型有概率**跳过写工具却声称「请在下方确认卡片中确认」**，导致界面让用户去找一张不存在的卡片。
已加服务端输出护栏（用服务端事实校正 `done` 文本）+ 系统提示词加固 + 3 条确定性测试。

⚠️ **未执行**：真实模型的**批量**评测（46 条 × 真实模型）。能力已实现
（`AI_EVAL_ONLINE=1 ... -Donline=true`），本次未跑，因此模型侧准确率**无数据**。

### E.5 无法验证的项目（诚实标注）

| 项目 | 原因 |
|---|---|
| GitHub Actions CI 真实运行 | 本地无 GitHub runner，只做了 YAML 编写与本地等价命令验证 |
| 真实模型批量评测、模型侧准确率 | 未执行在线评测集 |
| 真实网络抖动下的 SSE 断线重连 | 只在进程内模拟（`sse-interrupt-01`）验证了租约释放与恢复 |

---

## F. 评测结果

数据集：`src/test/resources/agent-eval/cases.json`，**46 条**，27 个类别。
报告：`vibe-music-server/target/agent-eval/report.md`（+ `report.json`）。

| 指标 | 数值 |
|---|---|
| 用例总数 | 46 |
| 通过 | 46 |
| 失败 | 0 |
| **任务成功率** | **100.0%** |
| 做了数据库事实校验的用例 | 24 |
| 耗时 P50 / P95 / 最大 | 20 / 55 / 583 ms |
| 模型调用总次数 | 56 |
| 工具调用总次数 | 32 |
| 工具失败次数 | 3（均为**故意构造**的负向用例：空关键词、编造 ID、游客写操作） |
| 工具调用成功率 | 90.6% |
| 拿到供应商实测用量的任务数 | 23 / 46 |
| 实测输入 / 输出 Token 合计 | 5520 / 1380 |
| 未获取用量的任务数 | 23（离线替身不返回 usage） |

**成本估算**：**不给出金额**。金额取决于当前模型的最新计费单价，本报告不硬编码单价以免
给出过期或错误的数字。换算方式 `成本 = 输入Token × 输入单价 + 输出Token × 输出单价`，
单价请以百炼控制台当前价目为准。线上真实 Token 已在 `tb_ai_task_metric` 落库，
可直接聚合出每用户/每功能成本。

> **口径说明**：100% 成功率来自**离线**评测（模型为脚本替身），
> 它验证的是「工具执行 / 协议 / 确认流程 / 幂等 / 越权 / 过期 / 失败分类 / 指标落库」这些**确定性部分**，
> **不代表真实模型的意图识别与工具选择准确率**。后者待在线评测，目前无数据。

---

## G. CI、部署与演示

### CI

`.github/workflows/ci.yml`：`server`（`mvn -B verify` + 上传评测报告）、`client`、`admin` 三个
push/PR 任务，加一个手动触发的 `online-eval`（需 `DASHSCOPE_API_KEY` Secret）。
`server` 任务不依赖任何外部服务与密钥（H2 + 模型替身），可在 CI 稳定重复执行。
**评测进 CI**：`AgentEvalReportTest` 断言失败会让构建失败。

### 部署

`docs/agent-deployment.md`：环境要求、环境变量清单、数据库迁移顺序、
模型服务配置、三端启动命令、测试与评测命令、常见错误排查表、安全注意事项。

### 演示

`docs/agent-demo.md`：4 段演示流程（主链路 / 10 个异常场景 / 评测报告 / 回归），
每段都带可直接执行的 SQL 与 curl 校验命令，以及「现象 → 设计」的讲解要点表。

### 测试与评测命令

```bash
cd vibe-music-server && mvn -o test                                  # 102 条
cd vibe-music-server && mvn -o test -Dtest=AgentEvalReportTest       # 46 条评测 + 报告
cd vibe-music-server && AI_EVAL_ONLINE=1 DASHSCOPE_API_KEY=<k> \
  mvn -o test -Dtest=AgentEvalReportTest -Donline=true               # 真实模型（未执行）
```

---

## H. 未解决问题

### 技术风险与局限

1. **模型有概率跳过写工具**（真实模型测试中复现过）：只调用检索工具，却在文本里声称已生成预览。
   已由服务端输出护栏兜底（用服务端事实校正 `done` 文本）并有 3 条确定性测试，
   但**根因在模型侧**，无法在应用层根除。`tb_ai_task_metric.output_guarded` 可以统计它的发生频率。
2. **`tb_ai_task_metric.playlist_id` 在「对话→确认」链路里恒为 NULL**。
   确认是后续的独立 HTTP 请求，与对话任务不是同一个 run。
   缓解：`task` 事件与指标摘要已补 `pendingTaskId`，两表也可通过 `chat_id` 关联。
   彻底解决需要在 confirm 成功后回写指标行（需要把两个 taskId 显式关联）。
3. **真实模型侧质量只有少量样本**。离线评测用替身替换模型，覆盖不到意图识别与工具选择；
   真实模型只跑了少量用例（均成功），**没有准确率统计**。
4. **`AiWidget` 的确认卡片编辑能力有限**：支持改名、改可见性、移除歌曲，
   不支持新增歌曲（要加歌需重新对话）。
5. **单会话串行**：同一 `chatId` 并发生成返回 409（这是既有设计，用于保护会话窗口一致性）。
6. **失败任务不自动重试**：`FAILED` 是终态，需用户重新发起。
   这是刻意的安全选择（不确定实际写入状态时重复执行是危险的）。
7. **`pending()` 每次调用都执行一次全表过期扫描**（`expireAll`）。当前数据量下无影响，
   量大时应改为只扫当前用户。
8. **`done` 与 `token` 的一致性依赖客户端**：护栏只作用于 `done`，
   只消费 `token` 而不处理 `done` 的客户端会看到未校正的中间态。已在协议文档中明确该契约。

### 测试环境限制

- 无 GitHub runner，CI 配置未经真实运行。
- 本地库原有用户密码哈希不可知，端到端验证用的是临时账号（**已删除**）。
- 验证期间为让新代码生效，**重启了 8080 端口上原有的旧后端实例**（该端口是项目文档中的开发端口）。

### 模型依赖

- 依赖 DashScope `qwen-flash`；`DASHSCOPE_API_KEY` 缺失时 AI 功能不可用，
  但**构建、102 条测试、46 条离线评测仍可正常运行**。
- 未升级 LangChain4j（保持 0.36.2），未引入新框架。

### 后续建议

1. 把 `AgentEvalReportTest` 扩展出在线模式并接入 CI 的 `online-eval`，补齐模型侧指标。
2. 给 `tb_ai_task_metric` 增加 `pending_task_id` 列，并让 confirm 成功后回写，
   打通「对话任务 → 待确认任务 → 真实歌单」的完整链路。
3. 前端补「在确认卡片里新增歌曲」的能力。
4. 人工按 `docs/agent-demo.md` 走一遍浏览器演示并留存截图。
5. `git log -S` 仍能挖出历史提交中的旧 DashScope Key 明文；
   如需彻底清除需 `git filter-repo` 重写历史（破坏性，需先与协作者沟通）。
