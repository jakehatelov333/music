package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.AgentPendingAction;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待确认任务的持久化。
 *
 * <p><b>幂等与并发保护全部落在数据库这一层</b>，不依赖 Java 里的“先查再写”：
 * <ul>
 *     <li>{@link #claim} 是一条带条件的 UPDATE，只有真正把 PENDING 改成 EXECUTING 的那次
 *         调用才会返回 1，其余并发请求返回 0 —— 这就是状态竞争保护；</li>
 *     <li>{@code updated_at < staleBefore} 的分支用于回收“进程在 EXECUTING 阶段崩溃”留下的僵死任务，
 *         回收后仍然走同一条幂等创建路径，不会重复建歌单；</li>
 *     <li>时间条件由 Java 传入，避免依赖数据库方言函数（H2 与 MySQL 都能跑）。</li>
 * </ul>
 */
@Mapper
public interface AgentPendingActionMapper {

    @Insert("""
            INSERT INTO tb_agent_pending_action
                (task_id, user_id, chat_id, action_type, draft_json, preview_json, version,
                 status, created_at, updated_at, expires_at)
            VALUES (#{taskId}, #{userId}, #{chatId}, #{actionType}, #{draftJson}, #{previewJson}, #{version},
                    #{status}, #{createdAt}, #{updatedAt}, #{expiresAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentPendingAction action);

    @Select("SELECT * FROM tb_agent_pending_action WHERE task_id = #{taskId}")
    AgentPendingAction find(@Param("taskId") String taskId);

    @Select("SELECT * FROM tb_agent_pending_action WHERE task_id = #{taskId} AND user_id = #{userId}")
    AgentPendingAction findOwned(@Param("taskId") String taskId, @Param("userId") Long userId);

    /** 未过期的待确认任务，用于页面刷新后重建确认卡片。 */
    @Select("""
            SELECT * FROM tb_agent_pending_action
            WHERE user_id = #{userId} AND chat_id = #{chatId} AND status = 'PENDING' AND expires_at > #{now}
            ORDER BY created_at DESC
            """)
    List<AgentPendingAction> findPending(@Param("userId") Long userId, @Param("chatId") String chatId,
                                         @Param("now") LocalDateTime now);

    /**
     * 当前用户在当前会话中最近的若干条创建歌单预览，用于「选曲去重」。
     *
     * <p><b>为什么要单独一条查询：</b>去重只需要「近期用过哪些歌」，不需要用户全部历史。
     * 这里把 user_id、chat_id、action_type 三个条件全部写进 SQL，并由 LIMIT 限制扫描行数，
     * 因此不会为了排除历史歌曲而把用户的历史记录全量加载进内存。
     *
     * <p><b>为什么不过滤 status：</b>只要预览曾经展示给用户，这些歌就算「已经用过」——
     * 包括用户尚未确认（PENDING）、已取消（CANCELLED）、已过期（EXPIRED）与已成功（SUCCEEDED）的。
     * 唯一例外是被解析失败或数据损坏的行，由上层服务跳过并记日志。
     *
     * <p>按自增主键倒序而不是 created_at：同一毫秒内创建的多条任务也能得到稳定顺序。
     */
    @Select("""
            SELECT * FROM tb_agent_pending_action
            WHERE user_id = #{userId} AND chat_id = #{chatId} AND action_type = #{actionType}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AgentPendingAction> findRecentByChat(@Param("userId") Long userId, @Param("chatId") String chatId,
                                              @Param("actionType") String actionType, @Param("limit") int limit);

    /** 不限定会话的待确认任务：前端不知道 chatId 时（例如换了设备）也能找回自己的待确认操作。 */
    @Select("""
            SELECT * FROM tb_agent_pending_action
            WHERE user_id = #{userId} AND status = 'PENDING' AND expires_at > #{now}
            ORDER BY created_at DESC
            """)
    List<AgentPendingAction> findPendingByUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 用户修改预览：带版本号的乐观更新，避免两个标签页互相覆盖。 */
    @Update("""
            UPDATE tb_agent_pending_action
            SET draft_json = #{draftJson}, preview_json = #{previewJson}, version = #{version}, updated_at = #{now}
            WHERE task_id = #{taskId} AND user_id = #{userId} AND status = 'PENDING' AND version = #{expectedVersion}
            """)
    int updateDraft(@Param("taskId") String taskId, @Param("userId") Long userId,
                    @Param("draftJson") String draftJson, @Param("previewJson") String previewJson,
                    @Param("version") int version, @Param("expectedVersion") int expectedVersion,
                    @Param("now") LocalDateTime now);

    /**
     * 抢占执行权。返回 1 表示本次请求拿到了执行权；返回 0 表示其他请求已抢占、
     * 任务已进入终态、或任务已过期 —— 调用方据此返回已保存的结果而不是重复执行。
     */
    @Update("""
            UPDATE tb_agent_pending_action
            SET status = 'EXECUTING', updated_at = #{now}
            WHERE task_id = #{taskId} AND user_id = #{userId}
              AND expires_at > #{now}
              AND (status = 'PENDING' OR (status = 'EXECUTING' AND updated_at < #{staleBefore}))
            """)
    int claim(@Param("taskId") String taskId, @Param("userId") Long userId,
              @Param("now") LocalDateTime now, @Param("staleBefore") LocalDateTime staleBefore);

    @Update("""
            UPDATE tb_agent_pending_action
            SET status = 'SUCCEEDED', result_playlist_id = #{playlistId}, error_code = NULL,
                error_message = NULL, updated_at = #{now}
            WHERE task_id = #{taskId}
            """)
    int complete(@Param("taskId") String taskId, @Param("playlistId") Long playlistId,
                 @Param("now") LocalDateTime now);

    @Update("""
            UPDATE tb_agent_pending_action
            SET status = 'FAILED', error_code = #{code}, error_message = #{message}, updated_at = #{now}
            WHERE task_id = #{taskId}
            """)
    int fail(@Param("taskId") String taskId, @Param("code") String code, @Param("message") String message,
             @Param("now") LocalDateTime now);

    /** 只有仍处于待确认状态的任务才能被取消。 */
    @Update("""
            UPDATE tb_agent_pending_action
            SET status = 'CANCELLED', updated_at = #{now}
            WHERE task_id = #{taskId} AND user_id = #{userId} AND status = 'PENDING'
            """)
    int cancel(@Param("taskId") String taskId, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * 删除会话时连带取消该会话下所有仍待确认的任务。
     *
     * <p><b>为什么必须做：</b>待确认任务独立于消息表存在。删掉会话却留下任务，
     * 用户在别的会话里会看到一张指向已不存在对话的确认卡片，点确认还会真的建出歌单 ——
     * 删了记录却留着动作，这是隐私与一致性双重问题。
     *
     * <p><b>为什么只取消 PENDING：</b>已成功（SUCCEEDED）的任务是「这个歌单由这次对话创建」
     * 的追溯凭据，删会话不应该抹掉它；EXECUTING 的任务正在写库，此刻改动状态会破坏幂等判定，
     * 交给它自己跑完。真正需要拦截的只有「用户还能再点确认」的那些。
     */
    @Update("""
            UPDATE tb_agent_pending_action
            SET status = 'CANCELLED', updated_at = #{now}
            WHERE user_id = #{userId} AND chat_id = #{chatId} AND status = 'PENDING'
            """)
    int cancelPendingByChat(@Param("userId") Long userId, @Param("chatId") String chatId,
                            @Param("now") LocalDateTime now);

    /**
     * 清空用户全部会话时，取消其名下所有仍待确认的任务。
     *
     * <p><b>为什么不复用 {@link #cancelPendingByChat} 逐个会话取消：</b>待确认任务表里可能存在
     * chat_id 已经不在 {@code ai_chat_state} 中的历史任务（例如会话行被删但任务被保留）。
     * 按 user 取消能一次覆盖全部，不依赖「先枚举出所有 chat_id」——枚举必然漏掉这些孤儿，
     * 而它们恰恰是「用户看不到、却能点确认建歌单」的那一类。
     *
     * <p>只动 PENDING：SUCCEEDED 是「这个歌单由这次对话创建」的追溯凭据，EXECUTING 正在写库，
     * 两者都不该被本次清除改写（与 {@link #cancelPendingByChat} 同一原则）。
     */
    @Update("""
            UPDATE tb_agent_pending_action
            SET status = 'CANCELLED', updated_at = #{now}
            WHERE user_id = #{userId} AND status = 'PENDING'
            """)
    int cancelPendingByUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE tb_agent_pending_action
            SET status = 'EXPIRED', updated_at = #{now}
            WHERE task_id = #{taskId} AND status = 'PENDING'
            """)
    int expire(@Param("taskId") String taskId, @Param("now") LocalDateTime now);

    /** 定时/惰性过期扫描：把已超时但仍标记为待确认的任务批量置为过期。 */
    @Update("""
            UPDATE tb_agent_pending_action
            SET status = 'EXPIRED', updated_at = #{now}
            WHERE status = 'PENDING' AND expires_at <= #{now}
            """)
    int expireAll(@Param("now") LocalDateTime now);
}
