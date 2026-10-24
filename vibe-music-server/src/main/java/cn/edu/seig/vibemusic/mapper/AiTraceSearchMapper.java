package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.dto.AiTraceQueryDTO;
import cn.edu.seig.vibemusic.model.vo.AiTraceListItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Trace 检索（动态条件 + 服务端分页）。
 *
 * <p><b>为什么单独一个 Mapper：</b>条件组合很多，用 XML 比一长串注解更可读；
 * 而写入与固定聚合放在 {@link AiTraceMapper} 里用注解更直接。两者都只做参数绑定。
 *
 * <p>{@code ORDER BY} 由 Service 传入<b>白名单映射后的列表达式</b>，不做字符串拼接。
 */
@Mapper
public interface AiTraceSearchMapper {

    /** 统计命中条数（与列表用同一套条件，保证分页总数与数据一致）。 */
    long count(@Param("q") AiTraceQueryDTO query,
               @Param("userIds") List<Long> userIds,
               @Param("toolTraceIds") List<String> toolTraceIds);

    List<AiTraceListItemVO> search(@Param("q") AiTraceQueryDTO query,
                                   @Param("userIds") List<Long> userIds,
                                   @Param("toolTraceIds") List<String> toolTraceIds,
                                   @Param("orderBy") String orderBy,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    /** 按账号 / ID 模糊搜索用户（下拉选项），只返回必要字段。 */
    List<java.util.Map<String, Object>> searchUsers(@Param("keyword") String keyword,
                                                    @Param("limit") int limit);
}
