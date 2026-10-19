package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * 歌曲上传最终确认参数。
 */
@Data
public class SongUploadCommitDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "uploadId不能为空")
    private String uploadId;

    @NotBlank(message = "歌曲名不能为空")
    private String songName;

    /** 歌手 id（单个，兼容旧客户端）。artistIds 非空时以 artistIds 为准。 */
    private Long artistId;

    /** 歌手名（单个，兼容旧客户端）。artistIds 非空时忽略。 */
    private String artistName;

    /**
     * 演唱歌手 id 列表，<b>顺序即展示顺序</b>（第一个为主艺人）。
     * 由上传预览的人工确认结果产出；留空时回退到 artistId / artistName。
     */
    private List<Long> artistIds;

    /**
     * 待新建的歌手名，按展示顺序排列。
     *
     * <p>与 {@link #artistIds} 配合使用：管理端在同一个选择器里既可能选中<b>已有歌手</b>
     * （有 id），也可能输入<b>库里没有的名字</b>（只有名字）。提交时前者进 artistIds、
     * 后者进本字段，服务端按 {@code artistIds + pendingArtistNames} 的顺序依次落库。
     *
     * <p><b>顺序语义</b>：两者共同决定展示顺序，因此不能简单「先 id 后名字」——
     * 主艺人可能是待创建的那个。实际顺序由 {@link #artistOrder} 决定。
     *
     * <p>为什么不让前端先建歌手再传 id：那样会把「建歌手」和「建歌曲」拆到两个事务里，
     * 歌曲入库失败就会留下孤儿歌手。这里交给 {@code commit} 在同一事务内完成。
     */
    private List<String> pendingArtistNames;

    /**
     * 完整歌手顺序，元素为 {@code "id:<artistId>"} 或 {@code "name:<歌手名>"}。
     *
     * <p>当 artistIds 与 pendingArtistNames 混排时，单靠两个数组无法还原顺序
     * （例如「新歌手 A / 周杰伦」，主艺人应是待创建的 A）。本字段给出权威顺序；
     * 留空时退化为「artistIds 在前、pendingArtistNames 在后」。
     */
    private List<String> artistOrder;

    private String album;

    private Long albumId;

    private Integer discNumber;

    private Integer trackNumber;

    @Min(value = 1900, message = "发行年份不能早于1900年")
    @Max(value = 2100, message = "发行年份不合法")
    private Integer year;

    /**
     * 完整发行日期，优先于兼容字段 year
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate releaseDate;

    private String style;

    private String lyrics;
}
