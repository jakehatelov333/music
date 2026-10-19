package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.constant.MediaCleanupSourceType;
import cn.edu.seig.vibemusic.mapper.AccountCleanupMapper;
import cn.edu.seig.vibemusic.mapper.AccountCleanupTaskMapper;
import cn.edu.seig.vibemusic.mapper.FeedbackMapper;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.mapper.UserMapper;
import cn.edu.seig.vibemusic.model.entity.Feedback;
import cn.edu.seig.vibemusic.model.entity.Playlist;
import cn.edu.seig.vibemusic.model.entity.User;
import cn.edu.seig.vibemusic.model.vo.AccountDeletionResultVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 账号删除的唯一实现。管理员单删、管理员批删、自助注销都走这里，入口只负责鉴权。
 *
 * <p>为什么必须统一：三条入口历史上各写各的，行为完全不一致 ——
 * 管理员删除只有一句 {@code deleteById}（不删头像、不清无外键表、不收集个人歌单封面），
 * 而且碰到有反馈的账号会被 {@code tb_feedback} 的 {@code ON DELETE RESTRICT} 直接拒绝；
 * 自助注销虽然清理完整，却漏掉了 {@code cover_is_custom=true} 的歌单封面。
 *
 * <p>顺序是硬约束（顺序错了会留下孤儿数据，而且事后无法找回引用）：
 * <ol>
 *     <li><b>事务内</b>：锁账号 → 收集待清理的外部资源（头像 + 个人歌单自定义封面）
 *         → 删反馈（RESTRICT 必须先删）→ 删无外键表 → 登记清理任务 → 删用户</li>
 *     <li><b>提交后</b>：作废 Redis token，唤醒媒体清理 worker</li>
 * </ol>
 *
 * <p>为什么封面必须在删用户<b>之前</b>收集：{@code tb_playlist.owner_user_id} 是
 * {@code ON DELETE CASCADE}，删用户后歌单行连同 {@code cover_url} 一起消失，
 * 那时再想找"这个用户有哪些自定义封面"已经没有任何数据可查，文件就永久成为孤儿。
 *
 * <p>封面清理交给 {@link MediaCleanupService}（带跨表引用核对），而不是
 * {@code tb_account_cleanup_task}：后者唯一键是 {@code (user_id, resource_type, resource_ref)}，
 * 一个用户有多个封面时才能各占一行；更重要的是封面可能与其他记录共享，
 * 必须由引用核对决定能不能删，不能"账号没了就直接删文件"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    /** Redis token 作废失败时的重试类型（仍走 tb_account_cleanup_task）。 */
    public static final String RESOURCE_REDIS_TOKEN = "REDIS_TOKEN";

    private final UserMapper userMapper;
    private final FeedbackMapper feedbackMapper;
    private final AccountCleanupMapper accountCleanupMapper;
    private final AccountCleanupTaskMapper accountCleanupTaskMapper;
    private final PlaylistMapper playlistMapper;
    private final MediaCleanupService mediaCleanupService;
    private final StringRedisTemplate stringRedisTemplate;
    private final PlatformTransactionManager transactionManager;

    /**
     * 删除一个账号。
     *
     * @param userId         待删除用户
     * @param tokenToRevoke  需要一并作废的登录 token；管理员删除时为 {@code null}（拿不到用户的 token）
     * @param lockAccountRow 是否在事务内先行锁定账号行（批量删除时锁同一行序列，避免死锁）
     * @return 删除结果；账号本来就不存在时返回 {@code deleted=false, existedInitially=false}
     */
    public AccountDeletionOutcome deleteAccount(Long userId, String tokenToRevoke, boolean lockAccountRow) {
        if (userId == null) {
            return AccountDeletionOutcome.notFound();
        }

        int coverCount;
        UserSnapshot snapshot;
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            Object[] captured = tx.execute(status -> {
                // 1) 锁账号行：并发删除同一账号时只有一个能继续。
                if (lockAccountRow) {
                    User locked = userMapper.selectOne(new QueryWrapper<User>()
                            .eq("id", userId)
                            .last("FOR UPDATE"));
                    if (locked == null) {
                        return null;
                    }
                }
                User user = userMapper.selectById(userId);
                if (user == null) {
                    return null;
                }
                UserSnapshot before = new UserSnapshot(user.getUserAvatar());

                // 2)~6) 与批量删除走完全相同的一段实现
                int covers = deleteAccountBody(userId);
                return new Object[]{before, covers};
            });

            if (captured == null) {
                return AccountDeletionOutcome.notFound();
            }
            snapshot = (UserSnapshot) captured[0];
            coverCount = (Integer) captured[1];
        } catch (RuntimeException e) {
            log.error("账号删除事务失败，userId={}", userId, e);
            return AccountDeletionOutcome.failed(e.getMessage());
        }

        // 7) DB 已提交，再处理外部资源。失败不回滚（也回滚不了），落重试记录并如实上报。
        Set<String> pending = new LinkedHashSet<>();
        if (StringUtils.hasText(tokenToRevoke)) {
            try {
                stringRedisTemplate.delete(tokenToRevoke);
            } catch (RuntimeException e) {
                log.error("账号删除后作废 Redis token 失败，userId={}", userId, e);
                pending.add(RESOURCE_REDIS_TOKEN);
                recordTokenRetry(userId, tokenToRevoke, e);
            }
        }

        return AccountDeletionOutcome.deleted(snapshot.avatar(), coverCount, pending);
    }

    /**
     * 批量删除。整批在<b>同一个事务</b>里完成：任一账号删不掉就全部回滚，
     * 不会出现"前几个删了、后面失败"的中间态。
     *
     * @param userIds 待删除用户 id，内部会按升序处理以固定加锁顺序
     */
    public BatchDeletionOutcome deleteAccounts(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return BatchDeletionOutcome.empty();
        }
        // 去重 + 升序：稳定的加锁顺序是避免死锁的关键
        List<Long> ordered = new ArrayList<>(new LinkedHashSet<>(userIds));
        ordered.sort(Long::compareTo);

        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            int[] counters = tx.execute(status -> {
                int deletedCount = 0;
                int covers = 0;
                for (Long userId : ordered) {
                    if (userId == null) {
                        continue;
                    }
                    // 锁行：并发删除同一账号时只有一个能继续
                    User locked = userMapper.selectOne(new QueryWrapper<User>()
                            .eq("id", userId).last("FOR UPDATE"));
                    if (locked == null) {
                        continue;
                    }
                    covers += deleteAccountBody(userId);
                    deletedCount++;
                }
                return new int[]{deletedCount, covers};
            });
            int deletedCount = counters == null ? 0 : counters[0];
            int covers = counters == null ? 0 : counters[1];
            log.info("批量删除账号完成，请求 {} 个、实际删除 {} 个，登记封面清理 {} 个",
                    ordered.size(), deletedCount, covers);
            return new BatchDeletionOutcome(true, null, ordered, covers);
        } catch (RuntimeException e) {
            log.error("批量删除账号失败，已整体回滚，userIds={}", userIds, e);
            return BatchDeletionOutcome.failed(e.getMessage());
        }
    }

    /**
     * 单账号删除的<b>唯一</b>实现（已在调用方事务内）。
     *
     * <p>刻意让单删与批删共用这一段 SQL 顺序，避免"单删修了、批删没修"的历史问题重演。
     * 顺序的理由见类注释。
     *
     * @return 本次登记待清理的封面数量
     */
    private int deleteAccountBody(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return 0;
        }

        // 必须在删用户之前收集：歌单是 ON DELETE CASCADE，删完就查不到了
        List<String> covers = new ArrayList<>();
        List<Playlist> owned = playlistMapper.selectList(new QueryWrapper<Playlist>()
                .eq("owner_user_id", userId)
                .eq("cover_is_custom", true));
        if (owned != null) {
            for (Playlist playlist : owned) {
                if (StringUtils.hasText(playlist.getCoverUrl())) {
                    covers.add(playlist.getCoverUrl());
                }
            }
        }

        // 反馈是 ON DELETE RESTRICT：不先删，删用户时会被外键拒绝
        // （这正是「管理员删不掉有反馈的用户」的根因）
        feedbackMapper.delete(new QueryWrapper<Feedback>().eq("user_id", userId));

        // 无外键、不会随 tb_user 级联的表必须显式清理
        List<String> chatIds = accountCleanupMapper.selectChatIdsByUserId(userId);
        if (chatIds != null && !chatIds.isEmpty()) {
            accountCleanupMapper.deleteChatMessagesByChatIds(chatIds);
        }
        accountCleanupMapper.deleteChatStateByUserId(userId);
        accountCleanupMapper.deleteTaskMetricsByUserId(userId);

        // 为每个封面登记"待核对后再删"的任务（同事务）：worker 跨表核对引用，
        // 仍被他人引用的对象判 RETAINED 保留，不会因为"账号没了"就误删共享封面。
        for (String coverUrl : covers) {
            mediaCleanupService.enqueue(coverUrl, MediaCleanupSourceType.ACCOUNT_DELETE,
                    String.valueOf(userId), "账号删除：个人歌单自定义封面");
        }

        // 删用户（其余关联数据由 FK ON DELETE CASCADE 处理）；返回 0 说明并发请求已删，视同成功
        userMapper.deleteById(userId);
        return covers.size();
    }

    private void recordTokenRetry(Long userId, String token, RuntimeException cause) {
        try {
            accountCleanupTaskMapper.upsertPending(userId, RESOURCE_REDIS_TOKEN, token,
                    truncate(cause.getMessage()), java.time.LocalDateTime.now());
        } catch (RuntimeException e) {
            // 记录本身失败只能记日志：账号已经删了，不能让调用方误以为删除失败
            log.error("记录账号删除后的 token 清理重试任务失败，userId={}", userId, e);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    /** 事务内取到的、提交后还要用的账号快照。 */
    private record UserSnapshot(String avatar) {
    }

    /** 单个账号删除的结果。 */
    public record AccountDeletionOutcome(boolean existed, boolean deleted, String failedMessage,
                                         String avatar, int coverCount, Set<String> pendingItems) {

        static AccountDeletionOutcome notFound() {
            return new AccountDeletionOutcome(false, false, null, null, 0, Set.of());
        }

        static AccountDeletionOutcome failed(String message) {
            return new AccountDeletionOutcome(true, false, message, null, 0, Set.of());
        }

        static AccountDeletionOutcome deleted(String avatar, int coverCount, Set<String> pendingItems) {
            return new AccountDeletionOutcome(true, true, null, avatar, coverCount, pendingItems);
        }

        /** 是否走到过"删除成功"（批量删除用它判断要不要计数）。 */
        public boolean handled() {
            return deleted;
        }
    }

    /** 批量删除的结果。 */
    public record BatchDeletionOutcome(boolean success, String failedMessage,
                                       List<Long> deletedUserIds, int coverCount) {

        static BatchDeletionOutcome empty() {
            return new BatchDeletionOutcome(true, null, List.of(), 0);
        }

        static BatchDeletionOutcome deleted(List<Long> ids, int coverCount) {
            return new BatchDeletionOutcome(true, null, ids, coverCount);
        }

        static BatchDeletionOutcome failed(String message) {
            return new BatchDeletionOutcome(false, message, List.of(), 0);
        }
    }

    /** 便于调用方组装 {@link AccountDeletionResultVO}。 */
    public static AccountDeletionResultVO toResultVO(AccountDeletionOutcome outcome) {
        boolean cleanupPending = !outcome.pendingItems().isEmpty();
        return new AccountDeletionResultVO(true, cleanupPending, new ArrayList<>(outcome.pendingItems()));
    }
}
