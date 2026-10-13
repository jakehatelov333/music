package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class AlbumUploadPrepareVO implements Serializable {
    private String uploadId;
    private String albumName;
    private String artistName;
    private Long artistId;
    private String releaseDate;
    private String coverUrl;
    private String warning;
    private List<Song> songs;

    @Data
    public static class Song implements Serializable {
        private String stagingKey;
        private String fileName;
        private String songName;
        /**
         * 音频标签里的歌手原始串（如 {@code 银临、Tacke竹桑}）。
         * <b>这是提交时唯一可信的歌手来源</b>，管理员也可以在预览里直接修改它。
         */
        private String artistName;
        private Long artistId;
        /**
         * 高置信度解析出的歌手 id 列表（仅 {@code EXACT_MATCH} / {@code SPLIT_CONFIRMED} 时非空），
         * 顺序即展示顺序；其余情况为 {@code null}，交给管理员人工确认。
         */
        private List<Long> artistIds;
        /** 该曲目歌手字段的解析结论（含未匹配的名字），供预览里核对。 */
        private ArtistParsingVO artistParsing;
        private Integer trackNumber;
        private Integer discNumber;
        private Double duration;
        private String durationText;
        private Long fileSize;
        private String coverUrl;
    }
}
