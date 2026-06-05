# test1 分支 vs master 分支 —— 改动详情与意义说明

## 目录

- [一、父仓库（music 根目录）](#一父仓库music-根目录)
- [二、后端核心改动：AI 聊天模块](#二后端核心改动ai-聊天模块)
- [三、后端配置与依赖改动](#三后端配置与依赖改动)
- [四、后端已有文件修改](#四后端已有文件修改)
- [五、用户端前端改动](#五用户端前端改动)
- [六、管理端前端改动](#六管理端前端改动)
- [七、本次会话增量：流式输出改造 + 修复 SYSTEM 消息存储](#七本次会话增量流式输出改造--修复-system-消息存储)

---

## 一、父仓库（music 根目录）

### 1.1 `.claude/settings.local.json` — 扩展工具权限

**改动前（仅 1 条规则）：**
```json
{
  "permissions": {
    "allow": [
      "Bash(git add *)"
    ]
  }
}
```

**改动后（42 条规则）：**
```json
{
  "permissions": {
    "allow": [
      "Bash(git add *)",
      "Bash(git commit *)",
      "Bash(git branch *)",
      "Bash(mvn clean *)",
      "WebSearch",
      "WebFetch(domain:docs.langchain4j.dev)",
      "Bash(jar tf *)",
      "Bash(javap *)",
      "Bash(mvn compile *)",
      "Bash(mvn spring-boot:run -q)",
      "Bash(curl *)",
      "Bash(mysql *)",
      "... (共 42 条)"
    ]
  }
}
```

**意义：** 允许 Claude Code 在该项目中执行 Maven 构建、jar 反编译、curl 测试、MySQL 操作等命令，这是开发 LangChain4j 集成时所需要的调试能力。

### 1.2 `CLAUDE.md` — 新建项目指导文件

**改动前：** 无此文件。

**改动后：** 新增约 80 行的项目架构文档，包含：
- 子项目概览表（server/admin/client）
- 常用命令（mvn、pnpm）
- 后端包结构和认证体系
- 前端技术栈和关键配置

**意义：** 为未来的 Claude Code 实例提供项目上下文，使其无需反复探索代码库即可高效工作。

---

## 二、后端核心改动：AI 聊天模块

这是 test1 分支最重要的功能增量：从零搭建了完整的 AI 对话系统。

### 2.1 `pom.xml` — 新增 LangChain4j 依赖

**改动前：**
```xml
<properties>
    <java.version>17</java.version>
</properties>
```
```xml
<!-- 没有任何 AI 相关依赖 -->
```

**改动后：**
```xml
<properties>
    <java.version>17</java.version>
    <langchain4j.version>0.36.2</langchain4j.version>
</properties>
```
```xml
<!-- Spring Boot Web（显式声明） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- LangChain4j 核心 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
    <version>${langchain4j.version}</version>
</dependency>

<!-- LangChain4j DashScope/Qwen 模型集成 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-dashscope</artifactId>
    <version>${langchain4j.version}</version>
</dependency>
```

**意义：**
- `langchain4j` 是 Java 生态中最主流的 LLM 应用开发框架，提供了 AiServices（声明式 AI 代理）、ChatMemory（对话记忆）、Tools（函数调用）等能力
- `langchain4j-dashscope` 是阿里云通义千问（Qwen）的 LangChain4j 适配器，支持流式和非流式调用
- 选用 `0.36.2` 版本是因为它稳定支持 `TokenStream` 流式输出和 `@Tool` 注解

### 2.2 `application.yml` — 新增 LangChain4j 和 AI 路径配置

**改动前：** 无 LangChain4j 配置，无 `/test` 路径白名单。

**改动后新增的关键配置：**

```yaml
# 角色权限：将 /test 加入 ROLE_USER 可访问路径
role-path-permissions:
  permissions:
    ROLE_USER:
      - "/test"           # 新增：AI 聊天端点

# LangChain4j DashScope 通义千问大模型配置
langchain4j:
  dashscope:
    api-key: sk-d4b224f3e44a4f5ea1c876606d4ed58e
    model-name: qwen-max

# 显式声明服务端口
server:
  port: 8080

# 调试日志级别
logging:
  level:
    com.heima: debug
```

**意义：**
- `/test` 加入权限白名单确保 AI 聊天端点可被用户和游客访问
- `qwen-max` 是阿里云最强的模型，支持函数调用和流式输出
- `debug` 日志用于开发阶段排查 LangChain4j 的 `InputRequiredException` 等问题

### 2.3 `constant/PathConstant.java` — 新增 AI 路径常量

**改动前：**
```java
public static final String SONG_DETAIL_PATH = "/song/getSongDetail/**";
// 文件结束
```

**改动后：**
```java
public static final String SONG_DETAIL_PATH = "/song/getSongDetail/**";
public static final String AI_PATH = "/test/**";       // 新增
```

**意义：** 统一管理路径常量，避免在拦截器中硬编码字符串，后续添加新 AI 端点时只需修改这一处。

### 2.4 `config/WebConfig.java` — 新增 UTF-8 编码过滤器

**改动前：**
```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Autowired
    private LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .excludePathPatterns(...);
    }
    // 文件结束
}
```

**改动后：** 在类末尾新增：
```java
    @Bean
    public CharacterEncodingFilter characterEncodingFilter() {
        CharacterEncodingFilter filter = new CharacterEncodingFilter();
        filter.setEncoding("UTF-8");
        filter.setForceEncoding(true);
        return filter;
    }
}
```

**意义：** AI 回复中经常包含中文、emoji、特殊符号等多字节字符。强制 UTF-8 编码确保 SSE 流中的中文字符不会出现乱码。`forceEncoding(true)` 保证即使客户端未声明 charset 也使用 UTF-8。

### 2.5 `interceptor/LoginInterceptor.java` — AI 路径放行 + 日志增强

**改动前（关键代码）：**
```java
@Component
public class LoginInterceptor implements HandlerInterceptor {
    // 无日志

    List<String> allowedPaths = Arrays.asList(
        PathConstant.PLAYLIST_DETAIL_PATH,
        PathConstant.ARTIST_DETAIL_PATH,
        PathConstant.SONG_LIST_PATH,
        PathConstant.SONG_DETAIL_PATH
        // 没有 AI_PATH
    );

    // 无日志输出
    if (token == null || token.isEmpty()) {
        if (isAllowedPath) return true;
        sendErrorResponse(response, 401, MessageConstant.NOT_LOGIN);
        return false;
    }
}
```

**改动后（关键代码）：**
```java
@Slf4j                                                       // 新增日志
@Component
public class LoginInterceptor implements HandlerInterceptor {

    List<String> allowedPaths = Arrays.asList(
        PathConstant.PLAYLIST_DETAIL_PATH,
        PathConstant.ARTIST_DETAIL_PATH,
        PathConstant.SONG_LIST_PATH,
        PathConstant.SONG_DETAIL_PATH,
        PathConstant.AI_PATH                                  // 新增：AI 端点放行
    );

    log.info("拦截器检查: path={}, allowedPaths={}, isAllowed={}", path, allowedPaths, isAllowed);

    if (token == null || token.isEmpty()) {
        if (isAllowedPath) {
            log.debug("放行未登录用户访问: {}", path);            // 新增日志
            return true;
        }
        log.debug("拒绝未登录用户访问: {}", path);               // 新增日志
        sendErrorResponse(response, 401, MessageConstant.NOT_LOGIN);
        return false;
    }
}
```

**意义：**
- 添加 `@Slf4j` 和详细日志是为了排查"为什么 AI 端点返回 401"之类的问题
- `PathConstant.AI_PATH` 加入白名单意味着**游客无需登录即可使用 AI 聊天**，后端通过 `guest_chat_id` Cookie 来区分游客会话

### 2.6 `model/entity/ChatMessage.java` — 新增聊天消息实体（全新文件）

**改动前：** 无此文件。

**改动后（73 行新代码）：**

```java
@TableName("chat_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("chat_id")
    private String chatId;           // 会话ID，区分不同用户/游客的对话

    @TableField("message_type")
    private MessageType messageType; // USER / ASSISTANT / SYSTEM / TOOL_REQUEST / TOOL_RESULT

    @TableField("content")
    private String content;          // 消息文本

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public enum MessageType {
        USER("用户消息"),
        ASSISTANT("AI助手消息"),
        SYSTEM("系统消息"),           // 不会被持久化（已在 MysqlChatMemoryStore 中过滤）
        TOOL_REQUEST("工具调用请求"),  // AI 决定调用工具时的 JSON
        TOOL_RESULT("工具调用结果");   // 工具返回的数据
    }
}
```

**意义：**
- `chatId` 字段是关键设计：登录用户使用 `user_chat_id_{userId}`，游客使用 Cookie 中的 `guestId`，实现了**同一用户不同会话隔离**
- `MessageType` 枚举包含 5 种类型，其中 `TOOL_REQUEST` 和 `TOOL_RESULT` 用于记录 AI 的函数调用链路，方便调试
- `@Builder` 模式使得创建消息对象更清晰：`ChatMessage.builder().chatId(...).messageType(...).content(...).build()`

### 2.7 `memory/MysqlChatMemoryStore.java` — 聊天记忆 MySQL 持久化（全新文件）

这是最核心的架构组件之一。LangChain4j 的 `MessageWindowChatMemory` 负责管理对话上下文窗口（最多 20 条消息），而 `MysqlChatMemoryStore` 提供了 MySQL 持久化后端。

**核心方法 `updateMessages()`：**
```java
@Override
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void updateMessages(Object memoryId, List<ChatMessage> messages) {
    String chatId = memoryId.toString();

    // 1. 先全量删除该会话的旧消息
    aiService.deleteMessagesByChatId(chatId);

    // 2. 再逐条插入新消息
    for (ChatMessage message : messages) {
        switch (message.type()) {
            case SYSTEM:
                // 系统消息不持久化（由 @SystemMessage 注解每次注入）
                break;
            case USER:
                aiService.saveMessage(chatId, USER, userContent);
                break;
            case AI:
                // 如果 AI 消息中包含工具调用请求，单独存储
                if (aiMessage.hasToolExecutionRequests()) { ... }
                aiService.saveMessage(chatId, ASSISTANT, aiContent);
                break;
            case TOOL_EXECUTION_RESULT:
                aiService.saveMessage(chatId, TOOL_RESULT, resultJson);
                break;
        }
    }
}
```

**意义：**
- 采用"全量删除 + 重新插入"而非"增量更新"模式，是因为 LangChain4j 的 `MessageWindowChatMemory` 每次都会把完整的上下文窗口传给 `updateMessages()`，增量对比反而更复杂
- `REQUIRES_NEW` 事务传播确保每条消息的持久化独立于业务事务，即使聊天出错也不丢失已保存的消息
- **SYSTEM 消息不持久化**——系统提示词由 `@SystemMessage` 注解定义在代码中，每次 AI 调用时框架自动注入，存入数据库只会造成冗余和重复

**核心方法 `getMessages()`：**
```java
@Override
public List<ChatMessage> getMessages(Object memoryId) {
    String chatId = memoryId.toString();
    List<ChatMessage> entities = aiService.getMessagesByChatId(chatId);

    for (ChatMessage entity : entities) {
        switch (entity.getMessageType()) {
            case USER:    messages.add(UserMessage.from(entity.getContent())); break;
            case ASSISTANT: messages.add(AiMessage.from(entity.getContent())); break;
            case SYSTEM:  /* 跳过，不加载 */ break;
            case TOOL_REQUEST: /* 反序列化 JSON 为 ToolExecutionRequest */ break;
            case TOOL_RESULT:  /* 反序列化 JSON 为 ToolExecutionResultMessage */ break;
        }
    }
    return messages;
}
```

**意义：**
- 将数据库实体转换回 LangChain4j 的消息对象，框架才能理解对话上下文
- 跳过 SYSTEM 消息防止历史中的系统提示词与新注入的 `@SystemMessage` 重复

### 2.8 `service/MusicAssistant.java` — AI 代理接口（全新文件）

```java
public interface MusicAssistant {

    @SystemMessage("""
            你是一个博学的音乐达人，熟悉近些年的各种流行音乐。你可以使用工具来搜索歌曲、歌手和歌单信息。

            重要规则：
            1. 先用工具搜索相关信息，然后基于搜索结果回答用户问题。
            2. 如果某个工具没有返回结果，尝试换个关键词或使用其他工具，但最多尝试2-3次。
            3. 如果多次尝试仍找不到相关信息，直接告诉用户未找到，不要反复调用工具。
            4. 回答时要基于工具返回的实际数据，不要编造信息。
            5. 回答要简洁友好，用中文。
            """)
    String chat(@MemoryId String chatId, @UserMessage String userMessage);

    @SystemMessage("...")  // 相同的提示词
    TokenStream chatStream(@MemoryId String chatId, @UserMessage String userMessage);
}
```

**意义：**
- LangChain4j 的 `AiServices` 会根据这个接口**自动生成代理实现**，无需手写 HTTP 调用代码
- `@SystemMessage` 定义了 AI 的角色（音乐达人）和行为约束（先用工具搜索、不编造信息）
- `@MemoryId` 将 `chatId` 绑定为记忆标识符，同一 `chatId` 的对话能自动关联历史上下文
- `chat()` 返回 `String`（阻塞式），`chatStream()` 返回 `TokenStream`（流式），两种模式共用一个接口

### 2.9 `tool/MusicTools.java` — AI 可调用的工具函数（全新文件）

暴露 7 个 `@Tool` 方法供 AI 自主调用：

```java
@Component
public class MusicTools {
    // 1. searchSongs(keyword)        —— 按歌名/专辑搜索歌曲
    // 2. searchArtists(keyword)      —— 按歌手名/地区搜索歌手
    // 3. getSongsByStyle(styleName)  —— 按风格获取歌曲
    // 4. getRandomSongs()            —— 随机推荐
    // 5. getArtistDetail(artistName) —— 获取歌手详情
    // 6. searchSongsByArtist(name)   —— 按歌手搜歌曲
    // 7. searchPlaylists(keyword)    —— 搜索歌单
}
```

每个工具通过 MyBatis-Plus 查询数据库，返回格式化文本。例如 `searchSongs`：
```java
@Tool("搜索歌曲，根据关键词匹配歌名或专辑名，返回匹配的歌曲列表")
public String searchSongs(String keyword) {
    wrapper.like(Song::getSongName, keyword).or().like(Song::getAlbum, keyword).last("LIMIT 10");
    List<Song> songs = songMapper.selectList(wrapper);
    if (songs.isEmpty()) return "未找到与 " + keyword + " 相关的歌曲";
    return songs.stream().map(s -> String.format("歌曲: %s | 专辑: %s | 风格: %s | 时长: %s", ...))
            .collect(Collectors.joining("\n"));
}
```

**意义：**
- 这是 LangChain4j 的 **Function Calling** 能力：AI 根据用户问题自主决定调用哪个工具、传什么参数
- 例如用户问"推荐几首周杰伦的歌"，AI 会先调用 `searchSongsByArtist("周杰伦")`，拿到数据后再生成推荐文案
- 限制 `LIMIT 10` 防止一次查询返回过多数据导致 token 超限

### 2.10 `controller/AiController.java` — AI 聊天控制器（全新文件）

提供 9 个 REST 端点（完整列表见 2.11 节），其中最核心的是：

**阻塞式对话：**
```java
@GetMapping("/simple/chat")
public String simpleChat(@RequestParam("query") String query, ...) {
    Long userId = getUserId();
    String chatId = resolveChatIdForGuest(userId, request, response);
    return musicAssistant.chat(chatId, query);
}
```

**流式对话（SSE）：**
```java
@GetMapping(value = "/simple/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamChat(@RequestParam("query") String query, ...) {
    SseEmitter emitter = new SseEmitter(120_000L);
    TokenStream tokenStream = musicAssistant.chatStream(chatId, query);

    tokenStream
        .onNext(token -> emitter.send(SseEmitter.event().name("token").data(token)))
        .onComplete(response -> {
            emitter.send(SseEmitter.event().name("done").data(fullText));
            emitter.complete();
        })
        .onError(error -> emitter.completeWithError(error))
        .start();

    return emitter;
}
```

**意义：**
- `resolveChatIdForGuest()` 实现了登录用户和游客的统一身份识别：登录用户从 JWT 中读取 userId，游客从 Cookie 中读取/生成 `guest_chat_id`
- SSE 端点使用 Spring 的 `SseEmitter`，120 秒超时覆盖工具调用多轮交互场景
- SSE 事件用 `token`/`done`/`error` 三种命名区分，前端可以精确处理每种事件

### 2.11 AI 聊天端点完整列表

| 端点 | 方法 | 说明 | 场景 |
|---|---|---|---|
| `/test/direct/chat` | GET | 直连 QwenChatModel，无记忆/无工具 | 隔离测试 API 连通性 |
| `/test/simple/chat` | GET | 阻塞式对话，带工具和记忆 | 被前端 AiWidget 旧版使用 |
| `/test/simple/chat-notools` | GET | 无工具对话 | 排查工具调用导致的问题 |
| `/test/simple/chat/stream` | GET | **流式 SSE**，带工具和记忆 | 被前端 AiWidget 新版使用 |
| `/test/chat/history` | GET | 全量历史 | 加载完整对话 |
| `/test/chat/history/page` | GET | 分页历史 | 分页展示长对话 |
| `/test/chat/recent` | GET | 最近 N 条 | 恢复最近的上下文 |
| `/test/chat/clear` | DELETE | 清空会话 | 用户手动清理 |
| `/test/chat/stats` | GET | 消息统计 | 调试用 |

---

## 三、后端配置与依赖改动

### 3.1 `application.yml` — 配置从占位符改为实际值

**数据库配置：**

| 配置项 | 改动前（master） | 改动后（test1） |
|---|---|---|
| MySQL URL | `jdbc:mysql://YOUR_MYSQL_HOST:3306/vibe_music?...` | `jdbc:mysql://localhost:3306/vibe_music?...&allowPublicKeyRetrieval=true` |
| 用户名 | `YOUR_MYSQL_USER` | `root` |
| 密码 | `YOUR_MYSQL_PASSWORD` | `1234` |

**Redis 配置：**

| 配置项 | 改动前 | 改动后 |
|---|---|---|
| password | `""`（空字符串） | 删除该行 |

**邮件配置：**

| 配置项 | 改动前 | 改动后 |
|---|---|---|
| SMTP 服务器 | `smtp.example.com` | `smtp.qq.com` |
| 账号 | `your-email@example.com` | `2509680148@qq.com` |
| 密码 | `YOUR_EMAIL_APP_PASSWORD` | `tdmklkghymgyeacg` |

**意义：** 从模板占位符替换为开发环境的实际连接参数。`allowPublicKeyRetrieval=true` 是 MySQL 8.0+ 连接所需的参数。

---

## 四、后端已有文件修改（注释增强为主）

### 4.1 `controller/AdminController.java` — 注释增强

**改动前：**
```java
/**
 * @param adminDTO      管理员信息
 * @param bindingResult 绑定结果
 * @return 结果
 */
@PostMapping("/register")
public Result register(@RequestBody @Valid AdminDTO adminDTO, BindingResult bindingResult) {
```

**改动后（新增 6 行注释说明注解含义）：**
```java
/**
 * @param adminDTO      管理员信息
 * @param bindingResult 绑定结果
 * @return 结果
 *
 * @RequestBody @Valid AdminDTO adminDTO：
 * @RequestBody：从请求体中解析 JSON 数据并绑定到 AdminDTO 对象。
 * @Valid：触发 Spring 的参数校验机制（基于 JSR-303/JSR-380 规范）。
 * BindingResult bindingResult：
 * 存储 @Valid 校验的结果（包括校验失败的字段和错误信息）。
 */
@PostMapping("/register")
```

**意义：** 增加了对 Spring 注解机制的注释，帮助理解每个注解的具体作用。同样 `logout()` 方法也增加了 `@RequestHeader` 的注释。

### 4.2 `config/RolePermissionManager.java` — 注释 + 格式

**改动前：**
```java
public class RolePermissionManager {
    public boolean hasPermission(String role, String requestURI) {
        List<String> allowedPaths = permissions.get(role);
        if (allowedPaths != null) {
            for (String path : allowedPaths) {
                if (requestURI.startsWith(path)) {
```

**改动后：**
```java
public class  RolePermissionManager {     // 多了空格（非预期改动）
    public boolean hasPermission(String role, String requestURI) {
        //根据角色（admin or user）得到对应的路径
        List<String> allowedPaths = permissions.get(role);
        if (allowedPaths != null) {
            for (String path : allowedPaths) {
                //指定路径开头
                if (requestURI.startsWith(path)) {
```

**意义：** 注释解释了权限校验逻辑——根据角色查找允许的路径列表，然后检查请求 URI 是否以这些路径开头。这是简单的路径前缀匹配策略。

### 4.3 `service/impl/UserFavoriteServiceImpl.java` — 缓存注释

**改动前：**
```java
/**
 * 获取用户收藏的歌曲列表
 *
 * @param songDTO 歌曲查询条件
 * @return 用户收藏的歌曲列表
 */
@Cacheable(value = "favoriteCache", key = "#songDTO.pageNum + '-' + #songDTO.pageSize + '-' + #songDTO.songName + '-' + #songDTO.artistName + '-' + #songDTO.album")
```

**改动后（新增 7 行缓存机制说明）：**
```java
/**
 * 获取用户收藏的歌曲列表
 *
 * 作用：将方法的返回值缓存起来，下次请求时直接返回缓存结果，避免重复查询数据库。
 * 缓存键（key）：
 * 动态生成，由 songDTO 的多个字段拼接而成，格式为：
 * "pageNum-pageSize-songName-artistName-album"
 * 例如："1-10-晴天-周杰伦-七里香"。
 * 触发条件：
 * 仅当缓存中不存在该键时，才会执行方法逻辑。
 * 如果缓存已存在，直接返回缓存结果，跳过方法体。
 *
 * @param songDTO 歌曲查询条件
 * @return 用户收藏的歌曲列表
 */
@Cacheable(...)
```

**意义：** 详细说明了 `@Cacheable` 的缓存策略：动态生成的缓存键格式、触发条件。这是给开发者看的注释，解释了为什么相同查询条件不会重复查数据库。

### 4.4 其他注释类改动汇总

| 文件 | 改动 |
|---|---|
| `config/MinioConfig.java` | 新增注释 `//这段代码的功能是创建一个MinioClient的Spring Bean` |
| `service/impl/EmailServiceImpl.java` | 新增注释 `// 创建MIME邮件消息对象` |
| `service/impl/SongServiceImpl.java` | 注释从 `// 查询用户收藏的歌曲 ID` 改为 `// 查询用户收藏的歌曲 ID 如果没有 则随机返回` |
| `util/RandomCodeUtil.java` | 新增注释 `//创建一个可变字符串对象，初始容量为 LENGTH（6），用于高效拼接字符` |
| `mapper/SongMapper.java` | 为 `getRandomSongsWithArtist()` 添加注释说明 `ORDER BY RAND()` 的随机排序原理 |
| `service/impl/PlaylistServiceImpl.java` | `QueryWrapper` 从单行改为多行格式，增加可读性 |

**意义：** 这些注释改动的共同特点是**解释了"为什么这样做"而不仅仅是"做了什么"**——这是好的代码注释的核心原则。例如 `ORDER BY RAND()` 的原理注释让后续维护者理解为什么用这个 SQL 语法以及它的性能影响。

---

## 五、用户端前端改动（vibe-music-client）

### 5.1 `src/api/ai.ts` — 新增 AI 聊天 API（全新文件）

**改动前：** 无此文件。

**改动后（~100 行新代码）：** 提供 4 个 API 函数。

**阻塞式 API：**
```typescript
export const aiSimpleChat = (query: string, chatId?: string) => {
  return http<string>('get', '/test/simple/chat', {
    params: { query, chatId },
    timeout: 60000,  // AI 回复可能较慢（含工具调用），60 秒超时
  })
}
```

**流式 API（SSE）：**
```typescript
export const aiStreamChat = (
  query: string, chatId: string,
  callbacks: { onToken, onDone, onError },
  signal?: AbortSignal,
): void => {
  const baseURL = 'http://localhost:8080'
  const userStore = UserStore()
  const token = userStore.userInfo?.token

  // 使用原生 fetch 而非 axios，因为 axios 不支持流式响应
  fetch(`${baseURL}/test/simple/chat/stream?query=...&chatId=...`, {
    headers: {
      Accept: 'text/event-stream',
      ...(token ? { Authorization: token } : {}),
    },
    credentials: 'include',  // 发送 Cookie（游客模式）
    signal,                   // 支持 AbortController 取消
  }).then(async (response) => {
    const reader = response.body!.getReader()
    const decoder = new TextDecoder('utf-8')
    // 逐行解析 SSE 格式：event:xxx\ndata:yyy\n\n
    // ...
  })
}
```

**意义：**
- 使用原生 `fetch` 而非 axios，因为 axios 不支持 `ReadableStream` 响应的消费
- `credentials: 'include'` 确保游客的 `guest_chat_id` Cookie 被自动发送
- `AbortSignal` 支持取消正在进行的 AI 回复（用户关闭窗口、快速连续发送等场景）

### 5.2 `src/components/Common/AiWidget.vue` — 浮窗 AI 聊天组件（全新文件）

**改动前：** 无此文件。

**改动后（~210 行新代码）：** 完整的浮窗聊天 UI。

**核心功能：**
- **流式渲染**：`onToken` 回调逐字追加到消息内容，Vue 3 Proxy 自动触发 UI 更新
- **拖拽**：支持鼠标和触摸屏拖拽，位置持久化到 localStorage
- **游客支持**：通过 `guest_chat_id` Cookie 识别游客身份
- **请求管理**：发送新消息时自动取消前一个请求（防止串话）

**流式发送核心逻辑：**
```typescript
const send = () => {
  if (!text || loading.value) return

  if (abortController.value) abortController.value.abort()  // 取消旧请求

  messages.value.push({ role: 'user', content: text })
  const assistantIdx = messages.value.length
  messages.value.push({ role: 'assistant', content: '' })   // 空占位

  aiStreamChat(text, chatId.value, {
    onToken: (token) => {
      messages.value[assistantIdx].content += token          // 逐字追加
    },
    onDone: () => loading.value = false,
    onError: (err) => {
      messages.value[assistantIdx].content = '抱歉，AI 回复出错: ' + err.message
      loading.value = false
    },
  }, controller.signal)
}
```

**意义：**
- 预添加空消息占位 + 逐字追加重写是流式 UI 的标准模式，避免了频繁创建/删除数组元素的性能开销
- 取消旧请求（`abort()`）防止用户快速连发多条消息时出现"串话"——旧回复的 token 叠加到新消息上

### 5.3 `src/layout/index.vue` — 主布局引入 AiWidget

**改动前：**
```vue
<script setup>
import Main from './components/main/index.vue'
import Footer from './components/footer/index.vue'
</script>

<template>
  <div>
    <div><Main /></div>
    <Footer />
  </div>
</template>
```

**改动后：**
```vue
<script setup>
import AiWidget from '@/components/Common/AiWidget.vue'  // 新增
</script>

<template>
  <div>
    <div><Main /></div>
    <Footer />
    <AiWidget />   <!-- 新增：全局浮窗 AI 助手 -->
  </div>
</template>
```

**意义：** 挂载在根布局中意味着 AiWidget 在用户端的**所有页面**都可用，用户可以随时唤起 AI 助手。

---

## 六、管理端前端改动（vibe-music-admin）

### 6.1 `.env.development` — 新增 API 地址

**改动前：**
```
VITE_PORT = 8089
VITE_PUBLIC_PATH = /
VITE_ROUTER_HISTORY = "hash"
```

**改动后：**
```
VITE_PORT = 8089
VITE_PUBLIC_PATH = /
VITE_ROUTER_HISTORY = "hash"

VITE_APP_BASE_API = 'http://localhost:8080'    # 新增
```

**意义：** 显式指定后端 API 地址，覆盖 Vite 默认代理配置。开发环境下直接请求 localhost:8080。

### 6.2 `src/assets/iconfont/iconfont.js` — 容错修复

**改动前：**
```javascript
console && console.log(t);
```

**改动后：**
```javascript
if (console) console.log(t);
```

**意义：** 某些 JavaScript 运行环境（如旧版 WebView）中 `console` 可能未定义。原代码的逗号表达式 `console && console.log(t)` 在严格模式下可能产生意外行为。改为 `if` 语句更安全、可读性更好。

---

## 七、本次会话增量：流式输出改造 + 修复 SYSTEM 消息存储

以下是本次对话中在 test1 已有改动**基础之上**再新增的修改。

### 7.1 问题背景

**问题 1：** AI 回复是阻塞式的——用户发送消息后必须等待 5~15 秒才能看到完整回复，期间界面卡死无反馈。

**问题 2：** 数据库 `chat_messages` 表中发现 `message_type = 'SYSTEM'` 的记录，内容是系统提示词"你是一个博学的音乐达人..."。这些提示词应该由代码管理而非存入数据库，每次对话都存一份造成数据冗余。

### 7.2 `memory/MysqlChatMemoryStore.java` — 修复 SYSTEM 消息存储

**改动前：**
```java
// ---- updateMessages() 中 ----
case SYSTEM:
    String sysContent = ((SystemMessage) message).text();
    if (sysContent != null) {
        aiService.saveMessage(chatId, ChatMessage.MessageType.SYSTEM, sysContent);
    }
    break;

// ---- getMessages() 中 ----
case SYSTEM:
    messages.add(SystemMessage.systemMessage(entity.getContent()));
    break;
```

**改动后：**
```java
// ---- updateMessages() 中 ----
case SYSTEM:
    // 系统消息不持久化到数据库，它由 @SystemMessage 注解在每次调用时注入
    break;

// ---- getMessages() 中 ----
case SYSTEM:
    // 系统消息由 @SystemMessage 注解每次注入，无需从数据库加载
    break;
```

**意义：**

| 维度 | 说明 |
|---|---|
| **为什么 LangChain4j 会把 SYSTEM 传给 MemoryStore** | LangChain4j 的 `MessageWindowChatMemory` 维护一个完整的消息列表，包含 SYSTEM+USER+AI+TOOL，它不知道哪些需要持久化，全量传给 `updateMessages()` |
| **为什么不存数据库** | `@SystemMessage` 注解的内容在 `MusicAssistant` 接口中已定义，每次 AI 调用时框架自动注入，无需从 DB 恢复。存了反而导致：每条对话记录都存一份相同提示词（数据冗余）、加载历史时出现两个 SYSTEM 消息（DB 中的 + 注解注入的） |
| **为什么也要在 getMessages 中过滤** | 历史数据已有 SYSTEM 记录，必须过滤掉避免与新注入的 SYSTEM 消息重复 |

### 7.3 `service/MusicAssistant.java` — 新增流式方法

**改动前：**
```java
public interface MusicAssistant {
    @SystemMessage("...")
    String chat(@MemoryId String chatId, @UserMessage String userMessage);
}
```

**改动后：**
```java
import dev.langchain4j.service.TokenStream;           // 新增导入

public interface MusicAssistant {
    @SystemMessage("...")
    String chat(@MemoryId String chatId, @UserMessage String userMessage);

    // === 以下为新增 ===
    @SystemMessage("...")                              // 相同的提示词
    TokenStream chatStream(@MemoryId String chatId, @UserMessage String userMessage);
}
```

**意义：**
- `TokenStream` 是 LangChain4j 的流式返回类型。AiServices 根据返回类型自动路由：`String` → 阻塞式模型，`TokenStream` → 流式模型
- 保留 `chat()` 方法确保旧的阻塞式端点仍然工作（向后兼容）
- 两个方法使用相同的 `@SystemMessage`，保证无论哪个模式 AI 行为一致

### 7.4 `config/LangChain4jConfig.java` — 配置流式模型

**改动前：**
```java
@Bean
public MusicAssistant musicAssistant(QwenChatModel model, ...) {
    LoggingQwenChatModel loggingModel = new LoggingQwenChatModel(model);
    return AiServices.builder(MusicAssistant.class)
            .chatLanguageModel(loggingModel)     // 只有阻塞模型
            .chatMemoryProvider(chatMemoryProvider)
            .tools(musicTools)
            .build();
}
```

**改动后：**
```java
// 新增 Bean：流式模型
@Bean
public QwenStreamingChatModel qwenStreamingChatModel() {
    return QwenStreamingChatModel.builder()
            .apiKey(apiKey).modelName(modelName)
            .build();
}

@Bean
public MusicAssistant musicAssistant(QwenChatModel model,
                                     QwenStreamingChatModel streamingModel, ...) {  // 新增参数
    LoggingQwenChatModel loggingModel = new LoggingQwenChatModel(model);
    return AiServices.builder(MusicAssistant.class)
            .chatLanguageModel(loggingModel)            // 阻塞式走这个
            .streamingChatLanguageModel(streamingModel)  // 流式走这个（新增）
            .chatMemoryProvider(chatMemoryProvider)
            .tools(musicTools)
            .build();
}
```

**意义：**
- `QwenChatModel` 只实现了 `ChatLanguageModel`（阻塞式），**不能**用于流式
- `QwenStreamingChatModel` 实现了 `StreamingChatLanguageModel`，才能支持 `TokenStream`
- AiServices 内部根据方法返回类型自动选择模型：返回 `String` 用 `chatLanguageModel`，返回 `TokenStream` 用 `streamingChatLanguageModel`

### 7.5 `controller/AiController.java` — 新增 SSE 流式端点

**改动前（文件已有的端点）：**
```java
@GetMapping("/simple/chat")
public String simpleChat(...) {
    return musicAssistant.chat(chatId, query);
}
```

**改动后（新增端点）：**
```java
@GetMapping(value = "/simple/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamChat(@RequestParam("query") String query,
                              HttpServletRequest request,
                              HttpServletResponse response) {
    Long userId = getUserId();
    String chatId = resolveChatIdForGuest(userId, request, response);

    SseEmitter emitter = new SseEmitter(120_000L);           // 2 分钟超时
    TokenStream tokenStream = musicAssistant.chatStream(chatId, query);

    tokenStream
        .onNext(token -> {
            // 每个 token 作为 SSE 事件发送
            emitter.send(SseEmitter.event().name("token").data(token));
        })
        .onComplete(responseMessage -> {
            // 完成时发送完整文本 + 关闭连接
            String fullText = responseMessage.content().text();
            emitter.send(SseEmitter.event().name("done").data(fullText));
            emitter.complete();
        })
        .onError(error -> {
            // 错误时发送错误事件
            emitter.send(SseEmitter.event().name("error")
                    .data(error.getMessage() != null ? error.getMessage() : "AI 回复出错"));
            emitter.completeWithError(error);
        })
        .start();

    return emitter;
}
```

**意义：**

| 维度 | 说明 |
|---|---|
| **SSE vs WebSocket** | 选用 SSE 而非 WebSocket 因为：AI 聊天是单向推送（服务器 → 客户端），不需要双向通信。SSE 基于 HTTP，更简单，无需额外协议升级 |
| **Event 命名** | `token` 表示增量文本（前端逐字追加），`done` 表示完成（前端可做完整性校验），`error` 表示失败（前端可重试或降级到阻塞模式） |
| **120 秒超时** | 阻塞端点是 60 秒，流式多给一倍时间。AI 回复含工具调用时可能需要多轮交互，120 秒覆盖绝大部分场景 |
| **SseEmitter 生命周期** | `start()` 触发 TokenStream 开始生成；`onComplete` 中手动 `complete()` 关闭连接；`onError` 中 `completeWithError()` 向上传递异常 |

### 7.6 `api/ai.ts` — 新增流式 API 函数

**改动前：** 只有 `aiSimpleChat`（axios GET，60 秒超时）。

**改动后：** 新增 `aiStreamChat` 函数（~70 行新代码）：

```typescript
export const aiStreamChat = (
  query: string, chatId: string,
  callbacks: { onToken, onDone, onError },
  signal?: AbortSignal,
): void => {
  fetch(`${baseURL}/test/simple/chat/stream?query=...&chatId=...`, {
    headers: {
      Accept: 'text/event-stream',
      ...(token ? { Authorization: token } : {}),
    },
    credentials: 'include',
    signal,
  }).then(async (response) => {
    const reader = response.body!.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''           // 缓冲区：处理跨 chunk 的不完整行

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''     // 最后一行可能不完整，保留到下次

      for (const line of lines) {
        if (line.startsWith('event:')) currentEvent = line.slice(6).trim()
        else if (line.startsWith('data:')) currentData = line.slice(5).trim()
        else if (line === '') {
          // 空行 = SSE 事件边界，触发回调
          switch (currentEvent) {
            case 'token': callbacks.onToken(currentData); break
            case 'done':  callbacks.onDone(currentData); break
            case 'error': callbacks.onError(new Error(currentData)); break
          }
        }
      }
    }
  }).catch((err) => {
    if (err.name === 'AbortError') return  // 主动取消不算错误
    callbacks.onError(err)
  })
}
```

**意义：**
- 使用 `fetch` + `ReadableStream` 而非 `EventSource`，因为 `EventSource` 不支持自定义 HTTP 头（无法传 Authorization）也不支持 `AbortController`
- `buffer` 机制处理 TCP 分包的边界问题：如果 `\n\n` 刚好被切在两块数据之间，buffer 保留不完整的最后一行到下一次拼接
- `TextDecoder({ stream: true })` 处理 UTF-8 多字节字符被截断的场景（如中文 3 字节字符被切成两块）

### 7.7 `AiWidget.vue` — send() 从阻塞式改为流式

**改动前（阻塞式）：**
```typescript
const send = async () => {
  messages.value.push({ role: 'user', content: text })
  loading.value = true
  try {
    const resp = await aiSimpleChat(text, chatId.value)      // 等 5~15 秒
    messages.value.push({ role: 'assistant', content: String(resp) })  // 一次性显示
  } finally {
    loading.value = false
  }
}
```

**改动后（流式）：**
```typescript
const abortController = ref<AbortController | null>(null)     // 新增

const send = () => {
  if (!text || loading.value) return

  // 取消之前的请求，防止串话
  if (abortController.value) abortController.value.abort()

  messages.value.push({ role: 'user', content: text })
  loading.value = true

  const controller = new AbortController()
  abortController.value = controller

  // 预添加空占位
  const assistantIdx = messages.value.length
  messages.value.push({ role: 'assistant', content: '' })

  aiStreamChat(text, chatId.value, {
    onToken: (token) => {
      messages.value[assistantIdx].content += token          // 逐字追加
    },
    onDone: () => {
      loading.value = false
    },
    onError: (error) => {
      messages.value[assistantIdx].content = '抱歉，AI 回复出错: ' + error.message
      loading.value = false
    },
  }, controller.signal)
}

const close = () => {
  if (abortController.value) abortController.value.abort()   // 新增：关闭时取消
  loading.value = false
  visible.value = false
}
```

**用户体验对比：**

| 维度 | 阻塞式（改动前） | 流式（改动后） |
|---|---|---|
| 首次文字出现 | 等 5~15 秒 | 0.5~1 秒首 token |
| 用户感知 | 卡死感强 | 像真人打字 |
| 长回复体验 | 一次性刷出大段文字 | 逐步推进，可边看边思考 |
| 取消操作 | 无法取消 | 关闭窗口即中断 |
| 中途弹错 | 只显示"失败" | 保留已生成的部分 + 错误提示 |

---

## 总结：test1 分支改动价值

| 序号 | 改动 | 核心价值 |
|---|---|---|
| 1 | AI 聊天模块 | 用户可以在音乐平台内直接用自然语言搜索歌曲、获取推荐，而非在搜索框中手动输入关键词 |
| 2 | LangChain4j + DashScope | 相比手写 HTTP 调用阿里云 API，代码量减少约 60%，且框架自动处理记忆管理、流式输出、工具调用 |
| 3 | 工具调用（Function Calling） | AI 不是"瞎编"答案，而是实时查询数据库后再回复，确保信息准确 |
| 4 | 聊天记忆持久化到 MySQL | 刷新页面、切换设备后对话不丢失，优于存内存或 Redis 的方案 |
| 5 | 流式输出（SSE） | 从"等 10 秒看结果"变成"0.5 秒开始逐字显示"，体验提升显著 |
| 6 | 游客支持 | 无需注册也能使用 AI 聊天，基于 Cookie 的会话隔离 |
| 7 | SYSTEM 消息过滤 | 减少数据库冗余，避免重复系统提示词污染对话上下文 |
