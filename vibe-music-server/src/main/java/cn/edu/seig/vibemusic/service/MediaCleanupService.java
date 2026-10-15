package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.mapper.MediaCleanupTaskMapper;
import cn.edu.seig.vibemusic.model.entity.MediaCleanupTask;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 媒体对象清理管线：把"业务删除 → 立即删文件"改成"业务事务内登记 → 提交后核对引用再删"。
 *
 * <h3>为什么不能就地删</h3>
 * 直接调 MinIO 删文件有两个已确认会造成实际损坏的缺陷：
 * <ol>
 *   <li><b>先删文件、后改数据库</b>：S3 不参与 MySQL 事务。业务事务回滚了，文件却已经没了。</li>
 *   <li><b>误删共享对象</b>：一张封面被多行引用（实测库里 262 个对象被 ≥2 行引用，最多的一个被 15 行
 *       共用），只解除其中一个引用就删文件，剩下的行全部指向不存在的对象。</li>
 * </ol>
 *
 * <h3>可靠性来自哪里</h3>
 * 只来自 {@code tb_media_cleanup_task} 这张表，不来自 {@code afterCommit} 回调或进程内存：
 * <pre>
 * ① 业务事务内：改业务数据 + 登记任务（同事务，可回滚）
 * ② 事务提交后：只"唤醒" worker（丢了也不影响正确性）
 * ③ worker：核对全部引用 → 无引用才删 → 失败可重试；租约过期可被重新领取
 * </pre>
 *
 * <h3>调用约束</h3>
 * {@link #enqueue} 必须在业务事务内调用。若检测到没有活动事务，会记一条 warn 后继续——此时任务会
 * 独立提交，业务事务若回滚会留下一条"多余但安全"的任务（worker 核对引用后判定 RETAINED），
 * 不会删掉仍被引用的对象。请把它当作需要修掉的使用方式，而不是可接受的常态。
 */
@Slf4j
@Service
public class MediaCleanupService {

    /** 单条任务的最大尝试次数，沿用 {@code AccountCleanupRetryTask} 的取值。 */
    public static final int MAX_ATTEMPTS = 10;

    /** 单轮最多处理的任务数，避免一次唤醒跑太久。 */
    static final int BATCH_LIMIT = 100;

    /** 执行租约时长，与既有 AI 待确认任务的取值一致；过期后任务可被重新领取。 */
    static final Duration LEASE_TTL = Duration.ofSeconds(60);

    /** 退避上限（分钟）。 */
    static final long BACKOFF_CAP_MINUTES = 30L;

    private final MediaCleanupTaskMapper taskMapper;
    private final MediaReferenceService referenceService;
    private final MinioService minioService;

    private final String bucketName;

    /** 本实例的租约标识，写入 {@code lease_owner}，用于识别"这次执行是不是我发起的"。 */
    private final String instanceId = "mc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    /** 合并同一批提交里的多次唤醒，避免 N 条任务触发 N 次全量扫描。 */
    private final AtomicBoolean runScheduled = new AtomicBoolean(false);

    private final ExecutorService wakeupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "media-cleanup-wakeup");
        t.setDaemon(true);
        return t;
    });

    public MediaCleanupService(MediaCleanupTaskMapper taskMapper,
                               MediaReferenceService referenceService,
                               MinioService minioService,
                               @Value("${minio.bucket}") String bucketName) {
        this.taskMapper = taskMapper;
        this.referenceService = referenceService;
        this.minioService = minioService;
        this.bucketName = bucketName;
    }

    // ------------------------------------------------------------------ 登记

    /**
     * 登记一个待清理对象（业务事务内调用）。
     *
     * @param url        对象 URL；空白或无法解析出对象键时不登记（没有可删的东西）
     * @param sourceType 来源业务，取值见 {@link cn.edu.seig.vibemusic.constant.MediaCleanupSourceType}
     * @param sourceRef  来源实体 id 或上传会话 id
     * @param reason     可读原因，便于人工排查
     */
    public void enqueue(String url, String sourceType, String sourceRef, String reason) {
        if (!StringUtils.hasText(url)) {
            return;
        }
        // 身份只取对象键：历史数据里同一对象的主机名既有 localhost 也有 127.0.0.1
        String objectKey = minioService.toObjectName(url);
        if (!StringUtils.hasText(objectKey)) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("媒体清理任务在事务外登记，可能与业务变更不同事务：sourceType={}，sourceRef={}，key={}",
                    sourceType, sourceRef, objectKey);
        }
        LocalDateTime now = LocalDateTime.now();
        // 立刻到期：登记与领取若落在同一微秒内，列精度舍入会让"刚登记的任务"当次领不到，
        // 提交后的唤醒就白跑一趟。提前 1 秒表示"现在就该执行"，不改变任何退避语义。
        taskMapper.enqueue(bucketName, objectKey, sha256Hex(objectKey), sourceType,
                sourceRef, truncate(reason, 64), now, now.minusSeconds(1));
        registerWakeUpAfterCommit();
    }

    /** 同 {@link #enqueue}，便于直接传实体主键。 */
    public void enqueueForId(String url, String sourceType, Long entityId, String reason) {
        enqueue(url, sourceType, entityId == null ? null : String.valueOf(entityId), reason);
    }

    /** 提交后唤醒 worker。只承担"尽快处理"，不承担可靠性——丢了由定时任务兜底。 */
    private void registerWakeUpAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                scheduleRun();
            }
        });
    }

    private void scheduleRun() {
        if (!runScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            wakeupExecutor.execute(() -> {
                try {
                    runDueTasks();
                } catch (RuntimeException e) {
                    // 事务已经提交，这里再抛也回滚不了任何东西，只会污染提交路径
                    log.warn("提交后媒体清理唤醒执行失败，等待定时任务重试：{}", e.getMessage());
                } finally {
                    runScheduled.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            runScheduled.set(false);
            log.warn("媒体清理唤醒线程不可用，等待定时任务重试：{}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ worker

    /**
     * 处理一批到期任务。由 {@code MediaCleanupRetryTask} 定时调用，也可在提交后立即唤醒。
     *
     * @return 实际处理（领取成功）的任务数
     */
    public int runDueTasks() {
        List<Long> dueIds = taskMapper.findDueIds(LocalDateTime.now(), BATCH_LIMIT);
        int processed = 0;
        for (Long id : dueIds) {
            if (processOne(id)) {
                processed++;
            }
        }
        return processed;
    }

    /**
     * 领取并执行一条任务。
     *
     * <p>网络调用（MinIO 删除）在事务外，且每次只删一个完整对象键——不使用前缀、通配符或多对象删除。
     *
     * @return 是否领取成功
     */
    private boolean processOne(Long id) {
        LocalDateTime claimedAt = LocalDateTime.now();
        if (taskMapper.claim(id, instanceId, claimedAt, claimedAt.plus(LEASE_TTL)) != 1) {
            return false;
        }
        MediaCleanupTask task = taskMapper.findById(id);
        if (task == null) {
            return false;
        }
        Long generation = task.getGeneration();
        try {
            if (!bucketName.equals(task.getBucket())) {
                // 桶不匹配说明配置变过或数据脏了，绝不猜测性地删
                String error = "任务桶与当前配置不一致: " + task.getBucket();
                taskMapper.markFailed(id, instanceId, generation, error, LocalDateTime.now());
                log.warn("媒体清理任务桶不匹配，转人工处理：id={}，任务桶={}，配置桶={}",
                        id, task.getBucket(), bucketName);
                return true;
            }

            long references = referenceService.countReferences(task.getObjectKey());
            if (references > 0) {
                taskMapper.markRetained(id, instanceId, generation, LocalDateTime.now());
                log.info("媒体对象仍有 {} 处引用，保留不删：id={}，key={}", references, id, task.getObjectKey());
                return true;
            }

            minioService.deleteObject(task.getObjectKey());
            taskMapper.markSucceeded(id, instanceId, generation, LocalDateTime.now());
            log.info("媒体对象已清理：id={}，key={}，来源={}:{}",
                    id, task.getObjectKey(), task.getSourceType(), task.getSourceRef());
            return true;
        } catch (RuntimeException e) {
            String error = truncate(e.getMessage(), 500);
            int attempts = task.getAttempts() == null ? 1 : task.getAttempts();
            LocalDateTime now = LocalDateTime.now();
            if (attempts >= MAX_ATTEMPTS) {
                taskMapper.markFailed(id, instanceId, generation, error, now);
                log.warn("媒体清理达到尝试上限，转人工处理：id={}，key={}，尝试={}，原因={}",
                        id, task.getObjectKey(), attempts, error);
            } else {
                taskMapper.markRetryWait(id, instanceId, generation,
                        now.plusMinutes(backoffMinutes(attempts)), error, now);
                log.info("媒体清理失败待重试：id={}，key={}，尝试={}，原因={}",
                        id, task.getObjectKey(), attempts, error);
            }
            return true;
        }
    }

    /** 退避：{@code min(2^attempts, 30)} 分钟。 */
    static long backoffMinutes(int attempts) {
        if (attempts <= 0) {
            return 1L;
        }
        if (attempts >= 5) {
            // 2^5 = 32 已超过上限，直接封顶，避免移位溢出
            return BACKOFF_CAP_MINUTES;
        }
        return Math.min(1L << attempts, BACKOFF_CAP_MINUTES);
    }

    /** {@code SHA-256(objectKey)} 小写十六进制。 */
    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String truncate(String message, int max) {
        if (message == null) {
            return null;
        }
        return message.length() <= max ? message : message.substring(0, max);
    }

    @PreDestroy
    void shutdown() {
        wakeupExecutor.shutdown();
    }
}
