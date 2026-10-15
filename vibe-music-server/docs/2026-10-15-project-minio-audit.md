**项目 bug 与 MinIO 遗留对象检查报告**

检查于北京时间 2026-10-14 夜间开始，2026-10-15 完成。对象核对快照时间为 2026-10-14 23:59:37–23:59:39，之后进行了独立分页复核。

后续更新（2026-10-15 00:13）：用户已授权清理本报告的 10 个候选，现已逐项删除并保留本地恢复副本，桶内剩余 989 个对象，未引用候选/缺失引用均为 0。见 [清理执行记录](D:/java_code/music/vibe-music-server/docs/2026-10-15-minio-cleanup-result.md)；[修复计划书](D:/java_code/music/vibe-music-server/docs/plans/2026-10-15-bugfix-and-storage-cleanup-plan.md)仍待批准，业务代码与数据库结构尚未修改。下文保留清理前的原始检查快照。

结论：当前 MinIO 有 **10 个未找到现行引用的对象，共 15,857,324 字节（15.12 MiB）**。其中 5 个为媒体文件，另外 5 个为 `.idea` 工程配置文件。现有数据库媒体引用均能找到对应对象。同时确认了会持续产生遗留文件的删除/失败补偿缺陷，以及会误删共享封面的高优先级问题。

本次只读检查线上数据，未删除或修改 MySQL、Redis、MinIO 数据，未调用注册、更新、删除等业务写接口。只新增只读审计脚本和本报告；业务代码未修改。已有自动化测试在隔离环境执行。

**MinIO 实际核对结果**

目标为项目配置中的 `http://127.0.0.1:9005`、桶 `vibe-music-data`、MySQL 库 `vibe_music`、Redis database 1。

| 项目 | 结果 |
| --- | ---: |
| 当前存储对象 | 999 |
| 总大小 | 5,436,054,482 字节，约 5.06 GiB |
| 数据库媒体引用 | 1,508 条 |
| 被数据库引用的不同对象 | 989 |
| 未找到现行引用的候选 | 10 |
| 候选大小 | 15,857,324 字节，15.12 MiB |
| 数据库引用但对象不存在 | 0 |
| 被多条记录共享的对象 | 262 |
| 其中被专辑和歌曲跨表共用的封面 | 261 |
| Redis 单曲/专辑上传会话 | 0 |
| Redis 单曲上传清理索引成员 | 0 |
| 当前 staging 对象 | 0 |
| 未完成分片上传 | 0 |
| 桶版本管理 | 未启用 |
| 账号注销清理重试表 | `tb_account_cleanup_task` 不存在 |

核对覆盖 `tb_song.cover_url/audio_url`、`tb_album.cover_url`、`tb_playlist.cover_url`、`tb_artist.avatar`、`tb_user.user_avatar`、`tb_banner.banner_url`，不按前端可见性过滤。停用歌手、私有歌单和草稿专辑均视为有效引用。另检查前后端运行时源码、public 资源以及 Redis 上传会话，未发现候选文件的引用。对候选进一步查 `chat_messages.content`、`ai_chat_state.context_json`、`tb_agent_pending_action.draft_json/preview_json`，也未命中；仓库 SQL 快照未命中这些完整对象名。

独立复核使用每页 100 个对象，实际读取 10 页，确认 999 个对象且无重复；重新查询数据库并使用独立 URL 解析逻辑，结果仍为 10 个候选、0 个缺失。进一步在数据库 64 个文本/JSON 字段中搜索候选对象的 UUID 或 `.idea/` 路径，并扩大运行时源码文件类型覆盖，均无命中。特别处理 MinIO 列表响应将空格编码成 `+` 的情况；数据库 URL 路径内的字面 `+` 没有被当作空格。

**候选文件完整清单**

| 对象 key | 字节数 | 对象最后修改时间（UTC） |
| --- | ---: | --- |
| `.idea/.gitignore` | 190 | 2025-06-01 09:15:50 |
| `.idea/misc.xml` | 276 | 2025-06-01 09:15:50 |
| `.idea/modules.xml` | 289 | 2025-06-01 09:15:50 |
| `.idea/vibe-music-data.iml` | 344 | 2025-06-01 09:15:50 |
| `.idea/workspace.xml` | 2,343 | 2025-06-01 09:15:50 |
| `artists/02d5b3f0-3aa8-473a-b037-a5beef327413-blob` | 58,226 | 2025-09-12 13:21:42 |
| `songCovers/7d5e35eb-ea81-447a-b709-b237549de862-blob` | 1,384,455 | 2025-06-01 09:14:18 |
| `songCovers/8f8a6233-d178-4ae1-aef6-e4d254ad96aa-blob` | 1,643,987 | 2025-06-01 09:14:19 |
| `songs/1cc99b92-1a13-477e-9544-f2e60fa401c4-Aaron Carter - Sooner Or Later.mp3` | 8,617,964 | 2025-06-01 09:13:23 |
| `songs/8bcb006d-b4d2-41a1-92e3-bfd207a0adf6-ELSIE - Real Love (EUNJUNG SOLO).mp3` | 4,149,250 | 2025-06-01 09:13:36 |

`Sooner Or Later` 当前仍有歌曲记录（ID 3），但引用的是另一个对象 `songs/e606cbc7-d919-42d1-90bb-56738b8208cf-Aaron Carter - Sooner Or Later.mp3`。不能按歌曲名批量删除；完整对象 key 才是核对依据。

这些文件可以确认“当前无引用”，但没有历史删除审计日志，不能断言每一个都由某次用户端/管理端删除操作遗留，也可能来自重复上传、上传失败或历史导入。这里的大小不是已回收空间。遵守用户禁止批量删除的要求，本次没有清理对象；需要批量清理时应由用户核对完整 key 后手动操作。

**确认的问题与修复方向**

1. **P1：匿名注册管理员。** [WebConfig.java:30](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/config/WebConfig.java:30) 将 `/admin/register` 排除鉴权，[AdminServiceImpl.java:46](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/AdminServiceImpl.java:46) 只检查重名即插入管理员。匿名调用者可创建管理员并登录管理端。应关闭公开管理员注册，改为受保护的管理员操作或受限初始化流程。证据为完整源码调用链，未实际创建账号。

2. **P1：用户资料更新信任请求体用户 ID，可越权修改他人资料。** [UserServiceImpl.java:228](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/UserServiceImpl.java:228) 通过 `BeanUtils.copyProperties` 复制 `UserDTO.userId` 后直接 `updateById`；从 token 读取的 ID 仅用于重名校验。提交他人 ID 和未占用的资料即可更新目标用户邮箱等字段，并可能结合重置密码接管账号。应强制实体 ID 来自认证上下文，并添加“用户 A 无法修改用户 B”的隔离测试。未对真实用户执行修改。

3. **P1：删除/替换歌曲或专辑会误删共享封面。** [AlbumUploadServiceImpl.java:174](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/AlbumUploadServiceImpl.java:174) 和该文件 206 行将同一封面写入专辑及歌曲；[SongServiceImpl.java:673](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/SongServiceImpl.java:673) 删除歌曲时无条件删封面，[AlbumServiceImpl.java:307](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/AlbumServiceImpl.java:307) 在“仅删专辑、保留歌曲”分支也删封面。更新封面同样没有共享检查。真实库中 `albumCovers/album-cover-7422f386-5297-494a-a861-80c43b5c75bd.jpeg` 被专辑 543 与歌曲 597–620 共 25 条记录引用，风险并非假设。应在数据库提交后检查所有媒体引用，只有引用数为零才清理；默认歌单封面也需参与检查。

4. **P1：文件先删、数据库后改，数据库回滚不能恢复文件。** [SongServiceImpl.java:664](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/SongServiceImpl.java:664) 的删除没有整体事务且先删对象；[AlbumServiceImpl.java:285](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/AlbumServiceImpl.java:285) 在数据库事务内先删文件。第二个文件删除或后续 SQL 失败时，存活/回滚恢复的记录会指向已经删掉的对象。应在事务中记录待清理任务，提交后执行，并支持失败重试；需要覆盖“第二次删除失败”和“数据库操作失败”的测试。

5. **P1：单曲提交成功后的响应组装异常会误删已经入库的媒体。** [SongUploadServiceImpl.java:394](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/SongUploadServiceImpl.java:394) 的小事务先提交，402 行再调用 `buildCommitVO`，其 564 行 `attachArtists` 会查询数据库。一旦该查询失败，403–405 行的补偿会删除已经被持久化歌曲引用的正式封面和音频。另外 355 行 `resolveAlbumId` 在小事务之前创建/更新专辑，歌曲提交失败也无法回滚这部分数据。应将相关写库合并在同一事务，并区分提交前补偿与提交后响应失败。

6. **P1：管理端删用户遗漏头像，所有用户删除路径遗漏个人歌单自定义封面。** [UserServiceImpl.java:645](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/UserServiceImpl.java:645) 及批量删除只删用户行。自助注销 [UserServiceImpl.java:397](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/UserServiceImpl.java:397) 依赖外键删除个人歌单，之后仅清理头像，未收集 `cover_is_custom=true` 的封面。真实库确认 `tb_playlist.owner_user_id` 为 `ON DELETE CASCADE`。应删除前收集头像和自定义封面，在用户删除事务中保存清理任务；不能连带删除歌单默认使用的歌曲封面。

7. **P1：当前数据库缺少注销清理重试表。** 已只读确认 `tb_account_cleanup_task` 不存在，但迁移 [phase10_account_cleanup.sql:16](D:/java_code/music/vibe-music-server/sql/phase10_account_cleanup.sql:16) 已在仓库中。[UserServiceImpl.java:438](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/UserServiceImpl.java:438) 写重试记录失败只记日志，接口返回 `cleanupPending=true` 也不意味着任务已保存；[AccountCleanupRetryTask.java:45](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/task/AccountCleanupRetryTask.java:45) 查询同样会失败。应部署现有迁移并验证重试闭环。本次未执行迁移。

8. **P1：禁用或删除用户未使已有会话失效。** [UserServiceImpl.java:629](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/UserServiceImpl.java:629) 禁用仅更新状态；[LoginInterceptor.java:110](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/interceptor/LoginInterceptor.java:110) 只检查 Redis token、JWT 和角色路径，不检查账号是否存在/是否停用。管理员删除同样不撤销 token，自助注销只撤销当前 token。应检查账号有效性或引入会话版本/用户 token 索引，确保全部会话撤销。禁用前已登录的用户目前可能继续使用原 token 直到过期。

9. **P2：管理端删除有反馈的用户会被外键拒绝，并绕过关联数据清理。** [UserServiceImpl.java:645](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/UserServiceImpl.java:645) 直接删用户；真实库 `tb_feedback.user_id` 的删除规则确认为 `RESTRICT`。用户有反馈时删除会失败，且该路径没有复用自助注销对聊天、指标等无外键数据的清理。应统一管理员删除与自助注销的账户清理服务，分别保留权限校验。

10. **P2：单曲暂存清理失败后仍移除清理索引，失去后续重试入口。** [SongUploadServiceImpl.java:634](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/SongUploadServiceImpl.java:634) 捕获 MinIO 删除异常后仍删除 Redis 会话/索引；429 行定时清理只从索引读取任务。因此一次清理失败就可能长期遗留 staging 文件。应在清理成功后移除索引，或保存持久化清理任务。

11. **P2：歌单封面清理失败只有日志，无可靠重试。** 用户端 [PersonalPlaylistService.java:205](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/PersonalPlaylistService.java:205) 和管理端 [PlaylistServiceImpl.java:338](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/PlaylistServiceImpl.java:338) 提交后删除自定义封面，异常仅记录日志。歌单/旧 URL 已消失，后续任务没有可重试引用。应把待清理对象和业务事务一起持久化。

12. **P2：旧专辑上传会话无过期时间，可阻止暂存目录清理。** [AlbumUploadServiceImpl.java:118](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/AlbumUploadServiceImpl.java:118) 写静态 `SESSIONS` 后没有 TTL，425 行只要内存包含该 ID 就跳过清理，498 行还在 Redis 过期后回退读取内存。旧 prepare 端点仍存在；进程长期运行且上传后放弃确认时可持续保留暂存文件。应统一采用有 TTL 的会话存储。

13. **P2：先上传正式文件再验证业务结果，失败没有补偿。** 例如 [AdminController.java:437](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/controller/AdminController.java:437) 先上传专辑封面，再调用业务更新；[AlbumServiceImpl.java:248](D:/java_code/music/vibe-music-server/src/main/java/cn/edu/seig/vibemusic/service/impl/AlbumServiceImpl.java:248) 在专辑不存在时返回错误，刚上传的对象仍存在。头像、歌曲媒体等入口也有同类顺序。应先完成可做的授权/存在性校验，再通过 staging 或可靠补偿处理后续写库失败。

14. **P2：用户端类型检查失败。** 5 个诊断来自 [build/plugins.ts:8](D:/java_code/music/vibe-music-client/build/plugins.ts:8)、[ResetPasswordForm.vue:48](D:/java_code/music/vibe-music-client/src/components/Auth/ResetPasswordForm.vue:48)、[pages/album/index.vue:24](D:/java_code/music/vibe-music-client/src/pages/album/index.vue:24) 的未使用变量/参数，以及 [themeTools.ts:48](D:/java_code/music/vibe-music-client/src/utils/themeTools.ts:48)、该文件 65 行将 `void | number[]` 赋给 `number[]`。前者应去掉未使用变量或按项目规则声明占位参数；后者应收紧 `hexToRgb` 返回契约或显式检查失败结果。

建议修复顺序为：先关闭管理员注册和用户越权入口；再处理共享封面、事务边界与用户删除的清理任务；随后部署重试表、补齐失败重试和登录态失效处理；最后处理类型检查问题。文件清理应以修复后的引用核查为前提，避免清完后继续新增遗留或误删共享媒体。

**验证记录**

| 检查 | 结果 |
| --- | --- |
| 后端 Maven verify | 281 个用例：280 通过、1 跳过、0 失败、0 错误 |
| 用户端现有 Node 测试 | 58/58 通过 |
| 用户端 vue-tsc | 失败，5 个诊断，见上文 |
| 管理端 tsc + vue-tsc | 均通过 |
| 管理端生产构建 | 通过 |
| 用户端生产构建 | 本次未执行；插件会更新源码中的自动生成文件 |

后端执行 `mvn verify '-Dtest=*,!RedisConnectionTest,!VibeMusicServerApplicationTests' '-DfailIfNoTests=true'`，排除了加载真实应用、可能写入真实 Redis 或启动清理任务的两类测试。跳过用例为默认关闭的 `LyricsMatchScoreProbe`。这些测试通过不代表覆盖了以上源码审查发现；没有对真实数据进行破坏性复现。

管理端直接调用 Vite 并设置 `--emptyOutDir false`、独立输出目录及 `VITE_COMPRESSION=none`，绕过原 build 脚本的 `rimraf`，避免清空已有目录或让压缩插件删除文件。非阻断警告包括后端重复声明 `spring-boot-starter-web`、管理端 Browserslist 数据陈旧。

详细日志：[验证目录](D:/java_code/music/out/audit-2026-10-14)。对象清单、引用明细和共享关系：[minio-audit.json](D:/java_code/music/out/audit-2026-10-14/minio-audit.json)。外键与候选历史引用补充核查：[supplementary-readonly-checks.json](D:/java_code/music/out/audit-2026-10-14/supplementary-readonly-checks.json)。

**复核方式与范围**

保留了标准库实现的 [audit_minio_readonly.py](D:/java_code/music/vibe-music-server/tools/audit_minio_readonly.py)。脚本读取本地配置及环境变量，通过 MySQL 只读事务、Redis 只读命令、MinIO GET 列表核对，不包含删除模式，也不下载媒体内容。需要 Python 3 和 PATH 中的 `mysql` 客户端。在项目目录可运行：

```powershell
python vibe-music-server/tools/audit_minio_readonly.py --output out/minio-audit-recheck
```

MySQL、Redis、MinIO 之间不是全局原子快照，因此实际清理前必须重新核查引用。脚本不能读取服务进程内存中的旧专辑会话；本次桶内没有 staging 对象，未影响上述 10 个候选结论。对象存在性不等于已经验证所有媒体内容可播放，未下载或逐首解码音频。本检查覆盖关键权限、上传删除链路及现有构建/测试，不是所有页面的完整端到端验收。
