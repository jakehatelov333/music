package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.AccountCleanupTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号注销遗留清理任务的持久化访问。
 */
@Mapper
public interface AccountCleanupTaskMapper {

    /**
     * 落一条待重试记录。同一 {@code (user_id, resource_type)} 幂等：
     * 重复注销/重复失败只会刷新引用与错误信息，不会产生重复任务。
     */
    @Insert("""
            INSERT INTO tb_account_cleanup_task
                (user_id, resource_type, resource_ref, status, attempts, last_error, created_at, updated_at)
            VALUES (#{userId}, #{resourceType}, #{resourceRef}, 'PENDING', 0, #{lastError}, #{now}, #{now})
            ON DUPLICATE KEY UPDATE
                resource_ref = #{resourceRef},
                status = 'PENDING',
                last_error = #{lastError},
                updated_at = #{now}
            """)
    int upsertPending(@Param("userId") Long userId, @Param("resourceType") String resourceType,
                      @Param("resourceRef") String resourceRef, @Param("lastError") String lastError,
                      @Param("now") LocalDateTime now);

    /** 取仍待重试、且未超过尝试上限的任务。 */
    @Select("""
            SELECT * FROM tb_account_cleanup_task
            WHERE status = 'PENDING' AND attempts < #{maxAttempts}
            ORDER BY id ASC LIMIT #{limit}
            """)
    List<AccountCleanupTask> findRetryable(@Param("maxAttempts") int maxAttempts, @Param("limit") int limit);

    @Update("""
            UPDATE tb_account_cleanup_task
            SET status = 'DONE', attempts = attempts + 1, updated_at = #{now}
            WHERE id = #{id}
            """)
    int markDone(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 本次重试失败但仍有重试机会：保持 PENDING，累计次数与错误。 */
    @Update("""
            UPDATE tb_account_cleanup_task
            SET attempts = attempts + 1, last_error = #{lastError}, updated_at = #{now}
            WHERE id = #{id}
            """)
    int markRetryFailed(@Param("id") Long id, @Param("lastError") String lastError,
                        @Param("now") LocalDateTime now);

    /** 达到重试上限仍失败：落终态 FAILED，等待人工处理。 */
    @Update("""
            UPDATE tb_account_cleanup_task
            SET status = 'FAILED', attempts = attempts + 1, last_error = #{lastError}, updated_at = #{now}
            WHERE id = #{id}
            """)
    int markExhausted(@Param("id") Long id, @Param("lastError") String lastError,
                      @Param("now") LocalDateTime now);
}
