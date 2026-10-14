# phase9：会话批量清除 / 密码规则 / 注销可靠性 / AI 偏好执行

> 本次为专项修复，不做整体重构。分支 `test1`，**改动未提交**（工作区状态见文末）。
> 后端全量 284 个测试 + 前端 58 个测试全部通过。

---

## 一、Sub-agent 执行情况

主 Agent 显式并行启动了 4 个 Sub-agent（同一批消息发出，实际并发执行），并在编码前统一拍板了 4 项接口契约。

| 子代理 | 任务名 | 文件边界 | 状态 |
|---|---|---|---|
| A | `backend-conversation-clearall` | 仅 `ConversationController` / `ConversationService` / `AiMapper` / `AgentPendingActionMapper`(只加) + 新 VO/测试 | 完成，9/9 |
| B | `backend-account-security` | 仅 `UserController` / `IUserService` / `UserServiceImpl` / `User*DTO` / `PasswordRules` / `MessageConstant` + 测试 | 完成，25/25 |
| C | `backend-agent-preference-enforcement` | 仅 `service/agent`(除 ConversationService) / `service/ai` / `tool` / `AgentSongMapper` / `AbstractAgentTest` + 测试 | 完成，9 + 138 回归 |
| D | `frontend-privacy-security` | 仅 `privacy-security/index.vue` / `api/ai.ts` / `api/system.ts` / `utils/password.ts` / 三个 Auth 表单 + 测试 | 完成，58/58 |

主 Agent 承担的集成工作：

- **契约先行**：批量清除接口路径/响应字段、密码规则与提示、清除期间并发策略、偏好约束落点，均在启动子代理前定死。
- **跨范围收口（2 次）**：① 注销返回值新增 `cleanupPending` 后，回给 D 适配前端；② B 发现 5 处范围外的残留密码正则（含**登录入口** `UserLoginDTO`），主 Agent 核实 `AdminDTO` 被注册/登录共用、实体注解是死代码后，授权 B 一并收口。
- **审核**：逐项核验 A/B/C/D 的实际改动（非只看汇报），确认前后端字段名逐字一致、无重复实现、无死代码。
- **验证**：跑后端全量 + 集成 + 前端全量，并修复了过程中暴露的团队基础设施缺陷（见 §6）。

---

## 二、修复总结

### 1. 清除全部 AI 对话记录漏删更早会话

**根因**：前端 `handleClearAllChats` 先 `listConversations()` 再逐个 `deleteConversation()`。而列表接口有 `ai.conversation.list-limit`（默认 20）上限，且列表 SQL 带 `EXISTS (SELECT 1 FROM chat_messages ...)` 过滤 —— **空会话根本不会出现在列表里**。两个因素叠加，必然漏删。

**修复**：新增专用批量清除接口 `DELETE /ai/conversations`，独立于列表分页与数量限制；前端改为**只调一次**该接口并严格判断业务 `code`。

### 2. 密码校验不真实

**根因**：旧正则 `^(?![0-9]+$)(?![a-zA-Z]+$)[0-9A-Za-z\W]{8,18}$` 只排除了"纯数字"和"纯字母"，**纯符号字符串可以通过**，且不保证同时含字母和数字。

**修复**：新建 `constant/PasswordRules` 作为唯一来源：
`^(?=.*[A-Za-z])(?=.*[0-9])[\x21-\x7E]{8,18}$`（8–18 位、必须含 ASCII 字母+数字、禁空格/非 ASCII）。
前端 `src/utils/password.ts` 提供逐字一致的正则（测试断言 `PASSWORD_PATTERN.source` 精确相等）。
**旧密码字段一律只校验非空**，不套新规则。

### 3. 注销账号缺乏失败恢复

**根因**：原顺序为「删 MinIO 头像 → 删反馈 → 删用户 → 删 Redis token」。MinIO 文件在 DB 成功**之前**就被永久删除；Redis/MinIO 不随 MySQL 事务回滚；失败无法区分。

**修复**：`TransactionTemplate` 包住全部 DB 删除（提交后才做外部清理），返回值改为 `Result<AccountDeletionResultVO>` 区分「删除失败」与「已删除但清理待重试」，失败落 `tb_account_cleanup_task` 由 `@Scheduled` 重试。

### 4. AI 排除偏好只在提示词里

**根因**：手动偏好仅由 `renderManualPreferenceBlock` 注入系统提示词。"提示词里告诉模型不要推荐" ≠ "候选查询真的过滤了"。

**修复**：`UserMemoryPreferenceService#exclusionConstraints` 复用**同一个 `merge()`** 输出结构化约束（歌手 id 集合 + 风格名集合），落到候选检索 SQL、随机推荐与歌单预览校验。

---

## 三、接口契约

### 3.1 清空全部会话（新增）

```
DELETE /ai/conversations
```
- 无路径参数、无请求体、**不接受客户端传入的 `userId`**；身份只来自服务端认证上下文（`PlaylistAccessService.requireUserId()`）。
- 成功 `200`：
```json
{ "code": 0, "message": "已清空全部会话",
  "data": { "conversationCount": 3, "messageCount": 42, "summaryCount": 2, "cancelledTaskCount": 1 } }
```
| 字段 | 口径 |
|---|---|
| `conversationCount` | 实际删除的 `ai_chat_state` 行数（含空会话、默认会话） |
| `messageCount` | 实际删除的 `chat_messages` 行数 |
| `summaryCount` | 被重置的摘要行数（原本 `summary_text` 非空） |
| `cancelledTaskCount` | 被取消的 PENDING 待确认任务数 |

- 冲突 `409`：`{ "code": 1, "message": "有对话正在生成回复，请等待生成结束后再清空全部会话" }`，**原数据完全不变**。
- 未登录 `401`；无会话时重复调用 `200 + code 0 + 四个计数全 0`（幂等）。

### 3.2 注销账号（返回值变更）

```
DELETE /user/deleteAccount
```
```json
{ "code": 0, "message": "账号已注销",
  "data": { "accountDeleted": true, "cleanupPending": false, "pendingItems": [] } }
```
- `accountDeleted=true, cleanupPending=true` → message `"账号已注销；部分外部数据清理将在后台重试"`，`pendingItems` 取值 `"REDIS_TOKEN"` / `"MINIO_AVATAR"`。
- DB 失败 → `code=1, accountDeleted=false`，数据未变。
- 用户已不存在（重复注销）→ 幂等成功，不报错、不二次误删。

### 3.3 密码规则

`^(?=.*[A-Za-z])(?=.*[0-9])[\x21-\x7E]{8,18}$`
- 应用范围：`UserRegisterDTO.password`、`UserResetPasswordDTO.newPassword`、`UserPasswordDTO.newPassword`、`UserAddDTO.password`。
- **不施加**：`UserPasswordDTO.oldPassword`、`UserPasswordDTO.repeatPassword`、`UserResetPasswordDTO.repeatPassword`（只校验非空）、`UserLoginDTO.password`、`AdminDTO.password`、`User`/`Admin` 实体。

---

## 四、关键设计取舍

### 4.1 会话并发清除：批量抢租约，抢不满就整体放弃

复用既有的数据库租约（`ai_chat_state.active_run_token` / `lease_expires_at`），但**不用"逐条删除"**——逐条会产生"删了一半才发现另一个会话正在生成"的中间态。做法：

1. `countConversationsByUser(userId)` 数出该用户**全部**会话行作为基准（不依赖列表接口）。
2. `acquireAllConversations(userId, token, 30)` —— **单条 UPDATE** 抢占全部空闲行，条件与单会话完全一致。
3. 抢到行数 **==** 基准行数才继续；否则 `releaseAllConversations(userId, token)` 释放已抢到的，抛 409，**零改动**。清除期间并发的生成请求因抢不到租约而失败，不会被写入。
4. 顺序不可颠倒：取消待确认任务 → 删 `chat_messages`（子查询靠 `ai_chat_state.user_id` 定位，先删会话行会让消息变成无法回收的孤儿）→ 清摘要 → 删会话行（连带带走 `context_json` 与租约，不留脏租约）。
5. 方法标 `@Transactional`，任一步失败整体回滚，绝不把"部分成功"当"全部成功"返回；失败路径额外补偿释放租约。

`releaseAllConversations` **必须带本次的 token**：不带就会误伤"抢租约之后、释放之前"被别人成功抢占的会话。

**`tb_ai_task_metric` 的处置**：**保留不删**。它是每任务一行的运行指标（模型调用数/工具调用数/耗时/token/成败），**不含消息内容**，属运行观测而非会话内容；`chat_id` 只是关联标识，删会话行后仍可独立统计。前端文案「删除你的全部 AI 会话及其消息记录」与之保持一致。该决策已写进代码注释。

### 4.2 密码规则：唯一来源 + 按入口区分强度要求

- 后端 `PasswordRules` / 前端 `password.ts` 各一份，**禁止再散落正则**。
- **登录不施加强度约束**：登录校验的是"已有账号的密码"，老用户密码可能不满足任何强度规则。若在登录端套新正则，合法老用户会被直接挡在门外，且此时无法通过改密自救（要先登录）。前端 `LoginForm.vue` 同步改为只校验必填。
- **`AdminDTO` 不施加强度约束**：该 DTO 被 `AdminController` 的 **register 与 login 两个接口共用**，套规则会挡住老管理员登录。已在类注释写明取舍。
- `User` / `Admin` 实体上的 `@Pattern` 是死代码（实体不参与请求体校验），且实体 `password` 常被写入 32 位 MD5 十六进制串，与"8-18 位"直接冲突 —— 一并删除。
- 旧的 `^(?![0-9]+$)(?![a-zA-Z]+$)[0-9A-Za-z\W]{8,18}$` 在后端与前端 **0 处残留**。

### 4.3 注销失败恢复：DB 先提交，外部资源可重试

顺序即理由：

1. **DB 事务**：删 `tb_feedback`（FK 为 `ON DELETE RESTRICT`，必须先删）→ 显式清理**无外键、不会级联**的表（`ai_chat_state` / `chat_messages` / `tb_ai_task_metric`）→ `deleteById(userId)`（其余表由 `ON DELETE CASCADE` 处理：`tb_playlist.owner_user_id`、`tb_playlist_create_request`、`tb_agent_pending_action`、`tb_user_music_profile`、`tb_user_memory_preference`、`tb_user_ai_memory_setting`、`tb_comment`、`tb_user_favorite`）。
2. **提交之后**才做外部清理：先 `stringRedisTemplate.delete(token)`（安全关键 —— token 留在 Redis 里等于注销后登录态仍有效），再 `minioService.deleteFile(avatar)`（孤儿文件无害）。两者各自 try/catch，互不影响，也不回滚。
3. 失败落 `tb_account_cleanup_task`（`UNIQUE(user_id, resource_type)` 保证幂等），由 `task/AccountCleanupRetryTask` 重试（初始延迟 2 分钟、每 10 分钟一次、上限 10 次，达上限标 `FAILED` 等人工处理）。

选择持久化重试表而非"只记日志"的依据：外部系统不参与 MySQL 事务，而 DB 已提交的注销不可逆，必须持久化待办 + 定时补偿才能同时保证"注销不回滚"与"最终一致"。

**已知取舍（需确认）**：`resource_ref` 对 `REDIS_TOKEN` 会**明文存待作废的 token**（短时有效），DB 读取者可短暂利用。已在 SQL 注释标注；若不接受，可改为只存 token 的 Redis key 摘要。

### 4.4 AI 排除规则：确定性过滤 vs 模型辅助

- **唯一合并入口**：`exclusionConstraints` 内部走与 `overview` 完全相同的 `merge()`（排除优先 / 冲突记录 / 自动画像只补充），**没有第二套"生效偏好"计算逻辑**。
- **落地范围**（确定性执行，不依赖模型守规矩）：
  - `MusicTools.searchPlaylistCandidates`（歌单候选检索）—— 歌手 + 风格排除
  - `MusicTools.getRandomSongs`（无条件随机推荐）—— 同
  - `PendingActionService.prepare` **与** `update`（歌单预览）—— 命中即 409 拒绝，草稿不落库、版本不推进
- **刻意不排除**：`searchSongs` / `searchSongsByArtist` / `getSongsByStyle`（用户明确发起的直接搜索）。这就是"当前请求优先于长期偏好"的**可验证规则** —— 不需要模型判断"这次要不要破例"，而是由"走哪个工具"决定。
- **比对用可靠业务数据**：歌手用 `tb_song_artist.artist_id` 的 `EXISTS`（合作曲只要任一位参与者被排除就整首排除）；风格用 `tb_song.style`（**逗号分隔多值**）做 token 精确比对：`CONCAT(',', REPLACE(REPLACE(REPLACE(COALESCE(s.style,''),'，',','),', ',','),' ,',','), ',') LIKE CONCAT('%,', #{style}, ',%')` —— 刻意**不用 `FIND_IN_SET`**（H2 `MODE=MySQL` 不保证支持）。
- **数量/分页/空结果**：排除在 SQL 层、`LIMIT` 之前完成（沿用 `findSongsExcluding` 既有模式），过滤后为空返回 `ToolResult.empty`，不引导模型绕过约束。
- **关闭个性化**：约束返回空（不再使用保存的画像与手动偏好），但当前请求的显式条件仍有效。
- **能力边界（不夸大）**：`EXCLUDE_NOTE` / `PREFERENCE_NOTE` **自由文本偏好不做确定性过滤** —— 无法从自然语言稳定推导出可参数化的 SQL 条件，因此保留为提示词里的模型辅助规则。代码注释已写明。
- **安全**：偏好全部以 `#{}` 参数化绑定，不作字符串拼接，也不当可执行指令。

---

## 五、文件变更清单

### 后端（vibe-music-server）

**新增**
| 文件 | 说明 |
|---|---|
| `constant/PasswordRules.java` | 密码规则唯一来源（PATTERN / MESSAGE） |
| `model/vo/ClearConversationsResultVO.java` | 批量清除回执（4 个计数） |
| `model/vo/AccountDeletionResultVO.java` | 注销回执（accountDeleted / cleanupPending / pendingItems） |
| `model/entity/AccountCleanupTask.java` | 外部清理重试任务实体 |
| `mapper/AccountCleanupMapper.java` | 注销时清理无外键表（chat_state / chat_messages / task_metric） |
| `mapper/AccountCleanupTaskMapper.java` | 重试表读写（upsertPending / findRetryable / markDone / markRetryFailed / markExhausted） |
| `task/AccountCleanupRetryTask.java` | `@Scheduled` 补偿重试（2min 起 / 10min 间隔 / 上限 10 次） |
| `sql/phase10_account_cleanup.sql` | `tb_account_cleanup_task` 建表迁移（幂等） |

**修改**
| 文件 | 说明 |
|---|---|
| `controller/ConversationController.java` | +`@DeleteMapping`（无路径）→ `DELETE /ai/conversations` |
| `service/agent/ConversationService.java` | +`clearAll(userId)`（`@Transactional`，批量抢租约 + 409 策略） |
| `mapper/AiMapper.java` | +6 方法（count/acquireAll/releaseAll/deleteMessagesByUser/clearSummariesByUser/deleteConversationsByUser） |
| `mapper/AgentPendingActionMapper.java` | +`cancelPendingByUser`（只加，未改既有） |
| `controller/UserController.java` / `service/IUserService.java` / `service/impl/UserServiceImpl.java` | 注销改 `Result<AccountDeletionResultVO>`；事务边界重排；新增用户判空幂等 |
| `model/dto/UserPasswordDTO.java` | newPassword 用 `PasswordRules`；oldPassword/repeatPassword 去掉 `@Pattern` |
| `model/dto/UserRegisterDTO.java` / `UserResetPasswordDTO.java` | 密码字段换成 `PasswordRules`；repeatPassword 去 `@Pattern` |
| `model/dto/UserLoginDTO.java` / `AdminDTO.java` / `model/dto/UserAddDTO.java` | 登录与复用型 DTO 去掉强度约束；`UserAddDTO` 套新规则 |
| `model/entity/User.java` / `Admin.java` | 删除死注解 `@Pattern`，修正过期注释 |
| `constant/MessageConstant.java` | +3 条注销结果文案 |
| `service/agent/UserMemoryPreferenceService.java` | +`ExclusionConstraints` / `exclusionConstraints(userId)`（复用 `merge()`） |
| `service/agent/AgentSongQueryService.java` | `searchExcluding` / `random` 接受排除约束 |
| `service/agent/PendingActionService.java` | +依赖、+`rejectExcludedSongs`（prepare 与 update 双路校验） |
| `mapper/AgentSongMapper.java` | `findSongsExcluding` / `findRandomSongs` 增排除参数与 SQL |
| `tool/MusicTools.java` | 候选检索与随机推荐应用排除；直接搜索加"刻意不排除"注释 |

**测试**
- 新增：`agent/ClearAllConversationsTest`(9)、`agent/PreferenceExclusionEnforcementTest`(9)、`security/PasswordRuleTest`(9)、`service/impl/UserUpdatePasswordTest`(5)、`task/AccountCleanupRetryTaskTest`(3)
- 修改：`agent/AbstractAgentTest`（新增 bean 与构造点）、`agent/AgentArtistPrecisionTest`、`agent/AgentToolProtocolTest`、`security/PlaylistBusinessTest`（构造点同步）、`service/impl/UserDeleteAccountTest`（8 条，原 2 条）

### 前端（vibe-music-client）
| 文件 | 说明 |
|---|---|
| `src/utils/password.ts`（新增） | PASSWORD_PATTERN / PASSWORD_RULE_MESSAGE / isPasswordValid |
| `src/api/ai.ts` | +`ClearConversationsResultVO`、+`clearAllConversations()`（保留旧 API） |
| `src/api/system.ts` | +`AccountDeletionResultVO`；`deleteUser()` 改用其结果泛型 |
| `src/pages/user/privacy-security/index.vue` | 清除会话改单次专用接口 + 严格判 `code` + 409 不重复弹窗；注销按 `cleanupPending` 分 success/warning；改密表单套新规则 |
| `src/components/Auth/LoginForm.vue` | 密码只校验必填（不套新规则） |
| `src/components/Auth/RegisterForm.vue` / `ResetPasswordForm.vue` | 新密码套 `PASSWORD_PATTERN` |
| `tests/privacy-security.test.mjs` | 35 → 58 条 |

### 本地工具（不入库，`.gitignore` 覆盖）
- `scripts/mvn-locked.sh` —— 多子代理并发时的 Maven 互斥锁（见 §6）。

---

## 六、测试结果（全部实际执行）

| 范围 | 命令 | 结果 |
|---|---|---|
| 后端全量（排除 2 个 `@SpringBootTest`） | `bash scripts/mvn-locked.sh mvn -o test "-Dtest=!RedisConnectionTest,!VibeMusicServerApplicationTests" -DfailIfNoTests=false` | **Tests run: 281, Failures: 0, Errors: 0, Skipped: 1 — BUILD SUCCESS** |
| 后端集成（真实 MySQL/Redis/MinIO + `.env`） | 导出 `.env` → `bash scripts/mvn-locked.sh mvn -o test -Dtest=RedisConnectionTest,VibeMusicServerApplicationTests -DfailIfNoTests=false` | **Tests run: 3, Failures: 0, Errors: 0 — BUILD SUCCESS**（`RedisConnectionTest` 2 + `VibeMusicServerApplicationTests` 1，`SecretsGuard` 密钥体检通过） |
| 前端 | `cd vibe-music-client && npm test` | **tests 58 / pass 58 / fail 0** |

**后端合计 284 个测试，0 失败、0 错误、1 跳过**（跳过项为既有的 `LyricsMatchScoreProbe`，非本次改动引入）。

本次新增/扩写的测试类（均在全量运行中确认执行）：
`ClearAllConversationsTest` 9 · `PreferenceExclusionEnforcementTest` 9 · `PasswordRuleTest` 9 · `UserDeleteAccountTest` 8 · `UserUpdatePasswordTest` 5 · `AccountCleanupRetryTaskTest` 3

关键覆盖：
- **会话**：活动租约 → 409 且数据不变；空会话被清；**25 个会话全清（证明不受 list-limit=20 限制）**；默认会话被清且可重建；部分失败不谎报成功；幂等；越权隔离（他人会话行/消息/任务无损）；PENDING 取消且不动 SUCCEEDED 与他人的任务。
- **密码**：纯数字/纯字母/纯符号/<8/>18/含空格/含中文 全部拒绝，字母+数字、字母+数字+符号接受；用 `jakarta.validation.Validator` 直接校验 DTO 实例并反射断言 `@Pattern.regexp() == PasswordRules.PATTERN`；**旧密码不合规但正确时允许改密**；登录 DTO 接受纯数字密码（证明登录不被格式卡住）。
- **注销**：DB 失败事务回滚（反馈+用户都还在，不碰 Redis/MinIO）；MinIO 失败 / Redis 失败 → `cleanupPending=true` 且落重试记录；重试成功标 DONE / 失败累计 / 达上限标 FAILED；重复注销幂等；级联数据清干净且不误删他人。
- **偏好**：自动画像喜欢摇滚 vs 手动排除摇滚（候选不含摇滚歌）；手动喜欢+排除同歌手 → 排除优先且 `overview.conflicts=1`；**双歌手合作曲排除任一即整首排除**；排除在 `LIMIT` 之前（28 首排除 1 首仍取满 20）；多值风格 `摇滚,电子` / 全角 `电子，古风` token 命中，排除「流行」不误命中「欧美流行」；prepare/update 塞入被排除歌 → 409、零落库、版本不推进；直接搜索仍搜得到被排除的歌手/风格；约束不影响其他用户与游客；关闭个性化 → 约束为空但显式请求仍有效。
- **前端**：>20 会话时仍只调一次专用接口且无列表循环；非零 `code` 不显示成功；409 不重复弹窗；成功提示用后端真实数字；密码正则 source 精确匹配 + 边界；AI 记忆入口与嵌套路由无回归。

### 过程中修复的团队基础设施缺陷（重要）

`scripts/mvn-locked.sh`（我为避免 3 个后端子代理并发写 `target/` 而写的 Maven 互斥锁）暴露了两个本机特有的坑，已修复并验证：

1. **`rm -rf` 的 Windows 路径陷阱**：`$TMPDIR` 是 `C:\Users\...\Temp` 形式，对其执行 `rm -rf` 会被沙箱 safe-delete 拒绝（`SAFE_DELETE_INVALID_PATH: embedded drive prefix is not allowed`）→ 无主锁**永远回收不掉**，等待循环**空转**，导致子代理的 Maven 卡死 10+ 分钟。修法：先把路径归一化成 MSYS 形式（`/c/Users/.../Temp`）再创建/删除，并给回收加 `rmdir` 兜底、删不掉就 `exit 97`（**绝不允许"删不掉还继续循环"**）。
2. **`find -mmin` 的退出码陷阱**：`find "$d" -maxdepth 0 -mmin +15` 无匹配时**也返回 0**，用 `&&` 判断会永远判为"陈旧" → 必须用 `[ -n "$(find ...)" ]`。

修复后已实测「连续两次加解锁 + 执行后无锁泄漏」通过。

---

## 七、未完成项与风险

**未完成**
1. **未做真实 MySQL/HTTP 端到端联调**：会话清除与注销的验证基于 H2 内存库 + 真实 mapper SQL + `@SpringBootTest` 上下文启动；未起真实服务端用 HTTP 打这两个接口。
2. **前端未做浏览器端到端验证**：前端验证为 `node --test` 静态/单元级；未用 Playwright 实际点击「清除全部 AI 对话记录」与「注销账号」。
3. **`AdminDTO` 注册仍无强度校验**：因该 DTO 复用注册与登录，本次刻意不加约束（加了会挡住老管理员登录）。彻底解决需拆分 DTO，未做。

**风险 / 需要确认**
1. **`tb_account_cleanup_task.resource_ref` 明文存 token**（`REDIS_TOKEN` 行，短时有效）。若不接受，需改为只存 key 摘要。
2. **生产部署必须先执行 `sql/phase10_account_cleanup.sql`**，否则 `AccountCleanupRetryTask` 每次调度都会因缺表报错。
3. **密码仍是无盐 MD5**（既有技术债，本次未动）。迁移建议：改 BCrypt/Argon2；因哈希不可逆需**惰性迁移**（登录/改密成功时若检测到库中是 32 位 hex 则用新算法重算并覆盖）；用 `{algo}$hash` 前缀标记算法版本便于灰度回滚；`password` 列需从 64 扩到 100+；迁移期双算法兼容。
4. **清除与"用户此刻新建会话"并发**时，新行可能被一并清掉并计入 `conversationCount`（fail-closed，符合"清空全部"语义）。
5. **`getRandomSongs` 用 `ORDER BY RAND()`**，小曲库下随机性可测但非确定（测试用集合断言规避）。
6. 登录 DTO 去掉格式约束后，**登录接口不再做任何密码格式校验**（只校验非空），强度判断完全交给注册/改密/重置入口。这是有意为之。

---

## 八、Git 状态

- 分支：**`test1`**；工作区**有未提交修改**（47 个变更条目：33 个修改文件 + 14 个新增文件）。
- 规模：`git diff --stat` → **33 files changed, 1121 insertions(+), 128 deletions(-)**（不含新增文件）。
- **新增迁移脚本**：`vibe-music-server/sql/phase10_account_cleanup.sql`（1 个，幂等）。
- 本次**未执行任何 `git commit` / `git push` / `git reset`**，也未覆盖用户既有未提交修改。
- `scripts/mvn-locked.sh` 属本地工具，被 `.gitignore` 覆盖，不会进入提交。
