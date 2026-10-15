package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.MediaCleanupTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体清理任务的持久化访问。
 *
 * <p>并发语义全在这几条 SQL 里，改动前请先读懂：
 * <ul>
 *   <li>{@link #enqueue} 用 {@code ON DUPLICATE KEY UPDATE} 保证"同一业务操作重复执行不重复入队"。</li>
 *   <li>{@link #claim} 是<b>条件更新</b>：只有行仍处于可领取状态（或租约已过期）时才成功，
 *       因此并发执行者只有一个能把行改成自己的租约，且 {@code generation} 自增。</li>
 *   <li>所有回写都带 {@code generation} + {@code lease_owner} 双重条件，旧代次的执行者
 *       （租约被抢占后仍在跑）无法覆盖新代次的结果。</li>
 * </ul>
 */
@Mapper
public interface MediaCleanupTaskMapper {

    /**
     * 登记一条待清理任务，同一 {@code (source_type, source_ref, key_digest)} 幂等。
     *
     * <p>重复入队<b>不改变状态</b>（只刷新桶名、原因与时间戳）：状态机由 worker 单方面推进，
     * 让调用方也能改状态会引入难以推理的竞争。之所以安全，是因为对象键由
     * {@code UUID + "-" + 文件名} 生成、永不复用，同一来源实体不可能第二次释放同一个键。
     *
     * <p>{@code nextRunAt} 与 {@code now} 分开传：新建任务应当<b>立刻到期</b>，但"登记那一刻"与
     * "领取那一刻"若落在同一微秒内，列精度舍入会让存储值略大于领取时刻，于是提交后立即唤醒
     * 白跑一趟（要等下一轮定时任务）。调用方传一个略微提前的时间即可消除这个边界。
     */
    @Insert("""
            INSERT INTO tb_media_cleanup_task
                (bucket, object_key, key_digest, source_type, source_ref, reason,
                 status, attempts, next_run_at, generation, created_at, updated_at)
            VALUES (#{bucket}, #{objectKey}, #{keyDigest}, #{sourceType}, #{sourceRef}, #{reason},
                    'PENDING', 0, #{nextRunAt}, 0, #{now}, #{now})
            ON DUPLICATE KEY UPDATE
                bucket = #{bucket},
                reason = #{reason},
                updated_at = #{now}
            """)
    int enqueue(@Param("bucket") String bucket, @Param("objectKey") String objectKey,
                @Param("keyDigest") String keyDigest, @Param("sourceType") String sourceType,
                @Param("sourceRef") String sourceRef, @Param("reason") String reason,
                @Param("now") LocalDateTime now, @Param("nextRunAt") LocalDateTime nextRunAt);

    /**
     * 找出当前可执行的候选任务 id：到期的 PENDING/RETRY_WAIT，或租约已过期的 RUNNING。
     *
     * <p>只是候选，不保证能领取到——{@link #claim} 才是原子抢占。
     */
    @Select("""
            SELECT id FROM tb_media_cleanup_task
            WHERE (status IN ('PENDING', 'RETRY_WAIT') AND next_run_at <= #{now})
               OR (status = 'RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at <= #{now})
            ORDER BY next_run_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<Long> findDueIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 原子领取：把行改成 {@code RUNNING} 并挂上本执行者的租约，{@code generation + 1}。
     *
     * @return 1 表示领取成功（本执行者独占该任务）；0 表示已被别人抢走或状态已变，必须放弃
     */
    @Update("""
            UPDATE tb_media_cleanup_task
            SET status = 'RUNNING',
                lease_owner = #{owner},
                lease_expires_at = #{leaseExpiresAt},
                generation = generation + 1,
                attempts = attempts + 1,
                updated_at = #{now}
            WHERE id = #{id}
              AND ((status IN ('PENDING', 'RETRY_WAIT') AND next_run_at <= #{now})
                   OR (status = 'RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at <= #{now}))
            """)
    int claim(@Param("id") Long id, @Param("owner") String owner,
              @Param("now") LocalDateTime now, @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Select("SELECT * FROM tb_media_cleanup_task WHERE id = #{id}")
    MediaCleanupTask findById(@Param("id") Long id);

    /** 对象已删除（或本来就不存在）→ 终态成功。 */
    @Update("""
            UPDATE tb_media_cleanup_task
            SET status = 'SUCCEEDED', lease_owner = NULL, lease_expires_at = NULL,
                last_error = NULL, updated_at = #{now}
            WHERE id = #{id} AND generation = #{generation} AND lease_owner = #{owner}
            """)
    int markSucceeded(@Param("id") Long id, @Param("owner") String owner,
                      @Param("generation") Long generation, @Param("now") LocalDateTime now);

    /** 核对后发现仍有引用 → 终态保留，不删对象。 */
    @Update("""
            UPDATE tb_media_cleanup_task
            SET status = 'RETAINED', lease_owner = NULL, lease_expires_at = NULL,
                last_error = NULL, updated_at = #{now}
            WHERE id = #{id} AND generation = #{generation} AND lease_owner = #{owner}
            """)
    int markRetained(@Param("id") Long id, @Param("owner") String owner,
                     @Param("generation") Long generation, @Param("now") LocalDateTime now);

    /** 本次失败但仍有重试机会：落 {@code RETRY_WAIT}，退避到 {@code nextRunAt}。 */
    @Update("""
            UPDATE tb_media_cleanup_task
            SET status = 'RETRY_WAIT', next_run_at = #{nextRunAt}, lease_owner = NULL,
                lease_expires_at = NULL, last_error = #{lastError}, updated_at = #{now}
            WHERE id = #{id} AND generation = #{generation} AND lease_owner = #{owner}
            """)
    int markRetryWait(@Param("id") Long id, @Param("owner") String owner, @Param("generation") Long generation,
                      @Param("nextRunAt") LocalDateTime nextRunAt, @Param("lastError") String lastError,
                      @Param("now") LocalDateTime now);

    /** 达到尝试上限仍失败 → 终态 {@code FAILED}，等待人工处理。 */
    @Update("""
            UPDATE tb_media_cleanup_task
            SET status = 'FAILED', lease_owner = NULL, lease_expires_at = NULL,
                last_error = #{lastError}, updated_at = #{now}
            WHERE id = #{id} AND generation = #{generation} AND lease_owner = #{owner}
            """)
    int markFailed(@Param("id") Long id, @Param("owner") String owner, @Param("generation") Long generation,
                   @Param("lastError") String lastError, @Param("now") LocalDateTime now);
}
