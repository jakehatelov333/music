package cn.edu.seig.vibemusic.model.agent;

import java.util.List;

/**
 * 待创建歌单的草稿。是「预览」与「执行」的<b>唯一共同数据源</b>：
 * 预览由它渲染，确认后也由它构造真实的创建请求，因此预览内容与最终执行内容结构上一致。
 */
public record PlaylistDraft(
        String title,
        String introduction,
        String style,
        boolean isPublic,
        List<Long> songIds
) {
    public PlaylistDraft normalized() {
        List<Long> ids = songIds == null ? List.of()
                : songIds.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
        return new PlaylistDraft(trim(title), trim(introduction), trim(style), isPublic, ids);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
