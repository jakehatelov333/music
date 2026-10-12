# Agent 部署与运行说明

面向从零启动本项目的人。所有命令都在仓库根目录 `D:/java_code/music`（或对应子目录）下执行。

---

## 一、环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | 后端编译目标为 17 |
| Maven | 3.9+ | 后端构建 |
| Node.js | 18.12+（建议 20） | 前端 |
| pnpm | 9.15.2 | 前端包管理（`packageManager` 已锁定） |
| MySQL | 8.0 | 业务库 `vibe_music` |
| Redis | 6+ | 登录态、AI 会话租约、限流（库号 1） |
| MinIO | 任意近期版本 | 封面/音频对象存储 |

---

## 二、环境变量

后端从**项目根目录**的 `.env` 读取（`spring.config.import: optional:file:./.env[.properties]`），
`.env` 已被 `.gitignore` 忽略，**不要把真实密钥提交到仓库**。

复制模板并填写：

```bash
cd vibe-music-server
cp .env.example .env   # 若仓库未提供模板，按下面清单手写
```

| 变量 | 必填 | 说明 |
|---|---|---|
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | 是 | MySQL 账号 |
| `DASHSCOPE_API_KEY` | **是（Agent 功能）** | 阿里云百炼 DashScope API Key。为空时 AI 对话不可用，但**构建、单元测试、离线评测仍可正常运行** |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | 是 | MinIO 账号 |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | 否 | 邮件验证码功能才需要 |
| `LYRICS_PROXY` | 否 | 歌词抓取代理 |
| `CORS_ALLOWED_ORIGINS` | 否 | 默认已包含 `http://localhost:8090` 与 `:8089` |

模型名在 `application.yml` 的 `langchain4j.dashscope.model-name`（当前 `qwen-flash`）。

---

## 三、数据库初始化与迁移

首次部署：导入基础库

```bash
mysql -h127.0.0.1 -u<user> -p --default-character-set=utf8mb4 vibe_music < vibe-music-server/sql/vibe_music.sql
```

然后按顺序执行增量迁移脚本（**均可重复执行**，不会自动运行）：

```bash
cd vibe-music-server
for f in sql/phase2_chat_migration.sql \
         sql/phase2_favorites_migration.sql \
         sql/phase2_personal_playlists.sql \
         sql/phase3_playlist_state_and_cover.sql \
         sql/phase4_agent_actions.sql; do
  echo ">>> $f"
  mysql -h127.0.0.1 -u<user> -p --default-character-set=utf8mb4 vibe_music < "$f"
done
```

`phase4_agent_actions.sql` 新增两张表：

- `tb_agent_pending_action`：服务端持久化的待确认任务
- `tb_ai_task_metric`：Agent 任务执行指标

脚本末尾会打印两张表的行数做 postflight 校验。

> **注意**：务必带 `--default-character-set=utf8mb4`，否则中文输出会被判成二进制。

---

## 四、模型服务配置

1. 到 <https://bailian.console.aliyun.com/> 创建 API Key；
2. 写入 `vibe-music-server/.env` 的 `DASHSCOPE_API_KEY`；
3. 启动时会由 `SecretsGuard` 做一次配置体检，缺失会给出明确提示；
4. 模型名在 `application.yml` 中配置，改模型不需要改代码。

---

## 五、启动

### 后端（8080）

```bash
cd vibe-music-server
mvn spring-boot:run
```

> **沙箱/CI 环境的坑**：某些运行环境会注入 `SERVER__PORT` 环境变量，
> Spring Boot 的宽松绑定会把它当成 `server.port` 导致端口冲突。此时用：
>
> ```bash
> env -u SERVER__PORT -u SERVER_PORT mvn spring-boot:run \
>   "-Dspring-boot.run.arguments=--server.port=8080"
> ```

健康判断：`curl http://localhost:8080/` 返回 **401「未登录，请先登录」** 即表示已完全启动。

### 用户端（8090）

```bash
cd vibe-music-client && pnpm install && pnpm dev
```

### 管理端（8089）

```bash
cd vibe-music-admin && pnpm install && pnpm dev
```

### 需要一个可登录账号

本地库里的历史用户密码哈希不可知。临时联调可以插一个：

```sql
-- 密码：Test@12345
INSERT INTO tb_user(username, password, status, create_time, update_time)
VALUES ('demo', MD5('Test@12345'), 0, NOW(), NOW());
```

**用完记得删。**

---

## 六、测试与评测

### 运行全部后端测试（102 条，含 46 条 Agent 离线评测）

```bash
cd vibe-music-server
mvn -o test          # -o 表示离线，依赖已在本地仓库时更快
```

测试使用 **H2 内存库（MySQL 兼容模式）+ 脚本化模型替身**，
不依赖 MySQL / Redis / MinIO / DashScope，可在任何环境稳定重复执行。

### 只跑 Agent 评测并查看报告

```bash
cd vibe-music-server
mvn -o test -Dtest=AgentEvalReportTest
cat target/agent-eval/report.md
```

报告包含：用例总数、通过/失败、任务成功率、分类统计、P50/P95 耗时、
模型与工具调用统计、Token 统计、失败用例及原因、逐条明细。
同时输出 `target/agent-eval/report.json` 便于机器消费。

### 只跑 Agent 相关测试

```bash
mvn -o test -Dtest='AgentToolProtocolTest,PendingActionTest,AgentSseProtocolTest,AgentEvalReportTest'
```

| 测试类 | 覆盖内容 |
|---|---|
| `AgentToolProtocolTest` | 工具返回协议、结构化歌曲数据、事件关联、可信上下文、游客写操作拦截 |
| `PendingActionTest` | 确认前不写库、幂等、并发、取消、过期、越权、修改与版本、刷新查询、失败回滚 |
| `AgentSseProtocolTest` | 真实控制器上的 SSE 事件顺序、confirm 事件、失败分类落库、异常文本不外泄 |
| `AgentEvalReportTest` | 46 条固定数据集评测 + 报告输出 |

### 真实模型端到端评测（可选）

离线评测用脚本替身替换模型，因此**不覆盖“模型是否选对工具”这类模型侧能力**。
配置好 Key 后可以跑真实模型：

```bash
cd vibe-music-server
AI_EVAL_ONLINE=1 DASHSCOPE_API_KEY=<key> mvn -o test -Dtest=AgentEvalReportTest -Donline=true
```

> 当前开发环境**没有配置 `DASHSCOPE_API_KEY` 且无外网**，
> 因此本仓库交付的报告中，真实模型端到端评测标记为**未执行**。

### 前端

```bash
cd vibe-music-client && pnpm build     # 生产构建
cd vibe-music-client && pnpm lint      # 代码规范
```

### 前端交互自动验证（Playwright + 系统 Chrome，真实模型）

需要后端 8080、用户端 8090 已启动，并存在可登录账号（默认 `ui_e2e@example.com` / `Test@12345`，
可在脚本顶部修改；用完记得删账号）。

```bash
NODE_PATH="C:/Users/lsx/.workbuddy-ai/binaries/node/workspace/node_modules" \
  node scripts/agent-ui-verify.cjs
```

产物：`out/agent-ui/` 下的逐步截图与 `result.json`。脚本复用**系统 Chrome**，不下载自带 Chromium。

---

## 七、CI

`.github/workflows/ci.yml` 包含四个任务：

| 任务 | 触发 | 内容 |
|---|---|---|
| `server` | push / PR | `mvn -B verify`：构建 + 102 条测试（含 Agent 评测，失败即失败）+ 上传评测报告 artifact |
| `client` | push / PR | `pnpm install --frozen-lockfile && pnpm build` |
| `admin` | push / PR | 同上 |
| `online-eval` | 手动 `workflow_dispatch` | 真实模型端到端评测，需要仓库 Secret `DASHSCOPE_API_KEY`，未配置则明确跳过 |

`server` 任务**不需要**任何外部服务或密钥，因为它用的是 H2 + 模型替身。

---

## 八、常见错误排查

| 现象 | 原因与处理 |
|---|---|
| 启动报 `Web Port 10644 was already in use` | 运行环境注入了 `SERVER__PORT`，用 `env -u SERVER__PORT -u SERVER_PORT mvn spring-boot:run` |
| `curl http://localhost:8080/` 返回 200 | 后端还没起完或端口被别的进程占用 |
| AI 对话返回 503 / `AI 服务暂不可用` | `DASHSCOPE_API_KEY` 为空或无效；看后端日志里的失败分类 |
| AI 对话返回 409「当前会话正在处理另一条消息」 | 同一会话有并发生成，等前一条结束或换会话 |
| 待确认任务点确认返回 409「正在执行中」 | 另一个请求已抢占，稍候刷新即可看到结果 |
| 待确认任务点确认返回 410「已过期」 | 超过 `ai.pending-action-ttl-minutes`（默认 30 分钟），重新发起创建 |
| `Table 'vibe_music.tb_agent_pending_action' doesn't exist` | 未执行 `sql/phase4_agent_actions.sql` |
| 中文乱码（脚本输出被当成二进制） | `mysql` 加 `--default-character-set=utf8mb4` |
| `mvn` 报无法解析依赖 | 去掉 `-o` 联网拉取，或检查本地仓库 |
| 前端 `pnpm build` 报批量删除被拦截 | 构建到项目外目录：`npx vite build --outDir "<项目外路径>"` |

---

## 九、安全注意事项

- **不要把真实密钥提交到仓库**。`.env` 已 gitignore；`application.yml` 里全部用
  `${VAR:}` 占位符。
- 历史提交中曾出现过明文密钥，当前值均已轮换。如需彻底清除需 `git filter-repo`
  重写历史（破坏性操作，需先与协作者沟通）。
- Agent 指标表与待确认任务表**不存储**用户消息、模型回复、API Key 或认证凭据。
- 服务端日志只记录异常**类型**与失败分类，不记录原始异常文本。
