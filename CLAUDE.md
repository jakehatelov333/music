# CLAUDE.md

此文件为 Claude Code (claude.ai/code) 在本仓库中工作时提供指导。

## 项目概览

Vibe Music 是一个全栈音乐流媒体平台，包含三个独立的子项目（无父级 POM/工作空间）：

| 子项目 | 类型 | 端口 | 核心技术 |
|---|---|---|---|
| `vibe-music-server/` | Spring Boot 3 后端 | 8080 | Java 17, Maven, MyBatis-Plus 3.5.9, MySQL, Redis, MinIO, LangChain4j 0.36.2 |
| `vibe-music-admin/` | 管理端 SPA | 8089 | Vue 3, Vite 6, Pinia, Element Plus, Tailwind CSS, TypeScript, vue-pure-admin |
| `vibe-music-client/` | 用户端 SPA | 8090 | Vue 3, Vite 6, Pinia, Element Plus, Tailwind CSS, TypeScript, ArtPlayer |

每个子项目是独立的应用，拥有各自的 `package.json` / `pom.xml`，不共享构建工具。

## 常用命令

### 服务端 (`vibe-music-server/`)

```bash
cd vibe-music-server
mvn spring-boot:run              # 启动开发服务器 (端口 8080)
mvn clean package -DskipTests    # 构建 JAR
mvn test                          # 运行测试（目前仅有上下文加载和 Redis 连通性两个基础测试）
```

### 管理端 (`vibe-music-admin/`)

```bash
cd vibe-music-admin
pnpm install         # 安装依赖（preinstall 钩子强制使用 pnpm）
pnpm dev             # 启动开发服务器 (端口 8089)，NODE_OPTIONS=--max-old-space-size=4096
pnpm build           # 生产构建，NODE_OPTIONS=--max-old-space-size=8192
pnpm build:staging   # 预发布构建 (--mode staging)
pnpm preview         # 预览生产构建
pnpm lint            # ESLint + Prettier + Stylelint 全量检查（含自动修复）
pnpm lint:eslint     # 仅 ESLint（含自动修复）
pnpm lint:prettier   # 仅 Prettier 格式化
pnpm lint:stylelint  # 仅 Stylelint
pnpm typecheck       # tsc --noEmit && vue-tsc --noEmit --skipLibCheck
pnpm svgo            # SVG 优化
pnpm clean:cache     # 清理 node_modules + pnpm-lock + 缓存 后重装
```

### 用户端 (`vibe-music-client/`)

```bash
cd vibe-music-client
pnpm install         # 安装依赖 (要求 pnpm ≥ 7)
pnpm dev             # 启动开发服务器 (端口 8090)
pnpm build           # 生产构建
pnpm build:test      # 测试环境构建
pnpm preview         # 预览生产构建
pnpm lint            # ESLint 检查
pnpm lint:fix        # ESLint 自动修复
pnpm format          # Prettier 格式化
pnpm type-check      # vue-tsc --noEmit
```

## 架构

### 后端 (`cn.edu.seig.vibemusic`)

**技术栈：** Spring Boot 3.3.7, Java 17, MyBatis-Plus 3.5.9, Alibaba Druid 1.2.18, LangChain4j 0.36.2, java-jwt 4.4.0, MinIO 8.5.9

**统一响应格式：**
- `Result<T>`（`result/Result.java`）：`code` 0=成功 1=失败，`message` 提示信息，`data` 响应数据。提供 `Result.success(data)` / `Result.error(msg)` 等静态工厂方法。
- `PageResult`（`result/PageResult.java`）：分页响应，包含 `total`、`pageNum`、`pageSize` 和 `list`。

**认证与授权：**
- JWT（Auth0 java-jwt 4.4.0），密钥 `VIBE_MUSIC`，有效期 6 小时。Token 存储在 Redis（database 1）中，key 为 token 本身。
- `LoginInterceptor` 拦截所有请求（`/**`），`WebConfig` 中排除登录/注册/验证码/重置密码/公开查询端点（见下方）。拦截器内使用 `AntPathMatcher` 对 5 个通配符路径做二次放行（未登录用户可访问）：`/playlist/getPlaylistDetail/**`、`/artist/getArtistDetail/**`、`/song/getAllSongs`、`/song/getSongDetail/**`、`/test/**`。
- CORS OPTIONS 预检请求直接放行。
- 角色权限：`RolePermissionManager.hasPermission(role, requestURI)` 基于路径前缀匹配（`application.yml` 中 `role-path-permissions` 配置）。`ROLE_ADMIN` 可访问 `/admin/**`，`ROLE_USER` 可访问 `/user/**`、`/playlist/**`、`/artist/**`、`/song/**`、`/favorite/**`、`/comment/**`、`/banner/**`、`/feedback/**`、`/test`。
- `JwtUtil` 负责生成/解析 Token，`ThreadLocalUtil` 在每个请求生命周期中持有当前用户 claims，请求结束后在 `afterCompletion` 中清除。

**WebConfig 中排除拦截器的路径（`/admin/login`、`/admin/logout`、`/admin/register`、`/user/login`、`/user/logout`、`/user/register`、`/user/sendVerificationCode`、`/user/resetUserPassword`、`/banner/getBannerList`、`/playlist/getAllPlaylists`、`/playlist/getRecommendedPlaylists`、`/playlist/getPlaylistDetail/**`、`/artist/getAllArtists`、`/artist/getArtistDetail/**`、`/song/getAllSongs`、`/song/getRecommendedSongs`、`/song/getSongDetail/**`）。

**包结构：**
- `controller/` — 13 个 REST 控制器（Admin、User、Song、Artist、Playlist、Comment、Banner、Genre、Style、Feedback、UserFavorite、PlaylistBinding、Ai）
- `service/` — 服务接口及实现
- `mapper/` — MyBatis-Plus Mapper 接口及 XML 映射文件（`@MapperScan("cn.edu.seig.vibemusic.mapper")`）
- `model/entity/` — 13 个实体类（Admin、Artist、Banner、ChatMessage、Comment、Feedback、Genre、Playlist、PlaylistBinding、Song、Style、User、UserFavorite），映射到 `tb_` 前缀表
- `model/dto/` — 请求 DTO
- `model/vo/` — 响应 VO
- `config/` — 配置类（见下方）
- `interceptor/` — `LoginInterceptor`
- `handler/` — `GlobalExceptionHandler` 全局异常处理
- `memory/` — LangChain4j 聊天记忆 MySQL 持久化（`MysqlChatMemoryStore`、`ChatMemoryHolder`）
- `result/` — `Result` 和 `PageResult` 统一 API 响应格式
- `constant/` — `PathConstant`（集中管理所有路径常量）、`JwtClaimsConstant`、`MessageConstant`
- `enumeration/` — 6 个枚举（`BannerStatusEnum`、`CommentTypeEnum`、`FavoriteTypeEnum`、`LikeStatusEnum`、`RoleEnum`、`UserStatusEnum`）
- `tool/` — `MusicTools`（7 个 `@Tool` 工具方法供 AI 调用）
- `util/` — `JwtUtil`、`ThreadLocalUtil`、`BindingResultUtil`、`TypeConversionUtil`、`RandomCodeUtil`

**核心配置类：**

| 配置类 | 职责 |
|---|---|
| `CorsConfig` | 开发环境全开放 CORS，暴露 `Authorization` 头，允许凭证 |
| `WebConfig` | 注册 `LoginInterceptor` + 排除路径；`CharacterEncodingFilter` 强制 UTF-8 |
| `RedisConfig` | `RedisTemplate`（Jackson2JsonRedisSerializer 序列化）；`RedisCacheManager`（TTL 6 小时，覆盖 yml 中 `spring.cache.redis.time-to-live: 600000ms` 的自动配置） |
| `MyBatisPlusConfig` | `@MapperScan` + `PaginationInnerInterceptor` 分页插件 |
| `MinioConfig` | 从 yml 读取端点/凭证创建 `MinioClient` Bean |
| `RolePathPermissionsConfig` | `@ConfigurationProperties(prefix="role-path-permissions")` 绑定 yml 权限配置 |
| `RolePermissionManager` | 路径前缀匹配的角色权限检查 |
| `LangChain4jConfig` | 创建 `QwenChatModel`、`QwenStreamingChatModel`、`ChatMemoryProvider`（每会话 20 条消息窗口 + MySQL 持久化）、`MusicAssistant`（带工具）、`MusicAssistantNoTools`（无工具） |
| `LoggingQwenChatModel` | `QwenChatModel` 装饰器，记录所有 API 调用用于调试 |
| `ChatMemoryCleanupFilter` | 请求结束后 `ChatMemoryHolder.clear()` 清理 ThreadLocal |

**AI 集成（LangChain4j）：**

- 使用 LangChain4j 0.36.2 + `langchain4j-dashscope` 对接阿里云 DashScope，模型 `qwen-max`。
- `MusicAssistant` 接口定义了两个方法：`chat()` 返回 `String`（阻塞式），`chatStream()` 返回 `TokenStream`（流式）。`AiServices` 根据返回类型自动选择 `QwenChatModel` 或 `QwenStreamingChatModel`。
- `MusicAssistantNoTools` 无工具版本，用于排查工具调用问题。
- `@SystemMessage` 定义 AI 角色为"博学的音乐达人"，要求先用工具搜索再回答，不编造信息，最多尝试 2-3 次工具调用。
- `MusicTools` 曝光 7 个 `@Tool` 方法：`searchSongs`、`searchArtists`、`getSongsByStyle`、`getRandomSongs`、`getArtistDetail`、`searchSongsByArtist`、`searchPlaylists`。每个工具限制最多返回 10 条。
- `MysqlChatMemoryStore` 将对话历史持久化到 `tb_chat_message` 表，采用"全量删除 + 重新插入"模式同步 `MessageWindowChatMemory`（最多 20 条消息窗口）。SYSTEM 消息不持久化（由 `@SystemMessage` 每次注入）。
- `ChatMemoryHolder` 使用 `ThreadLocal<Map<Object, ChatMemory>>` 保证同一请求内 ChatMemory 唯一，`ChatMemoryCleanupFilter` 在请求后清理。
- `AiController` 提供 9 个端点：阻塞对话、流式对话（SSE `text/event-stream`）、无工具对话、直连测试、全量/分页历史、清空会话、消息统计。流式端点使用 `SseEmitter`（120s 超时），事件名为 `token`/`done`/`error`。游客通过 Cookie `guest_chat_id` 识别，登录用户通过 JWT 识别。

**关键配置细节：**
- MySQL：`localhost:3306/vibe_music`，`root/1234`，Druid 连接池
- Redis：`127.0.0.1:6379`，database 1，无密码
- MinIO：`127.0.0.1:9005`，`minioadmin/minioadmin`，Bucket `vibe-music-data`
- 邮件：QQ SMTP `smtp.qq.com:465`（SSL），`2509680148@qq.com`
- 文件上传最大 100MB
- MyBatis-Plus 驼峰映射 + `tb_` 表前缀，日志输出 `StdOutImpl`
- SQL 建表脚本：`vibe-music-server/sql/vibe_music.sql`
- `logging.level.com.heima: debug` 是旧的包名残留，实际应根据需要改为 `cn.edu.seig.vibemusic`

### 管理端 (`vibe-music-admin/`)

- 基于 **vue-pure-admin** 模板构建。
- Vue 3 Composition API + `<script setup>`，TypeScript strict 模式未开启。
- Pinia 状态管理，`js-cookie` + `localforage` 用于认证持久化。
- Axios HTTP 请求，通过 Vite 代理 `/api` → `http://localhost:8080`（路径重写移除 `/api` 前缀）。`VITE_APP_BASE_API` 在 `.env.development` 中设为 `http://localhost:8080`。
- Vue Router hash 模式（`createWebHashHistory`），路由模块自动从 `src/router/modules/*.ts` 导入。
- 路由模块：`home`（`/welcome`）、`user`（`/user`）、`artist`（`/artist`）、`song`（`/song`）、`playlist`（`/playlist`）、`feedback`（`/feedback`）、`banner`（`/banner`）、`error`（`/error/403/404/500`）、`remaining`（`/login`、`/redirect`）。
- 路由守卫：检查 Cookie 中的 token + userInfo，验证 `roles`；白名单 `["/login"]`；隐藏首页模式 `VITE_HIDE_HOME=true` 时 `/welcome` 重定向 404；无权限跳 403。
- Tailwind CSS 暗色模式（`class` 策略），`preflight: false` 避免与 Element Plus 冲突；自定义颜色映射到 Element Plus CSS 变量。
- 使用 `@pureadmin/table` 表格组件。
- 视图目录按业务模块划分：`artist/`、`banner/`、`feedback/`、`playlist/`、`song/`、`user/`（每个含 `form/` 和 `utils/` 子目录）。
- 自定义指令：`auth`、`copy`、`longpress`、`optimize`、`perms`、`ripple`。
- 构建目标 `es2015`，chunk 大小警告阈值 4000KB。
- 环境文件：`.env`、`.env.development`、`.env.production`、`.env.staging`。
- 含 `Dockerfile`（Node 20 Alpine 构建 + Nginx Stable Alpine 运行，80 端口）。

### 用户端 (`vibe-music-client/`)

- Vue 3 Composition API + `<script setup>`，TypeScript strict 模式未开启。
- Pinia 状态管理，`pinia-plugin-persistedstate` 持久化 6 个 store（audio、menu、setting、theme、user）到 localStorage。
- **自动导入架构：** `unplugin-auto-import` 自动导入 `vue`、`vue-router`、`pinia` API 及 `src/utils/`、`src/stores/`、`src/hooks/` 下的导出；`unplugin-vue-components` 自动注册 `src/components/` 下的组件；`unplugin-icons` 自动按需加载 Iconify 图标（前缀 `icon-`）。
- Axios HTTP 请求，**baseURL 硬编码为 `http://localhost:8080`**（位于 `src/utils/http.ts`，不从环境变量读取），超时 20s；Bearer token 通过请求拦截器注入。
- Vue Router history 模式，路由列表：
  - `/` → 首页（`pages/index.vue`）
  - `/library` → 曲库
  - `/artist` → 歌手列表，`/artist/:id` → 歌手详情
  - `/playlist` → 歌单列表，`/playlist/:id` → 歌单详情
  - `/like` → 我的喜欢
  - `/user` → 用户中心
- Tailwind CSS 暗色模式，CSS 自定义属性定义主题色（默认 `#2a68fa`，store 默认 `#7E22CE`）。
- ArtPlayer 音乐播放器，音频状态由 `useAudioPlayer` hook 管理。
- `vue-i18n` 已安装但**未实际配置**——仅 Element Plus 使用中文本地化（`zhCn`）。
- `AiWidget.vue` 浮窗 AI 聊天组件，挂载在根布局中所有页面可用。支持流式输出（SSE，fetch + ReadableStream 逐字渲染）、拖拽、游客模式，发送新消息时自动取消旧请求防止串话。
- `src/api/ai.ts` — 4 个 API 函数：阻塞式 (`aiSimpleChat`)、流式 (`aiStreamChat`，原生 fetch 非 axios)、历史分页、清空/统计。流式 API 使用原生 fetch 因为 axios 不支持 `ReadableStream`。
- 环境文件：`.env.development`、`.env.production`、`.env.test`。
- Vite 代理配置从 `VITE_PROXY` 环境变量读取（开发环境为占位符，因为客户端直连 `http://localhost:8080` 不经过代理）。
