package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.UserAiMemorySetting;
import cn.edu.seig.vibemusic.model.entity.UserMemoryPreference;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户手动偏好与 AI 记忆个性化开关的持久化。
 *
 * <p><b>为什么全部用注解 SQL：</b>测试基座（{@code AbstractAgentTest}）只把
 * {@code mapper/PlaylistMapper.xml} 注册进 MyBatis，新增 XML 需要同步改基座。
 * 注解 SQL 无需注册，且所有语句都刻意避开数据库方言函数，
 * MySQL 8 与 H2(MODE=MySQL) 都能跑。
 *
 * <p><b>归属边界：</b>所有按行读写都带 {@code user_id} 条件 ——
 * 「不存在」与「不属于你」统一按 404 处理，不泄漏资源存在性。
 */
@Mapper
public interface AgentMemoryMapper {

    // ------------------------------------------------------------ 手动偏好

    @Select("SELECT * FROM tb_user_memory_preference WHERE user_id = #{userId} ORDER BY category, id")
    List<UserMemoryPreference> listByUser(@Param("userId") Long userId);

    @Select("SELECT * FROM tb_user_memory_preference WHERE id = #{id} AND user_id = #{userId}")
    UserMemoryPreference findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    @Insert("""
            INSERT INTO tb_user_memory_preference
                (user_id, category, target_id, target_value, created_at, updated_at)
            VALUES (#{p.userId}, #{p.category}, #{p.targetId}, #{p.targetValue}, #{now}, #{now})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "p.id", keyColumn = "id")
    int insert(@Param("p") UserMemoryPreference preference, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE tb_user_memory_preference
            SET target_id = #{targetId}, target_value = #{targetValue}, updated_at = #{now}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateValue(@Param("id") Long id, @Param("userId") Long userId,
                    @Param("targetId") Long targetId, @Param("targetValue") String targetValue,
                    @Param("now") LocalDateTime now);

    @Delete("DELETE FROM tb_user_memory_preference WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM tb_user_memory_preference
            WHERE user_id = #{userId} AND category = #{category}
            """)
    long countByUserAndCategory(@Param("userId") Long userId, @Param("category") String category);

    // ------------------------------------------------------------ 个性化开关

    @Select("SELECT * FROM tb_user_ai_memory_setting WHERE user_id = #{userId}")
    UserAiMemorySetting findSetting(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO tb_user_ai_memory_setting (user_id, personalization_enabled, updated_at)
            VALUES (#{userId}, #{enabled}, #{now})
            ON DUPLICATE KEY UPDATE personalization_enabled = #{enabled}, updated_at = #{now}
            """)
    int upsertSetting(@Param("userId") Long userId, @Param("enabled") boolean enabled,
                      @Param("now") LocalDateTime now);

    // ------------------------------------------------------------ 清除自动生成的记忆

    /** 只删自动画像行（事实 + 偏好概述）。手动偏好在另一张表，天然不受影响。 */
    @Delete("DELETE FROM tb_user_music_profile WHERE user_id = #{userId}")
    int deleteProfile(@Param("userId") Long userId);

    /**
     * 清空该用户全部会话的滚动摘要（重新打开会话后模型窗口内的消息仍在，
     * 只是「窗口之外被压缩掉的历史」不再被注入）。
     */
    @Update("""
            UPDATE ai_chat_state
            SET summary_text = NULL, summary_upto_id = 0, summary_updated_at = NULL
            WHERE user_id = #{userId} AND (summary_text IS NOT NULL OR summary_upto_id > 0)
            """)
    int clearSummariesByUser(@Param("userId") Long userId);

    // ------------------------------------------------------------ 风格选项（派生自曲库）

    /**
     * 曲库里的原始风格串。{@code tb_song.style} 是逗号分隔多值（如 {@code 欧美流行,电子}），
     * 拆分与去重由服务层负责 —— SQL 里做字符串拆分不可移植。
     */
    @Select("SELECT DISTINCT style FROM tb_song WHERE style IS NOT NULL AND style <> ''")
    List<String> distinctStyles();
}
