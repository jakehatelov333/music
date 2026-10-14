package cn.edu.seig.vibemusic.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 账号注销后遗留的外部资源清理任务（持久化重试）。
 *
 * <p>DB 事务提交后才会执行外部清理（Redis token / MinIO 头像）。这些外部系统不参与 MySQL 事务，
 * 一旦清理失败又不能把已提交的注销回滚，因此落一条待重试记录，由定时任务重试到成功或达到上限。
 *
 * <p>本表<b>不</b>指向 {@code tb_user}（用户行此时已删除）。{@code resourceRef} 对于
 * {@code REDIS_TOKEN} 存放待作废的 token，对于 {@code MINIO_AVATAR} 存放头像 URL。
 */
@Data
@TableName("tb_account_cleanup_task")
public class AccountCleanupTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** REDIS_TOKEN / MINIO_AVATAR */
    private String resourceType;

    /** 待清理资源的引用（token 串或头像 URL）。 */
    private String resourceRef;

    /** PENDING / DONE / FAILED。 */
    private String status;

    private Integer attempts;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
