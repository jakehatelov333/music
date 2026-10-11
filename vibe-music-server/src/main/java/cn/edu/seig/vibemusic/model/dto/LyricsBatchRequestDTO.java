package cn.edu.seig.vibemusic.model.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 批量补全歌词请求。
 * <p>
 * 三种用法：
 * <ul>
 *   <li>指定 {@code songIds}：只处理这些歌曲（管理端勾选后批量）</li>
 *   <li>指定 {@code albumId}：处理该专辑下的歌曲（专辑详情一键补全）</li>
 *   <li>都不指定：按 {@code onlyMissing} 条件全量处理</li>
 * </ul>
 */
@Data
public class LyricsBatchRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 待处理的歌曲 id 列表，可为空 */
    private List<Long> songIds;

    /** 只处理该专辑下的歌曲，可为空 */
    private Long albumId;

    /** 是否只处理缺失歌词的歌曲，默认 true */
    private Boolean onlyMissing;

    /** 最多处理多少首，为空时使用配置上限 */
    private Integer limit;

    /** 是否覆盖已有歌词，默认 false */
    private Boolean force;

}
