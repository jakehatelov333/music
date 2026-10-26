package cn.edu.seig.vibemusic.service.impl;


import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.enumeration.RoleEnum;
import cn.edu.seig.vibemusic.enumeration.UserStatusEnum;
import cn.edu.seig.vibemusic.mapper.UserMapper;
import cn.edu.seig.vibemusic.model.dto.*;
import cn.edu.seig.vibemusic.model.entity.User;
import cn.edu.seig.vibemusic.model.vo.AccountDeletionResultVO;
import cn.edu.seig.vibemusic.model.vo.UserManagementVO;
import cn.edu.seig.vibemusic.model.vo.UserVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.security.AuthSessionService;
import cn.edu.seig.vibemusic.security.CurrentUser;
import cn.edu.seig.vibemusic.security.ImageStorageService;
import cn.edu.seig.vibemusic.security.PasswordService;
import cn.edu.seig.vibemusic.security.TokenExtractor;
import cn.edu.seig.vibemusic.security.TransactionCallbacks;
import cn.edu.seig.vibemusic.security.VerificationCodeService;
import cn.edu.seig.vibemusic.security.VerificationCodeService.VerificationClaim;
import cn.edu.seig.vibemusic.security.VerificationPurpose;
import cn.edu.seig.vibemusic.service.EmailService;
import cn.edu.seig.vibemusic.service.IUserService;
import cn.edu.seig.vibemusic.service.AccountDeletionService;
import cn.edu.seig.vibemusic.service.AccountDeletionService.AccountDeletionOutcome;
import cn.edu.seig.vibemusic.service.AccountDeletionService.BatchDeletionOutcome;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.util.SortWhitelistUtil;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import cn.edu.seig.vibemusic.util.TypeConversionUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Service
@Slf4j
@CacheConfig(cacheNames = "userCache")
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private EmailService emailService;
    @Autowired
    private MinioService minioService;
    /** 账号删除的唯一实现：管理员单删/批删/自助注销都委托它，入口只负责鉴权。 */
    @Autowired
    private AccountDeletionService accountDeletionService;
    @Autowired
    private PasswordService passwordService;
    @Autowired
    private AuthSessionService authSessionService;
    @Autowired
    private VerificationCodeService verificationCodeService;
    @Autowired
    private ImageStorageService imageStorageService;

    /**
     * 发送验证码
     *
     * @param email 用户邮箱
     * @return 结果
     */
    @Override
    public Result sendVerificationCode(String email, VerificationPurpose purpose) {
        String verificationCode = verificationCodeService.issue(purpose, email);
        try {
            boolean sent = emailService.sendEmail(email, "【Vibe Music】验证码",
                    "您的验证码为：" + verificationCode + "，5 分钟内有效。请勿向他人泄露。");
            if (!sent) {
                verificationCodeService.consume(purpose, email, verificationCode);
                return Result.error(MessageConstant.EMAIL_SEND_FAILED);
            }
        } catch (RuntimeException e) {
            verificationCodeService.consume(purpose, email, verificationCode);
            return Result.error(MessageConstant.EMAIL_SEND_FAILED);
        }
        return Result.success(MessageConstant.EMAIL_SEND_SUCCESS);
    }

    /**
     * 用户注册
     *
     * @param userRegisterDTO 用户注册信息
     * @return 结果
     */
    @Override
    @CacheEvict(cacheNames = "userCache", allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public Result register(UserRegisterDTO userRegisterDTO) {
        User userByUsername = userMapper.selectOne(new QueryWrapper<User>().eq("username", userRegisterDTO.getUsername()));
        if (userByUsername != null) {
            return Result.error(MessageConstant.USERNAME + MessageConstant.ALREADY_EXISTS);
        }

        User userByEmail = userMapper.selectOne(new QueryWrapper<User>().eq("email", userRegisterDTO.getEmail()));
        if (userByEmail != null) {
            return Result.error(MessageConstant.EMAIL + MessageConstant.ALREADY_EXISTS);
        }

        VerificationClaim verificationClaim = verificationCodeService.claim(
                VerificationPurpose.REGISTER, userRegisterDTO.getEmail(), userRegisterDTO.getVerificationCode());
        String passwordHash = passwordService.encode(userRegisterDTO.getPassword());
        User user = new User();
        user.setUsername(userRegisterDTO.getUsername()).setPassword(passwordHash).setEmail(userRegisterDTO.getEmail())
                .setCreateTime(LocalDateTime.now()).setUpdateTime(LocalDateTime.now())
                .setUserStatus(UserStatusEnum.ENABLE).setAuthVersion(0L);

        if (userMapper.insert(user) == 0) {
            verificationCodeService.releaseClaim(verificationClaim);
            return Result.error(MessageConstant.REGISTER + MessageConstant.FAILED);
        }
        TransactionCallbacks.afterCommit(
                () -> verificationCodeService.finalizeClaim(verificationClaim),
                () -> verificationCodeService.releaseClaim(verificationClaim));
        return Result.success(MessageConstant.REGISTER + MessageConstant.SUCCESS);
    }

    /**
     * 用户登录
     *
     * @param userLoginDTO 用户登录信息
     * @return 结果
     */
    @Override
    public Result login(UserLoginDTO userLoginDTO) {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("email", userLoginDTO.getEmail()));
        if (user == null) {
            passwordService.dummyMatch(userLoginDTO.getPassword());
            return Result.error(MessageConstant.LOGIN + MessageConstant.FAILED);
        }
        boolean passwordMatches = passwordService.matches(userLoginDTO.getPassword(), user.getPassword());
        if (!passwordMatches || user.getUserStatus() != UserStatusEnum.ENABLE) {
            return Result.error(MessageConstant.LOGIN + MessageConstant.FAILED);
        }
        if (passwordService.needsUpgrade(user.getPassword())) {
            userMapper.update(new User().setPassword(passwordService.encode(userLoginDTO.getPassword()))
                    .setUpdateTime(LocalDateTime.now()), new QueryWrapper<User>().eq("id", user.getUserId()));
        }
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.ROLE, RoleEnum.USER.getRole());
        claims.put(JwtClaimsConstant.USER_ID, user.getUserId());
        claims.put(JwtClaimsConstant.USERNAME, user.getUsername());
        claims.put(JwtClaimsConstant.EMAIL, user.getEmail());
        Long authVersion = userMapper.selectAuthVersionById(user.getUserId());
        if (authVersion == null) {
            throw new IllegalStateException("账号缺少 auth_version，请先执行 phase14_auth_hardening.sql");
        }
        claims.put(JwtClaimsConstant.AUTH_VERSION, authVersion);
        String token = authSessionService.createSession(claims);
        return Result.success(MessageConstant.LOGIN + MessageConstant.SUCCESS, token);
    }

    /**
     * 用户信息
     *
     * @return 结果
     */
    @Override
    public Result<UserVO> userInfo() {
        Map<String, Object> map = ThreadLocalUtil.get();
        Object userIdObj = map.get(JwtClaimsConstant.USER_ID);
        Long userId = TypeConversionUtil.toLong(userIdObj);
        User user = userMapper.selectById(userId);
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);

        return Result.success(userVO);
    }

    /**
     * 更新用户信息。
     *
     * <p>身份只能来自认证上下文。请求体里的 {@code userId} 仅为兼容旧客户端而保留：
     * 缺省或等于本人才接受，指向他人一律 403。实体主键在 {@code copyProperties} 之后被显式覆盖，
     * 绝不依赖 DTO 是否传值（DTO 不传时 copyProperties 会把主键置空）。
     */
    @Override
    @CacheEvict(cacheNames = "userCache", allEntries = true)
    public Result updateUserInfo(UserDTO userDTO) {
        Map<String, Object> map = ThreadLocalUtil.get();
        Long userId = map == null ? null : TypeConversionUtil.toLong(map.get(JwtClaimsConstant.USER_ID));
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, MessageConstant.NOT_LOGIN);
        }

        // 越权判断必须排在唯一性检查之前：否则会拿他人身份去查重，等于给越权请求免费提供占用探测。
        if (userDTO.getUserId() != null && !userDTO.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, MessageConstant.NO_PERMISSION);
        }

        User userByUsername = userMapper.selectOne(new QueryWrapper<User>().eq("username", userDTO.getUsername()));
        if (userByUsername != null && !userByUsername.getUserId().equals(userId)) {
            return Result.error(MessageConstant.USERNAME + MessageConstant.ALREADY_EXISTS);
        }

        User userByPhone = userMapper.selectOne(new QueryWrapper<User>().eq("phone", userDTO.getPhone()));
        if (userByPhone != null && !userByPhone.getUserId().equals(userId)) {
            return Result.error(MessageConstant.PHONE + MessageConstant.ALREADY_EXISTS);
        }

        User user = new User();
        BeanUtils.copyProperties(userDTO, user);
        user.setUserId(userId); // 显式覆盖：实体主键只能来自认证上下文
        user.setEmail(null); // 邮箱只能走受验证的换绑流程
        user.setUpdateTime(LocalDateTime.now());

        if (userMapper.updateById(user) == 0) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 更新用户头像
     *
     * @param avatarUrl 用户头像
     * @return 结果
     */
    @Override
    @CacheEvict(cacheNames = "userCache", allEntries = true)
    public Result updateUserAvatar(MultipartFile avatar) {
        Long userId = CurrentUser.requireUserId();
        String avatarUrl = imageStorageService.storeAvatar(avatar, "users");

        User user = userMapper.selectById(userId);
        if (user == null) {
            minioService.deleteFile(avatarUrl);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, MessageConstant.SESSION_EXPIRED);
        }
        String userAvatar = user.getUserAvatar();
        if (userMapper.update(new User().setUserAvatar(avatarUrl).setUpdateTime(LocalDateTime.now()),
                new QueryWrapper<User>().eq("id", userId)) == 0) {
            minioService.deleteFile(avatarUrl);
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }
        if (userAvatar != null && !userAvatar.isEmpty() && !userAvatar.equals(avatarUrl)) {
            minioService.deleteFile(userAvatar);
        }
        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 更新用户密码
     *
     * @param userPasswordDTO 用户密码信息
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateUserPassword(UserPasswordDTO userPasswordDTO) {
        Long userId = CurrentUser.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.error(MessageConstant.USER + MessageConstant.NOT_EXIST);
        }
        if (!passwordService.matches(userPasswordDTO.getOldPassword(), user.getPassword())) {
            return Result.error(MessageConstant.OLD_PASSWORD_ERROR);
        }

        if (passwordService.matches(userPasswordDTO.getNewPassword(), user.getPassword())) {
            return Result.error(MessageConstant.NEW_PASSWORD_ERROR);
        }

        if (!userPasswordDTO.getRepeatPassword().equals(userPasswordDTO.getNewPassword())) {
            return Result.error(MessageConstant.PASSWORD_NOT_MATCH);
        }

        if (userMapper.update(new User().setPassword(passwordService.encode(userPasswordDTO.getNewPassword())).setUpdateTime(LocalDateTime.now()),
                new QueryWrapper<User>().eq("id", userId)) == 0) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }
        requireVersionIncrement(userMapper.incrementAuthVersion(userId));
        TransactionCallbacks.afterCommit(() -> authSessionService.revokeAllUserSessions(userId));

        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 重置用户密码
     *
     * @param userResetPasswordDTO 用户密码信息
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result resetUserPassword(UserResetPasswordDTO userResetPasswordDTO) {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("email", userResetPasswordDTO.getEmail()));
        if (user == null) {
            return Result.error(MessageConstant.EMAIL + MessageConstant.NOT_EXIST);
        }

        if (!userResetPasswordDTO.getRepeatPassword().equals(userResetPasswordDTO.getNewPassword())) {
            return Result.error(MessageConstant.PASSWORD_NOT_MATCH);
        }

        VerificationClaim verificationClaim = verificationCodeService.claim(
                VerificationPurpose.RESET_PASSWORD, userResetPasswordDTO.getEmail(),
                userResetPasswordDTO.getVerificationCode());
        if (userMapper.update(new User().setPassword(passwordService.encode(userResetPasswordDTO.getNewPassword())).setUpdateTime(LocalDateTime.now()),
                new QueryWrapper<User>().eq("id", user.getUserId())) == 0) {
            verificationCodeService.releaseClaim(verificationClaim);
            return Result.error(MessageConstant.PASSWORD + MessageConstant.RESET + MessageConstant.FAILED);
        }
        requireVersionIncrement(userMapper.incrementAuthVersion(user.getUserId()));
        TransactionCallbacks.afterCommit(
                () -> {
                    verificationCodeService.finalizeClaim(verificationClaim);
                    authSessionService.revokeAllUserSessions(user.getUserId());
                },
                () -> verificationCodeService.releaseClaim(verificationClaim));

        return Result.success(MessageConstant.PASSWORD + MessageConstant.RESET + MessageConstant.SUCCESS);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result changeEmail(UserEmailChangeDTO dto) {
        Long userId = CurrentUser.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, MessageConstant.SESSION_EXPIRED);
        }
        if (!passwordService.matches(dto.getCurrentPassword(), user.getPassword())) {
            return Result.error(MessageConstant.OLD_PASSWORD_ERROR);
        }
        String oldEmail = user.getEmail();
        String newEmail = dto.getNewEmail().trim().toLowerCase();
        if (!oldEmail.equalsIgnoreCase(dto.getOldEmail().trim())) {
            return Result.error(MessageConstant.EMAIL + MessageConstant.ERROR);
        }
        if (oldEmail.equalsIgnoreCase(newEmail)) {
            return Result.error(MessageConstant.EMAIL + MessageConstant.ERROR);
        }
        User occupied = userMapper.selectOne(new QueryWrapper<User>().eq("email", newEmail));
        if (occupied != null && !occupied.getUserId().equals(userId)) {
            return Result.error(MessageConstant.EMAIL + MessageConstant.ALREADY_EXISTS);
        }

        VerificationClaim oldClaim = verificationCodeService.claim(
                VerificationPurpose.EMAIL_CHANGE_OLD, oldEmail, dto.getOldEmailCode());
        VerificationClaim newClaim;
        try {
            newClaim = verificationCodeService.claim(
                    VerificationPurpose.EMAIL_CHANGE_NEW, newEmail, dto.getNewEmailCode());
        } catch (RuntimeException e) {
            verificationCodeService.releaseClaim(oldClaim);
            throw e;
        }

        if (userMapper.update(new User().setEmail(newEmail).setUpdateTime(LocalDateTime.now()),
                new QueryWrapper<User>().eq("id", userId)) == 0) {
            verificationCodeService.releaseClaim(oldClaim);
            verificationCodeService.releaseClaim(newClaim);
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }
        requireVersionIncrement(userMapper.incrementAuthVersion(userId));
        TransactionCallbacks.afterCommit(
                () -> {
                    verificationCodeService.finalizeClaim(oldClaim);
                    verificationCodeService.finalizeClaim(newClaim);
                    authSessionService.revokeAllUserSessions(userId);
                },
                () -> {
                    verificationCodeService.releaseClaim(oldClaim);
                    verificationCodeService.releaseClaim(newClaim);
                });
        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 登出
     *
     * @param token 认证token
     * @return 结果
     */
    @Override
    @CacheEvict(cacheNames = {"userCache", "userFavoriteCache", "songCache", "artistCache", "playlistCache"}, allEntries = true)
    public Result logout(String token) {
        authSessionService.logout(TokenExtractor.extract(token));
        return Result.success(MessageConstant.LOGOUT + MessageConstant.SUCCESS);
    }

    /**
     * 注销账户
     *
     * <p>顺序与边界：
     * <ol>
     *     <li>DB 事务：删反馈（RESTRICT，必须先删）→ 清理无外键表（会话/消息/任务指标）→ 删用户
     *         （其余级联表由 FK ON DELETE CASCADE 处理）。任一步异常 → 整个事务回滚，不留部分删除。</li>
     *     <li>DB 提交后清理外部资源：先 Redis token（安全关键），再 MinIO 头像（孤儿文件无害）。</li>
     * </ol>
     * 外部清理失败不回滚（DB 已提交且不可逆），而是落一条待重试记录，返回
     * {@code cleanupPending=true}，绝不报成"注销失败"。
     *
     * @param token 认证token
     * @return 结果
     */
    @Override
    @CacheEvict(cacheNames = "userCache", allEntries = true)
    public Result<AccountDeletionResultVO> deleteAccount(String token) {
        Map<String, Object> map = ThreadLocalUtil.get();
        Object userIdObj = map.get(JwtClaimsConstant.USER_ID);
        Long userId = TypeConversionUtil.toLong(userIdObj);

        if (userId == null) {
            return Result.error(MessageConstant.ACCOUNT_DELETE_FAILED);
        }

        // 统一委托 AccountDeletionService：与管理员单删/批删共用同一套清理顺序，
        // 补齐历史上漏掉的「个人歌单自定义封面」收集。封面必须在删用户之前收集 ——
        // tb_playlist.owner_user_id 是 ON DELETE CASCADE，歌单行一删 cover_url 就查不到了。
        AccountDeletionOutcome outcome = accountDeletionService.deleteAccount(userId, token, true);

        if (!outcome.existed()) {
            // 幂等：用户已不存在（重复注销，或上次注销后 token 未被成功作废）。
            // 视为已注销成功：不报错、不二次误删、也不动 token。
            return Result.success(MessageConstant.ACCOUNT_DELETED,
                    new AccountDeletionResultVO(true, false, List.of()));
        }
        if (!outcome.deleted()) {
            log.error("账号注销事务失败，userId={}，原因：{}", userId, outcome.failedMessage());
            return Result.error(MessageConstant.ACCOUNT_DELETE_FAILED);
        }

        if (outcome.pendingItems().isEmpty()) {
            return Result.success(MessageConstant.ACCOUNT_DELETED,
                    new AccountDeletionResultVO(true, false, List.of()));
        }
        return Result.success(MessageConstant.ACCOUNT_DELETED_PARTIAL,
                new AccountDeletionResultVO(true, true, List.copyOf(outcome.pendingItems())));
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static void requireVersionIncrement(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("账号认证版本递增失败");
        }
    }

    /**
     * 获取所有用户数量
     *
     * @return 用户数量
     */
    @Override
    public Result<Long> getAllUsersCount() {
        return Result.success(userMapper.selectCount(new QueryWrapper<>()));
    }

    /**
     * 分页查询所有用户
     *
     * @param userSearchDTO 用户查询条件
     * @return 用户分页信息
     */
    @Override
    // 排序参数必须并入缓存 key：同一筛选条件不同排序会命中同一 key，导致排序"不生效"。
    @Cacheable(key = "#userSearchDTO.pageNum + '-' + #userSearchDTO.pageSize + '-' + #userSearchDTO.username + '-' + #userSearchDTO.phone + '-' + #userSearchDTO.userStatus + '-' + #userSearchDTO.sortField + '-' + #userSearchDTO.order + '-v2'")
    public Result<PageResult<UserManagementVO>> getAllUsers(UserSearchDTO userSearchDTO) {
        // 分页查询
        Page<User> page = new Page<>(userSearchDTO.getPageNum(), userSearchDTO.getPageSize());
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        // 根据 userSearchDTO 的条件构建查询条件
        if (userSearchDTO.getUsername() != null) {
            queryWrapper.like("username", userSearchDTO.getUsername());
        }
        if (userSearchDTO.getPhone() != null) {
            queryWrapper.like("phone", userSearchDTO.getPhone());
        }
        if (userSearchDTO.getUserStatus() != null) {
            queryWrapper.eq("status", userSearchDTO.getUserStatus().getId());
        }

        // 排序：仅接受白名单字段，未命中/缺省时按创建时间倒序（历史行为）
        Map<String, String> sortWhitelist = Map.of(
                "userId", "id",
                "username", "CONVERT(username USING gbk)", // 拼音排序：中文按拼音、英文按字母
                "createTime", "create_time",
                "updateTime", "update_time"
        );
        Map.Entry<String, Boolean> sort = SortWhitelistUtil.toColumnAndDirection(
                userSearchDTO.getSortField(), userSearchDTO.getOrder(), sortWhitelist);
        if (sort == null) {
            queryWrapper.orderByDesc("create_time");
        } else if (sort.getValue()) {
            queryWrapper.orderByAsc(sort.getKey());
        } else {
            queryWrapper.orderByDesc(sort.getKey());
        }

        IPage<User> userPage = userMapper.selectPage(page, queryWrapper);
        if (userPage.getRecords().size() == 0) {
            return Result.success(MessageConstant.DATA_NOT_FOUND, new PageResult<>(0L, null));
        }

        // 转换为 UserManagementVO
        List<UserManagementVO> userVOList = userPage.getRecords().stream()
                .map(user -> {
                    UserManagementVO userVO = new UserManagementVO();
                    BeanUtils.copyProperties(user, userVO);
                    return userVO;
                }).toList();

        return Result.success(new PageResult<>(userPage.getTotal(), userVOList));
    }

    /**
     * 添加用户
     *
     * @param userAddDTO 用户信息
     * @return 结果
     */
    @Override
    @CacheEvict(cacheNames = "userCache", allEntries = true)
    public Result addUser(UserAddDTO userAddDTO) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("username", userAddDTO.getUsername())
                .or()
                .eq("phone", userAddDTO.getPhone())
                .or()
                .eq("email", userAddDTO.getEmail());

        List<User> existingUsers = userMapper.selectList(queryWrapper);
        if (existingUsers != null && !existingUsers.isEmpty()) {
            for (User user : existingUsers) {
                if (user.getUsername().equals(userAddDTO.getUsername())) {
                    return Result.error(MessageConstant.USERNAME + MessageConstant.ALREADY_EXISTS);
                }
                if (user.getPhone().equals(userAddDTO.getPhone())) {
                    return Result.error(MessageConstant.PHONE + MessageConstant.ALREADY_EXISTS);
                }
                if (user.getEmail().equals(userAddDTO.getEmail())) {
                    return Result.error(MessageConstant.EMAIL + MessageConstant.ALREADY_EXISTS);
                }
            }
        }

        String passwordHash = passwordService.encode(userAddDTO.getPassword());
        User user = new User();
        user.setUsername(userAddDTO.getUsername()).setPassword(passwordHash).setPhone(userAddDTO.getPhone())
                .setEmail(userAddDTO.getEmail()).setIntroduction(userAddDTO.getIntroduction())
                .setCreateTime(LocalDateTime.now()).setUpdateTime(LocalDateTime.now()).setAuthVersion(0L);

        // 前端传递的用户状态（1：启用，0：禁用）需反转
        if (userAddDTO.getUserStatus().getId() == 1) {
            user.setUserStatus(UserStatusEnum.ENABLE);  // 数据库（0：启用）
        } else if (userAddDTO.getUserStatus().getId() == 0) {
            user.setUserStatus(UserStatusEnum.DISABLE);    // 数据库（1：禁用）
        }

        if (userMapper.insert(user) == 0) {
            return Result.error(MessageConstant.ADD + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }

    /**
     * 更新用户
     *
     * @param userDTO 用户信息
     * @return 结果
     */
    @Override
    @CacheEvict(cacheNames = "userCache", allEntries = true)
    public Result updateUser(UserDTO userDTO) {
        Long userId = userDTO.getUserId();

        User userByUsername = userMapper.selectOne(new QueryWrapper<User>().eq("username", userDTO.getUsername()));
        if (userByUsername != null && !userByUsername.getUserId().equals(userId)) {
            return Result.error(MessageConstant.USERNAME + MessageConstant.ALREADY_EXISTS);
        }

        User userByPhone = userMapper.selectOne(new QueryWrapper<User>().eq("phone", userDTO.getPhone()));
        if (userByPhone != null && !userByPhone.getUserId().equals(userId)) {
            return Result.error(MessageConstant.PHONE + MessageConstant.ALREADY_EXISTS);
        }

        User user = new User();
        BeanUtils.copyProperties(userDTO, user);
        user.setEmail(null); // 管理员资料编辑同样不能绕过双邮箱验证
        user.setUpdateTime(LocalDateTime.now());

        if (userMapper.updateById(user) == 0) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 更新用户状态
     *
     * @param userId     用户id
     * @param userStatus 状态
     * @return 结果
     */
    @Override
    @CacheEvict(cacheNames = "userCache", allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public Result updateUserStatus(Long userId, Integer userStatus) {
        User existing = userMapper.selectById(userId);
        if (existing == null) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }
        // 确保用户状态有效
        UserStatusEnum statusEnum;
        if (userStatus == 0) {
            statusEnum = UserStatusEnum.ENABLE;
        } else if (userStatus == 1) {
            statusEnum = UserStatusEnum.DISABLE;
        } else {
            return Result.error(MessageConstant.USER_STATUS_INVALID);
        }

        // 更新用户状态
        User user = new User();
        user.setUserStatus(statusEnum).setUpdateTime(LocalDateTime.now());

        int rows = userMapper.update(user, new QueryWrapper<User>().eq("id", userId));
        if (rows == 0) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }
        requireVersionIncrement(userMapper.incrementAuthVersion(userId));
        TransactionCallbacks.afterCommit(() -> authSessionService.revokeAllUserSessions(userId));
        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 删除用户（管理员单个删除）。
     *
     * <p>历史上这里只有一句 {@code userMapper.deleteById}：不删头像、不清无外键表
     * （会话/消息/任务指标）、不收集个人歌单的自定义封面，而且碰到有反馈的账号会被
     * {@code tb_feedback} 的 {@code ON DELETE RESTRICT} 直接拒绝。
     * 现在统一委托 {@link AccountDeletionService}，与自助注销走同一套顺序。
     *
     * <p>管理员路径拿不到目标用户的登录 token，因此只清理数据与媒体，
     * token 由 6 小时有效期自然过期（与「放弃鉴权改造」的既有决策一致）。
     *
     * @param userId 用户id
     * @return 结果
     */
    @Override
    @CacheEvict(cacheNames = "userCache", allEntries = true)
    public Result deleteUser(Long userId) {
        if (userId == null) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        AccountDeletionOutcome outcome = accountDeletionService.deleteAccount(userId, null, true);
        if (!outcome.existed()) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        if (!outcome.deleted()) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

    /**
     * 批量删除用户。
     *
     * <p>整批在同一事务内完成：任一账号删不掉就整体回滚，不会留下"前几个删了、后面失败"
     * 的中间态。加锁顺序固定为 id 升序，避免并发批删互相等待成环。
     *
     * @param userIds 用户id数组
     * @return 结果
     */
    @Override
    @CacheEvict(cacheNames = "userCache", allEntries = true)
    public Result deleteUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        BatchDeletionOutcome outcome = accountDeletionService.deleteAccounts(userIds);
        if (!outcome.success()) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

}
