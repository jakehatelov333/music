# Vibe Music 未来演进计划书

## 前言

Vibe Music 目前是一个功能原型阶段的全栈音乐平台，核心 CRUD 功能完整，AI 聊天集成亮眼。但与真实生产环境相比，在安全性、可靠性、可观测性、用户体验、功能丰富度等方面存在显著差距。

本文档基于对三个子项目（server/admin/client）的深度分析，从**安全合规、架构演进、功能补齐、运维体系、体验优化**五个维度，按优先级逐项列出待改进点和实施方案。

---

## 一、安全与合规（最高优先级 — 上线前必须完成）

### 1.1 密钥与凭证管理

**现状问题：** 6 处硬编码密钥直接写在 `application.yml` 和 `JwtUtil.java` 中，已提交版本控制。

| 密钥 | 位置 | 风险等级 |
|---|---|---|
| MySQL 密码 `1234` | `application.yml:8` | 高 |
| QQ 邮箱授权码 | `application.yml:32` | 高（可发送邮件） |
| DashScope API Key | `application.yml:94` | 高（可产生费用） |
| MinIO 凭证 `minioadmin/minioadmin` | `application.yml:81-82` | 中 |
| JWT 签名密钥 `VIBE_MUSIC` | `JwtUtil.java:15` | 高（可伪造 Token） |

**改进方案：**
- 所有密钥迁移到环境变量，通过 `${ENV_VAR}` 占位符引用
- 本地开发使用 `.env` 文件（加入 `.gitignore`），生产环境通过 K8s Secret 或配置中心注入
- JWT 密钥改用 256 位随机字符串，通过环境变量注入
- 已在 Git 历史中的密钥需要轮换（尤其是邮箱授权码和 API Key）

### 1.2 密码安全

**现状问题：** 使用 **MD5 不加盐** 存储密码。MD5 已破解，无盐导致彩虹表可反查，相同密码产生相同哈希。

**改进方案：**
- 替换为 **bcrypt**（推荐）或 Argon2
- 迁移策略：新增 `password_hash` 和 `salt` 字段，后台逐步迁移旧密码
- BCrypt 工作因子设为 10-12

### 1.3 访问控制

**现状问题：**
- CORS 全开放（`allowedOriginPatterns("*")` + `allowCredentials(true)`）— 违反 CORS 规范
- 无任何速率限制 — 登录可暴力破解、AI API 可被刷费、邮箱验证码可被滥用
- 无 API 鉴权后的二次验证 — 删除操作无确认、敏感操作无审计

**改进方案：**
- CORS 按环境配置白名单（开发 `localhost:*`，生产 `https://*.vibemusic.com`）
- 引入 **bucket4j** 或 **resilience4j-ratelimiter** 对关键端点限流：
  - `/user/login` — 每 IP 每分钟 5 次
  - `/user/sendVerificationCode` — 每邮箱每天 5 次
  - `/test/simple/chat` — 每用户每分钟 10 次
- 验证码增加图形验证码（captcha）防机器调用

### 1.4 文件上传安全

**现状问题：** 无文件类型校验（可上传任意文件），`getOriginalFilename()` 未消毒（路径穿越风险）。

**改进方案：**
- 服务端校验文件类型（Magic Bytes 检测 + 扩展名白名单）
- 图片：仅允许 JPEG/PNG/WebP，限制尺寸
- 音频：仅允许 MP3/AAC/FLAC，限制时长
- 消毒文件名：只保留 UUID 部分，丢弃用户原始文件名
- 限制单文件上传大小（图片 5MB，音频 50MB）

### 1.5 日志脱敏

**现状问题：** `AiController.java` 将用户聊天完整内容写入日志，违反隐私。

**改进方案：**
- 用户聊天内容禁止写入日志（仅记录 chatId + 消息长度）
- 敏感字段（密码、Token、邮箱）在日志中脱敏
- 生产环境关闭 MyBatis SQL 日志（`StdOutImpl` → `Slf4jImpl` + INFO 级别）

---

## 二、架构演进（高优先级）

### 2.1 API 规范化与版本控制

**现状问题：** 无 API 版本前缀，命名风格不统一（动词路径如 `/song/getAllSongs` 而非 RESTful 风格）。

**改进方案：**
- 第一阶段：所有 API 增加 `/api/v1` 前缀，旧路径通过重定向兼容
- 第二阶段：路径改回 RESTful 风格：
  - `GET /api/v1/songs` 替代 `/song/getAllSongs`
  - `GET /api/v1/songs/{id}` 替代 `/song/getSongDetail`
  - `PATCH /api/v1/songs/{id}/audio` 替代 `/admin/updateSongAudio`
- 始终保留 `Result<T>` 统一响应格式

### 2.2 环境配置分离

**现状问题：** 后端仅一个 `application.yml`，无 dev/prod 分离；前端生产环境 API URL 为占位符 `localhost:3000`。

**改进方案：**
- Spring Boot profiles：
  - `application-dev.yml` — 本地开发配置
  - `application-prod.yml` — 生产环境配置
  - `application-test.yml` — 测试环境配置
- 前端 `.env.production` 改为真实域名
- 敏感配置通过环境变量注入，不写死在 yml 中

### 2.3 配置类重构

**现状问题：** `application.yml` 中 `mybatis-plus.type-aliases-package: cn.edu.seig.pojo` 指向不存在的包。`logging.level.com.heima: debug` 为旧包名残留。

**改进方案：**
- 修正为正确的包名：`cn.edu.seig.vibemusic.model.entity`
- 修正日志级别配置为正确的包名
- 统一缓存 TTL 配置（yml 说 10 分钟，`RedisConfig.java` 覆盖为 6 小时）——按业务需求统一

### 2.4 事务管理

**现状问题：** `@Transactional` 仅在 AI 相关代码中使用，多表写操作无事务保护。

**改进方案：**
- 在所有多表写操作的方法添加 `@Transactional`：
  - `SongServiceImpl.addSong/updateSong/deleteSong/deleteSongs`
  - `ArtistServiceImpl.deleteArtist`
  - `PlaylistServiceImpl.deletePlaylist`
  - `UserServiceImpl.deleteAccount`
- `CommentServiceImpl.likeComment/cancelLikeComment` 使用乐观锁防并发

### 2.5 异常处理完善

**现状问题：** `GlobalExceptionHandler` 仅捕获 2 种特定异常，通用 `Exception.class` 处理器被注释掉，未处理异常会暴露内部细节。

**改进方案：**
- 新增异常处理器：
  - `HttpMessageNotReadableException` → 400 参数格式错误
  - `MissingServletRequestParameterException` → 400 缺少必填参数
  - `MethodArgumentTypeMismatchException` → 400 参数类型错误
  - `MaxUploadSizeExceededException` → 413 文件过大
  - `AccessDeniedException` → 403 无权限
  - `HttpRequestMethodNotSupportedException` → 405 方法不允许
  - `Exception` → 500 服务器内部错误（隐藏内部细节，记录 traceId）

### 2.6 输入验证补全

**现状问题：** 8 个 DTO（ArtistAddDTO、ArtistUpdateDTO、SongAddDTO、SongUpdateDTO 等）完全没有验证注解。

**改进方案：**
- 按字段补全 `@NotBlank`、`@Size`、`@Pattern` 等验证注解
- 所有接收 DTO 的 Controller 方法添加 `@Valid`（或 `@Validated`）
- `BindingResult` 参数紧跟在 `@Valid` 参数之后

---

## 三、数据库与搜索优化

### 3.1 Schema 优化

**现状问题：**
- `tb_song.album` 是字符串列而非独立表——无法管理专辑信息
- 搜索依赖 `LIKE '%term%'`，无 FULLTEXT 索引
- `tb_playlist` 无索引
- 缺少多张业务表

**改进方案：**
- 新增索引：
  - `tb_song`: `(artist_id, album)` 复合索引，`name` FULLTEXT 索引
  - `tb_playlist`: `(title)` INDEX
  - `tb_user_favorite`: `(user_id, type)` 复合索引
  - `tb_comment`: `(song_id, type)`，`(playlist_id, type)` 复合索引
- 引入 MySQL FULLTEXT 或 Elasticsearch 做全文搜索
- 建议引入 Elasticsearch 做专业的音乐内容搜索（分词、拼音搜索、模糊匹配）

### 3.2 缓存策略优化

**现状问题：** `@CacheEvict(allEntries = true)` 粗粒度失效导致任何写操作清空整个缓存区；配置中 TTL 冲突（yml 10 分钟 vs RedisConfig 6 小时）。

**改进方案：**
- 精准缓存失效：`@CacheEvict(key = "#songDTO.xxx")` 替代 `allEntries = true`
- 统一 TTL 策略：歌曲/艺术家列表缓存 1 小时，用户信息缓存 30 分钟，验证码缓存 5 分钟
- 引入 `@CachePut` 用于原子更新操作
- 推荐歌曲的 Redis List 缓存改为定时任务刷新（而非请求时生成）

---

## 四、监控与可观测性（上线前必须完成）

### 4.1 健康检查

**现状问题：** 无 `spring-boot-starter-actuator`，无健康检查端点。当服务宕机时运维侧无法感知。

**改进方案：**
- 引入 `spring-boot-starter-actuator`
- 开放 `/actuator/health/liveness` 和 `/actuator/health/readiness`（用于 K8s probe）
- 配置敏感端点（如 `/actuator/env`）需认证访问

### 4.2 指标与监控

**改进方案：**
- 引入 Micrometer + Prometheus 导出关键指标：
  - JVM 内存/GC
  - HTTP 请求量/耗时/错误率
  - 数据库连接池状态
  - Redis 连接状态
- 前端接入 Sentry 进行错误追踪
- 前端接入百度统计 / Google Analytics 进行用户行为分析

### 4.3 日志体系

**改进方案：**
- 引入 Logback JSON encoder，生产环境输出结构化日志
- 每请求生成 traceId（MDC），贯穿整个请求链路
- 错误日志包含 stack trace，info 日志只包含业务关键信息
- 日志采集：Filebeat → Elasticsearch → Kibana / Grafana Loki

---

## 五、CI/CD 与容器化

### 5.1 容器化

**现状问题：** 仅管理端有 Dockerfile，后端无 Dockerfile，无 docker-compose 编排。

**改进方案：**
- 后端 Dockerfile：多阶段构建（Maven 构建 + JRE 运行），`openjdk:17-slim` 基础镜像
- 用户端 Dockerfile：vite 构建 + nginx 运行
- `docker-compose.yml` 编排全部服务：MySQL、Redis、MinIO、后端、管理端、用户端、Nginx 反向代理
- 添加 `.dockerignore` 文件（排除 node_modules、target、.git 等）

### 5.2 CI/CD 流水线

**改进方案（GitHub Actions）：**
- 后端 CI：
  - PR 触发：mvn test + 编译检查
  - main 分支合并：mvn test + 构建 Docker 镜像 + 推送镜像仓库
- 前端 CI：
  - PR 触发：pnpm lint + pnpm typecheck
  - main 分支合并：pnpm build + 构建 Docker 镜像 + 推送镜像仓库
- CD：使用 ArgoCD / GitHub Actions 部署到 K8s 集群

---

## 六、测试体系建设

### 6.1 后端测试

**现状问题：** 仅 2 个空壳测试，覆盖率为 0。

**改进方案（分阶段）：**
- **第一阶段**：Service 层单元测试（Mock Mapper）
  - `UserServiceImpl`、`SongServiceImpl`、`PlaylistServiceImpl` 等重点服务
  - 覆盖率目标：60%
- **第二阶段**：Controller 层集成测试（MockMvc）
  - 登录/注册流程、CRUD 操作、权限验证
- **第三阶段**：端到端测试（Testcontainers）
  - 真实 MySQL + Redis + MinIO 环境
  - 核心业务流程全覆盖

### 6.2 前端测试

**改进方案：**
- 引入 Vitest 做组件单元测试
- 引入 Playwright 做关键流程 E2E 测试：
  - 用户端：从首页 → 播放歌曲 → 收藏 → 评论
  - 管理端：登录 → 添加歌曲 → 编辑 → 删除

---

## 七、功能补全 — 对标主流音乐平台

### 7.1 用户侧核心功能

| 功能 | 优先级 | 说明 |
|---|---|---|
| **播放历史** | P0 | 新增 `tb_play_history` 表，记录每首歌的播放时间/进度 |
| **用户播放列表** | P0 | 用户可自建歌单，与管理员歌单区分 |
| **歌词同步** | P1 | 前端 `useAudioPlayer.ts` 中歌词代码已注释，需恢复并实现 LRC 解析 + 滚动高亮 |
| **排行榜** | P1 | 按周/月/总榜统计播放量 Top 100 |
| **社交功能** | P2 | 关注/粉丝 (`tb_user_follows`)、分享链接 |
| **协同歌单** | P2 | 多人协作编辑歌单 |
| **专辑页面** | P2 | `tb_album` 表，专辑封面 + 曲目列表 + 发行信息 |
| **通知系统** | P2 | `tb_notification` 表，点赞/评论/关注通知，前端轮询或 WebSocket 推送 |
| **离线下载** | P3 | Service Worker 缓存 + IndexedDB 存储音频 |
| **多语言** | P3 | `vue-i18n` 已安装但未配置，按计划激活 |

### 7.2 管理端功能

| 功能 | 优先级 | 说明 |
|---|---|---|
| **数据统计仪表盘** | P0 | 今日新增用户/歌曲/评论、DAU、播放量趋势图表 |
| **操作审计日志** | P1 | `tb_audit_log` 表，记录管理员的增删改操作 |
| **搜索增强** | P1 | 支持高级搜索（日期范围、多条件组合） |
| **导出功能** | P2 | 用户列表/歌曲列表导出 CSV/Excel |

### 7.3 推荐引擎升级

**现状问题：** 推荐逻辑为基于风格的简单匹配，无个性化。

**改进方向：**
- **短期（协同过滤）**：基于用户收藏和播放历史的 item-based / user-based CF
- **中期（内容推荐）**：基于歌曲风格、BPM、语言、年代的标签推荐
- **长期（深度学习）**：向量化歌曲特征，使用向量数据库（Milvus）做召回，精排模型打分

---

## 八、音频体验优化

### 8.1 音频流媒体服务

**现状问题：** 音频 URL 直接从 MinIO 暴露，无服务器端鉴权、无 Range 支持控制。

**改进方案：**
- 后端代理音频请求：`GET /api/v1/songs/{id}/stream`，鉴权后重定向 MinIO 预签名 URL 或流式传输
- 后端添加 Range 头处理，支持拖动进度条时的 HTTP Range 请求
- MinIO 预签名 URL 有效期 30 分钟，定期刷新

### 8.2 音频质量

**改进方案：**
- 前端播放器增加音质选择（标准 128kbps / 高清 320kbps / 无损 FLAC）
- 上传时服务端用 FFmpeg 转码为多种码率存储
- 支持均衡器预设（摇滚/流行/古典/自定义）

---

## 九、前端体验优化

### 9.1 错误处理与 Token 管理

**现状问题：** 管理端 token 刷新逻辑被注释；API 调用手动重复添加 token；硬编码默认凭据。

**改进方案：**
- 管理端修复 `handRefreshToken` 或简化为 token 过期直接跳转登录
- API 模块统一通过 `PureHttp` 拦截器管理 token，移除每个函数中手动拼接的代码
- 移除 `login/index.vue` 中硬编码的 `admin_1/123456abc`
- 客户端增加静默 token 刷新（在 401 拦截器中用 refresh token 换新 token）

### 9.2 可访问性 (a11y)

**改进方案：**
- 图片添加有意义的 `alt` 文本
- 表单控件关联 `label`
- 自定义组件（Tab 切换）添加 `role="tab"`、`aria-selected` 等 ARIA 属性
- 键盘导航支持：Tab 切换焦点、Enter 激活按钮、Escape 关闭弹窗

### 9.3 移动端适配

**改进方案：**
- 管理端：表格在小屏折叠为卡片视图，表单字段堆叠排列
- 用户端：播放器底部栏适配移动端触摸操作，加大点击热区
- 搜索框在移动端全宽展开

### 9.4 性能优化

**改进方案：**
- 管理端路由开启 `keep-alive` 缓存已访问页面
- 用户端图片使用 WebP 格式 + 多尺寸响应式图片 (`srcset`)
- 首屏关键 CSS 内联，非关键 CSS 异步加载
- Vite 构建开启 `rollupOptions.manualChunks` 精细化分包（管理端目前未配置）

---

## 十、实施路线图

### 第一阶段：安全底线（1-2 周）— 上线前必修

1. 密钥全部迁移到环境变量
2. MD5 → bcrypt 密码哈希迁移
3. CORS 配置按环境区分
4. API 速率限制（登录、注册、邮件、AI 聊天）
5. 文件上传类型/大小限制
6. 日志脱敏 + 生产环境关闭 SQL 日志
7. 引入 Actuator + 健康检查端点
8. 补全 `GlobalExceptionHandler`
9. 补全 DTO 验证注解

### 第二阶段：架构加固（2-3 周）

1. 环境配置分离（dev/test/prod profiles）
2. 多表写操作添加 `@Transactional`
3. 数据库索引优化 + 缺失表补充
4. 缓存策略精细化
5. Dockerfile + docker-compose 完整编排
6. GitHub Actions CI/CD 流水线
7. 后端 Service 层单元测试

### 第三阶段：功能补全（4-6 周）

1. 播放历史 + 排行榜
2. 歌词同步（恢复 + LRC 解析）
3. 管理端数据统计仪表盘
4. 音频流鉴权代理
5. 修复前端 token 管理
6. 搜索增强（FULLTEXT 索引）

### 第四阶段：体验与竞争力（8-12 周）

1. 推荐引擎升级（协同过滤）
2. 用户自建歌单
3. 社交功能（关注/分享）
4. 通知系统
5. Elasticsearch 搜索
6. Prometheus + Grafana 监控面板
7. 前端 E2E 测试
8. 可访问性 + 移动端深度适配

---

## 附录：关键指标对比

| 维度 | 当前状态 | 目标状态 |
|---|---|---|
| 测试覆盖率 | 0% | 后端 ≥ 60%，前端核心流程 E2E |
| 密码存储 | MD5（无盐） | bcrypt (work factor 12) |
| 密钥管理 | 6 处硬编码 | 全部环境变量 |
| 速率限制 | 无 | 关键端点全覆盖 |
| API 监控 | 无 | Actuator + Prometheus + Grafana |
| CI/CD | 无 | GitHub Actions 自动化 |
| 搜索性能 | MySQL LIKE | MySQL FULLTEXT → Elasticsearch |
| 搜索延时 | 全表扫描 | < 200ms (ES) |
| 音频鉴权 | MinIO 直接暴露 | 后端代理 + 预签名 URL |
| 离线缓存 | 无 | PWA + Service Worker |
| 协同过滤 | 无 | item-based CF |
