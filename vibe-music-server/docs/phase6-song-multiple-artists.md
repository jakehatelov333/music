# 歌曲多歌手合作演唱（phase 6 设计稿）

> **状态：方案讨论中，未实施。** 本文档不包含任何代码改动，也未执行任何 DDL/DML。
> 标注约定：**[事实]** 已从代码或数据库核实（附文件/行号或 SQL 结果）；**[建议]** 设计主张；
> **[待核实]** 无法从当前代码与本地库确认，需实测或业务确认。

---

## 0. 决策记录

| # | 议题 | 决策 |
|---|---|---|
| 1 | 数据模型 | **C2**：`tb_song_artist` 为权威关系表 + `tb_song.artist_names` 冗余展示串（` / ` 拼接）。`artist_id` 暂留，语义为「主艺人」 |
| 2 | 分隔符 | 统一 ` / `；列表单行省略，tooltip 展示完整 |
| 3 | 结构化字段 | 保留 `artistName`，**新增** `artists: [{artistId, artistName}]`（按 `display_order`） |
| 4 | ID 144 | **软删除**，且必须先完成引用迁移再停用 |
| 5 | Aki阿杰 | **新建**独立歌手记录 |
| 6 | 上传元数据含分隔符 | **有条件的自动拆分**：完整匹配优先 → 识别分隔符 → 全部唯一命中才自动拆 → 否则人工确认 |
| 7 | 歌词检索歌手参数 | 优先主艺人名，带回退（次数受限） |
| 8 | 合作专辑 | `tb_album.artist_id` **继续用主艺人**，本轮不改专辑为多艺人 |
| 9 | role 字段 | **不预留**，只做演唱关系 |

---

## 1. 现状（已核实）

### 1.1 数据模型

| 事实 | 依据 |
|---|---|
| `tb_song.artist_id` BIGINT **NOT NULL**，单值 | `model/entity/Song.java:43-44` |
| 外键 `fk_song_artist_id` → `tb_artist.id`，**ON DELETE CASCADE** | `sql/vibe_music.sql` |
| `tb_album.artist_id` NOT NULL，FK **ON DELETE RESTRICT** | `sql/album_migration.sql` |
| `tb_artist` 只有**非唯一** `INDEX name`，**无 `UNIQUE(name)`** | `sql/vibe_music.sql` |
| `tb_artist.gender` 已预留 `2-组合/乐队`，且前后端均已实现该选项 | DDL 注释；`vibe-music-admin/src/views/artist/index.vue:78-80`、`utils/hook.tsx:81-96` |
| 已有「多值 → 关联表」先例 | `tb_genre(song_id, style_id)` 与主表冗余 `tb_song.style` 并存 |

### 1.2 真实数据（本地库实测）

- 126 位歌手 / 529 首歌曲。名字含 `/` 的歌手**仅 1 条**：`id=144 银临/Aki阿杰`。
- `id=143 银临`（24 首，id 597–620）与 `id=144` 并存。
- 《牵丝戏》`tb_song.id=622` → `artist_id=144`、`album_id=545`；`tb_album.id=545.artist_id` **也是 144**。
- `id=144` 的 `gender/avatar/birth/area/introduction` **全为 NULL**；`id=143` 的 `introduction` 是空串。
- **[事实] 关键反例**：`id=3「接个吻，开一枪」` 是含**中文逗号**的合法艺名（`gender` 也为 NULL，但 `introduction` 有内容）→ **逗号绝不能当分隔符**。
- `tb_artist` dump 中 `AUTO_INCREMENT = 142`，说明 143/144 是 dump 之后新增的，未经人工登记流程。

### 1.3 组合歌手是怎么被造出来的（根因）

`SongUploadServiceImpl` 上传链路：`resolveArtist(dto)` 先按 `artistId`、再按 `name` 精确匹配（`:431-446`），匹配不到就 `createArtist(artistName)`（`:409-429`）**自动新建歌手**；提交时 `song.setArtistId(artist.getArtistId())`（`:343`）。

→ 上传《牵丝戏》时元数据 artist = `"银临/Aki阿杰"`，库里没有 → 自动建了 144。
**不堵住这个入口，改造后仍会继续产生 A/B、A/C、B/C… 的组合爆炸。**

### 1.4 读路径全部 `tb_artist` 关联点（改造面清单）

| # | 位置 | 用途 | 本轮处置 |
|---|---|---|---|
| 1 | `SongMapper.getSongsWithArtist` | 用户端歌曲列表 | 去 JOIN，读 `s.artist_names` |
| 2 | `SongMapper.getSongsWithArtistName` | 管理端歌曲列表（含 `artistId` 筛选） | 去 JOIN；`artistId` → `EXISTS` |
| 3 | `SongMapper.getRandomSongsWithArtist` | 随机/推荐兜底 | 去 JOIN |
| 4–6 | `SongMapper.xml` `getSongDetailById` / `getSongsByIds` / `getRecommendedSongsByStyles` | 详情 / 收藏 / 风格推荐 | 去 JOIN |
| 7–12 | `AgentSongMapper` 6 处 | Agent 检索与卡片 | 去 JOIN |
| 13 | `SearchMapper.searchSongs` | 全局搜索（含排序权重） | 去 JOIN |
| 14 | `ArtistMapper.xml` `getArtistDetailById` | **反向 join**：歌手详情页歌曲 | 改走 `tb_song_artist` |
| 15 | `PlaylistMapper.xml` `playlistDetailBody` | 歌单详情歌曲 | 去 JOIN |
| 16 | `UserMusicProfileMapper.topArtists` | 画像「常听歌手」 | 改走 `tb_song_artist` |
| 17 | `AlbumMapper` 5 处 + `SearchMapper.searchAlbums` | 专辑主艺人 | **不改**（取自 `tb_album.artist_id`） |

### 1.5 已确认的缺陷（真实，非推测）

- **管理端按歌手筛选漏歌**：`getSongsWithArtistName` 用 `s.artist_id = #{artistId}`；实测 `artistId=143` 返回 **24 首**，《牵丝戏》不在其中。
- **歌手详情页漏歌**：`ArtistMapper.xml` 的 `a.id = s.artist_id` 反向 join。
- **专辑归属错**：`AlbumMapper.getAlbumsByArtistId(143)` 拿不到《牵丝戏》专辑 545。
- **画像口径错**：`topArtists` 按 `a.id = s.artist_id` 分组 → 「银临」与「银临/Aki阿杰」被算成两位歌手。
- **巧合正确（易误导）**：Agent `searchSongsByArtist` 走 `a.name LIKE '%银临%'`，**恰好**命中《牵丝戏》。所以「能搜到」不能当作「已支持多歌手」的证据。

### 1.6 契约现状

- 后端 `artistName` 全是 **String**：`SongVO` / `SongDetailVO` / `SongAdminVO` / `AgentSongVO` / `SongRef` / `PlaylistDetailVO.songs`（`List<SongVO>`）。
- `SongRef` 是 record，**全项目只有 1 个构造点**：`AgentSongQueryService:88` → 加字段的编译期影响面极小。
- 前端 `interface.ts`：`Song.artistName` / `PlaylistSong.artistName`；`agent.ts` 的 `AgentSongRef`；`ai.ts` 的 `AgentSongsEvent.data[]`。

---

## 2. 目标数据模型

### 2.1 `tb_song_artist`（权威关系表）

```sql
CREATE TABLE IF NOT EXISTS `tb_song_artist` (
  `song_id`       bigint   NOT NULL COMMENT '歌曲 id',
  `artist_id`     bigint   NOT NULL COMMENT '歌手 id',
  `display_order` int      NOT NULL DEFAULT 1 COMMENT '展示顺序，从 1 开始连续',
  `created_at`    datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`song_id`, `artist_id`),
  KEY `idx_song_artist_artist` (`artist_id`, `song_id`),
  KEY `idx_song_artist_song_order` (`song_id`, `display_order`),
  CONSTRAINT `fk_song_artist_song`   FOREIGN KEY (`song_id`)   REFERENCES `tb_song`(`id`)   ON DELETE CASCADE,
  CONSTRAINT `fk_song_artist_artist` FOREIGN KEY (`artist_id`) REFERENCES `tb_artist`(`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  ROW_FORMAT=Dynamic COMMENT='歌曲-演唱歌手关系（权威数据源）';
```

**设计要点**

| 项 | 决定 | 理由 |
|---|---|---|
| 唯一性 | `PRIMARY KEY (song_id, artist_id)` | 直接保证「同一首歌不会重复关联同一歌手」，无需应用层去重 |
| `display_order` | 从 1 连续递增，**不加唯一约束** | 加唯一约束会在交换两位歌手顺序时撞键。改用「整组替换」写入（见 §3.1），顺序天然无冲突 |
| 排序兜底 | 读取统一 `ORDER BY display_order ASC, artist_id ASC` | 防止脏数据导致结果不确定 |
| 反查索引 | `(artist_id, song_id)` | 覆盖「该歌手的所有歌曲」（`ArtistMapper` 反向查询、`EXISTS` 筛选） |
| 外键 | song → CASCADE，artist → RESTRICT | 删歌自动清关系；删歌手必须显式处理，防止静默解绑 |
| `role` 列 | **不加** | 决策 9；未来若需词曲作者，另立 `tb_song_contributor(song_id, person_id, role)` |
| `created_at` | 保留 | 便于排查与审计（`tb_album` 已有 `created_at/updated_at` 先例） |

### 2.2 `tb_song` 变更

```sql
ALTER TABLE `tb_song` ADD COLUMN `artist_names` varchar(512) NULL
  COMMENT '歌手展示串，按 display_order 用 " / " 拼接；派生自 tb_song_artist';
```

- **长度 512**：按 10 位歌手 × 约 15 字估算约 150 字符，余量充足。
- **允许 NULL**：迁移回填前为 NULL；服务层写入时必填。回填完成后可另起一次迁移收紧为 `NOT NULL DEFAULT ''`（非必需）。
- **不建索引**：查询形态是 `LIKE '%x%'`，索引无效（与 `tb_song.style` 一致）。
- **`artist_id` 保留**，语义重定义为「主艺人」，恒等于 `tb_song_artist` 中 `display_order` 最小那条的 `artist_id`，由服务层保证。

### 2.3 三类数据的职责边界（防止「冗余字段变成第二份权威数据」）

| 数据 | 角色 | 权威性 | 谁可以读 |
|---|---|---|---|
| `tb_song_artist` | 关系 | **唯一权威** | 精确检索、歌手详情、画像统计、`artists` 字段 |
| `tb_song.artist_names` | 展示串 | **派生值**，禁止作为判断依据 | 列表/详情/搜索/Agent 卡片的 `artistName` |
| `tb_song.artist_id` | 主艺人 | 兼容字段 | 专辑归属、歌词检索、上传默认值 |

**硬规则（写进代码注释与评审清单）**

1. 判断「某歌手是否演唱某歌」**只能**查 `tb_song_artist`，**不得**用 `artist_names LIKE`。
2. `artist_names` **只能**由 `tb_song_artist` + `tb_artist.name` 派生，派生函数全项目唯一实现（§3）。
3. 读路径**禁止** JOIN `tb_song_artist`（见 §5.2 分页 count）。

### 2.4 `artist_id` 何时可以移除（本轮不移除）

需同时满足：
1. 所有按 `artistId` 的筛选都已改走关联表（本轮完成）；
2. 专辑归属有独立概念 —— `tb_album` 支持多艺人，或明确「专辑主艺人」是独立字段而非歌曲字段的复制；
3. `SongUploadServiceImpl` 不再需要「主艺人默认值」；
4. 歌词检索有替代的歌手来源（例如直接取 `display_order=1` 的歌手）；
5. `fk_song_artist_id` 约束被显式迁移处理。

---

## 3. 关系表与 `artist_names` 的事务内同步机制

### 3.1 唯一写入口：`SongArtistService`（新增）

```
replaceSongArtists(Long songId, List<Long> artistIds)   // @Transactional
renameArtist(Long artistId, String newName)             // @Transactional
removeSongArtists(Long songId)                          // @Transactional
renderArtistNames(List<String> names)                   // 纯函数，拼接唯一实现
```

**`replaceSongArtists` 步骤（单事务内）**

1. 校验：`artistIds` 非空、去重后保持传入顺序、逐个校验歌手存在且 `status = 'ACTIVE'`，否则抛业务异常（**不静默跳过**）。
2. 派生：`primaryArtistId = artistIds.get(0)`；`artistNames = renderArtistNames(按序查到的 name)`。
3. `UPDATE tb_song SET artist_id = ?, artist_names = ? WHERE id = ?`。
4. `DELETE FROM tb_song_artist WHERE song_id = ?`（整组替换，规避 `display_order` 冲突）。
5. 按顺序批量 `INSERT`，`display_order` = 1..n。
6. **事务内自检**：`SELECT COUNT(*) FROM tb_song_artist WHERE song_id = ?` 必须等于 `artistIds.size()`，否则抛异常回滚。

> 第 6 步是防「静默半写」的关键：关系表与展示串在同一事务内更新，任一环节失败整体回滚，不存在「展示串已改、关系表没改」的中间态。

**`renameArtist` 步骤**

1. `UPDATE tb_artist SET name = ? WHERE id = ?`。
2. 查出受影响的 `songId` 列表：`SELECT DISTINCT song_id FROM tb_song_artist WHERE artist_id = ?`。
3. 批量取这些歌曲的全部关系（`findBySongIds`），**在 Java 里**用同一个 `renderArtistNames` 重建展示串，逐首 `UPDATE tb_song.artist_names`。

> 为什么不写一条 `UPDATE ... JOIN ... GROUP_CONCAT(a.name ORDER BY sa.display_order)`：
> `GROUP_CONCAT` 的 `ORDER BY` 在 H2 上不可移植（项目测试跑 `H2(MODE=MySQL)`），
> 而 Java 重建可移植、可单测，且与写入路径**共用同一段拼接逻辑**，从根上杜绝两套拼接规则漂移。
> 歌手改名是低频操作，受影响歌曲数（如银临 24 首）完全可接受。

**`removeSongArtists`**：`DELETE FROM tb_song_artist WHERE song_id = ?` + 清空 `artist_names`。仅用于歌曲删除前的显式清理（正常删除由 FK CASCADE 处理）。

### 3.2 调用点（写路径全部收敛到该服务）

**全项目共 3 个歌曲写入路径**（已用 `grep -rn "songMapper.insert"` 与 `setArtistId` 全量确认）：

| # | 调用方 | 位置 | 说明 |
|---|---|---|---|
| 1 | 新增单曲 | `SongServiceImpl.addSong:304-361`（`songMapper.insert:325`） | 插入后调用 `replaceSongArtists` |
| 2 | 修改歌曲 | `SongServiceImpl.updateSong:372-479`（`updateById:412`） | 同上；`artistIds` 为空则保留旧关系 |
| 3 | **单曲上传提交** | `SongUploadServiceImpl.commit`（`insert:359`，`setArtistId:343`） | 用 §6 的解析结果调用 |
| 4 | **专辑批量上传** | `AlbumUploadServiceImpl`（`insert:149`，`setArtistId` 在同行的链式调用里） | ⚠️ **易遗漏的第三个写入口**：一个循环里为整张专辑逐首插歌，且**已支持逐首覆盖歌手**（`x.getArtistId() == null ? artistId : x.getArtistId()`）——多歌手最自然的落点就在这里 |
| 5 | 歌手改名 | `ArtistServiceImpl.updateArtist:299-314` | 调用 `renameArtist` |

> `AlbumUploadServiceImpl` 的歌曲插入是**长链式 `new Song()...` 单行语句**，可读性差、极易在改造时被忽略。
> 它同时也承担失败回滚（catch 里清理已转存对象），改造时需保证 `replaceSongArtists` 的异常同样触发回滚。
> `AlbumUploadCommitDTO.SongInfo` **已经有** `artistId` 字段，因此把它扩成 `artistIds` 是本次改造中最直接的切入点。

其余 `setArtistId` 出现点均属 `Album` 实体（`SongServiceImpl:524` 的 `createAlbum`、`SongUploadServiceImpl:463` 的 `resolveAlbumId`、`AlbumServiceImpl:151`、`AlbumUploadServiceImpl:141`），是**专辑主艺人**，本轮不改。

**注意**：`SongServiceImpl.addSong:330-335` 的「回查刚插入记录」依赖 `artist_id + name + album`，多歌手后仍以**主艺人**回查即可，**不需要改**。

---

## 4. `artists` 结构化字段的兼容策略

### 4.1 字段形态

```json
{
  "artistName": "银临 / Aki阿杰",
  "artists": [
    { "artistId": 143, "artistName": "银临" },
    { "artistId": 145, "artistName": "Aki阿杰" }
  ]
}
```

> `145` 仅为结构示例，**不是**已核实的数据库 ID；实际 ID 由迁移时新建记录生成。

- `artistName` 保持 String，向后兼容；**由 `artists` 派生**（`join(" / ")`），保证两者永不矛盾。
- `artists` 按 `display_order` 升序；元素复用现有 `ArtistNameVO(artistId, artistName)` 结构。
- `artists` 为**追加字段**，不改任何既有字段名/类型 → 老前端不受影响。

### 4.2 装配方式（零 JOIN，一次批量查询）

新增查询（`SongArtistMapper`）：

```sql
SELECT sa.song_id AS songId, sa.artist_id AS artistId,
       a.name AS artistName, sa.display_order AS displayOrder
FROM tb_song_artist sa
JOIN tb_artist a ON a.id = sa.artist_id
WHERE sa.song_id IN (...)
ORDER BY sa.song_id, sa.display_order, sa.artist_id
```

在 service 层按 `songId` 分组装配。**每批查询只多一次往返**，不引入 JOIN、不放大行数、不影响分页 count。

### 4.3 需要补充 `artists` 的返回结构（逐一核实）

**传统接口**

| VO | 出口 | 装配点 |
|---|---|---|
| `SongVO` | 用户端歌曲列表、随机/推荐、收藏列表、歌手详情页 songs、专辑详情 songs、全局搜索 songs、歌单详情 songs | `SongServiceImpl.getAllSongs` / `getRecommendedSongs`；`UserFavoriteServiceImpl`；`ArtistServiceImpl.getArtistDetail`；`AlbumServiceImpl` 专辑详情；`SearchServiceImpl.search`；`PlaylistServiceImpl` 歌单详情 |
| `SongDetailVO` | `GET /song/getSongDetail/{id}` | `SongServiceImpl.getSongDetail` |
| `SongAdminVO` | 管理端歌曲列表 | `SongServiceImpl.getAllSongsByArtist` |
| `SongUploadCommitVO` | 上传提交结果 | `SongUploadServiceImpl.buildCommitVO:522-528` |

**Agent 接口**

| 结构 | 出口 | 装配点 |
|---|---|---|
| `SongRef`（record） | 工具返回 JSON、SSE `songs` 事件、`/ai/songs` 分页、待确认预览 | `AgentSongQueryService.toRef:87-90`（**全项目唯一构造点**） |
| `AgentSongVO` | `AgentSongMapper` 投影 | 加 `artists` 后由 `toRef` 透传 |
| `AgentEvent.songs` payload | SSE 事件体 | 由 `SongRef` 序列化，自动带出 |

**前端类型（需同步追加字段，均为可选追加）**

- `vibe-music-client/src/api/interface.ts`：`Song` / `PlaylistSong` / `SongDetail`
- `vibe-music-client/src/api/agent.ts`：`AgentSongRef`
- `vibe-music-client/src/api/ai.ts`：`AgentSongsEvent.data[]`
- `vibe-music-admin/src/views/song/utils/hook.tsx`：管理端列表映射

### 4.4 一致性约束（必须可测）

1. **同源派生**：`artistName` 与 `artists` 都来自同一批 `tb_song_artist` 查询结果，不存在两个数据源。
2. **契约测试**：对任一接口返回的歌曲列表断言
   `song.artistName == song.artists.map(a -> a.artistName).join(" / ")`
   且 `artists` 的 `artistId` 顺序严格递增的 `display_order`。
3. **不要求一次性改造所有页面**：字段是追加的，页面可分批接入；未接入的页面继续用 `artistName`，不会出现名称不一致。

---

## 5. 精确检索 / 分页 count / 画像 / 歌词 调整清单

### 5.1 歌手精确检索（两种语义必须分开）

| 语义 | 触发方式 | 实现 | 理由 |
|---|---|---|---|
| **按歌手归属筛选** | 前端选中某歌手（传 `artistId`） | `EXISTS (SELECT 1 FROM tb_song_artist sa WHERE sa.song_id = s.id AND sa.artist_id = #{artistId})` | 唯一正确的归属判定 |
| **自由关键词召回** | 用户输入文本（`keyword`） | 保留 `s.artist_names LIKE '%kw%'` | 只是召回手段；**不得**据此判断归属 |
| **Agent「搜某歌手的歌」** | `searchSongsByArtist(artistName)` | **改为**：先 `tb_artist WHERE name LIKE` 解析出 ACTIVE 的 `artistId` 集合 → 再 `EXISTS` 关联表 | 迁移后 144 停用，旧的 `a.name LIKE` 路径会失效；且新方案精确、不丢召回 |

具体改动点：

- `SongMapper.getSongsWithArtistName`（管理端）：`s.artist_id = #{artistId}` → `EXISTS`。
- `AgentSongMapper` 的 `artistName` 参数：改为 `artistId` 集合 + `EXISTS`（新增 `findSongsByArtistIds` 或在现有语句中用 `<foreach>` 拼 `IN`）。
- `ArtistMapper.xml.getArtistDetailById`：反向 join 改 `tb_song_artist`，`artistNameOfSong` 取 `s.artist_names`。
- `MusicTools.searchSongsByArtist`：改为「解析 artistId → EXISTS」，工具描述与错误消息需同步更新（真实模型对措辞敏感）。

**P6 实施记录（2026-10-13）**

- 新增 `service/agent/AgentArtistResolver`：把歌手名解析成**启用** `artistId` 集合。
  候选片段 = 整体串 + 按 `/` 与 `、` 拆出的各段（**不含逗号**，因为 `id=3「接个吻，开一枪」` 是含中文逗号的合法艺名）。
  整体串也作为候选是为了兼容「调用方把展示串 `银临 / Aki阿杰` 原样传回」的情况 ——
  不拆分会解析失败、召回凭空丢失。解析结果按 id 升序，上限 50 条。
- `AgentSongMapper` 三处歌手筛选全部改为 `artistIds` + `EXISTS`：
  `searchPage`（`/ai/songs` 分页）、`findSongs`（组合筛选）、`findSongsExcluding`（歌单候选）；
  已写好但未接线的 `findSongsByArtistIds` 接到 `MusicTools.searchSongsByArtist` 上。
- `AgentSongQueryService` 在边界处解析：「给了歌手名但解析不到」返回**空结果**，
  与「没给歌手名（不加条件）」严格区分 —— 否则不存在的歌手会退化成「返回全部歌曲」。
- 语义说明：传完整展示串时按**并集**处理（搜「A / B」= 两位歌手各自的歌 + 合作歌），
  而不是只返回合作歌。这是「这些歌手的歌」的自然读法。
- `findSongsByKeyword` / `findRandomSongs` 的 `artist_names LIKE` **保留** —— 那是自由关键词召回，
  按 §5.1 的规则不得据此判断归属。

### 5.2 分页 count

**结论：本轮读路径零 JOIN，分页 count 全部保持不变，无需手写 count。**

| 查询 | 是否 IPage | 变化 | count 影响 |
|---|---|---|---|
| `SongMapper.getSongsWithArtist` | 是 | 去掉 `LEFT JOIN tb_artist` | 无（行数不变） |
| `SongMapper.getSongsWithArtistName` | 是 | 去掉 JOIN，`artistId` → `EXISTS` | 无（`EXISTS` 不放大行数） |
| `SongMapper.getSongsByIds` | 是 | 去掉 JOIN | 无 |
| `AgentSongMapper.searchPage` | 是 | 去掉 JOIN | 无 |
| `AlbumMapper.getAlbumList` | 是 | 不改 | 无 |

**硬规则**：任何 `IPage` 查询**禁止** JOIN `tb_song_artist`。若 JOIN，则必须 `GROUP BY s.id`，而 MyBatis-Plus 分页插件生成的 `COUNT(*)` 会返回**分组行数**而非歌曲数 —— 这正是要规避的错误。

`artists` 字段一律走 §4.2 的独立批量查询，不进主查询。

### 5.3 用户画像

`UserMusicProfileMapper.topArtists` 改为：

```sql
SELECT a.id AS id, a.name AS name, COUNT(DISTINCT t.song_id) AS count
FROM (
    SELECT f.song_id AS song_id FROM tb_user_favorite f
     WHERE f.user_id = #{userId} AND f.type = 0 AND f.song_id IS NOT NULL
    UNION ALL
    SELECT b.song_id AS song_id FROM tb_playlist_binding b
      JOIN tb_playlist p ON p.id = b.playlist_id
     WHERE p.owner_user_id = #{userId}
) t
JOIN tb_song s        ON s.id = t.song_id
JOIN tb_song_artist sa ON sa.song_id = s.id
JOIN tb_artist a      ON a.id = sa.artist_id
WHERE a.status = 'ACTIVE'
GROUP BY a.id, a.name
ORDER BY count DESC, a.name ASC
LIMIT #{limit}
```

- `COUNT(DISTINCT t.song_id)` 保持不变 → 一首合作歌曲给**每位参与者各计一次**，且不因 `t` 的 `UNION ALL` 重复而放大。
- 必须加 `a.status = 'ACTIVE'`，否则停用歌手会出现在画像里。
- **基线漂移（预期）**：迁移后《牵丝戏》给 143 计数 +1（24 → 25），144 从画像消失。
  `UserMusicProfileService.factsHash` 随之变化 → `MusicTasteSummaryService` 会重新生成概述。
  这是**正确的结果**，但需人工确认新画像合理。
- H2 测试基座需建 `tb_song_artist` 与 `tb_artist.status` 列。

### 5.4 歌词补全（含一个量化风险）

**现状**：`LyricsServiceImpl.resolveArtistName(song, cache)`（`:529-543`）按 `song.getArtistId()` 查 `tb_artist` 取名，传给 `QqMusicProvider` / `LrcLibProvider`；`LyricsMatcher.score` 用歌手名算分，`lyrics.match.min-score = 0.7`。

**风险（预测，已实跑证伪 —— 见 §5.5）**：

| 场景 | 歌手维度得分 | 歌名维度 | 合计 | 阈值余量 |
|---|---|---|---|---|
| 迁移前：传 `"银临/Aki阿杰"` | `+0.3`（相等） | `+0.6`（歌名精确） | **0.9** | 0.20 |
| 迁移后：传 `"银临"` | `+0.15`（包含关系） | `+0.6` | **0.75** | **0.05** |

依据：`LyricsMatcher:73-79` 完全相等 +0.3 / 包含 +0.15；`NON_WORD = [^\p{L}\p{N}]+` 会清掉 `/` 与空格，
而 `QqMusicProvider.joinSingers:194-201` 会把数据源返回的**多位歌手拼成一个字符串**。
因此传完整展示串时归一化后与数据源串相等（+0.3），传主艺人名时退化为包含关系（+0.15）。

**已实现的回退链（复用现有 provider 与 matcher，不重写歌词功能）**：

1. **主艺人名**（`tb_song.artist_id` → `tb_artist.name`）—— 对 LRCLIB 的 `artist_name` 参数最友好。
2. **完整展示串** `artist_names` —— 归一化后与 QQ 的合并歌手串相等，可拿回 `+0.3`。
3. **各合作歌手名**（按 `display_order`）。

**约束**：
- 每首最多尝试 2 次（主艺人 + 展示串），可配置 `lyrics.match.max-artist-attempts`（默认 2）；第 3 级仅在配置显式打开时启用。
- 复用现有 `artistCache`、`lyrics.batch.delay-ms=500`、`thread-pool-size=2` 节流，避免批量补全时请求量翻倍。
- **不放宽** `min-score`；仍走 `LyricsMatcher` 的既有校验（含时长维度），不因尝试次数而降低写入标准。
- `LyricsFetchVO.artistName`（展示用）改为完整展示串；检索参数用主艺人 —— **展示与检索分离**。
- `LyricsServiceImpl:536` 的 `artistMapper.selectById` **不需要**过滤停用状态（需能拿到停用歌手的名字以匹配历史歌词）。

### 5.5 实跑结论（2026-10-13，P6 实施后）

探针：`LyricsMatchScoreProbe`（默认不执行，`-Dlyrics.probe=true` 开启，需联网）。
真实数据：`tb_song.id=622`《牵丝戏》，歌名/专辑「牵丝戏」，时长 239.30s，
主艺人 `143 银临`，展示串「银临 / Aki阿杰」，阈值 0.7。

| 检索歌手名 | QQ_MUSIC 返回的候选歌手串 | QQ 得分 | 阈值余量 |
|---|---|---|---|
| `银临`（主艺人名） | `银临` | **1.000** | 0.30 |
| `银临 / Aki阿杰`（完整展示串） | `银临/Aki阿杰` | **1.100** | 0.40 |
| `Aki阿杰`（合作歌手名） | `银临/Aki阿杰` | 0.950 | 0.25 |

**结论：§5.4 预测的「0.9 → 0.75，余量缩到 0.05」在真实数据源上不成立。**

原因是 §5.4 的假设不完整：`QqMusicProvider` 会把**查询关键词**（`buildKeyword` = 歌名 + 空格 + 歌手名）
发给数据源，而 QQ 音乐对同一首歌存在多条记录、`singer` 字段各不相同 ——
搜「牵丝戏 银临」返回的记录 `singer` 恰好就是「银临」，归一化后与主艺人名**完全相等**，
拿到的是 `+0.3` 而不是 `+0.15`。所以主艺人名的得分是 **1.000**（歌名 0.6 + 歌手 0.3 + 时长 0.1），
比传完整展示串的 1.100 只少一个专辑维度的 0.1。

**因此回退链属于防御性设计而非必需**：对《牵丝戏》第 1 级（主艺人名）即以 1.000 通过，
不会触发第 2 级；只有当日志源返回的歌手串既不等于、也不包含主艺人名时才会升级
（`LyricsArtistFallbackTest` 用假数据源把这种情况固定下来：0.6 → 0.75）。

**LRCLIB 侧**（兜底源，不做内部阈值门控）：传主艺人名时最佳候选得分 1.100（歌手「银临」），
传完整展示串时 1.100（歌手「银临/Aki阿杰」），同样都远离阈值。

> 实跑还暴露一个**既有**的边界现象（非本次改动引入）：LRCLIB 里存在时长 719s 的《牵丝戏》，
> 因时长差 > 20s 被扣 0.2，恰好落在 0.700 上「擦线通过」。阈值判定是 `score < minScore` 才算丢弃，
> 所以 0.700 会被接受。是否收紧由后续决定，本次不动。

---

## 6. 上传元数据的自动识别与人工确认流程

### 6.1 判定优先级（严格按序短路）

```
输入 raw = 元数据 artist 字符串
│
├─ 1. raw 与某条 ACTIVE 歌手 name 精确匹配？
│     └─ 是 → strategy = EXACT_MATCH，视为【单一歌手】，不拆分。
│            （若该记录 gender = 2「组合/乐队」，置信度更高，仍不拆分）
│
├─ 2. raw 含明确合作分隔符？
│     分隔符白名单：` / `、`/`、` & `、`&`、`、`、`feat.` / `ft.` / `featuring`（大小写不敏感）
│     ⚠️ 绝不使用 `,` 与 `，` —— id=3「接个吻，开一枪」证明逗号是合法艺名字符
│     └─ 无 → strategy = UNRESOLVED，进入人工确认
│
├─ 3. 拆出的【每一段】都能唯一精确匹配一条 ACTIVE 记录？
│     ├─ 是 → strategy = SPLIT_CONFIRMED，生成多个候选关联（默认全部勾选）
│     └─ 否 → strategy = SPLIT_CANDIDATE，进入人工确认（列出已匹配 / 未匹配）
│
└─ 4. 人工确认
      ├─ 未匹配的名称：**不自动创建**，由管理员显式选择「新建歌手」或「绑定到已有歌手」
      ├─ 重复匹配 / 歧义（一段命中多条）：必须人工指定
      └─ 允许管理员勾选「作为单一歌手保留」（合法组合艺名场景）
```

**未识别出的名称不得自动创建，也不得静默丢弃** —— 它们保留在待确认列表中，直到管理员处理。

### 6.2 对《牵丝戏》套用（实测结果）

`raw = "银临/Aki阿杰"`
- 步骤 1：无 ACTIVE 记录叫这个名字（144 迁移前是 ACTIVE 但叫 `银临/Aki阿杰`……见下注）→ 不匹配。
- 步骤 2：含 `/` → 拆为 `银临`、`Aki阿杰`。
- 步骤 3：`银临` 命中 id=143；`Aki阿杰` **无记录** → **不满足"每一段都唯一命中"**。
- → `strategy = SPLIT_CANDIDATE` → **进入人工确认**，与决策 6 的预期一致。

> 注：迁移**前**步骤 1 会命中 id=144 本身（名字就是 `银临/Aki阿杰`），此时会被判定为 `EXACT_MATCH` 而**不拆分** —— 这正是「避免误拆合法组合名」规则在起作用。要拆 144，必须走 §7 的显式迁移流程，而不是靠上传解析。这一点必须在实施说明里写清楚，否则会出现「改完代码但 144 依然拆不开」的困惑。

### 6.3 预览展示

`SongUploadPrepareVO` 追加：

```json
"artistParsing": {
  "raw": "银临/Aki阿杰",
  "strategy": "SPLIT_CANDIDATE",
  "separator": "/",
  "candidates": [
    { "artistId": 143,   "artistName": "银临",     "matched": true  },
    { "artistId": null,  "artistName": "Aki阿杰",  "matched": false }
  ]
}
```

前端 `vibe-music-admin/src/views/song-upload/index.vue`：
- 展示解析出的歌手列表，**未匹配项高亮**；
- 提供「新建歌手」「绑定已有歌手」「作为单一歌手保留」三个动作；
- 提交前管理员必须显式确认最终歌手列表（对应决策 6「让管理员明确看到最终确认的歌手列表」）。

---

## 7. ID 144 的安全迁移与软删除

### 7.1 执行顺序（严格按决策 4 的 5 步）

**Step 0 — 引用盘点（只读，先做）**

```sql
SELECT COUNT(*) FROM tb_song            WHERE artist_id = 144;   -- 本地实测：1
SELECT COUNT(*) FROM tb_album           WHERE artist_id = 144;   -- 本地实测：1
SELECT COUNT(*) FROM tb_song_artist     WHERE artist_id = 144;   -- 迁移后：1
```

**Step 1 — 新建独立歌手「Aki阿杰」**
- `INSERT INTO tb_artist(name, gender, area, ...) VALUES ('Aki阿杰', ?, ...)`。
- `gender` 取值需业务确认（0/1/2）→ 见 §9 待核实。
- 「银临」继续用现有 `id=143`。

**Step 2 — 为《牵丝戏》建立独立关联**
```sql
INSERT INTO tb_song_artist(song_id, artist_id, display_order) VALUES
  (622, 143, 1), (622, <aki_id>, 2);
```

**Step 3 — 更新兼容字段，切断旧外键指向**
```sql
UPDATE tb_song  SET artist_id = 143, artist_names = '银临 / Aki阿杰' WHERE id = 622;
UPDATE tb_album SET artist_id = 143                                  WHERE id = 545;  -- ⚠️ 勿遗漏
```
> `tb_album.id=545.artist_id` 实测也是 144。**只改 `tb_song` 而不改 `tb_album`**，会让专辑主艺人继续指向停用记录，
> 属于决策 4 明确要求避免的「旧外键关系继续指向组合记录」。

**Step 4 — 验证（全部通过才进入 Step 5）**
```sql
-- a) 关系表恰 2 行且顺序正确
SELECT artist_id, display_order FROM tb_song_artist WHERE song_id = 622 ORDER BY display_order;
-- b) 主艺人 = display_order 最小者
SELECT (SELECT artist_id FROM tb_song WHERE id=622)
     = (SELECT artist_id FROM tb_song_artist WHERE song_id=622 ORDER BY display_order LIMIT 1);
-- c) 展示串与关系表一致
SELECT (SELECT artist_names FROM tb_song WHERE id=622)
     = (SELECT GROUP_CONCAT(a.name ORDER BY sa.display_order SEPARATOR ' / ')
        FROM tb_song_artist sa JOIN tb_artist a ON a.id = sa.artist_id WHERE sa.song_id = 622);
-- d) 已无任何引用指向 144
SELECT COUNT(*) FROM tb_song  WHERE artist_id = 144;   -- 期望 0
SELECT COUNT(*) FROM tb_album WHERE artist_id = 144;   -- 期望 0
```
另加**功能回归**：搜「银临」命中《牵丝戏》；`/artist/143` 详情页列出《牵丝戏》；`artistId=143` 管理端筛选返回 25 首；`artist_names` 与 `artists` 一致。

**Step 5 — 标记停用（软删除）**
```sql
UPDATE tb_artist SET status = 'DISABLED', merged_into_artist_id = 143 WHERE id = 144;
```

**绝不物理删除**：`fk_song_artist_id` 是 `ON DELETE CASCADE`，删除 144 会连带删掉《牵丝戏》；`tb_album` 亦引用它。

### 7.2 停用状态字段

```sql
ALTER TABLE `tb_artist`
  ADD COLUMN `status` varchar(20) NOT NULL DEFAULT 'ACTIVE'
    COMMENT '状态：ACTIVE / DISABLED（软删除）',
  ADD COLUMN `merged_into_artist_id` bigint NULL DEFAULT NULL
    COMMENT '若因合并而停用，记录归并到的歌手 id';
```

- 命名沿用项目既有约定（`tb_playlist.status` / `tb_album.status` 均为 varchar 枚举）。
- **不要**加 `UNIQUE(name)`：`tb_artist` 现只有非唯一 `INDEX name`；软删除后「停用的旧名 + 启用的新名」允许同名，MySQL 也无部分唯一索引可用。

### 7.3 影响范围（全量清单，**这是决策 4 要求重点评估的部分**）

**A. 需要加 `status = 'ACTIVE'` 过滤（16 处）**

| # | 位置 | 场景 |
|---|---|---|
| 1 | `ArtistServiceImpl.getAllArtists:74-86` | 用户端歌手列表 |
| 2 | `ArtistServiceImpl.getAllArtistsAndDetail:113-128` | 管理端歌手列表（需支持「包含停用」开关） |
| 3 | `ArtistServiceImpl.getAllArtistNames:144` | 歌手选择器 / 歌手树 / 上传匹配下拉 |
| 4 | `ArtistServiceImpl.getRandomArtists:168-171` | 随机歌手 |
| 5 | `ArtistServiceImpl.getAllArtistsCount:258-266` | 歌手计数 |
| 6 | `ArtistServiceImpl.getArtistDetail:196` + `ArtistMapper.xml:27-46` | 直接访问停用歌手详情 → 返回「歌手不存在」或「已合并至 X」 |
| 7 | `SearchMapper.searchArtists:55-73` | 全局搜索歌手 |
| 8 | `MusicTools.searchArtists:177-189` | Agent 搜歌手 |
| 9 | `MusicTools.getArtistDetail:191-204` | Agent 歌手详情 |
| 10 | `UserMusicProfileMapper.topArtists:77-92` | 画像常听歌手（§5.3） |
| 11 | `SongUploadServiceImpl.resolveArtist:431-446` / `matchArtist:555-565` | 上传匹配（不得匹配停用歌手） |
| 12 | `AlbumUploadServiceImpl:123` | 专辑上传按名匹配 |
| 13 | `AlbumServiceImpl:142` / `:178` | 专辑校验主艺人 → 拒绝停用 |
| 14 | `ArtistServiceImpl.addArtist:278-282` | 重名检查：**必须包含停用记录**（见下） |
| 15 | `ArtistServiceImpl.updateArtist:302` | 同上 |
| 16 | `ArtistServiceImpl.getArtistDetail` 的 `albums` 分支 | 停用歌手的专辑不应通过该页暴露 |

> #14/#15 的特殊处理：重名检查要查**全部**记录（含停用）。若唯一命中是停用记录 → 不报「已存在」，
> 而是提示「已存在同名停用歌手（id=144），是否恢复？」。这样既不会与停用记录产生同名冲突，也避免管理员被迫改名。

**B. 明确「不需要过滤」（防止过度修改，同样重要）**

| 位置 | 为什么不过滤 |
|---|---|
| `AlbumMapper` 5 处 `LEFT JOIN tb_artist ar` | 仅取**专辑主艺人名**用于展示；加过滤会导致专辑丢失艺人名 |
| `SearchMapper.searchAlbums:86` | 同上 |
| `LyricsServiceImpl.resolveArtistName:536` | 需能拿到停用歌手的名字以匹配历史歌词 |
| `ArtistServiceImpl.updateArtistAvatar:326` / `deleteArtist:350` / `deleteArtists:379` | 管理操作必须能查到停用记录 |
| `SongMapper` / `SongMapper.xml` / `PlaylistMapper.xml` / `AgentSongMapper` | 改读 `s.artist_names` 后已不再 JOIN `tb_artist`，无过滤点 |

**C. 实现方式二选一（建议 a）**

| | (a) 显式条件 `.eq("status","ACTIVE")` | (b) `@TableLogic(value="ACTIVE", delval="DISABLED")` |
|---|---|---|
| 防遗漏 | 需手工 16 处，靠清单 + 契约测试兜底 | MP 自动给 `selectList/selectPage/selectById/selectCount/deleteById` 加条件，**结构上防遗漏** |
| 管理端看停用记录 | 加个条件即可（灵活） | 必须另写原生 SQL |
| `deleteById` 语义 | 仍物理删除 → 需改为显式 UPDATE | 自动变 UPDATE（软删除） |
| 迁移校验 | `selectById` 能查到停用记录 | `selectById` **查不到**，校验需用原生 SQL |
| 项目一致性 | 与 `tb_playlist` 可见性「显式条件 + 约定」一致 | 项目从未用过 `@TableLogic`，属新机制 |

**建议 (a)**：与项目既有约定一致，管理端「包含停用」开关天然可做，迁移校验不被吞掉；
用一份**契约测试**（枚举上述 16 处，断言停用歌手不出现）来补偿手工遗漏风险。

### 7.4 停用歌手是否还出现在歌曲的 `artist_names` 里？

- **迁移完成后**：144 已不再被任何歌曲引用（Step 4 验证 d），因此不存在这个问题。
- **未来场景**（停用一位真实歌手）：建议 `artist_names` **保留**其名字（歌曲确实由他演唱，是历史事实），
  但该歌手不再出现在歌手列表/选择器/搜索/画像中。**此点建议在实施前确认**（见 §9）。

### 7.5 回滚

| 阶段 | 回滚动作 |
|---|---|
| Step 1–3 | 反向 UPDATE + 删除新增的 `tb_song_artist` 行 + 删除新建的「Aki阿杰」记录 |
| Step 5 | `UPDATE tb_artist SET status='ACTIVE', merged_into_artist_id=NULL WHERE id=144` |
| 整表 | `DROP TABLE tb_song_artist` + 清空 `artist_names` + 删 `status`/`merged_into_artist_id` 列 |

回滚脚本应随迁移脚本一并提交（`sql/phase6_*.rollback.sql`）。

---

## 8. 分阶段实施与验收

| 阶段 | 内容 | 验收标准 | 可回滚 |
|---|---|---|---|
| **P1** | DB 迁移：建 `tb_song_artist`、加 `artist_names`、回填单歌手 | `COUNT(tb_song_artist) = COUNT(tb_song) = 529`；`artist_names` 无 NULL；`GROUP BY song_id HAVING COUNT(*)>1` 为空；老功能全回归 | `DROP TABLE` + 清列 |
| **P2** | 读路径切换：17 处 `a.name` → `s.artist_names`，去 JOIN；`artistId` 筛选 → `EXISTS`；`ArtistMapper.xml` 反向 join 改走关系表 | 对现有单歌手数据，接口返回**逐字段一致**；分页 `total` 不变；`artistId=143` 管理端筛选由 24 → **25 首**（P7 前为 24，须说明）；`AgentEvalReportTest` 基线零漂移 | 回退代码 |
| **P3** | `artists` 结构化字段（传统接口 + Agent），走独立批量查询 | 契约测试：`artistName == artists.join(" / ")`；`artists` 按 `display_order`；老前端不受影响；`SongRef` 唯一构造点已更新 | 移除字段 |
| **P4** | `SongArtistService` + 全部 3 个写路径（`addSong` / `updateSong` / 单曲上传提交 / **专辑批量上传**）+ 管理端多选 + 上传解析预览 | 新建合作歌曲后关系表 2 行、`artist_names` 正确；事务自检生效（构造失败场景验证整体回滚）；上传「银临/Aki阿杰」进入人工确认；**专辑批量上传逐首指定多歌手生效** | 回退代码 |
| **P5** | `tb_artist.status` + 16 处过滤 + 管理端「包含停用」开关 | 契约测试覆盖 16 处；停用歌手不出现在列表/选择器/搜索/随机；`deleteArtist` 变为软删除；专辑仍能显示停用歌手的名字 | 清列 |
| **P6** | 精确检索收口 + 画像 `topArtists` + 歌词回退链 | 画像中合作歌曲给每位参与者各 +1；`factsHash` 变化后概述重新生成且人工确认合理；《牵丝戏》歌词实跑成功（记录实际得分） | 回退代码 |
| **P7** | 历史数据：新建「Aki阿杰」、拆分《牵丝戏》、更新 `tb_song`+`tb_album`、停用 144 | §7.1 Step 4 的 a–d 全部通过 + 功能回归；`tb_song.artist_id=144` 与 `tb_album.artist_id=144` 均为 0 | 见 §7.5 |

**P6 完成记录（2026-10-13）**

| 项 | 状态 |
|---|---|
| 精确检索收口（`searchSongsByArtist` / `/ai/songs` 分页 / `searchPlaylistCandidates`） | ✅ 已实施，见 §5.1 的「P6 实施记录」 |
| 画像 `topArtists` 走关系表 + `status='ACTIVE'` | ✅ 已在 P5 一并完成（`UserMusicProfileMapper.topArtists`） |
| 歌词回退链（主艺人名 → 完整展示串 → 合作歌手，限次） | ✅ 已实施，见 §5.4 / §5.5 |
| 展示与检索分离（`LyricsFetchVO.artistName` 用完整展示串） | ✅ 已实施 |
| 《牵丝戏》歌词实跑并记录得分 | ✅ 已实跑，结论见 §5.5（预测的 0.9→0.75 风险**未复现**） |

验收证据：`mvn -o test` **195/195**（新增 `AgentArtistPrecisionTest` 7 条、`LyricsArtistFallbackTest` 5 条）；
真实 MySQL 端到端（18080 验证实例 + 临时账号，用完即删）：
`artistName=Aki阿杰` → `total=1` 且命中 622（**非主艺人**能查到合作歌曲）；
`artistName=银临` → `total=25`；`artistName=不存在的歌手` → `total=0`（未退化成全部歌曲）；
`keyword=牵丝戏` → 命中 622（关键词召回未受影响）。

**阶段独立性**：P1 纯加、零行为变化；P2 只换字段来源，行为不变；P3 只加字段；P4 起才引入新能力。
每阶段单独可验证、可回滚。

---

## 9. 待核实事项

1. **[已核实 2026-10-13] QQ 音乐对《牵丝戏》返回的 `singer` 取决于查询关键词**，不是固定串：
   搜「牵丝戏 银临」返回 `银临`（与主艺人名完全相等），搜「牵丝戏 银临 / Aki阿杰」返回 `银临/Aki阿杰`。
   因此 §5.4 预测的「0.9 → 0.75」**不成立**，实测主艺人名 1.000、完整展示串 1.100。详见 §5.5。
2. **[待核实] 新建歌手「Aki阿杰」的 `gender` 取值**（0 男 / 1 女 / 2 组合乐队）与 `area`、`introduction` 由谁提供 —— 需业务确认。
3. **[待核实] 生产库是否只有 `tb_album.id=545` 一处 `artist_id` 指向待停用歌手**。
   本地库实测为 1 条，生产库需先跑 §7.1 Step 0 的只读盘点。
4. **[已核实] 歌曲写入路径共 3 个**：`SongServiceImpl.addSong:325`、`SongUploadServiceImpl:359`、`AlbumUploadServiceImpl:149`。
   其中 `AlbumUploadServiceImpl` 是循环内的链式单行插入，**已支持逐首覆盖歌手**（`AlbumUploadCommitDTO.SongInfo.artistId`），
   改造时最易遗漏，必须一并处理（见 §3.2）。
5. **[待核实] 停用真实歌手时，其名字是否保留在歌曲 `artist_names` 中**（§7.4 的取舍需业务拍板）。
6. **[待核实] 生产库与本地库的歌手/歌曲规模差异**，用于评估 P7 人工确认清单的长度。

---

## 10. 附录：改动点索引

**数据库**
- 新增 `sql/phase6_song_multiple_artists.sql`（幂等：`CREATE TABLE IF NOT EXISTS` + `information_schema` 判存在后 `ALTER` + 回填 + `DROP PROCEDURE`），另附 `.rollback.sql`。

**后端 — 新增**
- `SongArtistService`（§3.1，唯一写入口 + `renderArtistNames`）
- `SongArtistMapper` + `SongArtistRef`（§4.2 批量装配查询）
- `ArtistNameVO` 复用为 `artists` 元素类型（已存在，无需新建）

**后端 — 修改（读路径，17 处）**
`SongMapper`(3) / `SongMapper.xml`(3) / `ArtistMapper.xml`(1) / `PlaylistMapper.xml`(1) / `AgentSongMapper`(6) / `SearchMapper.searchSongs`(1) / `UserMusicProfileMapper.topArtists`(1) / `AlbumMapper`+`SearchMapper.searchAlbums`（**不改**）

**后端 — 修改（写路径）**
`SongServiceImpl.addSong` / `updateSong` / `getAllSongs` / `getRecommendedSongs` / `getSongDetail` / `getAllSongsByArtist`；
`SongUploadServiceImpl.resolveArtist` / `createArtist` / `commit` / `buildCommitVO` / `matchArtist`；
**`AlbumUploadServiceImpl`（循环内逐首插歌，`:149`）**；
`ArtistServiceImpl.addArtist` / `updateArtist` / `updateArtistAvatar` / `deleteArtist` / `deleteArtists` / `getAllArtists` / `getAllArtistsAndDetail` / `getAllArtistNames` / `getRandomArtists` / `getArtistDetail` / `getAllArtistsCount`；
`AlbumServiceImpl` / `LyricsServiceImpl` / `SearchServiceImpl` / `UserFavoriteServiceImpl` / `PlaylistServiceImpl`（装配 `artists`）。

**后端 — DTO/VO**
新增字段：`SongAddDTO.artistIds` / `SongUpdateDTO.artistIds` / `SongUploadCommitDTO.artistIds` / **`AlbumUploadCommitDTO.SongInfo.artistIds`**（已有 `artistId`，直接扩为有序列表）；
新增结构：`SongVO.artists` / `SongDetailVO.artists` / `SongAdminVO.artists` / `SongUploadCommitVO.artists` / `AgentSongVO.artists` / `SongRef.artists`；
新增：`SongUploadPrepareVO.artistParsing`。

**Agent**
`MusicTools.searchSongsByArtist`（改走关联表）/ `searchArtists` / `getArtistDetail`（加状态过滤）；
`AgentSongQueryService.toRef`（唯一 `SongRef` 构造点）；
提示词检查：`AgentSystemPrompt` / `PersonalizedSystemPrompt`（是否隐含单歌手假设）。

**前端**
管理端：`views/song-upload/index.vue`、`api/songUpload.ts`、`views/song/utils/hook.tsx`、`views/song/form/index.vue`、`views/artist/index.vue`、`views/artist/utils/hook.tsx`、`api/system.ts`；
用户端：`api/interface.ts`、`api/agent.ts`、`api/ai.ts`、`components/Common/AiWidget.vue`（接入 `artists` 跳转）。

**测试**
`AbstractAgentTest`（H2 建 `tb_song_artist` + `tb_song.artist_names` + `tb_artist.status`，夹具补值）；
新增契约测试：`artistName == artists.join(" / ")`、停用歌手 16 处不可见、事务整体回滚；
`AgentEvalReportTest` 基线零漂移；`UserMusicProfileTest` 需验证新 `topArtists` 在 H2 可跑。

---

## 11. 追补（2026-10-13）：专辑上传的逐首歌手与「确认后创建」

> §3.2 已把 `AlbumUploadServiceImpl` 标为「易遗漏的写入口」，本节记录它实际暴露出的两个问题与修法。

### 11.1 问题一：解析预览正确，入库后多歌手全丢

**现象**：专辑上传「解析信息」步骤每首歌的「艺人」显示正确（如 `银临、Tacke竹桑`），
提交后专辑详情里每首歌的艺人全部退化成专辑主艺人 `银临`。

**根因**：解析与提交两段用的歌手字段不是同一个。

| 阶段 | 原来干了什么 |
|---|---|
| 解析（`toPrepare` / `prepare`） | `x.setArtistName(m.getArtist())` —— 直接把音频标签里的原始串透传，**看起来完全正确** |
| 提交（`commit`） | 只用 `normalizeArtistIds(x.getArtistIds(), x.getArtistId())`，**完全没读 `x.getArtistName()`** |

而 `artistIds` 前端根本没传（`AlbumUploadPrepareVO.Song` 当时没有这个字段，
管理端每行「艺人」只是纯文本 `el-input`），`artistId` 也恒为 `null`
→ `normalizeArtistIds` 返回空 → 落到 `List.of(专辑主艺人)`。**多歌手被静默抹掉。**

### 11.2 问题二：库里没有的歌手，既不创建也不保留

专辑上传页的「艺人」输入框长得像可以随便填名字，但后端对每首歌的名字**既不创建也不保留**；
专辑主艺人反而因为 `isActiveArtist` 校验直接报错阻断。同一份数据在两个流程里语义不一致。

### 11.3 修法（业务决策：**A 确认后创建**；专辑主艺人**必须已存在**）

**新增 `ArtistProvisioningService`**（`service/`）——「歌手名 → 歌手 id」的**唯一写入口**：

- `resolve(raw)`：只读解析，返回 `slots`（有序歌手位，`artistId` 为空表示待创建）+
  `missingNames`（库里没有，确认后可建）+ `ambiguousNames`（同名多条，必须人工处理）。
  与 `ArtistNameParser` 共用同一套分隔符/匹配规则；判定优先级与 §6 一致。
- `createMissing(names)` / `resolveOrCreateId(name)`：真正落库，复用 `ArtistServiceImpl.addArtist`
  （因此天然带重名校验与 `artistCache` 失效）；并发重名收敛为「复用已存在的那条」。
- `SongUploadServiceImpl` 的私有 `createArtist` 已删除并改为委托本类，**创建歌手不再有两套实现**。

**`AlbumUploadCommitDTO` 新增 `allowCreate`**；`Result` 新增 `code = 409`
（`CODE_CONFIRMATION_REQUIRED`）与 `Result.confirmationRequired(msg, data)`。

**`commit` 的歌手流程改为三段**（全部在写任何数据之前完成解析）：

1. 逐首歌：显式 `artistIds`/`artistId` → 否则解析 `artistName` → 否则沿用专辑主艺人；
2. 汇总缺失/歧义名单：有歧义 → 直接报错；有缺失且未确认 → 返回 `409` + `AlbumUploadPendingArtistsVO`（**不写库**）；
3. 已确认 → `createMissing` 建歌手，把名字换成 id，再走原有的转存与插歌。
   建歌手与歌曲入库**同一事务**，后续任一步失败一起回滚。

> ⚠️ 这一步的顺序很关键：`@Transactional` 方法里 `return Result.error(...)` **不会回滚**。
> 因此所有校验（含 `minioService.objectExists`）必须排在 `createMissing` 之前，
> 否则会出现「歌手建了、歌曲没入库」的残留。

**解析阶段回传解析结论**：`AlbumUploadPrepareVO.Song` 新增 `artistIds`（仅
`EXACT_MATCH`/`SPLIT_CONFIRMED` 时非空）与 `artistParsing`，前端据此显示「已匹配 / 未匹配」标签。

**管理端 `views/album-upload/index.vue`**：

- 主艺人由纯文本输入改为**可搜索下拉**（数据源 `GET /admin/getAllArtistNames`），直接落实「必须已存在」；
- 曲目行「艺人」保留可编辑文本，下方用标签展示解析结果（未匹配为红色）；
  **手动改过某行名字后会清空该行的 `artistIds`**，否则后端会优先用旧 id 把改动盖掉；
- 提交收到 `409` 时弹出「确认创建歌手」对话框，列出将创建的歌手名；
  点「创建并上传」才带 `allowCreate: true` 重提，点「取消」则什么都不写。

**测试**：`ArtistProvisioningServiceTest`（9 例）覆盖多歌手拆分、完整匹配不拆（保护含逗号的合法艺名）、
未知单名、停用记录可重建、同名歧义阻断、批量创建去重复用、歧义时不建歌手。
