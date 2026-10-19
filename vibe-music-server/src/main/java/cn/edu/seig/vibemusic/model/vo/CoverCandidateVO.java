package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个封面候选 —— 对应 QQ 音乐里的一张专辑。
 *
 * <p>候选的粒度是<b>专辑</b>而不是搜索结果条目：同一张专辑里的多首歌共用一张封面，
 * 按曲目返回会把同一张图重复列好几遍（实测「稻香」与「兰亭序」都指向《魔杰座》）。
 *
 * <p>{@code coverUrls} 一次给出全部尺寸，前端切换 300/500/800 时不需要再请求后端 ——
 * 实测该 CDN 无防盗链，浏览器可直接加载。
 */
@Data
public class CoverCandidateVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 专辑 mid，封面地址的唯一身份；调用下载接口时回传它 */
    private String albumMid;

    /** 专辑名，可能为空（翻唱、自制上传常常没有专辑） */
    private String albumName;

    /** 歌手展示串，可能为空 */
    private String artistName;

    /** 曲名（候选是按专辑聚拢的，这里给出来源曲目便于管理员辨认） */
    private String trackName;

    /**
     * 版本标签：录音室 / Live / 翻唱 / 其他。
     *
     * <p>同名不同版本在 QQ 音乐里普遍存在（实测「青花瓷」同时有录音室版、
     * 魔天伦 Live 版、中国好声音翻唱版），不标注的话管理员只能靠专辑名猜。
     */
    private String versionLabel;

    /** 匹配得分，便于管理员判断可信度 */
    private Double score;

    /** 时长（秒），用于与本地音频比对，辅助判断是不是同一个版本 */
    private Integer durationSeconds;

    /**
     * 三档封面地址，key 为尺寸（"300" / "500" / "800"）。
     *
     * <p>用 Map 而不是三个字段：尺寸集合将来若要调整，前端与序列化格式都不用改。
     */
    private Map<String, String> coverUrls = new LinkedHashMap<>();

}
