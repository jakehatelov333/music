package cn.edu.seig.vibemusic.util;

import java.util.Map;

/**
 * 列表排序白名单解析工具。
 *
 * <p>排序字段（sortField）来自前端，绝不能直接拼进 SQL。所有列表查询在拼接
 * {@code ORDER BY} 之前都必须经过这里的白名单映射：字段名只允许命中调用方
 * 提供的固定 {@code 字段 -> SQL 列表达式} 映射表，未命中一律回退默认排序。</p>
 */
public final class SortWhitelistUtil {

    private SortWhitelistUtil() {
    }

    /**
     * 解析为可直接拼入 ORDER BY 的子句（不含 ORDER BY 关键字）。
     *
     * @param sortField     前端排序字段
     * @param order         asc / desc
     * @param whitelist     字段 -> SQL 列表达式 的固定映射
     * @param defaultClause 未命中白名单或参数缺失时使用的默认子句
     * @return 形如 {@code s.release_time DESC} 的排序子句
     */
    public static String toOrderBy(String sortField, String order,
                                   Map<String, String> whitelist, String defaultClause) {
        if (sortField == null || order == null) {
            return defaultClause;
        }
        String column = whitelist.get(sortField);
        if (column == null) {
            return defaultClause;
        }
        return column + ("asc".equalsIgnoreCase(order) ? " ASC" : " DESC");
    }

    /**
     * 解析为（列表达式, 是否升序），供 MyBatis-Plus {@code QueryWrapper.orderByAsc/Desc} 使用。
     * 未命中白名单或参数缺失时返回 {@code null}，由调用方走默认排序。
     */
    public static Map.Entry<String, Boolean> toColumnAndDirection(String sortField, String order,
                                                                  Map<String, String> whitelist) {
        if (sortField == null || order == null) {
            return null;
        }
        String column = whitelist.get(sortField);
        if (column == null) {
            return null;
        }
        return Map.entry(column, "asc".equalsIgnoreCase(order));
    }
}
