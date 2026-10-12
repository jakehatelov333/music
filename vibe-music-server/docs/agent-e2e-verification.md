# 真实模型端到端验证记录

本文记录一次**真实环境**下的端到端验证：真实后端进程 + 真实 MySQL + 真实 DashScope `qwen-flash` 模型。
不是脚本替身、不是截图、不是构造数据。

- 验证时间：2026-10-12
- 后端：`mvn spring-boot:run --server.port=18080`（避开被占用的 8080）
- 数据库：本地 MySQL `vibe_music`（527 首真实歌曲、18 个真实歌单）
- 模型：`qwen-flash`（`langchain4j.dashscope.model-name`）
- 临时账号：`agent_e2e@example.com`（**验证完成后已删除**，库恢复为 65 用户 / 18 歌单）

---

## 一、输入

```
帮我创建一个适合开车时听的歌单，名字叫「夜间驾驶」，从 Ariana Grande 的歌里选 3 首
```

## 二、SSE 事件流（原样摘录）

```
[start]  {"taskId":"d8ce92a9-...","message":"Agent 开始处理","data":{"model":"qwen-flash"}}

[tool]   {"toolCallId":"...-searchArtists-1","tool":"searchArtists","phase":"start","message":"正在搜索歌手…"}
[tool]   {"toolCallId":"...-searchArtists-1","tool":"searchArtists","phase":"end",
          "ok":true,"code":"OK","message":"找到 1 位相关歌手","total":1,"kind":"ARTIST_LIST","durationMs":7}

[tool]   {"toolCallId":"...-searchSongsByArtist-2","tool":"searchSongsByArtist","phase":"start","message":"正在搜索该歌手的歌曲…"}
[tool]   {"toolCallId":"...-searchSongsByArtist-2","tool":"searchSongsByArtist","phase":"end",
          "ok":true,"code":"OK","message":"找到歌手「Ariana Grande」的 20 首歌曲","total":20,"kind":"SONG_LIST","durationMs":7}
[songs]  {"toolCallId":"...-searchSongsByArtist-2","kind":"SONG_LIST","total":20,
          "data":[{"songId":6,"songName":"34+35","artistName":"Ariana Grande", ...}, ...]}

[tool]   {"toolCallId":"...-preparePlaylistCreation-3","tool":"preparePlaylistCreation","phase":"start","message":"正在准备歌单预览…"}
[tool]   {"toolCallId":"...-preparePlaylistCreation-3","tool":"preparePlaylistCreation","phase":"end",
          "ok":true,"code":"OK","message":"已生成待确认的歌单预览：「夜间驾驶」共 3 首歌曲。请在界面上点击确认后创建。",
          "total":3,"kind":"PLAYLIST_PREVIEW"}

[confirm] {"kind":"PLAYLIST_PREVIEW","message":"等待用户确认",
          "data":{"taskId":"44bf2d12-...","status":"PENDING","title":"夜间驾驶","songCount":3,
                  "songs":[{"songId":7,"songName":"7 rings",...},
                           {"songId":16,"songName":"love language",...},
                           {"songId":25,"songName":"positions",...}]}}

[task]   {"ok":true,"code":"NONE","message":"任务完成",
          "data":{"model":"qwen-flash","success":true,"failureCategory":"NONE",
                  "modelCalls":4,"toolCalls":3,"toolFailures":0,"resultCount":20,
                  "latencyMs":2687,"inputTokens":13793,"outputTokens":186,
                  "tokenSource":"PROVIDER","playlistId":null,"pendingTaskId":"44bf2d12-..."}}

[done]   已生成预览，请在下方确认卡片中确认或取消：
         歌单名称：**夜间驾驶**
         歌曲数量：3 首
         简介：适合开车时聆听的Ariana Grande精选歌单，节奏适中，氛围感强。
         请确认是否创建该歌单。
```

**观察到的关键行为**

1. 模型自主选择了 3 个工具并按顺序调用：`searchArtists` → `searchSongsByArtist` → `preparePlaylistCreation`。
2. `songs` 事件推送了 20 首真实歌曲的结构化数据（含 `songId` / `coverUrl` / `audioUrl`）。
3. `confirm` 事件推送的待确认任务状态是 **PENDING**，此时**没有任何歌单被创建**。
4. `done` 文本明确说「请确认是否创建」——模型没有抢跑说「已创建成功」。
5. `tokenSource=PROVIDER`：13793 / 186 是**供应商返回的实测 Token**，不是估算。

## 三、确认前的数据库状态

```sql
SELECT COUNT(*) FROM tb_playlist;                        -- 18（与基线一致）
SELECT COUNT(*) FROM tb_agent_pending_action;            -- 1
-- 待确认列表接口
GET /ai/actions/pending  ->  1 条，status = PENDING
```

## 四、确认执行

```
POST /ai/actions/44bf2d12-.../confirm?version=1
HTTP 200
{"code":0,"message":"操作成功","data":{
   "taskId":"44bf2d12-...","status":"SUCCEEDED","statusDescription":"成功",
   "title":"夜间驾驶",
   "introduction":"适合开车时聆听的Ariana Grande精选歌单，节奏适中，氛围感强。",
   "style":"欧美流行, 节奏布鲁斯","isPublic":false,"version":1,
   "songs":[{"songId":7,"songName":"7 rings","artistName":"Ariana Grande",...}, ...],
   "playlistId":41, ...}}
```

## 五、数据库核对（确认后）

```sql
SELECT id,title,owner_user_id,status,is_public,cover_url,cover_is_custom
FROM tb_playlist WHERE id=41;
-- 41 | 夜间驾驶 | 154 | PUBLISHED | 0 | http://localhost:9005/vibe-music-data/songCovers/... | 0

SELECT song_id FROM tb_playlist_binding WHERE playlist_id=41 ORDER BY song_id;
-- 7, 16, 25      ← 与 confirm 事件里的预览歌曲**完全一致**

SELECT task_id,user_id,status,result_playlist_id,version
FROM tb_agent_pending_action ORDER BY id DESC LIMIT 1;
-- 44bf2d12-... | 154 | SUCCEEDED | 41 | 1

SELECT user_id,model,model_calls,tool_calls,tool_failures,result_count,
       input_tokens,output_tokens,token_source,success,failure_category,latency_ms
FROM tb_ai_task_metric ORDER BY id DESC LIMIT 1;
-- 154 | qwen-flash | 4 | 3 | 0 | 20 | 13793 | 186 | PROVIDER | 1 | NONE | 2687
```

**逐项核对**

| 检查项 | 结果 |
|---|---|
| 歌单归属 | `owner_user_id=154`，正是登录用户，**不是模型或前端指定的** |
| 歌单状态 | `PUBLISHED`（用户歌单恒为已发布）+ `is_public=0`（默认私有，可在确认卡片切换） |
| 封面规则 | `cover_url` 取自第一首歌曲封面，`cover_is_custom=0` → **沿用既有默认封面规则**，未破坏原业务 |
| 歌曲关联 | `{7,16,25}` 与预览完全一致 → 预览与执行内容一致 |
| 待确认任务 | `SUCCEEDED` + `result_playlist_id=41` |
| 指标 | 真实 Token（`PROVIDER`）、真实耗时、真实工具调用次数 |

## 六、幂等验证（重复确认）

```
POST /ai/actions/44bf2d12-.../confirm        （第二次，无 version 参数）
-> playlistId = 41，与第一次相同
SELECT COUNT(*) FROM tb_playlist;            -- 仍为 19（只增加 1）
```

## 七、刷新后查询

```
GET /ai/actions/44bf2d12-...
-> {"status":"SUCCEEDED","playlistId":41,"songCount":3}
```

## 八、鉴权验证（匿名访问新接口）

```
GET  /ai/actions/pending                                    -> 401 {"message":"请先登录用户账号"}
GET  /ai/actions/{id}                                       -> 401
POST /ai/actions/{id}/confirm                               -> 401
POST /ai/actions/{id}/cancel                                -> 401
PUT  /ai/actions/{id}                                       -> 401
GET  /ai/chat/metrics                                       -> 401
```

即：身份完全来自服务端认证，客户端无法通过传参绕过。

---

## 九、前端交互验证（Playwright + 系统 Chrome，真实浏览器）

第一轮验证时前端交互未覆盖，现已补齐。脚本：`scripts/agent-ui-verify.cjs`
（复用系统 Chrome，不下载自带 Chromium）。

```bash
NODE_PATH="C:/Users/lsx/.workbuddy-ai/binaries/node/workspace/node_modules" \
  node scripts/agent-ui-verify.cjs
```

结果：**23 / 23 通过**，截图在 `out/agent-ui/`，结构化结果在 `out/agent-ui/result.json`。

| 验证点 | 结果 | 证据 |
|---|---|---|
| 登录用户端（`UserStore` 写入 localStorage） | ✅ | `01-home.png` |
| AI 助手浮窗可打开 | ✅ | `02-widget-open.png` |
| 自然语言请求收到完整回复（加载态结束） | ✅ | `03-after-answer.png` |
| **前端展示工具执行状态**（含真实耗时） | ✅ | 「找到 1 位相关歌手 1ms / 找到歌手「Ariana Grande」的 20 首歌曲 1ms / 已生成待确认的歌单预览 5ms」 |
| **渲染结构化歌曲卡片**（封面 + 歌名 + 歌手 + 时长 + 播放按钮） | ✅ | 20 首：34+35, 7 rings, bad idea, bloodline, … `04-song-cards.png` |
| **点击歌曲卡片播放按钮 → 音频真实播放** | ✅ | `{paused:false, currentTime:3.49, duration:173.7}`，底部播放器显示 `34+35 / Ariana Grande 00:03/02:53` |
| 渲染待确认卡片 | ✅ | `06-confirm-card.png`、`06b-confirm-card-zoom.png` |
| 确认卡片预填歌单名称 | ✅ | `title="夜间驾驶"` |
| 确认卡片列出待创建歌曲（含逐首播放/移除按钮） | ✅ | 3 首 |
| **确认前数据库没有歌单**（只有待确认任务） | ✅ | `GET /ai/actions/pending` → 1 条 PENDING |
| 预览可编辑（改名 / 改可见性 / 移除歌曲） | ✅ | 3 → 2 首 |
| **点击确认 → 前端显示真实创建成功** | ✅ | 「已创建歌单「夜间驾驶 · 精选」，共 2 首歌曲」`08-created.png` |
| 成功卡片提供「查看歌单」入口并可跳转 | ✅ | 跳到 `http://localhost:8090/playlist/46` |
| **落库标题 = 预览中修改后的标题** | ✅ | `title="夜间驾驶 · 精选"` |
| **落库歌曲数 = 预览中移除后的数量** | ✅ | 2 首，与卡片上的 2 首一致 |
| 歌单归属当前登录用户 + 可见性跟随卡片勾选 | ✅ | `ownerUserId=155 isPublic=true` |
| 点击取消 → 显示已取消且无歌单创建 | ✅ | 卡片变为「已取消」`10-cancelled.png` |
| 刷新页面后待确认卡片从服务端恢复 | ✅ | 重新加载后出现「你还有待确认的歌单操作：待确认：创建歌单」`11-after-refresh.png` |
| 刷新后仍可确认并真实创建成功 | ✅ | 「已创建歌单「刷新恢复验证1」，共 2 首歌曲」 |
| 确认后待确认列表被清空 | ✅ | 1 → 0 |
| 页面无 JS 运行时错误 | ✅ | 控制台与 pageerror 均无错误 |

数据库侧交叉核对（本轮真实数据）：

```
任务1 556d3c09  模型调用4 工具3 结果20  token 14271/167(PROVIDER) success=1 耗时2523ms
任务2 4830c41f  模型调用4 工具3 结果20  token 14106/161(PROVIDER) success=1 耗时2500ms
任务3 e767efb8  模型调用4 工具3 结果20  token 14068/145(PROVIDER) success=1 耗时2595ms
歌单 46「夜间驾驶 · 精选」is_public=1   歌单 47「刷新恢复验证1」is_public=0
待确认任务：SUCCEEDED×2  CANCELLED×1
```

---

## 十、验证过程中发现并修复的真实缺陷

**这是本轮最有价值的产出 —— 它只有靠真实模型 + 真实浏览器才能发现。**

### 现象

连续第三次让模型建歌单时，模型**只调用了 2 个检索工具、没有调用写工具
`preparePlaylistCreation`**，但回复文本却写着：

> 已为你生成歌单预览，请在下方确认卡片中确认或取消：- 歌单名称：刷新恢复验证 …

于是前端显示「请在下方确认卡片中确认」，而**下方根本没有卡片**（`confirm` 事件未产生）。
数据库是安全的（没调用工具就没有任何写入），但**界面在误导用户**。

### 根因

模型在上下文中已经"见过"前两轮的工具返回（里面含完整预览 JSON），于是直接照着格式
"复述"了一个预览，跳过了工具调用。这是典型的上下文污染 + 工具调用被跳过。

### 修复（三层，均已落地并有测试）

1. **服务端输出护栏**（`AiController.guardFinalText`）：用**服务端事实**校正模型话术。
   - 声称「有待确认卡片」→ 必须本轮真的产生过待确认任务（`pendingTaskId != null`）；
   - 声称「已创建成功」→ 必须本轮真的写入过歌单（`playlistId != null`）。
   不满足就把 `done` 文本换成如实说明。命中时 `task` 事件的 `outputGuarded=true`
   并落库到 `tb_ai_task_metric.output_guarded`（衡量「模型有没有真的执行工具」的直接信号）。
2. **系统提示词加固**：明确「即使上文见过类似预览，本轮也必须重新调用工具」；
   「本轮没调用工具就不要提确认卡片、也不要列出预览内容」。
3. **契约明确化**：`done` 是权威最终文本，`token` 只是渐进预览；客户端必须用 `done` 覆盖已累积文本。

新增 3 条自动化测试（`AgentSseProtocolTest`）：
`previewClaimWithoutToolCallIsCorrected`（跳过工具时被校正）、
`legitimatePreviewClaimIsKept`（真有卡片时不动话术）、
`guardFinalTextIsFactDriven`（纯函数事实驱动）。测试总数 **102 → 105**。

> 说明：本轮的 3 次真实请求里模型都正常调用了工具（`output_guarded=0`），
> 所以护栏没有触发；它是为**间歇性**失败模式准备的安全网，
> 由上述确定性测试保证行为正确。

---

## 十一、本次验证**未覆盖**的部分

诚实标注：

- **真实模型的批量质量统计仍未做**：只跑了少量真实用例（均成功）。
  「工具选择准确率」「意图识别准确率」这类统计需要跑在线评测集
  （`AI_EVAL_ONLINE=1 ... -Donline=true`），该能力已实现但**未在本环境批量执行**。
- **未验证的网络场景**：真实网络抖动下的 SSE 断线重连（只在进程内模拟验证了租约释放与恢复）。
- 演示/验证用的临时账号与数据已删除，库恢复为 **65 用户 / 18 歌单 / 0 指标 / 0 待确认任务**。
