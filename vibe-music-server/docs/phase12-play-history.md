# 播放历史（最近播放）实施记录

日期：2026-10-20
基线：`test1`，工作树含一处并发进行中的上传链路改动（与本次无关）
对应迁移：`sql/phase12_play_history.sql`

## 1. 现状审计

| 能力 | 改造前 | 结论 |
|---|---|---|
| 播放历史表 | 无 | **缺失**，本次新增 `tb_play_history` |
| 后端接口 | 无 | **缺失** |
| 前端页面 | 无 | **缺失** |
| 播放器记录播放 | 无任何上报 | **缺失** |
| 「播放列表」浮层 | `footer/components/recently.vue` | **已存在但不是历史**：它是本次会话的队列（localStorage），与「听过什么」无关。未改动 |
| 收藏功能 | `UserFavoriteController` + `tb_user_favorite` | 已存在，是本次「显式信号」的来源之一 |
| 用户认证 | JWT + Redis + `PlaylistAccessService.requireUserId()` | 已存在，直接复用 |
| 统一响应 | `Result<T>` / `PageResult<T>` | 已存在，直接复用 |
| 歌曲表 | `tb_song`（`artist_names` 冗余列 + `tb_song_artist` 关系表） | 已存在，读查询直接复用，不重复 JOIN 歌手 |
| AI 画像 | `UserMusicProfileService` + `tb_user_music_profile` | 已存在；文档明确写「暂无播放历史表，不产出听过什么」，本次按**独立字段**接入 |

## 2. 数据库

```sql
CREATE TABLE tb_play_history (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id               BIGINT      NOT NULL,
    song_id               BIGINT      NOT NULL,
    last_played_at        DATETIME(6) NOT NULL,
    play_count            INT         NOT NULL DEFAULT 1,
    last_position_seconds INT         NULL,
    last_play_id          VARCHAR(64) NULL,
    UNIQUE KEY uk_play_history_user_song (user_id, song_id),
    KEY idx_play_history_user_time (user_id, last_played_at),
    CONSTRAINT fk_play_history_user FOREIGN KEY (user_id) REFERENCES tb_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_play_history_song FOREIGN KEY (song_id) REFERENCES tb_song(id) ON DELETE CASCADE
);
```

另给 `tb_user_music_profile` 加两列（幂等存储过程）：`played_song_count INT NOT NULL DEFAULT 0`、
`recent_play_artists TEXT NULL`。

### 关键决策

**为什么「一个用户一首歌一行」而不是事件流水账**
产品要的是「最近播放 + 播放了几次」，不是完整时间线。流水账需要独立的保留策略、
按事件分页，而且「这首歌播放次数」要聚合才能得到。唯一键让重复播放变成 UPDATE，
天然幂等，也不会无限增长。

**为什么 `last_position_seconds` 存了但不下发**
它只用于留痕与后续「续播」需求。列表展示不需要它，下发即等于多暴露一份细粒度行为数据。

**为什么 `song_id` 是 CASCADE 而不是保留孤儿**
历史行离开歌曲就无法展示（没有歌名、封面、可播放地址）。保留只会得到一堆孤儿：
列表 COUNT 虚高、JOIN 出空行。读查询另外 `INNER JOIN tb_song`，即使外键被绕过
（旧 dump 恢复）也不会返回孤儿行。

**索引**
`(user_id, song_id)` 唯一键承担去重与「按歌查」；`(user_id, last_played_at)` 承担
列表的 `WHERE user_id=? ORDER BY last_played_at DESC`，避免 filesort。

## 3. 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/play-history/record` | 上报一次有效播放 |
| POST | `/play-history/page` | 分页查询自己的历史 |
| GET | `/play-history/recent?limit=` | 最近 N 首（上限 50） |
| DELETE | `/play-history/{historyId}` | 删除单条 |
| DELETE | `/play-history/clear` | 清空自己的历史 |

- 全部要求登录。`/play-history/` 已登记到 `application.yml` 的 `ROLE_USER` 权限前缀。
- **任何接口都不接受 userId 参数**。当前用户由 `PlaylistAccessService.requireUserId()`
  从 JWT + ThreadLocal 取，游客 401，管理员 403（没有该前缀权限）。
- 不属于自己的 `historyId` 与不存在的 `historyId` 统一 404 —— 若前者返回 403，
  这个接口就成了「某个 id 是否存在」的探测器。
- 刻意**没有**做「播放历史统计」接口：前端没有这个展示需求，加了就是无用的攻击面。

### ⚠️ 路径坑

`RolePermissionManager.hasPermission` 用的是 `requestURI.startsWith(prefix)`，前缀是
`/play-history/`（带斜杠）。因此 `DELETE /play-history` 不匹配、会 403。
改成 `/play-history/clear` 后所有接口都落在同一前缀下。
`PlayHistoryEndpointSecurityTest` 用真实 `WebConfig` 跑 MockMvc，专门钉住这件事。

## 4. 有效播放判定

判定在前端（`vibe-music-client/src/utils/playHistory.ts` 的 `PlaybackTracker`），
后端只做边界校验。理由：服务端无法感知音频是否真的出声，把阈值判定放在服务端
只能变成「客户端说有效就有效」的假验证；放在前端至少是真实播放器事件驱动的。

**同时满足两条才算一次有效播放：**

1. 累计「真正在播」的时间 ≥ `min(5s, 歌曲时长 × 50%)`；
2. 当前播放位置 ≥ `min(5s, 上述阈值)`。

> **阈值从 30 秒降到 5 秒（2026-10-20 真实浏览器验证后调整）**
>
> 30 秒的初衷是排除误点，但实测的用户行为是：在推荐页点一首、听十几秒觉得不对就切下一首。
> 这类用户听了一整晚，「最近播放」仍然是空的 —— 功能在体感上等于「坏了」。
> 5 秒仍能排除点开就走 / 页面加载自动触发（那只有 0～2 秒），
> 同时让「听几句就切歌」被记录下来。主流音乐 App 的常见区间是 5～30 秒，
> 取偏小的值更符合「最近播放」的用途：它记的是「我碰过这首歌」，不是「我听完了」。
>
> 回归用例：`listening for about ten seconds then switching still records the song`。

第二条的必要性：只有时长没有位置，用户「播 30 秒然后拖回开头」会被重复计；
只有位置没有时长，「直接拖到 1 分钟」也会被误判。位置门槛同样受阈值约束，
否则 15 秒的歌永远到不了 20 秒位置 —— 「怎么听都记不上」。

**这些情况不会记录：**

| 场景 | 为什么 |
|---|---|
| 页面刚加载 / 播放器初始化 | `start` 不产生记录，只有 `tick` 越过阈值才记录 |
| 点开就关掉 | 累计时长不足 |
| 拖动进度条到中间 | 位置跳变不计入时长（只累加 `0 < delta ≤ 2s` 的增量） |
| 暂停期间 | `playing=false` 时不累加 |
| 浏览器后台节流后一次跳几分钟 | 单次增量上限 2 秒，那些时间用户没在听 |
| 播放失败（404 / 网络断 / 自动播放被拦） | `play()` 的 reject 与 `error` 事件都会 `fail()`，丢弃本次尝试 |
| 游客 | 不上报（后端也会 401） |

## 5. 去重 / 幂等 / 并发

| 层 | 手段 |
|---|---|
| 前端单次播放 | `recorded` 标记：一次播放尝试只上报一次；继续播 / 单曲循环都不会再报 |
| 前端切歌 | `loadTrack` 里 `start(id, true)` 重置 tracker，新 `playId`；旧歌的累计时长不会算到新歌 |
| 网络重试 | `playId` 幂等键，重试复用同一个值 |
| 数据库 | 唯一键 `(user_id, song_id)`：并发重复请求最终只留一行 |
| 计数 | `play_count = play_count + 1` 由数据库原子自增，不用「先读后写」（并发会丢计数） |
| 幂等条件 | `UPDATE ... WHERE play_id IS NULL OR last_play_id <> #{playId}`：同一 playId 的第二次上报返回 0，不计数 |

### 为什么不是「insert-if-absent，再条件 update」两条语句（2026-10-20 修正）

最初的实现用 `INSERT ... ON DUPLICATE KEY UPDATE id = id` 的**返回值**判断
「是不是首次插入」：返回 1 就 `return true`，否则走累加。

**这在真实 MySQL 上是坏的。** MySQL Connector/J 默认使用 found-rows 语义
（`useAffectedRows=false`），「命中行但没有实际改动」也返回 **1**，于是
「首次插入」的判断恒为真 —— 播放次数永远停在 1，而接口仍然返回 `counted=true`。
H2 在这种情况下返回 0，所以当时 565 个测试全绿，问题只在真实库上暴露。

**现行实现**（`PlayHistoryService#recordPlay`）：

1. 裸 `INSERT`（`PlayHistoryMapper#insertNew`），靠唯一键抛
   `DuplicateKeyException` 识别「已有行」—— 标准异常，两种数据库语义一致；
2. 冲突时走带幂等条件的 `UPDATE`（`incrementPlay`）；
3. UPDATE 返回 0 时用 `exists()` 区分「被幂等键拦下」与「行在两步之间被删了」，
   后者重试一次插入。

**教训**：affected-rows 的语义依赖驱动配置，不能拿来当业务判断依据。
回归测试 `replayingTheSameSongAlwaysIncrementsTheCount` 直接断言「计数必须真的涨」，
不依赖任何 affected-rows 行为。

## 6. 前端

| 文件 | 职责 |
|---|---|
| `utils/playHistory.ts` | `PlaybackTracker`（纯函数，无 Vue 依赖）+ 阈值计算 + playId 生成 |
| `api/playHistory.ts` | 4 个接口封装；上报带 `X-Silent-Request` |
| `hooks/useAudioPlayer.ts` | 把 play / pause / seek / timeupdate / ended / error 接到 tracker |
| `pages/play-history/index.vue` | 最近播放页：分页、加载中、空、失败重试、删除、清空二次确认 |
| `layout/components/footer/components/recent-plays.vue` | 播放器右侧「刚刚听过」浮层（懒加载，与播放队列分开） |

**不阻塞播放**：上报是 fire-and-forget，且 `PlaybackTracker` 内部显式
`catch` 掉 promise rejection —— `void promise` 只会丢掉返回值，rejection 仍会冒成
`unhandledrejection`（浏览器控制台报错）。这一条是被测试逼出来的：
`a rejected report does not make the tracker report the same play twice` 最初就是红的。

**失败静默**：`http.ts` 识别 `X-Silent-Request`（或 URL 含 `/play-history/record`），
不弹 NProgress、不弹全局错误提示，只 `console.warn`。用户点播放是为了听歌，
不是为了看「记录失败」的红字。

**旧事件清理**：`onMounted` 注册的 `ended` 监听是 `onEnded`（内部先结算 tracker 再
`nextTrack`），`onUnmounted` 移除**同一个引用**。原实现注册 `nextTrack` 并移除 `nextTrack`，
改成 `onEnded` 后若不移除同名引用，会留下悬挂监听。

### 行内不能是 `<button>`（2026-10-20 浏览器实测发现）

「最近播放」每一行既要「点整行播放」，又要在歌手 / 专辑上放 `RouterLink`。
最初把整行写成 `<button>`，结果是**点歌曲名会跳到专辑页而不是播放** ——
`<a>` 嵌套在 `<button>` 里是无效 HTML，浏览器会把行拆开，点击落到链接上。
`@click.stop` 只解决了「点链接不触发播放」，解决不了「点空白处落到链接」。

改成 `div[role="button"][tabindex="0"]` + `@keydown.enter/space`，键盘可达性不变。

⚠️ 另外：**模板注释不能写在 `v-for` 内部**。放在 `<li>` 里的 `<!-- -->`
会被 Vue 当作循环内容渲染成文本节点，实测污染了无障碍名称（`listitem` 的名字变成整段注释）。
注释要放在 `v-for` 元素之外。

## 7. 与 AI 画像的集成

详见 `agent-personalization.md` §2.1.1。要点：

- 播放历史进 `played_song_count` / `recent_play_artists` **两个独立字段**，
  绝不写入 `top_artists` / `top_styles`（那两个只由收藏与自建歌单产生）。
- `recent_play_artists` 要求一位歌手 **≥2 首不同歌曲**被播放过；
  SQL 按不同歌曲数排序而非总播放次数，单曲循环进不来。
- 注入提示词时隐式信号单独成段，显式标注「只能作为弱参考，不能据此断定用户喜欢什么」。
- 手动偏好优先级不变：用户显式排除的歌手不会被播放数据加回。
- 没有引入任何固定权重或衰减算法。

## 8. 测试

| 文件 | 覆盖 |
|---|---|
| `playhistory/PlayHistoryServiceTest`（25 例） | 查询自己的历史、用户隔离、未登录 401、非 USER 角色 401、重复播放不重复建行、playId 幂等、**重复播放必须真的累加计数**、已有行走累加分支、并发 16 次只留一行且计数不丢、无效输入、删除只能删自己的、清空不影响他人、空结果、分页无重叠且可重复、非法分页、歌名筛选、删歌级联、孤儿行不返回、recent 边界、删号级联 |
| `playhistory/PlayHistoryEndpointSecurityTest`（2 例） | 用真实 `WebConfig` + `LoginInterceptor` 跑 MockMvc：游客 5 个端点全 401；有效 token 能进且身份正确；token 失效 401；管理员 403 |
| `agent/PlayHistoryProfileTest`（9 例） | 播放不写入 top_artists、单曲循环不成偏好、无历史时行为不变、提示词弱参考措辞、只有播放也注入、播放不覆盖手动排除、用户间不串、指纹随播放变化、删歌后统计下降 |
| `vibe-music-client/tests/play-history.test.mjs`（27 例） | 阈值、开始不记录、越阈值只记一次、拖进度条、回拖、暂停累计、暂停不累计、后台跳变、短歌、切歌隔离、连续切歌、重播新键、单曲循环、失败丢弃、失败后重新计时、dispose、上报失败不重复、每尝试独立 playId、5 个 API 的路径与载荷 |

## 9. 已知限制 / 后续

1. **历史不设保留上限**。每个用户最多「曲库歌曲数」行，实际远小于此。
   若将来需要「只保留最近 N 条」，应作为独立清理任务，不要在写入路径上做删除。
2. **播放进度只留痕未使用**。要做「续播」时再在 VO 里加 `lastPositionSeconds` 并明确产品语义
   （续播 vs 从头播）。
3. **没有「最近播放」的全局搜索**。当前只支持按歌名筛选自己的历史（`songName` 参数），
   前端暂未使用。加之前先确认产品是否真的需要。
4. **画像只用了两个隐式信号**（不同歌曲数、反复播放的歌手）。风格维度的隐式信号
   刻意没做：播放某风格的歌不代表喜欢该风格（可能只是电台随机播到）。
   要做必须先想清楚口径，不能直接照搬收藏的口径。
5. **上报失败不重试**。播放历史是锦上添花，堆重试队列（尤其 localStorage 队列）
   会带来跨账号串数据、容量失控等新问题。当前选择「丢一次记录，不影响播放」。
