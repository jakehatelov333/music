# 第一批基础加固（2026-10-12）

本批次实现用户缓存隔离、AI 会话归属、限流与调用限制、参数校验、异常处理和 AI 日志脱敏。按用户要求，不升级密码存储，也不更换 LangChain4j 版本。

## 数据隔离

- 收藏缓存键使用 `v2:业务类型:userId:查询参数JSON`，不再读取旧缓存键。旧键由原有 TTL 自然过期。
- 歌曲列表、歌曲详情和歌单详情的响应含当前用户收藏状态，因此取消这些完整响应的公共缓存。其他公共元数据缓存保持原有行为。
- 登录用户由经过 JWT 和 Redis 登录状态校验的身份决定会话，保留原 `user_chat_id_用户ID`，已有登录用户历史仍可使用。
- 游客 Cookie 改为 `vibe_guest_session`：32 字节安全随机凭证，Redis 映射到独立内部聊天 ID，固定有效期一小时，设置 HttpOnly、SameSite=Lax、Path=/ai，HTTPS 请求设置 Secure。
- 客户端不能指定内部聊天 ID。历史、最近消息、清空和统计均由服务端解析当前会话。
- 原来的 `guest_chat_id` Cookie 不再信任；旧游客会开始新会话。原数据不删除。
- 前端切换账号或退出登录时，取消当前输出并清空旧账号消息。

## 接口迁移

后端和用户端需要一起更新。原 `/test/**` AI 接口及直连模型调试接口不再提供。

| 方法 | 路径 | 请求 |
|---|---|---|
| POST | `/ai/chat` | JSON：`{"query":"帮我找轻音乐"}` |
| POST | `/ai/chat/stream` | 同上；SSE：token / done / error |
| GET | `/ai/chat/history` | 无 chatId |
| GET | `/ai/chat/history/page` | page（1～100000），size（1～100） |
| GET | `/ai/chat/recent` | limit（1～100） |
| DELETE | `/ai/chat/clear` | 无 chatId |
| GET | `/ai/chat/stats` | 无 chatId |

登录请求携带 Authorization；游客请求需要携带 Cookie。聊天内容放在 JSON 请求体，不进入 URL。AI 响应设置 Cache-Control: no-store。

## 限流和 AI 运行限制

Redis Lua 原子计数，多个应用实例共享配额；计数从首次请求开始按固定窗口过期。拒绝返回 HTTP 429、统一错误 JSON 和 Retry-After 秒数。请求参数不合法和执行失败也会消耗已申请的配额。

| 入口 | 限制 |
|---|---|
| 用户/管理员登录 | 每 IP 20 次 / 5 分钟，共享窗口 |
| 注册/重置密码 | 每 IP 5 次 / 10 分钟，共享窗口 |
| 发送验证码 | 每 IP 3 次 / 分钟；每邮箱 5 次 / 小时 |
| 所有 AI 接口 | 每 IP 60 次 / 分钟 |
| AI 生成，登录用户 | 每用户 10 次 / 分钟、100 次 / 24 小时 |
| AI 生成，游客 | 每 IP 5 次 / 分钟、20 次 / 24 小时 |

同步和流式生成共享配额，更换游客 Cookie 不能重置 IP 配额。匹配实际处理接口的路由模式，避免矩阵参数等 URL 写法绕过限制。OPTIONS 和异步二次分派不重复计费。

AI 输入最多 2000 字符。以下参数可在 application.yml 调整：

```yaml
ai:
  timeout-seconds: 60
  max-tool-calls: 4
  max-output-tokens: 512
```

每次模型请求最多输出 512 token；每个任务最多执行 4 个工具调用（包括同一轮批量调用），模型轮次最多 5 次。预算对象按请求创建，流式回调跨线程时仍共享同一个预算。模型工作线程池及队列也有上限。

超时或断线后停止应用侧输出，并阻止后续模型轮次与工具调用。LangChain4j/DashScope 0.36.2 没有在这条接口上暴露可靠的上游取消句柄，因此已经发出的单次供应商请求可能继续完成和计费；这里不承诺强制终止上游请求。

## 错误、校验和日志

- 查询 DTO 的分页限制为 pageNum 1～100000、pageSize 1～100；查询文字最多 200 字符。
- 评论必须有有效资源 ID 和非空正文，长度最多 255 字符，与现有数据库列保持一致。
- 参数错误返回 400，身份失效返回 401，权限不足返回 403，数据约束冲突返回 409，限流返回 429，数据服务不可用返回 503，同步 AI 超时返回 504。
- 错误响应沿用 Result 的 code/message/data 格式；流式开始后的失败通过 error 事件通知。
- 不向客户端返回 SQL、模型服务异常原文。未知错误日志只记录错误类型和 errorId。
- 关闭 MyBatis 参数打印；AI 控制器、工具调用和损坏历史解析不再记录聊天正文、工具入参或异常中可能包含的原始内容。

## 部署

- Redis 同时承担会话登记、限流和已有登录状态管理，必须可用；出错时拒绝相关请求，不回退为信任客户端会话。
- 默认允许 localhost/127.0.0.1 的 8090 和 8089 前端。通过 `CORS_ALLOWED_ORIGINS` 配置实际来源（逗号分隔、包含协议和端口），不使用通配来源配合凭据。
- IP 使用容器识别的 remoteAddr，不直接信任客户端的 X-Forwarded-For。部署反向代理时，应由受信任代理和容器正确恢复客户端地址，否则代理后的用户会共享 IP 配额。
- HTTPS 终止于反向代理时，同样需要配置可信转发信息，保证应用识别 HTTPS 并设置 Secure Cookie。
- 此批次没有数据库迁移。完整历史与模型窗口分离、会话并发排序、写工具及确认执行流程属于后续批次。

## 验证

后端完整测试（含原有 Spring 上下文和 Redis 连接测试，需要项目本地依赖）：

```powershell
cd vibe-music-server
mvn test
```

独立安全回归（模拟模型、数据库、Redis，不访问模型供应商）：

```powershell
mvn "-Dtest=cn.edu.seig.vibemusic.security.*Test" test
```

前端流式协议测试与构建：

```powershell
cd vibe-music-client
node --test tests/ai-stream.test.mjs
pnpm build
pnpm type-check
```

安全回归覆盖双账号缓存、个人收藏状态、伪造/过期游客凭证、登录身份优先、历史和清空归属、参数边界、分钟/每日配额、真实 Redis 并发配额、工具循环和批量调用上限、同步/流式超时、异常脱敏、跨域凭据隔离及 SSE 分片/空格/中断处理。

### 本次验证结果

- `mvn test`：41 项测试通过，无失败或跳过；包含实际 Redis 并发限流测试。
- `node --test tests/ai-stream.test.mjs`：4 项通过。
- 前端生产构建、两个改动前端文件的 ESLint、`git diff --check`：通过。
- 前端全量 `type-check` 仍有 5 处既有错误：`build/plugins.ts`、`ResetPasswordForm.vue`、`pages/album/index.vue` 各 1 处未使用变量，以及 `utils/themeTools.ts` 的 2 处返回类型错误。这些文件不在本次修改范围。
- 模型调用通过模拟响应和实际 LangChain4j 编排代码验证，没有发送真实供应商请求。部署后的真实模型交互仍需联调。
