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

- [x] 统一 `STAGING_TTL_MILLIS = 24h`（:62）与 `SESSION_TTL_HOURS = 48h`（:63）为 12h，响应返回准确 `expiresAt`
      （**已随 P1-4 完成**：单一常量 `UPLOAD_TTL_HOURS = 12`，清理改按最后活动时间）
- [ ] 处理 `AlbumUploadServiceImpl` :48 静态 `ConcurrentHashMap SESSIONS` 与 :498 Redis 过期后的内存回退（永不过期）
- [ ] 评估建 `tb_media_upload_session`（计划书本意）或单删内存回退
- [ ] 问题 13：先做授权/存在性校验再上传，或补持久补偿

估时：E-1 单独先做；E-2/E-3 合并，整体 1–2 天。

---

## P1-4　歌曲上传页：封面搜索（复用 QQ 音乐专辑封面）

**目标**：管理员上传歌曲时，除了「内嵌封面 / 手动上传」，还能按歌名+歌手搜出候选封面并选用。

### 关键实测结论（2026-10-19 已验证）

| 验证项 | 结果 |
| --- | --- |
| QQ 搜索接口返回 `albummid` | 有（如 `0041WVfh2vtlJE`） |
| 封面 URL 可下载 | `https://y.gtimg.cn/music/photo_new/T002R{size}M000{albumMid}.jpg` |
| 尺寸 | 300 / 500 / 800 均可用（500px 约 107 KB） |
| **防盗链** | **无**。不带 Referer 也返回 200 → 浏览器可直接加载，无需后端代理 |
| LRCLIB 是否有封面 | **无**，只有歌词 |

**⚠️ 不能直接把搜索结果当候选列表**：实测搜「淘金小镇 周杰伦」返回 8 条，只有 1 条是目标歌曲，
其余是《稻香》《夜曲》《兰亭序》等。必须**先按歌名+歌手过滤**，再**按专辑去重**聚拢成候选。
参考实测：「青花瓷」→ 录音室版（我很忙）/ Live 版（魔天伦）/ 翻唱版（中国好声音）；
「孤勇者」→ 录音室版 / Live 版 / DJ 版 / 童声版。所以候选天然存在多版本，需要标注版本名。

### 已确认的产品决策

| 项 | 决定 |
| --- | --- |
| 封面来源 | 仅 QQ 音乐 |
| 存储方式 | **下载成文件**存 MinIO staging（不存外链）；候选图不预下载，浏览器直连 CDN |
| 候选数量 | 自动模式最多 4 个，手动模式最多 8 个，按专辑聚拢并标注版本 |
| 尺寸 | 允许切换 300 / 500 / 800，默认 500；一次响应带回三档地址 |
| 自定义封面 | 保留原有功能，与搜索封面并列 |
| **手动搜索** | **支持**。元数据解析错时自动搜索必然也错，需由管理员输入关键词重搜 |
| 暂存过期 | **12 小时**（与上传会话统一，见下方 TTL 章节） |
| 换选封面时旧图 | 直接 `safeDeleteObject`（**偏离原计划**，见下） |
| 是否显示剩余有效期 | **不显示**（用户明确不要） |

#### 实施时对原计划的两处偏离

1. **换选旧图不登记 `tb_media_cleanup_task`**：staging 对象不可能被任何业务表引用，
   引用核对恒为 0，登记等于绕圈；且 `updateAssets` 无事务，每次换选都会打
   「事务外登记」警告日志；12h TTL 已兜底。改用 `safeDeleteObject`。
2. **`cover-from-album` 只接受 `albumMid + size`，不接受 URL**：接受 URL 等于给管理端
   开一个任意地址抓取入口（SSRF）。改为服务端按固定模板拼地址 + mid 白名单校验。

### 任务拆解

**后端**

- [x] `QqMusicProvider` 的 `QqSong` record 保留 `albummid`（当前解析后丢弃）
- [x] 抽取 `QqMusicCatalog` / `QqSongHit`，歌词与封面共用同一份检索结果
- [x] 新增封面候选模型 `CoverCandidateVO`：`{ albumMid, albumName, artistName, trackName, versionLabel, score, durationSeconds, coverUrls }`
- [x] `QqCoverUrl` 按尺寸拼装地址 + `albumMid` 白名单校验（`^[A-Za-z0-9]{10,20}$`）
- [x] `CoverNameNormalizer` 归一化歌名（剥离版本后缀）与歌手集合求交集
- [x] `POST /admin/song-cover/search`：自动模式（过滤+去重，≤4）/ 手动模式（关键词原样检索，≤8）
- [x] `POST /admin/song-upload/{uploadId}/cover-from-album`：下载到该 uploadId 的 staging
- [x] `CoverDownloader`：流式 5 MB 截断、Content-Type 必须是 image/*、404 与超限给出不同提示
- [x] 下载成功后写入会话 `coverObjectName` / `coverUrl`，来源标记 `SEARCHED`

**TTL 统一（与封面强相关，同批做）**

- [x] 统一 `STAGING_TTL_MILLIS = 24h` 与 `SESSION_TTL_HOURS = 48h` 为 **12 小时**（单一常量 `UPLOAD_TTL_HOURS = 12`）
- [x] 清理判据从「创建时间」改为「最后活动时间」：`saveSession` 时更新 zset 分数为 `now`
- [x] 同步更新上传页提示文案（当前硬编码「24 小时后自动清理」）

**前端（`vibe-music-admin/src/views/song-upload/index.vue`）**

- [x] 封面区增加「搜索封面」按钮（与「上传封面」并列）
- [x] 候选卡片网格：缩略图 + 专辑名 + 版本标注 + 时长；点击选中
- [x] 尺寸切换（300/500/800），切换只改 img src，不重新请求
- [x] 选中后显示预览，并保留「上传自定义封面」入口
- [x] **手动搜索模式**：单选切换，输入关键词原样检索

**测试**

- [x] 候选过滤与去重：只保留同名+同歌手，按 albummid 去重
- [x] `albummid` 为空 / 封面下载 404 时该候选被跳过而非整体失败
- [x] 下载体积超限被拒绝（本地 HttpServer 验证流式截断）
- [x] 非法 `albumMid` 一律拒绝且不发起下载（SSRF 边界）
- [x] 换选封面时旧对象被回收
- [x] TTL 统一后：12 小时内可提交、超过 12 小时会话与文件同时失效

**验收**

- [x] 真实歌曲搜索能返回带封面的候选；选中后封面出现在 staging 且可预览；
      提交后落到 `songCovers/`；超过 12 小时未提交则会话与文件一起被清理。

**完成记录（2026-10-19）**：新增 47 例测试，全量 **396 通过 / 0 失败 / 0 错误**。
真实端到端（临时管理员 + 真实 mp3，验证后已清理）：自动模式「青花瓷」→ 3 个候选且
《稻香》《七里香》《兰亭序》全部被过滤；手动模式「淘金小镇 周杰伦」→ 目标专辑
《太阳之子》排第一；下载 500px 得到真实 JPEG（107 KB）；尺寸切换 300/800 正常
（60 KB / 203 KB）；SSRF 防护全部拦截；提交后封面转存 `songCovers/`、staging 清理；
删除歌曲后封面与音频各入一条清理任务且均 `SUCCEEDED`；`tb_song` 540 行、`tb_admin` 81 行
与验证前一致。

**未做**：浏览器 UI 实操（bsk 扩展未连接，无法驱动文件选择与点击）。接口层已完整验证。

**估时**：半天到一天。

---

## P2　工程债
### P2-1　C2：媒体登记表 + 锁协议（暂不推荐）
- [ ] 新建 `tb_media_object`（DDL 以注释形式存于 `sql/phaseC_media_cleanup.sql` 末尾）
- [ ] 全部媒体写入口与 worker 锁定同一登记行，worker 锁内复核引用后才标记 `DELETING`

说明：消除「核对无引用」与「执行删除」之间的窄竞态窗口。单实例本地项目触发概率极低，**建议排在 P1 之后或不做**。

### P2-3　登录 token 的 Redis 生命周期治理

现状（2026-10-19 实测 db1）：共 16 个键，其中 7 个是登录 token。**代码本身已有 6 小时 TTL**
（`UserServiceImpl:184`、`AdminServiceImpl:88` 均为 `set(token, token, 6, TimeUnit.HOURS)`），
但实测发现 3 个 token 的 `TTL = -1`（永不过期），且解码 JWT 的 `exp` 后确认
**它们早在 2026-10-14 就已过期，属「加 TTL 之前」写入的历史残留**。

- [ ] 清理历史残留 token：3 个 `adminId=58(admin_172)` 的过期键 + 1 个测试遗留的 `adminId=190(e2e_lyrics_tmp)`
- [ ] 清掉 2 个已失效的上传会话（`song-upload:session:*` 与索引中对应成员，staging 文件早已被 24h 任务删除）
- [ ] （可选，单独 commit）`LoginInterceptor` 增加 JWT `exp` 兜底校验：JWT 已过期时即使 Redis 有键也拒绝，并顺手删掉脏键

**边界说明**：第 3 项是防御性加固，**不改令牌形态、不引入 auth_version、不碰已放弃的鉴权改造**。
「禁用/改密后旧 token 仍可用最多 6 小时」是既有决策下已接受的已知风险，不在本项范围。

### P2-2　文档与环境

- [ ] 修正 `docs/plans/2026-10-15-bugfix-and-storage-cleanup-plan.md:3` 过期状态（仍写「待用户批准，尚未开始」）
- [ ] `scripts/` 下 phaseA/phaseC 验收脚本未入库（被 gitignore），评估是否纳入版本控制
- [ ] `git config core.longpaths true`，消除 `git status` 的 `Filename too long` 噪音
- [ ] 管理端 Browserslist 数据已 23 个月：`npx update-browserslist-db@latest`
- [ ] 环境一致性：`java` 为 JDK 20（项目要求 17）；`node` 为 24.14.0 而 `pnpm` 走 20.13.1 自带版本

---

## 待用户拍板的决策

1. 管理员删除有反馈的用户：反馈物理删除（与自助注销一致）还是保留反馈、`user_id` 置空？
2. 上传会话：单删静态 `SESSIONS` 回退（约 1 小时）还是建 `tb_media_upload_session`（约半天）？
3. C2 是否实施：建议暂不做。
4. P2-3 第 3 项（`LoginInterceptor` 加 JWT `exp` 兜底）是否做？建议做，但需单独 commit。

**已决策（2026-10-19）**

- D 阶段封面清理：**复用 `tb_media_cleanup_task`**（`source_type = ACCOUNT_DELETE`），已实现。
- 封面搜索：见 P1-4 的「已确认的产品决策」表。

---

## 推荐执行顺序

1. ~~P0-1 类型错误 + CI~~ ✅ 已完成
2. ~~P1-1 统一账号删除服务 + phaseD 迁移~~ ✅ 已完成
3. ~~P1-4 封面搜索 + TTL 统一~~ ✅ 已完成
4. P2-3 token 历史残留清理（10 分钟，零风险）
5. P1-2 头像/Banner 接入媒体清理（2–3 小时）
6. P1-3 上传链路：E-2 / E-3 剩余部分（1–2 天）
7. P2-2 文档与环境债（随手）
8. P2-1 C2（可长期不做）

每步独立 commit，可单独验收与回滚。

---

## 完成记录

（每完成一项在此追加一行）

| 日期 | 项 | 结果 |
| --- | --- | --- |
| 2026-10-19 | E-1 提交后失败不得删已入库媒体 | `SongUploadServiceImpl.commit` 引入 `committed` 分界线；提交后异常走 `recoverCommittedResult` 重读，绝不 `safeDeleteObject`；`resolveAlbumId` 改为接收 `TransactionTemplate`，与歌曲行同事务。新增 `SongUploadCommitBoundaryTest`（2 例）。全量回归 **325 通过 / 0 失败 / 0 错误 / 1 跳过**（改动前 323）。 |
| 2026-10-19 | P0-1 前端类型错误 + CI | `hexToRgb` 返回契约改为 `number[] \| null`，`getDarkColor`/`getLightColor` 显式判空（去掉 `!` 断言）；`plugins.ts` 参数改 `_viteEnv`；`ResetPasswordForm.vue` 参数改 `_rule`；删除专辑页未使用的 `matchesKeyword`。`pnpm type-check` 退出码 0、`pnpm build` 通过，CI 用户端 job 增加「类型检查」步骤。浏览器实测：落地页、曲库、专辑页（265 条）、专辑内搜索（筛到 1 条）、播放条均正常，控制台无报错。 |
| 2026-10-19 | P1-1 统一账号删除服务 | 新增 `AccountDeletionService`（单删/批删/自助注销唯一实现）、`MediaCleanupSourceType.ACCOUNT_DELETE`、`sql/phaseD_account_cleanup.sql`（唯一键改 `(user_id, resource_type, resource_ref)`）。修复：有反馈账号可删、管理员路径补齐无外键表与封面清理、批删整批回滚、封面走媒体清理管线保留共享对象。新增 `AccountDeletionServiceTest`（12 例），改造 `UserDeleteAccountTest` 装配与头像语义断言。全量回归 **337 通过 / 0 失败 / 0 错误 / 1 跳过**（改动前 325）。浏览器实测：落地页、曲库、播放（红玫瑰 00:20 正常推进）无回归，控制台无报错。 |
| 2026-10-19 | 歌词搜索（上传页按元数据） | 新增 `POST /admin/song/lyrics/search` 与 `LyricsSearchDTO`；复用 `fetchFromProviders`（QQ 主源 + LRCLIB 兜底 + 打分阈值），歌手展示串拆成候选链，时长参与消歧。上传页加「搜索歌词」按钮与结果预览。新增 `LyricsSearchByMetaTest`（6 例）。全量回归 **343 通过 / 0 失败 / 0 错误 / 1 跳过**（改动前 337）。真实端到端：《淘金小镇》→ 命中 QQ_MUSIC、匹配度 0.9、返回完整 LRC；临时管理员与 staging 已清理。 |
| 2026-10-19 | P1-4 封面搜索（4 个 commit） | ①`refactor(lyrics)` 抽取 `QqMusicCatalog`/`QqSongHit`，保留 `albummid`，歌词与封面共用同一份检索结果；②`fix(upload)` 统一 TTL 为 12h 且清理改按「最后活动时间」，修掉「界面说还在、文件已没」的缺陷；③`feat(cover)` 新增 `CoverSearchService`（自动过滤+去重 ≤4 / 手动原样检索 ≤8）、`CoverNameNormalizer`（剥离版本后缀、歌手交集）、`QqCoverUrl`（拼地址+白名单防 SSRF）、`CoverDownloader`（流式 5 MB 截断、Content-Type 校验）与两个端点；④`feat(admin)` 上传页封面区拆出「搜索封面」，含手动搜索模式与尺寸切换。新增 47 例测试，全量 **396 通过 / 0 失败 / 0 错误**。真实端到端：自动模式「青花瓷」→ 3 个候选（稻香/七里香/兰亭序 被正确过滤）；手动模式「淘金小镇 周杰伦」→ 目标专辑《太阳之子》排第一；下载 500px → 真实 JPEG 107 KB；尺寸 300/800 → 60 KB/203 KB；SSRF 四种非法输入全部拦截；提交后封面转存 `songCovers/`、staging 清理；删歌后两条清理任务均 `SUCCEEDED`；`tb_song` 540 行、`tb_admin` 81 行与验证前一致。 |
