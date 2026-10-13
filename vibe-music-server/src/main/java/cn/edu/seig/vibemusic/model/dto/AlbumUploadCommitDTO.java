package cn.edu.seig.vibemusic.model.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

@Data
public class AlbumUploadCommitDTO implements Serializable {
    private String uploadId;
    private AlbumInfo album;
    private List<SongInfo> songs;
    /**
     * 管理员已确认「可以创建库中不存在的歌手」。
     *
     * <p>为 {@code null}/{@code false} 时，只要出现库里没有的歌手名，提交会返回
     * {@code code = 409} 与待创建清单（不写任何数据）；前端弹确认框后再带 {@code true} 重新提交。
     */
    private Boolean allowCreate;

    @Data
    public static class AlbumInfo implements Serializable {
        private String albumName;
        private Long artistId;
        private String artistName;
        private String coverUrl;
        private LocalDate releaseDate;
        private String description;
        private String status;
    }

    @Data
    public static class SongInfo implements Serializable {
        private String stagingKey;
        private String fileName;
        private String songName;
        /** 歌手 id（单个，兼容旧客户端）。artistIds 非空时以 artistIds 为准。 */
        private Long artistId;
        private String artistName;
        /**
         * 该曲目的演唱歌手 id 列表，<b>顺序即展示顺序</b>（第一个为主艺人）。
         * 留空时回退到 artistId；两者都为空则沿用专辑主艺人。
         */
        private List<Long> artistIds;
        private Integer trackNumber;
        private Integer discNumber;
        private String lyrics;
    }
}
