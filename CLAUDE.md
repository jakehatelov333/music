# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

Vibe Music 是一个全栈音乐流媒体平台，包含三个独立子项目（无父级 POM/工作空间）：

| 子项目 | 类型 | 端口 | 核心技术 |
|---|---|---|---|
| `vibe-music-server/` | Spring Boot 3 后端 | 8080 | Java 17, Maven, MyBatis-Plus 3.5.9, MySQL, Redis, MinIO, LangChain4j 0.36.2 |
| `vibe-music-admin/` | 管理端 SPA | 8089 | Vue 3, Vite 6, Pinia, Element Plus, Tailwind CSS, vue-pure-admin |
| `vibe-music-client/` | 用户端 SPA | 8090 | Vue 3, Vite 6, Pinia, Element Plus, Tailwind CSS, ArtPlayer |

**远程仓库：** `github.com/jakehatelov333/music`（当前分支 `test1`）

## 常用命令

### 服务端 (`vibe-music-server/`)

```bash
cd vibe-music-server
mvn spring-boot:run              # 启动开发服务器 (端口 8080)
mvn clean package -DskipTests    # 构建 JAR
mvn test                          # 运行测试（基础测试）
```

### 管理端 (`vibe-music-admin/`)

```bash
cd vibe-music-admin
pnpm install         # 安装依赖
pnpm dev             # 启动开发服务器 (端口 8089)
pnpm build           # 生产构建
pnpm lint            # ESLint + Prettier + Stylelint 全量检查
pnpm typecheck       # tsc --noEmit && vue-tsc --noEmit --skipLibCheck
```

### 用户端 (`vibe-music-client/`)

```bash
cd vibe-music-client
pnpm install         # 安装依赖
pnpm dev             # 启动开发服务器 (端口 8090)
pnpm build           # 生产构建
pnpm lint            # ESLint 检查
pnpm lint:fix        # ESLint 自动修复
pnpm format          # Prettier 格式化
pnpm type-check      # vue-tsc --noEmit
```

## 架构

### 后端 (`cn.edu.seig.vibemusic`)

**包结构：** 
- `controller/` — 13 个 REST 控制器（Admin、User、Song、Artist、Playlist、Comment、Banner、Genre、Style、Feedback、UserFavorite、PlaylistBinding、Ai）
- `service/` — 服务接口及实现
- `mapper/` — MyBatis-Plus Mapper 接口及 XML 映射文件
- `model/entity/` — 13 个实体类映射 `tb_` 前缀表
- `model/dto/` — 请求 DTO
- `model/vo/` — 响应 VO
- `config/` — CorsConfig、WebConfig、RedisConfig、MyBatisPlusConfig、MinioConfig、LangChain4jConfig 等
- `interceptor/` — LoginInterceptor（JWT 拦截器）
- `handler/` — GlobalExceptionHandler
- `result/` — `Result<T>` 和 `PageResult` 统一 API 响应格式
- `tool/` — `MusicTools`（7 个 `@Tool` 方法供 AI 调用）
- `util/` — JwtUtil、ThreadLocalUtil 等工具类
- `constant/` — 路径和消息常量
- `enumeration/` — 6 个枚举（BannerStatus、CommentType、FavoriteType、LikeStatus、Role、UserStatus）
- `memory/` — LangChain4j 聊天记忆 MySQL 持久化

**认证与授权：**
- JWT（Auth0 java-jwt 4.4.0），密钥 `VIBE_MUSIC`，有效期 6 小时，Token 存 Redis（database 1）
- `LoginInterceptor` 拦截所有 `/**`，`WebConfig` 中排除登录/注册/验证码/重置密码/公开查询端点
- 角色权限：`ROLE_ADMIN` 可访问 `/admin/**`，`ROLE_USER` 可访问其他业务路径
- 5 个通配符路径对未登录用户放行：`/playlist/getPlaylistDetail/**`、`/artist/getArtistDetail/**`、`/song/getAllSongs`、`/song/getSongDetail/**`、`/test/**`

**AI 集成（LangChain4j + DashScope）：**
- 模型 `qwen-max`，`MusicAssistant` 接口提供阻塞式 `chat()` 和流式 `chatStream()`（SSE）
- `@SystemMessage` 设定角色为"博学的音乐达人"，要求先用工具搜索再回答
- `MusicTools` 曝光 7 个工具：searchSongs、searchArtists、getSongsByStyle、getRandomSongs、getArtistDetail、searchSongsByArtist、searchPlaylists
- 对话历史持久化到 `tb_chat_message` 表，`MessageWindowChatMemory` 最多 20 条消息
- `MysqlChatMemoryStore` 采用"全量删除 + 重新插入"模式同步
- 游客通过 Cookie `guest_chat_id` 识别，登录用户通过 JWT 识别
- 流式使用 `SseEmitter`（120s 超时），事件名 `token`/`done`/`error`

**核心配置：**
- MySQL: `localhost:3306/vibe_music`，root/1234，Druid 连接池
- Redis: `127.0.0.1:6379`，database 1，无密码
- MinIO: `127.0.0.1:9005`，`minioadmin/minioadmin`，Bucket `vibe-music-data`
- 邮件: QQ SMTP `smtp.qq.com:465`（SSL）
- 文件上传最大 100MB
- MyBatis-Plus 驼峰映射 + `tb_` 表前缀
- SQL 建表脚本: `vibe-music-server/sql/vibe_music.sql`

### 管理端 (`vibe-music-admin/`)

- 基于 vue-pure-admin 模板，Vue 3 Composition API + `<script setup>`
- Axios 通过 Vite 代理 `/api` → `http://localhost:8080`（路径重写移除 `/api` 前缀）
- Vue Router hash 模式，路由模块自动从 `src/router/modules/*.ts` 导入
- 路由模块：home、user、artist、song、playlist、feedback、banner、error、remaining
- 路由守卫验证 Cookie 中 token + userInfo 的 roles，白名单 `["/login"]`
- 视图按业务模块划分：`artist/`、`banner/`、`feedback/`、`playlist/`、`song/`、`user/`（各含 `form/` 和 `utils/`）
- 自定义指令：auth、copy、longpress、optimize、perms、ripple
- Tailwind CSS 暗色模式（class 策略），`preflight: false` 避免与 Element Plus 冲突

### 用户端 (`vibe-music-client/`)

- Vue 3 Composition API + `<script setup>`，Pinia 状态管理（6 个 store 持久化到 localStorage）
- 自动导入架构：unplugin-auto-import 自动导入 vue/vue-router/pinia API 及 `src/utils/`、`src/stores/`、`src/hooks/` 导出
- Axios baseURL 硬编码为 `http://localhost:8080`（`src/utils/http.ts`），超时 20s，Bearer token 通过请求拦截器注入
- Vue Router history 模式，路由：`/`（首页）、`/library`（曲库）、`/artist`（歌手列表/详情）、`/playlist`（歌单列表/详情）、`/like`（我的喜欢）、`/user`（用户中心）
- ArtPlayer 音乐播放器，`useAudioPlayer` hook 管理音频状态
- `AiWidget.vue` 浮窗 AI 聊天组件，支持流式输出（SSE + fetch + ReadableStream 逐字渲染）、拖拽、游客模式
- 流式 API 使用原生 fetch（非 axios），因为 axios 不支持 `ReadableStream`
- `vue-i18n` 已安装但未实际配置，仅 Element Plus 使用 `zhCn` 本地化