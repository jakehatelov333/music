package cn.edu.seig.vibemusic.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 某位 QQ 音乐歌手的完整资料，用于「一键填充」新增歌手表单。
 *
 * <p>每个字段都可能为 {@code null}，含义是<b>「没查到」而不是「查到了空值」</b>：
 * 前端据此保留用户已经填的内容，不拿 null 去覆盖。
 */
@Data
public class ArtistProfileVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** QQ 音乐歌手 mid。 */
    private String singerMid;

    /** QQ 侧歌手名。 */
    private String artistName;

    /** 性别：0-男，1-女，2-组合/乐队；{@code null} 表示无法确定。 */
    private Integer gender;

    /** 生日。QQ 侧只提供公历的结构化字段，解析不了就为 {@code null}。 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate birth;

    /** 国籍/地区。取自 wiki 的「国籍」项 —— {@code ex_info.area} 是分区码不是国家。 */
    private String area;

    /** 简介。实测最长 2765 字，`tb_artist.introduction` 已放宽到 TEXT。 */
    private String introduction;

    /** 外文名，仅用于展示。 */
    private String foreignName;

    /** 各尺寸头像地址：{@code {"300": url, "500": url, "800": url}}；无头像时为空 map。 */
    private Map<String, String> avatarUrls;

    /**
     * 对方是否确实有头像。
     *
     * <p>与「{@link #avatarUrls} 非空」不等价：地址由 mid 唯一决定，mid 合法就一定拼得出 URL，
     * 但对方可能根本没有照片（CDN 会 404）。前端据此决定要不要显示「使用该头像」的勾选项。
     */
    private Boolean hasAvatar;

    /**
     * wiki 里解析出的全部键值对（出生地、职业、出道日期、代表作品…）。
     *
     * <p>原样返回是为了让前端能在不改后端的情况下多展示几项，
     * 也便于排查「为什么某个字段没填上」。
     */
    private Map<String, String> extraFields;

    /** 本次结果里实际有值的业务字段名，前端据此提示「填充了哪几项」。 */
    private List<String> filledFields;

    /** 该歌手是否已在本地库中（按归一化名字比对）；{@code true} 时新增会被拒。 */
    private Boolean existingInLibrary;

    /** 已存在时对应的本地歌手 id。 */
    private Long existingArtistId;
}
