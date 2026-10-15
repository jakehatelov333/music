**A 阶段详细方案：封堵两个 P1 权限缺口**

日期：2026-10-15。状态：**待用户确认，尚未修改任何业务代码。**
依据：[项目检查报告](../2026-10-15-project-minio-audit.md) 问题 1、2；[修复计划书](./2026-10-15-bugfix-and-storage-cleanup-plan.md) A 阶段。

范围声明：本阶段**只做这两件事**。不涉及 JWT 令牌方案、不做统一认证入口、不迁移 `auth_version`、不做密钥外置与密码哈希迁移（以上均已按用户要求放弃）。

---

## 一、现状证据

### 1.1 P1-1：匿名可注册管理员

| 环节 | 位置 | 事实 |
| --- | --- | --- |
| 免登录配置 | `config/WebConfig.java:30` | `excludePathPatterns` 含 `"/admin/register"`，该路径**不经过任何拦截器** |
| 端点 | `controller/AdminController.java:64` | `@PostMapping("/register")`，类级 `@RequestMapping("/admin")` |
| 实现 | `service/impl/AdminServiceImpl.java:46-60` | 只查用户名重名，随后 `adminMapper.insert` |

链路完整：匿名调用者 → 跳过拦截器（无角色校验）→ 直接插入一条管理员记录 → 可用该账号登录管理端。`role-path-permissions` 的 `ROLE_ADMIN: ["/admin/"]` 对这条路径不生效，因为它根本没进拦截器。

补充事实（影响修复方案选择）：

- 全仓库检索 `adminService.register` 的调用点**只有** `AdminController.java:72` 一处，没有其他调用方。
- 管理端前端**没有任何** `/register` 调用，也没有注册页或路由（`grep -rin "register" vibe-music-admin/src` 只命中无关注释）。
- 因此下线该端点的影响面为零。

### 1.2 P1-2：用户资料更新可越权改他人

`service/impl/UserServiceImpl.java:208-236`：

```java
Map<String, Object> map = ThreadLocalUtil.get();
Long userId = TypeConversionUtil.toLong(map.get(JwtClaimsConstant.USER_ID));   // 209-211：认证身份
// …214/219/224：userId 只用于"重名是否是本人"的比较…
User user = new User();
BeanUtils.copyProperties(userDTO, user);   // 229：把请求体的 userId 一起复制进实体
user.setUpdateTime(LocalDateTime.now());
if (userMapper.updateById(user) == 0) {    // 232：按实体主键更新 → 主键来自请求体
```

`model/entity/User.java:42-43` 为 `@TableId(value = "id", type = IdType.AUTO) private Long userId;`，即 `updateById` 的目标行完全由 `userDTO.userId` 决定。认证身份虽已取出，却**从未写回实体**。

后果：任意已登录用户提交 `{"userId": 2, "username": "...", "email": "..."}` 即可改写 2 号用户的用户名、手机号、邮箱、简介。邮箱被改写后配合"重置密码"流程即可接管该账号。

需要澄清的一点：`UserDTO` 只含 `userId/username/phone/email/introduction` 五个字段，**不含** `password`、`userStatus`、`userAvatar`，所以 `copyProperties` 不会覆盖这三项。本次只需修 `userId` 一处，不必扩大改动。

兼容性约束：客户端 `pages/user/ProfilePanel.vue:21` 会把 `userStore.userInfo.userId`（本人的 id）放进表单，`:149` 一起提交。所以**请求体带本人 id 必须继续可用**，不能改成"一律拒绝带 id 的请求"。

---

## 二、修复设计

### 2.1 P1-1：下线管理员注册端点

1. 删除 `AdminController.register` 方法及其 `@PostMapping("/register")`。
2. 从 `WebConfig.excludePathPatterns` 中删除 `"/admin/register"`。
3. `IAdminService.register` / `AdminServiceImpl.register`：**保留**，在 Javadoc 加注「不再暴露为 HTTP 路由，仅供后续受控初始化入口复用」。理由：删掉端点后它是唯一的程序化建号路径，未来做受控入口可直接复用，且可避免连带修改接口签名。
4. 不新增任何"管理员开户"产品功能（与计划书一致）。

预期行为：匿名 `POST /admin/register` → **404**（无匹配 handler 时拦截器不会被触发，直接返回未找到）。这比 401 更好——不泄露该路径曾经存在。`/admin/login`、`/admin/logout` 的白名单条目保持不变，管理员登录不受影响。

> 备选方案（若你希望保留"管理员建管理员"能力）：保留端点、只删除白名单条目。此时该路径落入拦截器，仅持 `ROLE_ADMIN` 令牌可调用，返回 401/403。代价是凭空多出一个没有 UI、没有审计的管理员开户功能，与计划书结论相反。**我建议按下线处理。**

### 2.2 P1-2：身份强制取自认证上下文

`UserServiceImpl.updateUserInfo` 改为：

```java
Map<String, Object> map = ThreadLocalUtil.get();
Long userId = TypeConversionUtil.toLong(map.get(JwtClaimsConstant.USER_ID));

// 身份只能来自认证上下文。请求体的 userId 仅为兼容旧客户端：缺省或等于本人才接受。
if (userDTO.getUserId() != null && !userDTO.getUserId().equals(userId)) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, MessageConstant.NO_PERMISSION);
}
// …三个唯一性检查保持不变…

User user = new User();
BeanUtils.copyProperties(userDTO, user);
user.setUserId(userId);                      // 显式覆盖，绝不依赖 DTO
user.setUpdateTime(LocalDateTime.now());
```

三个设计点：

- **越权判断放在唯一性检查之前**：否则会拿他人身份去跑三次 `selectOne`，等于给越权请求免费提供用户名/邮箱占用探测。
- **403 走 `ResponseStatusException`**：`handler/GlobalExceptionHandler.java:40-44` 已有该异常的分支，直接产出 HTTP 403 + `Result.error(message)`，无需新增异常类、无需改控制器签名。客户端 `utils/http.ts:83-85` 也已有 403 分支。
- **`setUserId` 必须写在 `copyProperties` 之后**：`UserDTO.userId` 为 null 时 `copyProperties` 会把实体的 `userId` 置空，此时 `updateById` 会因主键缺失而失败或走异常路径，所以不能靠"DTO 没传就不会覆盖"。

顺带的小加固（可选）：`map` 为 null 时当前会抛 NPE → 500。加一行 `userId == null → 401`，避免把鉴权缺失伪装成服务端故障。

### 2.3 明确不改的部分

- `UserServiceImpl.updateUser`（`:577`，管理员编辑用户）**保持按请求体 userId 选择目标用户**的语义——这是管理员应有的能力，且 `/admin/updateUser` 已被 `role-path-permissions` 的 `ROLE_ADMIN: ["/admin/"]` 前缀匹配拦住。本阶段只补一条"普通用户令牌调它会被拒"的测试来锁住这个边界。
- 不改前端（`ProfilePanel.vue` 继续传本人 id，后端兼容）。
- 不动 `LoginInterceptor`、`JwtUtil`、令牌存储、`auth_version`。

---

## 三、改动文件清单

| 文件 | 改动 | 目的 |
| --- | --- | --- |
| `controller/AdminController.java` | 删除 `register` 方法（`:53-73` 注释与方法体） | 下线匿名注册入口 |
| `config/WebConfig.java` | `excludePathPatterns` 删除 `"/admin/register"` | 该路径不再免鉴权 |
| `service/impl/AdminServiceImpl.java` | `register` 方法加 Javadoc 警示 | 标明无 HTTP 暴露 |
| `service/IAdminService.java` | `register` 方法加 Javadoc 警示 | 同上 |
| `service/impl/UserServiceImpl.java` | `updateUserInfo` 增加越权判断 + `user.setUserId(userId)` | 身份强制来自认证上下文 |
| `test/.../security/AdminRegisterExposureTest.java` | 新增 | 锁死匿名注册不可达 |
| `test/.../service/impl/UserProfileUpdateIsolationTest.java` | 新增 | 锁死越权改资料 |

无数据库变更、无前端变更、无配置项变更。

---

## 四、测试计划

遵循仓库既有约定：纯 JUnit 5 + Mockito，`ReflectionTestUtils.setField` 注入依赖，`ThreadLocalUtil.set(...)` 构造认证上下文，不起 Spring 容器、不连数据库。拦截器层测试沿用 `security/PlaylistEndpointSecurityTest.java` 的做法（`AnnotationConfigWebApplicationContext` + 生产 `WebConfig` + mock `StringRedisTemplate`）。

**`AdminRegisterExposureTest`（新增）**

1. 匿名 `POST /admin/register` → 404，且 `verifyNoInteractions(adminService)`。
2. 携带有效 `ROLE_ADMIN` 令牌 `POST /admin/register` → 仍 404（端点已下线，不因角色而复活）。
3. `GET /admin/login` 之外，`POST /admin/login` 匿名可达（白名单未误伤）——用 stub 控制器断言请求确实到达控制器。
4. 断言 `WebConfig` 的白名单不再包含 `/admin/register`（通过生产配置的 MockMvc 行为间接验证，不读源码字符串）。

**`UserProfileUpdateIsolationTest`（新增）**

1. claims userId=1，请求体 `userId=2` → 抛 `ResponseStatusException` 且状态为 403；`verify(userMapper, never()).updateById(any())`。
2. claims userId=1，请求体 `userId=1` → 成功；`ArgumentCaptor<User>` 断言实体 `userId == 1`。
3. claims userId=1，请求体 `userId=null` → 成功；断言实体 `userId == 1`（防止主键丢失）。
4. claims userId=1，请求体 `userId=2` 且用户名与现有用户重名 → 仍为 403 而非"用户名已存在"（证明越权判断排在唯一性检查之前）。

---

## 五、验收用例

| 场景 | 期望结果 | 验证方式 |
| --- | --- | --- |
| 匿名 `POST /admin/register` | 404，`tb_admin` 行数不变 | curl + `SELECT COUNT(*) FROM tb_admin` |
| 普通用户令牌 `POST /admin/register` | 404 | curl 带 token |
| 管理员 `POST /admin/login` | 成功，返回 token | curl |
| 用户 A 提交用户 B 的 id | 403，B 的资料不变 | 造两个测试账号，提交后查库 |
| 用户 A 提交本人 id / 不传 id | 成功，仅 A 的资料变化 | 同上，前端个人中心页实测 |
| 用户 A 带普通令牌 `POST /admin/updateUser` | 403 | curl |
| 管理端用户编辑页 | 功能正常 | 手工点检 |

---

## 六、存量数据核查结果（2026-10-15 执行，只读）

**结论：没有发现该端点被利用的痕迹。**

| 核查项 | 结果 |
| --- | --- |
| `tb_admin` 账号数 | 81 |
| 其中来自仓库种子文件 `sql/vibe_music.sql` 的 | 80 条（id 58–170，与数据库逐条一致） |
| 数据库中存在但种子文件中没有的 | 仅 `lsxc`（id=171），为使用者自建账号 |
| `tb_admin` 额外新增账号 | **0** |
| `tb_user` 总数 | 65 |
| 资料被改动过的用户（`update_time > create_time + 60s`） | 7 个，均为 2025-03 至 2026-05 的历史测试数据 |
| 2026-10-15 当天被改动的用户 | **0** |
| `tb_admin` 表结构 | 只有 `id / username / password`，**无任何时间字段**，无法判断创建时间 |

两个补充事实：

1. `tb_admin` 的 80 个种子账号（`admin_172`、`admin_888` 等）**共享同一个密码哈希** `df10ef8509dc176d733d59549e7dbfaf`，且账号至今可用。该哈希未在常见口令表中命中，但它无盐、且 80 个账号共用，属于独立于本次修复的存量风险。
2. id 断档（63/64、70/71…）在种子文件里就已存在，不是删除痕迹。

因此本次修复的定性与原计划一致：**下线入口**，不需要按"已被入侵"处理（无需删除账号或轮换凭据）。

> 遗留建议（不在本阶段范围，需你决定）：清理这 80 个种子管理员账号，只保留实际在用的账号。这是独立于权限修复的一项数据清理。

---

## 七、风险与回滚

| 风险 | 评估 | 应对 |
| --- | --- | --- |
| 下线注册端点后无法通过界面新建管理员 | 已确认前端无引用，当前无此需求 | 需要时用 SQL 建号；后续如需产品化再做受控入口 |
| 旧客户端提交本人 id 被误拒 | 兼容逻辑覆盖 | 测试用例 2、3 锁定 |
| 前端传 id 与后端判断不一致 | 后端以认证上下文为准，前端传值不参与授权决策 | — |
| 回滚 | 改动集中在 2 个业务方法 + 2 处配置/端点 | `git revert` 单次提交即可 |

---

## 八、顺带发现的观察项（**不在本次范围**，仅登记）

1. `/admin/logout`、`/user/logout` 也在免鉴权白名单内，匿名携带任意 token 调用会返回"登出失败"，无实际危害，但语义上不该免鉴权。
2. `RolePermissionManager.hasPermission` 用 `requestURI.startsWith(path)` 做前缀匹配，`/admin/` 会放行**所有**以它开头的新路径——新增管理端接口会自动对管理员开放，属于"默认开放"而非"默认拒绝"。
3. `LoginInterceptor` 之外仍有 7 处直接 `JwtUtil.parseToken`（`SongServiceImpl`×3、`SearchServiceImpl`、`ArtistServiceImpl`、`AlbumServiceImpl`），不校验 Redis 白名单。按你的决定本轮不处理，已记录为已知风险。

---

## 九、三个决定的落地

1. **P1-1 按下线处理** —— 删除端点 + 移出白名单。
2. **`AdminServiceImpl.register` 保留** —— 连同 `IAdminService.register` 加 Javadoc 警示，标明不再暴露 HTTP 路由。
3. **先做存量核查再改代码** —— 已完成，见第六节。

---

## 十、实施结果（2026-10-15）

状态：**已完成，待提交**。所有改动均未触及 JWT、令牌存储、`LoginInterceptor`、密码哈希与密钥配置。

### 10.1 实际改动

| 文件 | 改动 |
| --- | --- |
| `controller/AdminController.java` | 删除 `register` 方法与 `@PostMapping("/register")`，原位置留注释说明下线原因 |
| `config/WebConfig.java` | `excludePathPatterns` 移除 `"/admin/register"`；顶部注释同步更新 |
| `service/IAdminService.java` | `register` 加 Javadoc 警示（不再暴露 HTTP 路由） |
| `service/impl/AdminServiceImpl.java` | 同上 |
| `service/impl/UserServiceImpl.java` | `updateUserInfo`：新增 401/403 判断，越权判断排在唯一性检查之前，`copyProperties` 之后显式 `setUserId(userId)`；新增 `HttpStatus`、`ResponseStatusException` 两个 import |
| `test/.../security/AdminRegisterExposureTest.java` | 新增（2 个用例） |
| `test/.../service/impl/UserProfileUpdateIsolationTest.java` | 新增（5 个用例） |

无数据库变更、无前端变更、无配置项变更。

### 10.2 自动化测试

- 新增用例：7 个，全部通过。
- 全量回归：`mvn -o test -Dtest='!RedisConnectionTest,!VibeMusicServerApplicationTests'` → **Tests run: 288, Failures: 0, Errors: 0, Skipped: 1**，BUILD SUCCESS（1 个 skip 是既有的 `LyricsMatchScoreProbe`）。

### 10.3 端到端验收（真实后端 + 真实数据库）

用 SQL 造两个临时账号（A/B），跑完立即删除。6/6 通过：

| 场景 | 结果 |
| --- | --- |
| 匿名 `POST /admin/register` | **HTTP 401**（端点已下线，匿名又被拦截器拒绝），`tb_admin` 仍为 81 条、无新增账号 |
| 临时账号 A 登录 | HTTP 200，拿到 token |
| A 提交 B 的 `userId` + B 的资料 | **HTTP 403** `{"code":1,"message":"您没有权限访问此资源"}` |
| A 提交 B 的 `userId` + 自己的资料 | **HTTP 403** |
| A 提交本人 `userId` | HTTP 200，`code=0` |
| A 不传 `userId` | HTTP 200，`code=0` |
| 数据库复核 | B 的 username/phone/email/introduction 与 `update_time` **完全未变**；A 的简介按预期更新 |

验收脚本与 SQL 归档在仓库根 `scripts/`（该目录在 `.gitignore` 内）：`phaseA-e2e.cjs`、`phaseA-e2e-seed.sql`、`phaseA-e2e-verify.sql`、`phaseA-e2e-cleanup.sql`。

### 10.4 与方案的偏差（重要）

方案里预期匿名调用返回 **404**，实际返回 **401**。原因：Spring Boot 默认注册静态资源兜底 handler（`/**` → `ResourceHttpRequestHandler`），因此"路径不存在"时 `DispatcherServlet` 仍能取到 handler，拦截器照常执行并先行拒绝。已用 `GET /nonexistent-xyz → 401` 验证这个机制。

这不影响结论（401 同样不可能创建管理员，且不泄露该路径的历史），验收脚本已按 401/404 都接受处理。另外注意：`AdminRegisterExposureTest` 里那条 404 断言用的是 `standaloneSetup`（不注册静态资源 handler），所以它验证的是"真实控制器不再映射该路径"，而不是生产环境的 HTTP 状态码——两条断言互补，不要误读为生产返回 404。

### 10.5 环境问题（与本次改动无关）

验收过程中发现本机 **Redis 未运行**，导致登录接口返回 503（`RedisConnectionFailureException`）。启动 Redis 时另发现一个配置陷阱：

- `redis.windows.conf` 有 `requirepass 123456`；
- `redis.windows-service.conf` 的 `requirepass` 是注释掉的（无密码）。

而 `application.yml` 的 redis 配置**没有 password 字段**，所以必须用 **`redis.windows-service.conf`** 启动，用 `redis.windows.conf` 会因认证失败而全站 503。验收使用后者启动，未修改任何 Redis 配置。

### 10.6 未做（按用户决定）

JWT 令牌方案改造、统一认证入口、`auth_version`、密钥外置、密码 BCrypt 迁移、计划书 B 阶段，全部未做。
