package cn.edu.seig.vibemusic.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 封面搜索请求。
 *
 * <p>支持两种模式，区别在于是否传 {@code keyword}：
 *
 * <h3>自动模式（不传 keyword）</h3>
 * 按 {@code songName} + {@code artistName} 检索，再用<b>歌名完全相等 + 歌手包含</b>过滤，
 * 最后按专辑去重。用于「元数据解析正确」的常规场景 —— 上传时自动带出表单里的歌名歌手，
 * 管理员点一下就能看到 2–4 个候选。
 *
 * <h3>手动模式（传 keyword）</h3>
 * 用 {@code keyword} 原样检索，<b>不做任何歌名/歌手过滤</b>，直接按专辑聚拢。
 * 这是必需的逃生通道：音频标签解析错误时（歌名带乱码、歌手填成文件名、
 * 专辑名写错），自动模式会拿着错误元数据去搜，搜出来的图当然也是错的。
 * 此时管理员可以自己输入正确的歌名或专辑名重新搜。
 *
 * <p>手动模式刻意不沿用自动模式的过滤条件：既然元数据不可信，拿它过滤只会
 * 把正确结果筛掉。代价是召回变宽，所以候选数上限放宽到 8，并保留专辑名与
 * 版本标签供人工辨认。
 */
@Data
public class CoverSearchDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 手动搜索关键词。非空即进入手动模式。
     *
     * <p>可以只填专辑名（实测「我很忙」能返回整张专辑的全部曲目），
     * 也可以只填歌名，或「歌名 歌手」组合。
     */
    private String keyword;

    /** 歌名，自动模式必填 */
    private String songName;

    /** 歌手展示串，例如「银临 / Aki阿杰」 */
    private String artistName;

    /** 专辑名，自动模式下命中同专辑时加分 */
    private String album;

    /** 本地音频时长（秒），用于版本消歧；可为空 */
    private Double durationSeconds;

    /**
     * 候选数上限。为空时自动模式取 4、手动模式取 8。
     *
     * <p>为什么两种模式默认不同：自动模式有过滤，结果天然收敛，4 个够选；
     * 手动模式没有过滤，召回里混着同名不同版本，需要更宽的选择面。
     */
    private Integer limit;
}
