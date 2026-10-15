package cn.edu.seig.vibemusic.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 媒体对象清理任务：一条记录 = 一个待清理的 MinIO 对象（身份 = {@code (bucket, objectKey)}）。
 *
 * <p>为什么要有这张表：删除/替换业务数据时若直接调 MinIO 删文件，会出现两个真实缺陷——
 * 业务事务回滚了但文件已被删；以及共享封面（一张专辑封面被多首歌引用）只解除一个引用就被删掉。
 * 改成"业务事务内登记任务 + 提交后由 worker 核对引用再删"，可靠性只来自这张表（不依赖
 * {@code afterCommit} 回调或进程内存），因此进程崩溃、MinIO 临时不可用都能恢复。
 *
 * <p>生命周期：
 * <pre>
 * enqueue(PENDING) ──claim──&gt; RUNNING ──核对──┬─ 无引用 ─&gt; 删对象 ─┬─ 成功 ─&gt; SUCCEEDED
 *                                            │                     └─ 异常 ─&gt; RETRY_WAIT ─&gt; 超上限 ─&gt; FAILED
 *                                            └─ 有引用 ─&gt; RETAINED
 * </pre>
 * {@code RETAINED} 是终态：说明此刻仍有行引用该对象，等最后一次引用解除时由<b>新的</b>业务操作再入队。
 *
 * <p>本表<b>不设外键</b>：业务行在任务写入时可能已经删除或已被替换。
 */
@Data
@TableName("tb_media_cleanup_task")
public class MediaCleanupTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 已登记，等待 worker 领取。 */
    public static final String STATUS_PENDING = "PENDING";

    /** 已被某个执行者领取（持租约）。 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 对象已删除（或本来就不存在），终态。 */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";

    /** 本次执行失败，等待退避后重试。 */
    public static final String STATUS_RETRY_WAIT = "RETRY_WAIT";

    /** 达到尝试上限仍失败，终态，等待人工处理。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 核对后发现仍有引用，终态，保留对象不删。 */
    public static final String STATUS_RETAINED = "RETAINED";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 对象所在桶，取自 {@code minio.bucket} 配置。 */
    private String bucket;

    /**
     * MinIO 对象键（大小写敏感），不是完整 URL。
     *
     * <p>DB 列是 {@code VARCHAR(255) COLLATE utf8mb4_bin}：S3 的 key 大小写敏感，而本库默认
     * 排序规则 {@code utf8mb4_0900_ai_ci} 不敏感，用默认值会把 {@code A.jpg} 与 {@code a.jpg}
     * 当成同一个对象。
     */
    private String objectKey;

    /** {@code SHA-256(objectKey)} 小写十六进制，仅用于唯一约束（规避排序规则与索引长度限制）。 */
    private String keyDigest;

    /** 来源业务，取值见 {@link cn.edu.seig.vibemusic.constant.MediaCleanupSourceType}。 */
    private String sourceType;

    /** 来源实体 id 或上传会话 id。 */
    private String sourceRef;

    /** 可读原因，便于人工排查。 */
    private String reason;

    /** {@code PENDING / RUNNING / SUCCEEDED / RETRY_WAIT / FAILED / RETAINED}。 */
    private String status;

    /** 已尝试次数，在领取时自增。 */
    private Integer attempts;

    /** 下次可执行时间（退避用）。 */
    private LocalDateTime nextRunAt;

    /** 执行租约持有者，空表示无人在执行。 */
    private String leaseOwner;

    /** 租约到期时间，过期后可被重新领取（进程崩溃恢复靠它）。 */
    private LocalDateTime leaseExpiresAt;

    /** 执行代次；每次领取 +1，旧代次不得覆盖新代次的结果。 */
    private Long generation;

    /** 最近失败原因（截断到 500 字符，禁止写入凭据）。 */
    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
