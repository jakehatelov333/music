package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.Admin;
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
public interface AdminMapper extends BaseMapper<Admin> {

    @Select("SELECT auth_version FROM tb_admin WHERE id = #{adminId}")
    Long selectAuthVersionById(@Param("adminId") Long adminId);

    @Update("UPDATE tb_admin SET auth_version = auth_version + 1 WHERE id = #{adminId}")
    int incrementAuthVersion(@Param("adminId") Long adminId);

}
