**C 阶段详细方案：媒体清理基础**

日期：2026-10-15。状态：**C1 已实施完成并验收通过**（迁移已部署、代码已落地、测试与端到端验收通过）。C2 未开始。
依据：[项目检查报告](../2026-10-15-project-minio-audit.md) 问题 3、4、7、11；[修复计划书](./2026-10-15-bugfix-and-storage-cleanup-plan.md) C 阶段。
前序：A 阶段已完成（`./2026-10-15-phaseA-permission-hardening.md`）。

范围声明：本阶段**只做媒体清理的可靠性**。不涉及 JWT/鉴权（已放弃）、不涉及账号删除的入口统一（D 阶段）、不涉及上传会话与暂存清理（E 阶段）。不新增"扫描全桶后自动删除"的能力，不做全库 URL 重写，不改动 `tb_*.url` 字段结构。

> **实施结果见 §八**（含两处对 §2.4 设计的重要修正，原因是用真实数据实测后才暴露的）。

---

## 一、现状证据

### 1.1 引用现状（只读实测）

| 引用列 | 总行数 | 非空 | 类型 |
| --- | ---: | ---: | --- |
| `tb_song.cover_url` | 540 | 540 | varchar(255) |
| `tb_song.audio_url` | 540 | 540 | varchar(255) |
| `tb_album.cover_url` | 265 | 265 | varchar(255) |
| `tb_playlist.cover_url` | 20 | 20 | varchar(255) |
| `tb_artist.avatar` | 129 | 129 | varchar(255) |
| `tb_user.user_avatar` | 65 | 3 | varchar(255) |
| `tb_banner.banner_url` | 11 | 11 | varchar(255) |

实测最长 URL 为 227 字符。`information_schema` 确认：**`tb_media_object`、`tb_media_cleanup_task`、`tb_account_cleanup_task` 三张表在当前库中都不存在**（问题 7 成立）。

### 1.2 问题 3：删除/替换会误删共享封面

共享是**设计使然**，不是脏数据：

- `AlbumUploadServiceImpl:206` 把同一个 `coverUrl` 同时写入专辑与专辑内每一首歌（`:174` 新建、`:178` 更新），所以一张专辑封面天然被 1 + N 条记录引用。
- `SongServiceImpl:672-677` 删歌曲时**无条件**删封面和音频。
- `AlbumServiceImpl:306-308`「只删专辑、保留歌曲」分支也删专辑封面，而歌曲仍指向它。
- `AlbumServiceImpl:329-333` 批量删专辑同样无条件删。
- 封面替换路径（`updateSongCover`、`updateAlbumCover` 等）也没有共享检查。

报告中提到的真实样本 `albumCovers/album-cover-7422f386-...jpeg` 被专辑 543 与歌曲 597–620 共 25 条记录引用——删掉其中任何一首歌都会让剩下 24 条记录的封面失效。

### 1.3 问题 4：先删文件、后改数据库

- `SongServiceImpl.deleteSong`（`:664`）**没有事务**，且 `:672-677` 先删对象、`:679` 才删记录。
- `AlbumServiceImpl.deleteAlbum`（`:273`）有 `@Transactional`，但 `:285-292` 在事务内先删文件——一旦后续 SQL 失败回滚，记录还在、文件已经没了。

MinIO 的 S3 DELETE 是幂等的，删错/删不存在的对象都不报错，所以这类损坏是**静默**的。

### 1.4 问题 11：歌单封面清理失败无重试

- 用户端 `PersonalPlaylistService:195-209`：`afterCommit` 里 `try/catch` 后只打一条 warn。
- 管理端 `PlaylistServiceImpl.deleteCoverAfterCommit`（`:335-343`）：同样是 `afterCommit` + 只记日志。

两处都在事务提交后才删，方向正确；缺的是**失败后的持久化重试**——歌单行和旧 URL 此时都已消失，没有任何地方还能找回这个待清理对象。

### 1.5 关键实现事实（决定设计细节）

1. `MinioServiceImpl.publicUrlInternal`（`:298-301`）= `endpoint + "/" + bucket + "/" + objectName`，**不做 URL 编码**。因此数据库里存的是原始对象名（中文、空格、字面 `+` 都原样保留）。
2. `MinioServiceImpl.resolveObjectName`（`:150-179`）只丢弃 `scheme://host:port` 与查询串，再按 `/{bucket}/` 定位，**刻意不做百分号解码**（否则数据库里的字面 `+` 会被误解成空格）。这个函数是"URL → 对象名"的唯一正确实现，新代码必须复用它。
3. 现有重试范式可直接沿用：`AccountCleanupRetryTask` + `AccountCleanupTaskMapper`（`@Scheduled` 定时扫描 + `attempts` 上限 + 三态落库）。但它是**按账号**的、唯一键是 `(user_id, resource_type)`，且只有 `REDIS_TOKEN` / `MINIO_AVATAR` 两种类型——**不能**直接改造成"一个用户多个封面"或"通用媒体清理"的队列（这正是计划书里指出的点）。
4. 业务实体删除已有级联：`tb_comment` / `tb_genre` / `tb_playlist_binding` / `tb_user_favorite` 对 `tb_song` 是 `ON DELETE CASCADE`；`tb_playlist.owner_user_id` 对 `tb_user` 是 `ON DELETE CASCADE`。

---

## 二、设计

### 2.1 核心原则

把"业务删除 → 立即删对象"改成三段：

```
① 业务事务内：变更业务数据 + 写入待清理任务（同事务，可回滚）
② 事务提交后：只唤醒 worker（不承担可靠性）
③ worker：核对全部引用 → 无引用才删 → 失败可重试
```

**可靠性只来自数据库**，不来自 `afterCommit` 回调或进程内存。

### 2.2 对象身份

- 身份 = `(bucket, object_key)`，`bucket` 取 `minio.bucket` 配置。
- `object_key` 通过 `resolveObjectName(url)` 得到，**绝不用完整域名或歌曲名做身份**。
- ⚠️ **`object_key` 列必须用 `utf8mb4_bin`（大小写敏感）排序规则**。MySQL 默认的 `utf8mb4_0900_ai_ci` 大小写不敏感，会把 `A.jpg` 与 `a.jpg` 判为同一对象——而 S3/MinIO 的 key 是大小写敏感的。用默认排序规则会导致"另一个对象被当成我的引用"从而误删或漏删。
- 唯一索引用 `key_digest = SHA-256(object_key)` 的十六进制（64 字符）而非 `object_key` 本身：规避排序规则与长度差异，同时**查询命中后必须再校验完整 `object_key`**，防止摘要碰撞导致误判。

### 2.3 新增表

**`tb_media_cleanup_task`** —— 逐对象清理任务（本阶段实施）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT AUTO_INCREMENT | 主键 |
| `bucket` | VARCHAR(64) | 桶名 |
| `object_key` | VARCHAR(255) COLLATE utf8mb4_bin | 完整对象名 |
| `key_digest` | CHAR(64) | SHA-256(object_key) |
| `source_type` | VARCHAR(32) | 来源业务：`SONG_DELETE` / `ALBUM_DELETE` / `SONG_COVER_REPLACE` / `SONG_AUDIO_REPLACE` / `ALBUM_COVER_REPLACE` / `PLAYLIST_COVER` / `UPLOAD_ABORT` … |
| `source_ref` | VARCHAR(64) | 来源实体 id 或上传会话 id |
| `reason` | VARCHAR(64) | 可读原因，便于人工排查 |
| `status` | VARCHAR(16) | `PENDING` / `RUNNING` / `SUCCEEDED` / `RETRY_WAIT` / `FAILED` / `RETAINED` |
| `attempts` | INT | 已尝试次数 |
| `next_run_at` | DATETIME(6) | 下次可执行时间（退避用） |
| `lease_owner` | VARCHAR(64) | 执行租约持有者 |
| `lease_expires_at` | DATETIME(6) | 租约到期时间 |
| `generation` | BIGINT | 执行代次，防止旧执行者覆盖新结果 |
| `last_error` | VARCHAR(500) | 最近失败原因（脱敏） |
| `created_at` / `updated_at` | DATETIME(6) | |

约束：`UNIQUE uk_media_cleanup (source_type, source_ref, key_digest)`（同一业务操作重复执行不重复入队）、`KEY idx_media_cleanup_due (status, next_run_at, id)`。**不设外键**（业务行可能已删除）。

**`tb_media_object`** —— 媒体对象登记（本阶段**只出设计，不实施**，见 §2.6）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT AUTO_INCREMENT | |
| `bucket` | VARCHAR(64) | |
| `object_key` | VARCHAR(255) COLLATE utf8mb4_bin | |
| `key_digest` | CHAR(64) | |
| `status` | VARCHAR(16) | `ACTIVE` / `DELETING` / `DELETED` |
| `upload_session_id` | VARCHAR(64) NULL | 来源上传会话 |
| `created_at` / `updated_at` | DATETIME(6) | |

约束：`UNIQUE uk_media_object (bucket, key_digest)`。

### 2.4 统一引用核对服务

`MediaReferenceService.countReferences(objectKey)` 在**一条**查询里汇总 7 类引用（用 `UNION ALL` 求和），语义上等价于：

| # | 引用 | 条件 |
| --- | --- | --- |
| 1 | `tb_song.cover_url` | 相等 |
| 2 | `tb_song.audio_url` | 相等 |
| 3 | `tb_album.cover_url` | 相等 |
| 4 | `tb_playlist.cover_url` | 相等 |
| 5 | `tb_artist.avatar` | 相等 |
| 6 | `tb_user.user_avatar` | 相等 |
| 7 | `tb_banner.banner_url` | 相等 |

三条硬规则：

- **不过滤可见性**：停用歌手、草稿专辑（`status='DRAFT'`）、私有歌单（`is_public=FALSE`）都算有效引用。
- **不看 `cover_is_custom` 标志**：歌单默认封面是歌曲的共享图，它本来就在 `tb_playlist.cover_url` 里，天然被计入引用计数。是否可删**只由引用计数决定**，不由标志决定。
- 比较用完整 URL 字符串相等（因为 `publicUrlInternal` 不做编码，同一对象在不同记录里的 URL 串是一致的）。

### 2.5 任务生命周期

```
enqueue (PENDING) ──claim──> RUNNING ──核对──┬─ 无引用 ─> 删对象 ─┬─ 成功 ─> SUCCEEDED
                                              │                    └─ 异常 ─> RETRY_WAIT(退避) ─> 超上限 ─> FAILED
                                              └─ 有引用 ─> RETAINED（终态，等最后一次引用解除时由新的业务操作再入队）
```

- `RUNNING` 带租约 + 代次；租约到期（默认 60s，与既有 AI 待确认任务的取值一致）可被重新领取；**旧代次不能覆盖新代次的结果**。
- 对象不存在**视为删除成功**（S3 DELETE 幂等），超时后重复执行同样幂等。
- 退避：`next_run_at = now + min(2^attempts 分钟, 30 分钟)`；上限 10 次（沿用 `AccountCleanupRetryTask.MAX_ATTEMPTS` 的取值）。
- **每次删除只用一个完整对象 key**，不使用前缀、通配符或多对象删除 API。
- **网络调用期间不持有长事务**：领取、标记状态各用短事务，MinIO 调用在事务外。

### 2.6 ⚠️ 本阶段解决不了的残余竞态（需要你决定是否一并做）

上面这套设计满足计划书 C 阶段的三条完成条件（回滚不删文件、共享封面不删、进程重启可恢复），但**存在一个窄窗口的竞态**：

> worker 在 T1 时刻核对"引用数 = 0"，在 T2 时刻执行删除。若在 T1–T2 之间另一个请求把该对象引用给了新记录，就会产生"存活引用指向已删除对象"。

彻底消除它需要计划书里的**媒体登记 + 锁协议**：所有"新增/替换引用"的入口与 worker 都锁定同一条 `tb_media_object` 行，worker 在锁内重新核对引用，确认无引用才标记 `DELETING` 并提交，绑定方不得引用 `DELETING/DELETED` 对象。

代价：需要改动**每一个**写入媒体引用的入口（歌曲新增/更新、专辑新增/更新、歌单封面、歌手头像、用户头像、Banner、上传提交），并新增登记行维护逻辑。改动面明显大于本阶段其余部分。

**我的建议**：拆成 C1 / C2 两步。

- **C1（本方案）**：清理任务管线 + 统一引用核对 + 事务边界修正 + 部署 `phase10` 迁移。覆盖检查项 3、4、7、11，把"误删共享封面"和"先删文件后改库"这两个已确认会造成实际损坏的问题解决掉。
- **C2（紧随其后，单独一轮）**：`tb_media_object` 登记表 + 锁协议，消除上面那个窗口。

理由：C1 的收益是确定的、改动面可控、可独立验收；C2 需要触及全部写入路径，适合在 C1 的任务管线跑通、有回归保护之后再做。**若你希望一次做完，我可以把两张表和锁协议都纳入本轮，但请预期改动面会大很多。**

---

## 三、改动清单

### 3.1 新增

| 文件 | 内容 |
| --- | --- |
| `sql/phaseC_media_cleanup.sql` | 幂等迁移：建 `tb_media_cleanup_task`（C1）；`tb_media_object` 的 DDL 以注释形式附在文件末尾备 C2 使用 |
| `model/entity/MediaCleanupTask.java` | 实体 |
| `mapper/MediaCleanupTaskMapper.java` | `enqueue`（`ON DUPLICATE KEY UPDATE` 幂等）、`claimDue`（带租约的条件更新）、`markRunning/ markSucceeded / markRetryWait / markExhausted / markRetained` |
| `service/MediaReferenceService.java` | `countReferences(bucket, objectKey)`，7 类引用汇总 |
| `service/MediaCleanupService.java` | `enqueueAfterCommit(...)`（登记 + 注册 `afterCommit` 唤醒）、`runDueTasks()`（worker 主体，供定时任务调用） |
| `task/MediaCleanupRetryTask.java` | `@Scheduled` 包装，只负责调用 worker 并记日志 |
| 测试 4 个类 | 见 §四 |

### 3.2 改造（把"立即删"换成"事务内登记"）

| 文件:位置 | 现状 | 改法 |
| --- | --- | --- |
| `SongServiceImpl.deleteSong` `:664-684` | 无事务、先删对象后删记录 | 加 `@Transactional`；删除记录后对封面与音频各 `enqueue`；移除 `minioService.deleteFile` |
| `SongServiceImpl.deleteSongs` `:692+` | 同上 | 同上（逐首入队） |
| `SongServiceImpl.updateSongCover` `:617` / `updateSongAudio` `:641` | 替换时直接删旧对象 | 旧对象改为 `enqueue`（`SONG_COVER_REPLACE` / `SONG_AUDIO_REPLACE`） |
| `AlbumServiceImpl.deleteAlbum` `:273-310` | 事务内先删文件 | 两个分支都改为：删记录后对专辑封面 `enqueue`；`deleteSongs=true` 时逐首 `enqueue` 音频与封面 |
| `AlbumServiceImpl.deleteAlbums` `:314-335` | 无条件删封面 | 同上 |
| `AlbumServiceImpl.updateAlbumCover` `:246` | 直接删旧对象 | 改为 `enqueue` |
| `PersonalPlaylistService:195-209` | `afterCommit` + 只记日志 | 改为事务内 `enqueue`，删掉 `safeDelete` |
| `PlaylistServiceImpl.deleteCoverAfterCommit` `:335-343` | 同上 | 改为事务内 `enqueue`（保留"默认封面不入队"的判断：`cover_is_custom=false` 时不入队） |

**本轮不接入**（留给 D / E 阶段，理由见 §六）：`UserServiceImpl.updateUserAvatar`、`ArtistServiceImpl.updateArtistAvatar`、`BannerServiceImpl.updateBanner`、`SongUploadServiceImpl` / `AlbumUploadServiceImpl` 的暂存与提交补偿、账号删除路径。

### 3.3 部署既有迁移

执行 `sql/phase10_account_cleanup.sql`（幂等），使 `tb_account_cleanup_task` 存在，修复问题 7 的"重试表缺失"。

---

## 四、测试计划

沿用仓库约定：纯 JUnit 5 + Mockito、`ReflectionTestUtils` 注入、`ThreadLocalUtil` 造身份、H2 可离线跑；涉及 SQL 语义的部分用 H2 的 MySQL 兼容模式，**但 `utf8mb4_bin` 排序规则与 `ON DUPLICATE KEY UPDATE` 必须在隔离 MySQL 中另验一次**（H2 测不出排序规则差异）。

**`MediaCleanupTaskTest`（新增，约 10 例）**

1. 同一 `(source_type, source_ref, key_digest)` 重复入队只产生一条任务。
2. 引用数 > 0 → 任务终态 `RETAINED`，且**不调用** MinIO 删除。
3. 引用数 = 0 → 删除成功 → `SUCCEEDED`，删除调用只传一个完整 key。
4. MinIO 抛异常 → `RETRY_WAIT`、`attempts + 1`、`next_run_at` 推后。
5. 达到上限 → `FAILED`，不再被领取。
6. 对象不存在（`deleteObject` 正常返回）→ 视为成功。
7. 租约未到期时任务不被重复领取。
8. 租约过期后可被重新领取，且旧代次的回写被拒绝。
9. 领取后进程"崩溃"（不回写）→ 租约到期后任务仍可恢复执行。
10. 每次删除只调用单对象删除接口（`verify(minioService, never()).deleteObjectsByPrefix(any())`）。

**`MediaReferenceCheckTest`（新增，约 4 例）**

1. 七类引用各自单独存在时都能被检出。
2. 停用歌手 / 草稿专辑 / 私有歌单的引用同样计入。
3. `cover_is_custom=false` 的默认歌单封面计入引用。
4. 引用计数为 0 仅当七类都不命中。

**`SharedCoverDeletionTest`（新增，约 5 例）**

1. 专辑 + 3 首歌共享封面：删 1 首歌 → 封面任务 `RETAINED`，歌曲与音频任务正常清理。
2. 删最后一条引用 → 入队且提交后执行删除。
3. 只删专辑、保留歌曲 → 专辑封面 `RETAINED`（歌曲仍引用）。
4. 业务事务回滚 → 任务不落库、MinIO 不被调用。
5. 任务写入失败 → 业务事务一并回滚。

**`PlaylistCoverCleanupTest`（新增，约 3 例）**

1. 替换自定义封面 → 旧对象入队。
2. 删除自定义封面 → 入队。
3. 默认封面（`cover_is_custom=false`）→ 不入队。

---

## 五、验收用例（对应计划书 C 阶段）

| 场景 | 必须满足 |
| --- | --- |
| 专辑和两首歌曲共享封面，删其中一首 | 共享对象保留，其他歌曲与专辑封面可用 |
| 只删专辑、保留歌曲 | 歌曲与音频保留，共享封面保留 |
| 最后一个引用解除 | 提交后生成并执行单对象清理，文件最终不存在 |
| 数据库操作或任务写入失败 | 业务事务回滚，MinIO 不执行删除 |
| MinIO 超时/临时不可用 | 业务提交不伪装回滚，任务保留；恢复后重试成功 |
| 进程在领取/删除/回写状态时崩溃 | 租约恢复；重复执行安全；旧执行者不能覆盖新结果 |
| 对象不存在 | 视为删除成功，重复执行幂等 |

迁移验证方式：先在**隔离库**（同实例新建 `vibe_music_phaseC_verify`，验证后 DROP）跑两遍迁移确认幂等，再在你的真实库执行。

---

## 六、明确不做

1. 不新增"扫描全桶后自动删除"的能力，不扩大到未经核实的历史文件。
2. 不做全库 URL 重写，不把 `tb_*.url` 换成媒体外键。
3. 不改上传流程的事务边界与暂存清理（问题 5、10、12、13 → E 阶段）。
4. 不改账号删除的入口与清理范围（问题 6、9 → D 阶段）。
5. 不动头像 / Banner 的替换清理（可与 C2 一起做，或并入 E 阶段）。
6. 不碰 JWT / 鉴权 / 密码哈希。

---

## 七、待你确认的四个决定

1. **是否接受 C1 / C2 拆分**（本轮只做清理任务管线，`tb_media_object` 与锁协议留下一轮）？还是要求本轮一次做完？
2. **改造范围**：本轮按 §3.2 的 8 处接入（歌曲、专辑、歌单封面），头像 / Banner / 上传补偿留给后续——可以吗？
3. **迁移执行**：允许我建隔离库验证幂等后，直接在 `vibe_music` 真实库执行 `phase10` 与新的 `phaseC` 迁移吗？（`phase10` 只是建表，幂等，不涉及数据变更）
4. **任务上限与退避**：沿用 `attempts` 上限 10 次、租约 60s、退避上限 30 分钟，可以吗？

---

## 八、实施结果（C1）

### 8.1 用户决策（2026-10-15）

四项决定全部按方案推荐执行：接受 C1/C2 拆分；按 §3.2 的 8 处接入；允许隔离库验证后直接对真实库执行迁移；沿用 10 次上限 / 60s 租约 / 30 分钟退避。

### 8.2 ⚠️ 对 §2.4 的重要修正：引用核对改用"对象键后缀相等"

§2.4 原设计写的是"比较用完整 URL 字符串相等"。**实测数据否定了这个做法**：

```
tb_song.cover_url      http://localhost:9005   499 行
tb_song.cover_url      http://127.0.0.1:9005    41 行
tb_song.audio_url      http://localhost:9005   499 行
tb_song.audio_url      http://127.0.0.1:9005    41 行
tb_album.cover_url     两种主机名并存（261 / 4）
tb_playlist / tb_artist / tb_banner 同样并存
```

同一列里两种主机名并存，而 `minio.endpoint` 只能配一个。反证实验：

```
stored_url                       = http://localhost:9005/vibe-music-data/songs/<key>
url_rebuilt_from_current_endpoint = http://127.0.0.1:9005/vibe-music-data/songs/<key>
url_equality_matches              = 0     ← 完整 URL 相等：判定"无人引用"
suffix_predicate_matches          = 1     ← 对象键后缀相等：判定"有引用"
```

也就是说，**按原设计会得出"引用数 = 0"从而删掉仍被引用的对象**。改为：

```sql
RIGHT(列, CHAR_LENGTH(#{objectKey})) = #{objectKey}
```

即"URL 以该对象键结尾"。不用 `LIKE` 的原因：对象键实测含空格（499 首歌曲的音频文件名带空格）与字面 `+`（如 `34+35.mp3`），且 `_` 是 LIKE 通配符，需要转义才正确。

**已知边界（刻意接受）**：若另一目录下恰好存在以本键为后缀的对象（如 `other/<key>`），也会被计入引用。方向是**多算 → 漏删**（宁可文件多留一会儿，也不能删掉别人还在用的文件）；且键含 UUID，真实数据里几乎不可能出现后缀撞车。空键防护放在 `MediaReferenceService`，mapper 层不重复（空串在 `RIGHT(x,0)` 下反而全列匹配——这一点由测试发现并写进 mapper 注释）。

未加 `COLLATE utf8mb4_bin`：用列自身的排序规则（大小写不敏感）只会多算引用，不会少算；而"两个对象仅大小写不同"在生成键上不可能发生（键 = `目录/UUID-原文件名`，UUID 已保证唯一，差异必然出现在 UUID 段）。

### 8.3 修正：新登记任务立刻到期（`next_run_at = now - 1s`）

`enqueue` 原设计让 `next_run_at = now`，与领取条件的 `now` 比较。当"登记那一刻"与"领取那一刻"落在同一微秒内时，`DATETIME(6)` / `TIMESTAMP(6)` 的精度舍入会让存储值略大于领取时刻，于是**提交后立即唤醒白跑一趟**，要等下一轮定时任务（30s）。

改为 `enqueue` 时传 `now.minusSeconds(1)`（`now` 仍用于 `created_at/updated_at`）：语义是"新任务现在就该执行"，不改变任何退避语义（退避由 `markRetryWait` 写未来时间）。这个不确定性由全量回归时的偶发失败暴露出来，属真实缺陷而非测试问题。

### 8.4 关键实测数据（决定设计取舍）

| 事实 | 数值 | 意义 |
| --- | ---: | --- |
| 被 ≥2 行引用的对象数 | **262** | "误删共享封面"不是理论风险，是普遍存在的隐患 |
| 单对象最多被引用行数 | **15** | 删一首歌会连带毁掉 14 行的封面 |
| `tb_song.cover_url` 行数 / 不同 URL 数 | 540 / 287 | 共享是设计使然（专辑封面即歌曲封面） |
| 跨表共享（song ∩ album 的 cover_url） | **261** | 跨表共享才是主形态，不是同表重复 |
| 对象键含空格的行数 | 499（audio_url） | 决定不能用 LIKE 匹配 |
| URL 中百分号编码 | 0 | `resolveObjectName` 不做解码是安全的 |
| 不含 `/vibe-music-data/` 的 URL | 0 | 但引用核对仍不依赖该片段（保持主机名无关） |

### 8.5 迁移与验证

- `sql/phaseC_media_cleanup.sql`（新增，幂等）建 `tb_media_cleanup_task`；`tb_media_object` 的 DDL 以注释形式附在文件末尾备 C2 使用。
- 隔离库验证：新建 `vibe_music_phaseC_verify`，`phase10` + `phaseC` **各跑两遍**，无错误；确认 `object_key` 排序规则为 `utf8mb4_bin`（表默认是 `utf8mb4_0900_ai_ci`，必须显式覆盖）；功能验证 `cover/A.jpg` 与 `cover/a.jpg` 能共存、唯一键拦住重复入队。验证后 `DROP` 隔离库。
- 真实库：`phase10` + `phaseC` 均已执行，两表存在且为空。

### 8.6 改动清单

**新增（8 个文件）**

| 文件 | 内容 |
| --- | --- |
| `sql/phaseC_media_cleanup.sql` | 幂等迁移；含 `tb_media_object` 注释版 DDL |
| `constant/MediaCleanupSourceType.java` | `source_type` 取值域 |
| `model/entity/MediaCleanupTask.java` | 实体 + 状态常量 |
| `mapper/MediaCleanupTaskMapper.java` | `enqueue` / `findDueIds` / `claim` / `mark*` |
| `mapper/MediaReferenceMapper.java` | 7 类引用汇总（一条 SQL） |
| `service/MediaReferenceService.java` | 引用核对唯一入口（含空键防护） |
| `service/MediaCleanupService.java` | 登记 + 提交后唤醒 + worker |
| `task/MediaCleanupRetryTask.java` | 30s 定时兜底（非可靠性来源） |

**修改（7 个文件）**

| 文件 | 改动 |
| --- | --- |
| `service/MinioService(.java/Impl)` | 新增公开 `toObjectName(url)`（原 `resolveObjectName` 为私有） |
| `service/impl/SongServiceImpl.java` | `deleteSong`/`deleteSongs` 加 `@Transactional`；4 处"立即删"改"事务内登记"；移除已无用的 `MinioService` 依赖 |
| `service/impl/AlbumServiceImpl.java` | `updateAlbumCover`/`deleteAlbum`/`deleteAlbums` 3 处改登记；移除 `MinioService` 依赖 |
| `service/PersonalPlaylistService.java` | 删掉 `afterCommit + safeDelete`，改事务内登记；移除 `TransactionSynchronization*` 依赖 |
| `service/impl/PlaylistServiceImpl.java` | 同上；`deleteCoverAfterCommit` → `enqueueCoverCleanup` |
| 测试装配 ×2 | `AbstractAgentTest` / `PlaylistBusinessTest` 补 `MediaCleanupService` 依赖 |

未接入（留给 D/E 阶段，与 §六 一致）：`UserServiceImpl` 头像、`ArtistServiceImpl` 头像、`BannerServiceImpl`、上传暂存与提交补偿、账号删除路径。

### 8.7 验收结果

| 项 | 结果 |
| --- | --- |
| 新增测试 | **35 例全通过**（`MediaCleanupTaskTest` 12 / `MediaReferenceCheckTest` 7 / `SharedCoverDeletionTest` 5 / `PlaylistCoverCleanupTest` 3 / `MediaCleanupSqlConcurrencyTest` 8） |
| 全量回归 | **323 tests / 0 failures / 0 errors / 1 skipped**（既有 skip），连续两次一致 |
| 端到端 | **16/16 通过**（真实 MySQL + Redis + MinIO + 后端 8080，`scripts/phaseC-e2e.sh`） |
| 迁移幂等 | 隔离库连跑两遍无错；真实库已部署 |
| 临时数据 | E2E 用的管理员 / 专辑 / 歌曲 / 对象 / 任务行全部清理，库与桶均无残留 |

E2E 覆盖的两个核心场景：

1. **专辑 + 两首歌共享封面，删其中一首** → 封面任务终态 `RETAINED`、封面对象仍在 MinIO；该曲音频任务 `SUCCEEDED`、对象已删除；另一首的音频未受影响。
2. **删专辑并连带删歌（最后一个引用解除）** → 封面任务出现 `SUCCEEDED`、封面对象已从 MinIO 删除；音频同样清理。

对应的单测还覆盖了：业务事务回滚时任务一并回滚、任务写入失败时业务回滚、租约未到期不可抢占、租约过期可恢复、旧代次回写被拒（`generation` 守卫）、对象不存在视为成功、删除粒度只能是单对象（禁止前缀/多对象删除）。

### 8.8 已知边界与遗留

1. **窄窗口竞态仍在**（§2.6）：worker 在 T1 核对引用为 0、T2 执行删除，其间若有新请求引用该对象，会出现"存活引用指向已删除对象"。彻底消除需要 C2 的 `tb_media_object` + 锁协议。
2. **`RETAINED` 是终态且刻意不复活**：同一对象被多个来源登记时，早先那条会停在 `RETAINED`，只有新的业务操作登记的新任务才会执行删除。这是设计意图（状态机只由 worker 推进），E2E 的 `RETAINED,SUCCEEDED` 组合即此形态。
3. **`enqueue` 在事务外调用会记 warn 并继续**：任务独立提交，业务回滚后留下"多余但安全"的任务（worker 核对引用后判 `RETAINED`）。这是 fail-safe 降级，不是可接受的常态。
4. **C1 未解决**：头像 / Banner 的替换清理、上传暂存与提交补偿（问题 5、10、12、13）、账号删除的入口统一（问题 6、9）。
5. **与本阶段无关的既有风险**（未处理）：`tb_admin` 密码仍是无盐 MD5，80 个种子账号共用同一哈希。

### 8.9 部署注意

1. 生产必须先执行 `sql/phase10_account_cleanup.sql` 与 `sql/phaseC_media_cleanup.sql`（均幂等）。`phase10` 是修复问题 7"重试表缺失"的前置。
2. 定时任务默认 60s 首延迟 / 30s 间隔，可用 `media-cleanup.retry-initial-delay-ms`、`media-cleanup.retry-interval-ms` 覆盖。
3. 部署后建议观察 `tb_media_cleanup_task` 的 `FAILED` 与长期 `RETRY_WAIT` 行；`FAILED` 表示达到 10 次上限需人工介入。
