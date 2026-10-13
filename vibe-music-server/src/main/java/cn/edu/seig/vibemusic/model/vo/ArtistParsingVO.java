package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 上传元数据中歌手字段的解析结果，供管理端在上传预览里做人工确认。
 *
 * <p>它回答的问题是：「这段 artist 文本，到底是一位歌手，还是多位合作歌手？」
 * 解析本身<b>不会创建任何歌手</b>，未匹配的名称只是原样列出来等管理员处理。
 */
@Data
public class ArtistParsingVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 原始文本，例如 {@code 银临/Aki阿杰} */
    private String raw;

    /**
     * 判定结果：
     * <ul>
     *     <li>{@code EXACT_MATCH} —— 完整文本就是某位现有歌手，视为单一歌手，不拆分；</li>
     *     <li>{@code SPLIT_CONFIRMED} —— 拆出的每一段都唯一命中现有歌手，可自动拆分；</li>
     *     <li>{@code SPLIT_CANDIDATE} —— 识别出分隔符但有段落未匹配/有歧义，需人工确认；</li>
     *     <li>{@code UNRESOLVED} —— 既不是现有歌手，也没有可识别的分隔符，需人工确认。</li>
     * </ul>
     */
    private String strategy;

    /** 实际命中的分隔符文本；未识别时为 null。 */
    private String separator;

    /** 拆解出的候选歌手，顺序即建议的展示顺序。 */
    private List<Candidate> candidates;

    @Data
    public static class Candidate implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 唯一精确命中时的歌手 id；未匹配或歧义时为 null。 */
        private Long artistId;

        /** 文本片段（匹配成功时是库里的歌手名，否则是原文片段）。 */
        private String artistName;

        /** 是否唯一精确命中一条现有歌手记录。 */
        private boolean matched;
    }
}
