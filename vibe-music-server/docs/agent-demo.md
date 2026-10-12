# Agent 面试演示脚本

目标：在真实环境里**连续演示**从自然语言请求到真实歌单创建的完整链路，
并当场验证异常场景与评测报告。全程使用真实数据库、真实业务服务，
不使用截图或假数据代替功能验证。

---

## 〇、演示前 5 分钟准备

```bash
# 1) 确认中间件在跑
mysqladmin -h127.0.0.1 -u<user> -p status        # MySQL 3306
redis-cli ping                                     # 应返回 PONG

# 2) 确认迁移已执行（两张表存在）
mysql -h127.0.0.1 -u<user> -p --default-character-set=utf8mb4 vibe_music \
  -e "SHOW TABLES LIKE 'tb_agent%'; SHOW TABLES LIKE 'tb_ai_task%';"

# 3) 准备一个可登录账号（密码 Test@12345）
mysql -h127.0.0.1 -u<user> -p --default-character-set=utf8mb4 vibe_music -e "
INSERT INTO tb_user(username,password,status,create_time,update_time)
SELECT 'demo', MD5('Test@12345'), 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM tb_user WHERE username='demo');"

# 4) 起后端与用户端
cd vibe-music-server && mvn spring-boot:run          # 8080
cd vibe-music-client && pnpm dev                     # 8090
```

> 若后端启动报端口 10644 被占用，用 `env -u SERVER__PORT -u SERVER_PORT mvn spring-boot:run`。

**记录基线数据**，方便后面证明“确实写进去了”：

```sql
SELECT COUNT(*) AS playlists_before FROM tb_playlist;
SELECT COUNT(*) AS bindings_before  FROM tb_playlist_binding;
```

浏览器打开 <http://localhost:8090>，用 `demo / Test@12345` 登录。

---

## 一、演示 1：自然语言 → 工具调用 → 歌曲卡片 → 确认 → 真实歌单（主链路）

### 1. 输入自然语言请求

在右下角 AI 助手（`Vibe AI 助手`）里输入：

> 帮我创建一个适合开车时听的歌单，叫「夜间驾驶」，选 3 首流行的歌

### 2. 观察前端实时展示的过程

- **工具执行状态**：出现 `正在搜索歌曲…` → 打勾并显示真实耗时（如 `69ms`）
- **结构化歌曲卡片**：列出真实歌曲（封面缩略图、歌名、歌手、时长），每首带 ▶ 播放按钮
  - 点一下播放按钮 → 底部播放器真的开始播放（歌曲卡是**可播放**的，不是纯文本列表）
- 随后出现 **确认卡片**：歌单名称输入框、「公开」开关、歌曲清单（可逐首移除）、
  「确认创建 / 取消」两个按钮

### 3. 关键断言（此时数据库还没变）

```sql
SELECT COUNT(*) FROM tb_playlist;                        -- 与 playlists_before 相同
SELECT COUNT(*) FROM tb_playlist_binding;                 -- 与 bindings_before 相同
SELECT task_id, status, title, version FROM tb_agent_pending_action ORDER BY id DESC LIMIT 1;
-- status = PENDING，说明只落了待确认任务，没有写业务数据
```

> **讲解点**：`preparePlaylistCreation` 只是“准备”，工具层无法创建歌单。
> 模型即使生成了“已创建成功”的文本，也不会让数据库发生变化。

### 4. 在确认卡片上做一次修改（体现预览与执行一致）

把名称改成「夜间驾驶 · 精选」，取消勾选一首歌，然后点 **确认创建**。

前端会先 `PUT /ai/actions/{taskId}` 提交草稿拿到**新版本号**，再用该版本号 `confirm`，
因此不会出现“确认的是旧预览、执行的是新内容”。

### 5. 验证真实执行结果

- 卡片变成绿色「已创建歌单「夜间驾驶 · 精选」，共 N 首歌曲」+ **查看歌单** 按钮
- 点「查看歌单」→ 跳到 `/playlist/{id}` 详情页，歌曲列表与预览完全一致
- 助手底部显示本次任务的真实指标：`任务 xxxxxxxx · 模型调用 3 次 · 工具 2 次 · 耗时 348ms · Token 320/80`

数据库侧交叉验证：

```sql
SELECT p.id, p.title, p.owner_user_id, p.status, p.is_public, p.cover_url, p.cover_is_custom
FROM tb_playlist p ORDER BY p.id DESC LIMIT 1;
-- owner_user_id = demo 用户 id；status = PUBLISHED；cover_url 取自第一首歌曲封面
-- cover_is_custom = 0，说明走的是既有「默认封面」规则，未破坏原业务

SELECT pb.song_id FROM tb_playlist_binding pb
WHERE pb.playlist_id = (SELECT MAX(id) FROM tb_playlist) ORDER BY pb.song_id;
-- 与预览里的 songId 集合完全一致

SELECT task_id, status, result_playlist_id FROM tb_agent_pending_action ORDER BY id DESC LIMIT 1;
-- status = SUCCEEDED，result_playlist_id 与上面的歌单 id 相同

SELECT task_id, model, model_calls, tool_calls, tool_failures,
       input_tokens, output_tokens, token_source, result_count,
       success, failure_category, latency_ms, playlist_id
FROM tb_ai_task_metric ORDER BY id DESC LIMIT 1;
-- success = 1；token_source = PROVIDER 表示 Token 是供应商实测值
```

---

## 二、演示 2：异常场景（逐个现场验证）

### 2.1 检索为空

输入：`帮我建一个歌单，叫「不存在」，只放「完全查不到的曲目」`

预期：助手如实说没有找到，**不出现确认卡片**，数据库不变。
`tb_ai_task_metric` 里这条任务 `success=1`（检索为空不是失败）。

### 2.2 数量不足

输入：`建一个歌单叫「冷门精选」，要 10 首民谣`

预期：助手如实说明实际只找到几首（曲库就这么大），不凑数。

### 2.3 用户在预览环节取消

对一张待确认卡片点 **取消**。

```sql
SELECT status FROM tb_agent_pending_action ORDER BY id DESC LIMIT 1;   -- CANCELLED
SELECT COUNT(*) FROM tb_playlist;                                       -- 未增加
```

再点一次确认（如果卡片还在）→ 服务端返回 409「该任务已取消，无法执行」。

### 2.4 重复点击确认

在预览卡片上**连点两次**「确认创建」。

预期：第二次返回**同一个歌单**（幂等），数据库只有一行歌单。

```sql
SELECT COUNT(*) FROM tb_playlist WHERE title LIKE '%夜间驾驶%';   -- 仍是 1
```

命令行也可以直接压：

```bash
TASK=<task_id>
for i in 1 2 3 4 5 6; do
  curl -s -X POST -H "Authorization: <token>" \
    "http://localhost:8080/ai/actions/$TASK/confirm" &
done; wait
mysql ... -e "SELECT COUNT(*) FROM tb_playlist;"   # 只增加 1
```

### 2.5 刷新页面后继续查询

生成一张待确认卡片后，**按 F5 刷新页面**，再打开 AI 助手。

预期：助手自动从服务端拉回待确认任务并重建确认卡片（数据来自
`GET /ai/actions/pending`，不是浏览器内存），可以继续确认。

### 2.6 越权：确认别人的任务

用另一个账号（或直接改 `taskId` 调接口）：

```bash
curl -i -X POST -H "Authorization: <另一个用户的 token>" \
  "http://localhost:8080/ai/actions/$TASK/confirm"
# 期望 404，而不是 403/500 —— 不泄漏“这个任务存在但不属于你”
```

原任务状态保持 `PENDING`，没有被执行。

### 2.7 待确认任务过期

把有效期改成过去，再点确认：

```sql
UPDATE tb_agent_pending_action SET expires_at = DATE_SUB(NOW(), INTERVAL 5 MINUTE)
WHERE task_id = '<task_id>';
```

预期：确认返回 **410**，任务状态落成 `EXPIRED`，没有歌单被创建。

（也可以把 `ai.pending-action-ttl-minutes` 调成 1 分钟现场等。）

### 2.8 歌单创建失败（业务数据变化）

生成预览后，把其中一首歌删掉，再确认：

```sql
DELETE FROM tb_playlist_binding WHERE song_id = <某首歌 id>;
DELETE FROM tb_song WHERE id = <某首歌 id>;
```

预期：确认返回 404，**整体回滚**（没有半成品歌单），任务状态落成 `FAILED`
并保留失败原因。前端展示明确失败状态，**不会谎报成功**。

### 2.9 模型调用失败

临时把 `DASHSCOPE_API_KEY` 改错，或断网后发一条消息。

预期：SSE 收到 `task(false, failureCategory=MODEL_ERROR)` + `error`，
页面正常结束加载（不会一直转圈）。

```sql
SELECT success, failure_category, failure_message FROM tb_ai_task_metric ORDER BY id DESC LIMIT 1;
-- success = 0，failure_category = MODEL_ERROR
-- failure_message 是分类级说明，不含原始异常文本、更不含密钥
```

### 2.10 SSE 中断后恢复

在助手回复过程中点关闭按钮（或切走页面再回来）。

预期：页面不卡在加载态；同一会话**下一次请求可以正常建立**（服务端已释放会话租约）。
指标表里那条任务 `failure_category = INTERRUPTED`。

---

### 2.11 模型跳过写工具却谎称"请确认下方卡片"（真实发现）

连续多次让模型建歌单时，模型偶尔**只调检索工具、不调写工具**，却在文本里写
「已为你生成歌单预览，请在下方确认卡片中确认」。此时下方**没有卡片**。

预期（已修复）：服务端输出护栏会把最终文本换成如实说明
（"抱歉，我这一轮没有真正生成待确认卡片…"），并把这轮标记为 `outputGuarded`。

```sql
SELECT task_id, tool_calls, output_guarded, success
FROM tb_ai_task_metric ORDER BY id DESC LIMIT 5;
-- 若模型跳过工具，该行 output_guarded = 1（衡量"模型有没有真的执行工具"的直接信号）
```

数据库始终是安全的：没调用写工具就没有任何写入。

---

## 三、自动化演示前端（可选，2 分钟跑完）

手工点一遍之外，还可以用脚本在真实浏览器里自动走完整条链路并留证据：

```bash
# 前置：后端 8080 + 用户端 8090 已启动，且已按上面第〇节建好 demo 账号
#       （脚本默认用 ui_e2e@example.com / Test@12345，可在脚本顶部改）
NODE_PATH="C:/Users/lsx/.workbuddy-ai/binaries/node/workspace/node_modules" \
  node scripts/agent-ui-verify.cjs
```

输出：`out/agent-ui/` 下的逐步截图 + `result.json`（含每一步的通过/失败与实测值）。
当前实测结果：**23 / 23 通过**，包括「歌曲卡片点击后音频真实播放（currentTime 前进）」、
「改名+移除一首后确认 → 落库内容与卡片完全一致」、「刷新后卡片从服务端恢复并可继续确认」。

## 四、演示 4：评测报告（真实数字）
```bash
cd vibe-music-server
mvn -o test -Dtest=AgentEvalReportTest
cat target/agent-eval/report.md
```

现场展示：

- 用例总数 / 通过 / 失败 / **任务成功率**
- 分类统计（检索、创建、幂等、越权、过期、失败分类……）
- 耗时 P50 / P95
- 模型与工具调用统计
- Token 统计与来源标注（`PROVIDER` = 供应商实测）
- 失败用例及原因

再跑一次同样的命令，结果一致 → 说明**可重复**，不依赖手工改库。

```bash
mvn -o test -Dtest=AgentEvalReportTest && echo "第二次运行同样通过"
```

---

## 五、原有功能没有回归（回归验证）

```bash
cd vibe-music-server && mvn -o test      # 102 条全部通过
cd vibe-music-client && pnpm build       # 构建成功
```

手工回归（浏览器）：

- 首页、搜索、歌手/专辑/歌单详情、播放器、收藏、我的歌单创建与编辑、管理端歌单列表
  —— 全部保持原有行为。
- 特别确认：**普通「我的歌单」创建流程**（`POST /playlist/mine` + `Idempotency-Key`）
  没有被改动，Agent 复用的就是它。

---

## 六、演示时的一句话讲解要点

| 观察到的现象 | 背后的设计 |
|---|---|
| 工具状态和歌曲卡片是分开的两块 UI | SSE 事件分了 `tool`（过程）与 `songs`（数据），前端按 `kind` 渲染 |
| 歌曲卡片能直接播放 | 工具返回里带 `songId` / `coverUrl` / `audioUrl`，是结构化协议而不是给人看的字符串 |
| 没搜到不等于失败 | `EMPTY` 与 `ERROR` 是不同 code，模型据此决定“换词重试”还是“放弃” |
| 确认前数据库没变 | 写工具只落待确认任务；真正的写入在 `confirm` 里，且要重新鉴权 |
| 连点两次不会建两个歌单 | 数据库条件更新抢占 + 创建收据表，两层幂等 |
| 刷新页面确认卡片还在 | 待确认任务持久化在 `tb_agent_pending_action`，前端按 `taskId` 重建 |
| 改了名字再确认，落库是新名字 | 预览与执行共享同一份 `PlaylistDraft`，修改会提升版本号 |
| 模型说“创建成功”不算数 | 只有服务端写入成功、拿到真实 `playlistId` 才会显示成功卡片 |
| 模型跳过工具却谎称“请确认下方卡片”时界面不会骗人 | 服务端输出护栏用事实校正最终文本，`outputGuarded` 会落库 |
| 报错信息里看不到原始异常 | 对外只暴露分类级说明；真实异常类型只进服务端日志 |
| 指标里的 Token 有来源标注 | 供应商返回的才算实测；拿不到就写 `UNAVAILABLE`，不估算冒充实测 |
