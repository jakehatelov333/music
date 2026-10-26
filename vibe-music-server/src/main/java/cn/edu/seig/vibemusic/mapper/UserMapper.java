package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT auth_version FROM tb_user WHERE id = #{userId}")
    Long selectAuthVersionById(@Param("userId") Long userId);

    @Update("UPDATE tb_user SET auth_version = auth_version + 1 WHERE id = #{userId}")
    int incrementAuthVersion(@Param("userId") Long userId);

}
