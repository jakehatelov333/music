package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 歌手搜索结果里的一个候选。
 *
 * <p>刻意<b>不带</b>简介等长字段：搜索是为了让人「认出是哪一位」，
 * 名字 + 头像足够，而一次搜索可能返回多条，把 wiki 全文带上会让响应从几 KB 涨到几百 KB。
 * 详情在用户点选后再单独取。
 */
@Data
public class ArtistCandidateVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** QQ 音乐歌手 mid，点选后用它取详情。 */
    private String singerMid;

    /** QQ 侧歌手名。可能与库里写法不同（如 {@code G.E.M.邓紫棋}）。 */
    private String artistName;

    /** 各尺寸头像地址：{@code {"300": url, "500": url, "800": url}}。 */
    private Map<String, String> avatarUrls;

    /** 该候选与本系统已有歌手同名（大小写/标点归一化后相等）。 */
    private Boolean existingInLibrary;

    /** 已存在时对应的本地歌手 id，便于前端直接跳转查看而不是重复创建。 */
    private Long existingArtistId;

    /**
     * 是否与搜索关键词「完全一致」（归一化后）。
     *
     * <p>只有为 {@code true} 时前端才默认选中；{@code false} 表示名字有出入
     * （如 {@code 周杰伦} vs {@code 周杰伦战队}），必须由人确认。
     */
    private Boolean exactMatch;

    /** 匹配得分，仅用于展示排序依据，前端不依赖具体数值。 */
    private Integer score;
}
