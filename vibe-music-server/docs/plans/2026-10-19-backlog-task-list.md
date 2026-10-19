# Vibe Music 遗留工作清单（Backlog）

起始日期：2026-10-19
基线：`test1` @ `4e04eba`，工作树干净，本地与远程同步
依据：`docs/plans/2026-10-15-bugfix-and-storage-cleanup-plan.md`（A–F 六阶段）、`docs/plans/2026-10-15-phaseC-media-cleanup-plan.md`、`docs/2026-10-15-project-minio-audit.md`

## 状态说明

- `[ ]` 未开始
- `[~]` 进行中
- `[x]` 已完成
- `[-]` 已放弃 / 明确不做
- 每完成一项，把该项前面的 `[ ]` 改成 `[x]` 并补一行「完成记录」（日期 + 关键结果）

## 基线与已决事项（不在本清单范围）

- `[x]` A 阶段：权限入口封堵（`0fda5ef`）
- `[x]` C1 阶段：媒体清理基础管线（`78cf51b`）
- `[-]` B 阶段：可靠会话撤销 / 鉴权改造 —— 2026-10-15 用户明确放弃，`auth_version` 刻意未实现，**勿再提议**
- `[-]` `tb_admin` 无盐 MD5 与 80 个种子账号共用哈希 —— 已报告，用户未要求处理

---

## P0　立即可修、零风险

### P0-1　修用户端 5 个类型错误 + CI 补 type-check（F 阶段 / 审计问题 14）

- [x] `vibe-music-client/build/plugins.ts:8` —— `viteEnv` 声明未使用（TS6133）
- [x] `vibe-music-client/src/components/Auth/ResetPasswordForm.vue:48` —— `rule` 未使用（TS6133）
- [x] `vibe-music-client/src/pages/album/index.vue:24` —— `matchesKeyword` 未使用（TS6133）
- [x] `vibe-music-client/src/utils/themeTools.ts:48` —— `void | number[]` 不能赋给 `number[]`（TS2322）
- [x] `vibe-music-client/src/utils/themeTools.ts:65` —— 同上
- [x] 修 `hexToRgb` 返回契约（失败返回 `null` 并显式判断），两处共用
- [x] `.github/workflows/ci.yml` 用户端 job 增加 `pnpm type-check`

验收：`pnpm type-check` 退出码 0；`pnpm build` 仍通过；专辑页与主题色无回归。 ✅ 已达成（2026-10-19）
风险：低。`themeTools` 两处需看真实调用链，不可只加 `!`。
估时：约 30 分钟。

---

## P1　有真实数据损坏风险

### P1-1　统一账号删除服务（D 阶段 / 审计问题 6、9）

现状（已核实）：

| 入口 | 位置 | 现状 |
| --- | --- | --- |
| 管理员单个删 | `UserServiceImpl.deleteUser` :655 | 只有 `userMapper.deleteById(userId)`，无任何清理 |
| 管理员批量删 | `UserServiceImpl.deleteUsers` :670 | 只有 `deleteByIds`，无任何清理 |
| 自助注销 | `UserServiceImpl.deleteAccount` :377–442 | 清理完整，但只处理头像，不处理个人歌单自定义封面 |

- [x] 抽 `AccountDeletionService`，管理员单删 / 批删 / 自助注销三入口统一复用
- [x] 修复「有反馈的用户删不掉」：`tb_feedback.user_id` 为 `ON DELETE RESTRICT`，事务内先删反馈
- [x] 补管理员路径的无外键表清理：`ai_chat_state` / `ai_chat_message` / `tb_ai_task_metric`
- [x] 收集头像，以及 `owner_user_id = ? AND cover_is_custom = TRUE` 的歌单封面，逐对象登记清理任务
- [x] 解决重试表容量问题：唯一键改为 `(user_id, resource_type, resource_ref)`；封面改走 `tb_media_cleanup_task`（可做跨表引用核对）
- [x] 批量删按固定 ID 顺序锁行，避免死锁
- [x] 管理员端响应区分「删除失败」与「已删除、附件待清理」(`cleanupPending`)
- [x] 新增幂等迁移 `sql/phaseD_account_cleanup.sql`
- [x] 测试：有反馈账号可删 / 多封面分别入队不覆盖 / 管理员与自助注销结果一致 / 批删其一失败整体回滚

验收：`AccountDeletionService` 为唯一删除实现；H2 真 SQL 测试全绿；真实库确认无孤儿行。 ✅ 已达成（2026-10-19，12 例新增测试 + 全量 337 通过）
风险：中。不可逆删除，先用 H2 + 隔离库验证，不得拿真实用户试。
估时：半天到一天。

> **实现说明**：头像与歌单封面<b>不再</b>在注销路径上立即删除，改为登记到 `tb_media_cleanup_task`（`source_type = ACCOUNT_DELETE`），由 `MediaCleanupService` 跨表核对引用后决定能否删。共享封面因此会被判 `RETAINED` 保留。`tb_account_cleanup_task` 只保留 `REDIS_TOKEN` 这类无法核对引用的资源。

### P1-2　头像 / Banner 接入引用核对（C1 遗留）

现状：以下 6 处仍为立即删除、零引用核对，与已治理的歌曲/专辑封面标准不一致。

- [ ] `UserServiceImpl` :265 —— 用户换头像删旧图
- [ ] `UserServiceImpl` :428 —— 自助注销删头像
- [ ] `ArtistServiceImpl` :424 —— 歌手换头像删旧图
- [ ] `BannerServiceImpl` :111 —— 更新轮播图删旧图
- [ ] `BannerServiceImpl` :168 —— 删除单张轮播图
- [ ] `BannerServiceImpl` :191 —— 批量删轮播图逐个直接删
- [ ] `MediaCleanupSourceType` 增加 `USER_AVATAR_REPLACE` / `ARTIST_AVATAR_REPLACE` / `BANNER_REPLACE` / `BANNER_DELETE`
- [ ] 全部改为 `mediaCleanupService.enqueue(...)`；批量删必须逐张入队，禁止前缀删除

注：`MediaReferenceMapper` 已包含 `tb_artist.avatar`、`tb_user.user_avatar`、`tb_banner.banner_url` 三类引用，引用核对本身无需改动。
验收：头像被两账号共用时换掉其一 → 对象保留（`RETAINED`）。
估时：2–3 小时 + 测试。

### P1-3　上传链路可靠化（E 阶段 / 审计问题 5、10、12、13）

#### E-1　提交后响应失败不该删已入库媒体（问题 5，P1）

- [x] `SongUploadServiceImpl` :394–400 拆分两种失败：事务未提交 → 回收新对象；已提交（:387 `TransactionTemplate`）→ **绝不删除**，按 `uploadId` 重读已提交结果返回
- [x] `resolveAlbumId`（:355）在小事务之外创建/更新专辑，并入同一事务
- [x] 测试：模拟 `buildCommitVO` 查询异常，断言正式封面与音频仍在

#### E-2　暂存清理失败后不丢重试入口（问题 10，P2）

- [ ] `cleanupExpired`（:428–437）失败时登记持久清理任务，成功后移除 Redis 索引

#### E-3　会话窗口与持久化（问题 12、13，P2）

- [ ] 统一 `STAGING_TTL_MILLIS = 24h`（:62）与 `SESSION_TTL_HOURS = 48h`（:63）为 48h，响应返回准确 `expiresAt`
- [ ] 处理 `AlbumUploadServiceImpl` :48 静态 `ConcurrentHashMap SESSIONS` 与 :498 Redis 过期后的内存回退（永不过期）
- [ ] 评估建 `tb_media_upload_session`（计划书本意）或单删内存回退
- [ ] 问题 13：先做授权/存在性校验再上传，或补持久补偿

估时：E-1 单独先做；E-2/E-3 合并，整体 1–2 天。

---

## P2　工程债

### P2-1　C2：媒体登记表 + 锁协议（暂不推荐）

- [ ] 新建 `tb_media_object`（DDL 以注释形式存于 `sql/phaseC_media_cleanup.sql` 末尾）
- [ ] 全部媒体写入口与 worker 锁定同一登记行，worker 锁内复核引用后才标记 `DELETING`

说明：消除「核对无引用」与「执行删除」之间的窄竞态窗口。单实例本地项目触发概率极低，**建议排在 P1 之后或不做**。

### P2-2　文档与环境

- [ ] 修正 `docs/plans/2026-10-15-bugfix-and-storage-cleanup-plan.md:3` 过期状态（仍写「待用户批准，尚未开始」）
- [ ] `scripts/` 下 phaseA/phaseC 验收脚本未入库（被 gitignore），评估是否纳入版本控制
- [ ] `git config core.longpaths true`，消除 `git status` 的 `Filename too long` 噪音
- [ ] 管理端 Browserslist 数据已 23 个月：`npx update-browserslist-db@latest`
- [ ] 环境一致性：`java` 为 JDK 20（项目要求 17）；`node` 为 24.14.0 而 `pnpm` 走 20.13.1 自带版本

---

## 待用户拍板的决策

1. D 阶段自定义封面：写入 `tb_account_cleanup_task`（加 `resource_name`）还是复用 `tb_media_cleanup_task`？倾向后者。
2. 管理员删除有反馈的用户：反馈物理删除（与自助注销一致）还是保留反馈、`user_id` 置空？
3. 上传会话：单删静态 `SESSIONS` 回退（约 1 小时）还是建 `tb_media_upload_session`（约半天）？
4. C2 是否实施：建议暂不做。

---

## 推荐执行顺序

1. P0-1 类型错误 + CI（约 30 分钟，零风险）
2. P1-1 统一账号删除服务 + phaseD 迁移（半天，含测试）
3. P1-2 头像/Banner 接入媒体清理（2–3 小时）
4. P1-3 上传链路：E-1 → E-2/E-3（1–2 天）
5. P2-2 文档与环境债（随手）
6. P2-1 C2（可长期不做）

每步独立 commit，可单独验收与回滚。

---

## 完成记录

（每完成一项在此追加一行）

| 日期 | 项 | 结果 |
| --- | --- | --- |
| 2026-10-19 | E-1 提交后失败不得删已入库媒体 | `SongUploadServiceImpl.commit` 引入 `committed` 分界线；提交后异常走 `recoverCommittedResult` 重读，绝不 `safeDeleteObject`；`resolveAlbumId` 改为接收 `TransactionTemplate`，与歌曲行同事务。新增 `SongUploadCommitBoundaryTest`（2 例）。全量回归 **325 通过 / 0 失败 / 0 错误 / 1 跳过**（改动前 323）。 |
| 2026-10-19 | P0-1 前端类型错误 + CI | `hexToRgb` 返回契约改为 `number[] \| null`，`getDarkColor`/`getLightColor` 显式判空（去掉 `!` 断言）；`plugins.ts` 参数改 `_viteEnv`；`ResetPasswordForm.vue` 参数改 `_rule`；删除专辑页未使用的 `matchesKeyword`。`pnpm type-check` 退出码 0、`pnpm build` 通过，CI 用户端 job 增加「类型检查」步骤。浏览器实测：落地页、曲库、专辑页（265 条）、专辑内搜索（筛到 1 条）、播放条均正常，控制台无报错。 |
| 2026-10-19 | P1-1 统一账号删除服务 | 新增 `AccountDeletionService`（单删/批删/自助注销唯一实现）、`MediaCleanupSourceType.ACCOUNT_DELETE`、`sql/phaseD_account_cleanup.sql`（唯一键改 `(user_id, resource_type, resource_ref)`）。修复：有反馈账号可删、管理员路径补齐无外键表与封面清理、批删整批回滚、封面走媒体清理管线保留共享对象。新增 `AccountDeletionServiceTest`（12 例），改造 `UserDeleteAccountTest` 装配与头像语义断言。全量回归 **337 通过 / 0 失败 / 0 错误 / 1 跳过**（改动前 325）。浏览器实测：落地页、曲库、播放（红玫瑰 00:20 正常推进）无回归，控制台无报错。 |
