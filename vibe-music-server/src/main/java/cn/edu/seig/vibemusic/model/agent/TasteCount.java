package cn.edu.seig.vibemusic.model.agent;

import lombok.Data;

/**
 * 画像聚合的一行：某个风格或某位歌手在用户偏好里出现的次数。
 *
 * <p>刻意用普通 bean 而不是 record：这张结果集由 MyBatis 直接映射，
 * bean 的 setter 映射路径最稳，不依赖编译期 {@code -parameters} 保留构造器参数名。
 */
@Data
public class TasteCount {

    /** 歌手场景为 artistId；风格场景无对应 id，保持 null。 */
    private Long id;

    /** 风格名或歌手名。 */
    private String name;

    /** 出现次数（跨收藏与自建歌单去重后的计数）。 */
    private int count;
}
