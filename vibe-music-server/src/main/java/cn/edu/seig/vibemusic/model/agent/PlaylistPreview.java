package cn.edu.seig.vibemusic.model.agent;

import java.util.List;

/**
 * 待确认歌单的预览快照。
 *
 * <p>它由 {@link PlaylistDraft} 渲染而来，并被持久化。确认执行时仍然从
 * {@link PlaylistDraft} 构造真实创建请求，因此<b>预览与执行共享同一份数据源</b>，
 * 不存在“看到的和写进去的不一致”。{@code version} 用来检测预览是否已被更新。
 */
public record PlaylistPreview(
        String title,
        String introduction,
        String style,
        boolean isPublic,
        int songCount,
        List<SongRef> songs,
        int version
) {
}
