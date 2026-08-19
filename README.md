# Vibe Music 🎵

全栈音乐流媒体平台 — 智能推荐、AI 对话、多端管理

## 项目结构

本项目包含三个独立子项目（无父级 POM/工作空间）：

| 子项目 | 类型 | 端口 | 技术栈 |
|---|---|---|---|
| `vibe-music-server/` | Spring Boot 3 后端 | 8080 | Java 17, Maven, MyBatis-Plus, MySQL, Redis, MinIO, LangChain4j |
| `vibe-music-admin/` | 管理端 SPA | 8089 | Vue 3, Vite 6, Pinia, Element Plus, vue-pure-admin |
| `vibe-music-client/` | 用户端 SPA | 8090 | Vue 3, Vite 6, Pinia, Element Plus, ArtPlayer |

## 系统架构图

```mermaid
graph TB
    subgraph 用户端["🎧 用户端 (port 8090)"]
        U_Pages[页面: 首页 / 曲库 / 歌手 / 歌单 / 我的喜欢 / 用户中心]
        U_Components[组件: ArtPlayer / AiWidget / 抽屉歌单]
        U_Stores[状态管理: audio / user / playlist / theme / favorite / library]
        U_Axios[HTTP: Axios → localhost:8080]
    end

    subgraph 管理端["🖥️ 管理端 (port 8089)"]
        A_Views[视图: 用户 / 歌曲 / 歌手 / 歌单 / Banner / 反馈]
        A_Router[路由: Vue Router hash 模式 + 角色守卫]
        A_API[HTTP: Axios → /api 代理 → localhost:8080]
    end

    subgraph 后端["⚙️ Spring Boot 后端 (port 8080)"]
        direction TB
        CORS[CorsConfig 跨域]
        JWT[LoginInterceptor JWT 拦截]
        Role[RolePermissionManager 角色权限]
        Ctrl[13 个 REST Controller]
        Service[Service 层]
        Mapper[MyBatis-Plus Mapper + XML]
        AI[AI 模块: LangChain4j + DashScope]
    end

    subgraph 存储层["💾 存储层"]
        MySQL[(MySQL - vibe_music)]
        Redis[(Redis - Token / 缓存 / 验证码)]
        MinIO[(MinIO - 文件存储)]
    end

    subgraph 外部服务["☁️ 外部服务"]
        QQ_Email[QQ 邮箱 SMTP]
        DashScope[阿里云 DashScope qwen-max]
    end

    用户端 -->|直连 HTTP| 后端
    管理端 -->|代理 /api| 后端
    后端 --> MySQL
    后端 --> Redis
    后端 --> MinIO
    后端 --> QQ_Email
    AI --> DashScope
```

## 功能特性

### 🎶 音乐管理
- 歌曲、歌单、歌手 CRUD 管理
- 推荐算法（热门推荐、风格推荐）
- 文件上传（MinIO 分布式存储）
- 评论与收藏系统

### 🤖 AI 音乐助手
- 基于 LangChain4j + 阿里云 DashScope（qwen-max）的智能对话
- 7 个工具函数：歌曲搜索、歌手详情、歌单查询、风格检索等
- 流式 SSE 输出，前端逐字渲染
- 对话历史持久化（MySQL + 20 条消息窗口）
- 支持登录用户和游客两种模式

### 🔐 认证与权限
- JWT 令牌认证（有效期 6 小时，Redis 缓存）
- 双角色权限控制（ROLE_ADMIN / ROLE_USER）
- 路径级拦截器 + 通配符放行

### 🖥️ 管理端
- 用户管理、歌曲管理、歌手管理、歌单管理
- 反馈管理、Banner 管理
- 数据看板

### 🎧 用户端
- 音乐播放器（ArtPlayer）
- 曲库浏览、歌单收藏
- 歌手详情
- AI 聊天浮窗

## 认证流程

```mermaid
sequenceDiagram
    participant Client as 用户端/管理端
    participant Interceptor as LoginInterceptor
    participant JWT as JwtUtil
    participant Redis as Redis
    participant Controller as Controller

    Note over Client,Controller: 登录
    Client->>Controller: POST /user/login (账号+密码)
    Controller->>JWT: 生成 Token (6h 有效期)
    JWT-->>Controller: token
    Controller->>Redis: SET token (database 1)
    Controller-->>Client: 返回 token

    Note over Client,Controller: 请求鉴权
    Client->>Interceptor: 请求 + Bearer token
    Interceptor->>Interceptor: 排除路径? → 放行
    Interceptor->>JWT: 验证 token 签名
    Interceptor->>Redis: 检查 token 是否存在
    Interceptor->>ThreadLocalUtil: 存入用户 claims
    Interceptor->>RolePermissionManager: 检查角色路径权限
    Interceptor-->>Client: 通过 → 放行到 Controller

    Note over Client,Controller: 请求结束
    Controller->>ThreadLocalUtil: afterCompletion 清除
```

## AI 对话流程

```mermaid
sequenceDiagram
    participant Frontend as 前端 AiWidget
    participant Backend as AiController
    participant Memory as ChatMemoryHolder
    participant Tools as MusicTools
    participant LLM as DashScope qwen-max
    participant DB as MySQL tb_chat_message

    Frontend->>Backend: POST /test/ai/chat (用户消息)
    Backend->>Memory: 获取/创建会话记忆
    Memory->>DB: 查询历史消息(最多20条)
    DB-->>Memory: 历史消息列表
    Backend->>LLM: 发送消息 + 系统提示 + 工具定义
    LLM-->>Backend: 思考 → 决定调用工具
    Backend->>Tools: 执行工具函数(如 searchSongs)
    Tools-->>Backend: 工具返回结果
    Backend->>LLM: 提交工具结果
    LLM-->>Backend: 生成最终回答
    Backend->>Memory: 保存对话(全量删除+重新插入)
    Memory->>DB: 持久化消息
    Backend-->>Frontend: 返回回答文本

    Note over Frontend,DB: 流式模式
    Frontend->>Backend: POST /test/ai/chatStream
    Backend-->>Frontend: SseEmitter (120s超时)
    Note over Backend,LLM: LLM 流式生成
    Backend-->>Frontend: event:token → 逐字推送
    Backend-->>Frontend: event:done → 完成
    alt 错误
        Backend-->>Frontend: event:error → 错误信息
    end
```

## 后端包结构

```mermaid
graph LR
    subgraph 入口["入口"]
        App[VibeMusicServerApplication]
    end

    subgraph 控制层["controller"]
        Admin[AdminController]
        User[UserController]
        Song[SongController]
        Artist[ArtistController]
        Playlist[PlaylistController]
        Comment[CommentController]
        Banner[BannerController]
        Genre[GenreController]
        Style[StyleController]
        Feedback[FeedbackController]
        Favorite[UserFavoriteController]
        Binding[PlaylistBindingController]
        AI[AiController]
    end

    subgraph 服务层["service"]
        S[Service 接口 + 实现]
    end

    subgraph 数据层["mapper"]
        M[Mapper 接口 + XML]
        E[Entity: 13个 tb_ 表映射]
    end

    subgraph 基础设施["config / interceptor"]
        CORS[CorsConfig]
        Web[WebConfig]
        JWT[LoginInterceptor]
        Role[RolePermissionManager]
        RedisC[RedisConfig]
        MyBatis[MyBatisPlusConfig]
        MinioC[MinioConfig]
        LC[LangChain4jConfig]
        Ex[GlobalExceptionHandler]
    end

    subgraph AI["ai / tool"]
        MT[MusicTools - 7个工具]
        MA[MusicAssistant]
        MAN[MusicAssistantNoTools]
        CM[ChatMemoryHolder]
        MS[MysqlChatMemoryStore]
    end

    subgraph 工具["util / result"]
        JT[JwtUtil]
        TT[ThreadLocalUtil]
        R[Result / PageResult]
        Enum[6个枚举]
        Const[常量]
    end

    App --> 控制层
    控制层 --> 服务层
    服务层 --> 数据层
    控制层 --> AI
    控制层 -.-> 基础设施
    服务层 -.-> 基础设施
```

## 用户端页面路由

```mermaid
graph TB
    Root["/ (根路由)"] --> Layout["App.vue 根布局"]
    Layout --> AiWidget["AiWidget.vue 全局浮窗"]

    Layout --> Index["/ 首页<br/>pages/index.vue"]
    Layout --> Library["/library 曲库<br/>pages/library"]

    Layout --> ArtistList["/artist 歌手列表<br/>pages/artist"]
    Layout --> ArtistDetail["/artist/:id 歌手详情<br/>pages/artist"]

    Layout --> PlaylistList["/playlist 歌单列表<br/>pages/playlist"]
    Layout --> PlaylistDetail["/playlist/:id 歌单详情<br/>pages/playlist"]

    Layout --> Like["/like 我的喜欢<br/>pages/like"]
    Layout --> User["/user 用户中心<br/>pages/user"]

    subgraph Store["状态管理 (Pinia + localStorage)"]
        Audio[audio 播放器]
        UserS[user 用户]
        PlaylistS[playlist 歌单]
        Theme[theme 主题]
        Menu[menu 菜单]
        Setting[setting 设置]
    end
```

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- Node.js 18+
- pnpm 7+
- MySQL 8.0+
- Redis 6+
- MinIO

### 启动后端

```bash
# 1. 导入数据库
cd vibe-music-server/sql
mysql -u root -p < vibe_music.sql

# 2. 配置 application.yml（MySQL、Redis、MinIO、邮箱、DashScope API Key）

# 3. 启动服务
cd vibe-music-server
mvn spring-boot:run
```

### 启动管理端

```bash
cd vibe-music-admin
pnpm install
pnpm dev
```

### 启动用户端

```bash
cd vibe-music-client
pnpm install
pnpm dev
```

## 技术亮点

- **AI 深度集成**：LangChain4j + DashScope，工具调用驱动的音乐领域问答
- **流式响应**：SSE + ReadableStream 实现 AI 回复逐字渲染
- **缓存策略**：Spring Cache + Redis 多级缓存
- **文件存储**：MinIO 分布式对象存储
- **自动导入**：Vite 插件实现 Vue/组件/工具函数自动导入

## 技术栈

| 层 | 技术 |
|---|---|
| 后端框架 | Spring Boot 3.3.7, MyBatis-Plus 3.5.9 |
| 数据库 | MySQL 8.0, Redis |
| 对象存储 | MinIO |
| AI | LangChain4j 0.36.2, DashScope (qwen-max) |
| 认证 | Auth0 java-jwt 4.4.0 |
| 管理端 | Vue 3, Vite 6, Pinia, Element Plus, vue-pure-admin |
| 用户端 | Vue 3, Vite 6, Pinia, Element Plus, ArtPlayer |
| 构建工具 | Maven, pnpm |